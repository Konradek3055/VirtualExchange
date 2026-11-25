
import decimal
from decimal import Decimal, ROUND_DOWN
from fastapi import HTTPException
from .db import transaction

# Wysoka precyzja: iloczyn cena*ilość musi być zawsze dokładny (także w wątkach FastAPI).
decimal.DefaultContext.prec = 60
decimal.getcontext().prec = 60

D = Decimal
MAX_DP = 8
STEP = Decimal(1).scaleb(-MAX_DP)
PRICE_SCALE = 10 ** MAX_DP
MAX_PRICE = Decimal(10) ** 10     # cena*1e8 mieści się w 64-bitowym INTEGER SQLite
MAX_QTY = Decimal(10) ** 24

def get_balance(db, account, symbol):
    row = db.execute(
        "SELECT available,locked FROM balances WHERE account_id=? AND symbol=?",
        (account,symbol)
    ).fetchone()
    if row is None:
        db.execute(
            "INSERT INTO balances(account_id,symbol,available,locked) VALUES(?,?,?,?)",
            (account,symbol,"0","0")
        )
        return D("0"),D("0")
    return D(row["available"]),D(row["locked"])

def set_balance(db, account, symbol, available, locked):
    if available < 0 or locked < 0:
        raise RuntimeError("Negative balance blocked")
    db.execute("""
        INSERT INTO balances(account_id,symbol,available,locked)
        VALUES(?,?,?,?)
        ON CONFLICT(account_id,symbol) DO UPDATE SET
        available=excluded.available,locked=excluded.locked
    """,(account,symbol,str(available),str(locked)))

def _check_decimal(value, field, max_value):
    if not value.is_finite() or value <= 0 or value > max_value:
        raise HTTPException(400, f"Invalid {field}")
    if value.normalize().as_tuple().exponent < -MAX_DP:
        raise HTTPException(400, f"{field}: max {MAX_DP} decimal places")

def place_order(account_id, data):
    with transaction() as db:
        symbol = data.symbol.upper()
        asset = db.execute("SELECT symbol FROM assets WHERE symbol=?",(symbol,)).fetchone()
        if not asset:
            raise HTTPException(404,"Unknown asset")

        qty = D(str(data.quantity))
        price = D(str(data.price)) if data.price is not None else None
        _check_decimal(qty, "quantity", MAX_QTY)

        if data.order_type == "limit":
            if price is None:
                raise HTTPException(400,"Limit order requires price")
            _check_decimal(price, "price", MAX_PRICE)
        elif price is not None:
            raise HTTPException(400,"Market order cannot have price")

        # --- rezerwacja środków (nie da się wydać więcej niż się ma) ---
        if data.side == "buy":
            available,locked = get_balance(db,account_id,"USD")
            if data.order_type == "limit":
                reserve = qty * price
                if available < reserve:
                    raise HTTPException(400,"Insufficient USD")
                set_balance(db,account_id,"USD",available-reserve,locked+reserve)
            elif available <= 0:
                raise HTTPException(400,"No USD available")
        else:
            available,locked = get_balance(db,account_id,symbol)
            if available < qty:
                raise HTTPException(400,"Insufficient asset balance")
            set_balance(db,account_id,symbol,available-qty,locked+qty)

        price_i = int(price * PRICE_SCALE) if price is not None else None
        cur = db.execute("""
            INSERT INTO orders(account_id,symbol,side,order_type,quantity,remaining,price,price_i,status)
            VALUES(?,?,?,?,?,?,?,?,?)
        """,(account_id,symbol,data.side,data.order_type,str(qty),str(qty),
             str(price) if price is not None else None,price_i,"OPEN"))
        order_id = cur.lastrowid
        executed = []
        remaining = qty
        self_trade = False

        if data.side == "buy":
            sql = """SELECT * FROM orders WHERE symbol=? AND side='sell'
                     AND status IN ('OPEN','PARTIAL') AND price_i IS NOT NULL
                     AND (? IS NULL OR price_i<=?) ORDER BY price_i,id LIMIT 1"""
        else:
            sql = """SELECT * FROM orders WHERE symbol=? AND side='buy'
                     AND status IN ('OPEN','PARTIAL') AND price_i IS NOT NULL
                     AND (? IS NULL OR price_i>=?) ORDER BY price_i DESC,id LIMIT 1"""

        while remaining > 0:
            maker = db.execute(sql,(symbol,price_i,price_i)).fetchone()
            if maker is None:
                break
            if maker["account_id"] == account_id:     # ochrona przed self-trade: anuluj resztę nowego zlecenia
                self_trade = True
                break

            maker_remaining = D(maker["remaining"])
            trade_price = D(maker["price"])             # cena = cena zlecenia czekającego
            fill = min(remaining,maker_remaining)

            if data.side == "buy" and data.order_type == "market":
                cash,_ = get_balance(db,account_id,"USD")
                fill = min(fill,(cash/trade_price).quantize(STEP,rounding=ROUND_DOWN))
                if fill <= 0:
                    break

            if data.side == "buy":
                buyer_id,seller_id = account_id,maker["account_id"]
                buy_id,sell_id = order_id,maker["id"]
                buy_limit = price if data.order_type == "limit" else None
            else:
                buyer_id,seller_id = maker["account_id"],account_id
                buy_id,sell_id = maker["id"],order_id
                buy_limit = trade_price                 # maker-kupujący zawsze ma limit

            cost = fill * trade_price

            buyer_asset,buyer_asset_locked = get_balance(db,buyer_id,symbol)
            set_balance(db,buyer_id,symbol,buyer_asset+fill,buyer_asset_locked)
            seller_asset,seller_asset_locked = get_balance(db,seller_id,symbol)
            set_balance(db,seller_id,symbol,seller_asset,seller_asset_locked-fill)

            buyer_cash,buyer_locked = get_balance(db,buyer_id,"USD")
            if buy_limit is not None:
                set_balance(db,buyer_id,"USD",buyer_cash+(buy_limit-trade_price)*fill,buyer_locked-buy_limit*fill)
            else:
                set_balance(db,buyer_id,"USD",buyer_cash-cost,buyer_locked)

            seller_cash,seller_locked = get_balance(db,seller_id,"USD")
            set_balance(db,seller_id,"USD",seller_cash+cost,seller_locked)

            maker_left = maker_remaining-fill
            remaining -= fill
            db.execute("UPDATE orders SET remaining=?,status=? WHERE id=?",
                (str(maker_left),"FILLED" if maker_left==0 else "PARTIAL",maker["id"]))
            db.execute("UPDATE assets SET last_price=? WHERE symbol=?",(str(trade_price),symbol))
            db.execute("""
                INSERT INTO trades(symbol,price,quantity,buy_order_id,sell_order_id,buyer_id,seller_id)
                VALUES(?,?,?,?,?,?,?)
            """,(symbol,str(trade_price),str(fill),buy_id,sell_id,buyer_id,seller_id))
            executed.append({"price":str(trade_price),"quantity":str(fill)})

        # --- stan końcowy zlecenia ---
        if remaining == 0:
            status = "FILLED"
        elif data.order_type == "market" or self_trade:
            status = "CANCELLED"                        # reszta nie trafia do księgi: zwolnij blokadę
            if data.side == "sell":
                av,locked = get_balance(db,account_id,symbol)
                set_balance(db,account_id,symbol,av+remaining,locked-remaining)
            elif data.order_type == "limit":
                release = price * remaining
                av,locked = get_balance(db,account_id,"USD")
                set_balance(db,account_id,"USD",av+release,locked-release)
        else:
            status = "PARTIAL" if remaining < qty else "OPEN"
        db.execute("UPDATE orders SET remaining=?,status=? WHERE id=?",(str(remaining),status,order_id))
        return {"order_id":order_id,"status":status,"remaining":str(remaining),"trades":executed,"symbol":symbol}

def cancel_order(account_id, order_id):
    with transaction() as db:
        order = db.execute(
            "SELECT * FROM orders WHERE id=? AND account_id=?",(order_id,account_id)
        ).fetchone()
        if not order:
            raise HTTPException(404,"Order not found")
        if order["status"] not in ("OPEN","PARTIAL"):
            raise HTTPException(400,"Order is not open")

        remaining = D(order["remaining"])
        if order["side"] == "buy":
            av,locked = get_balance(db,account_id,"USD")
            release = remaining*D(order["price"])
            set_balance(db,account_id,"USD",av+release,locked-release)
        else:
            av,locked = get_balance(db,account_id,order["symbol"])
            set_balance(db,account_id,order["symbol"],av+remaining,locked-remaining)

        db.execute("UPDATE orders SET status='CANCELLED' WHERE id=?",(order_id,))
        return {"ok":True,"symbol":order["symbol"]}

def seed():
    with transaction() as db:
        db.execute("INSERT OR IGNORE INTO accounts(username,api_key) VALUES('demo','demo-token')")
        account = db.execute("SELECT id FROM accounts WHERE username='demo'").fetchone()["id"]

        for symbol,name,price in [
            ("BTC","Bitcoin","65000"),("ETH","Ethereum","3200"),
            ("NVDA","NVIDIA","140"),("AAPL","Apple","230"),
            ("MSFT","Microsoft","425"),("GOOGL","Alphabet","175"),
            ("AMZN","Amazon","185"),("META","Meta Platforms","520"),
            ("TSLA","Tesla","250"),("AMD","Advanced Micro Devices","160"),
            ("INTC","Intel","30"),("JPM","JPMorgan Chase","225"),
            ("KO","Coca-Cola","63"),("DIS","Disney","105"),
            ("NFLX","Netflix","900"),("ORCL","Oracle","170")
        ]:
            db.execute(
                "INSERT OR IGNORE INTO assets(symbol,name,last_price) VALUES(?,?,?)",
                (symbol,name,price)
            )

        for symbol,amount in [
            ("USD","100000"),("BTC","2"),("ETH","20"),
            ("NVDA","1000"),("AAPL","1000"),("MSFT","1000"),("GOOGL","1000"),
            ("AMZN","1000"),("META","1000"),("TSLA","1000"),("AMD","1000"),
            ("INTC","1000"),("JPM","1000"),("KO","1000"),("DIS","1000"),
            ("NFLX","1000"),("ORCL","1000")
        ]:
            av,locked = get_balance(db,account,symbol)
            if av == 0 and locked == 0:
                set_balance(db,account,symbol,D(amount),D("0"))
