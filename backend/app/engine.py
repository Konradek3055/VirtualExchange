
from decimal import Decimal
from fastapi import HTTPException
from .db import transaction

D = Decimal

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

def place_order(account_id, data):
    with transaction() as db:
        symbol = data.symbol.upper()
        asset = db.execute("SELECT symbol FROM assets WHERE symbol=?",(symbol,)).fetchone()
        if not asset:
            raise HTTPException(404,"Unknown asset")

        qty = D(str(data.quantity))
        price = D(str(data.price)) if data.price is not None else None

        if data.order_type == "limit" and (price is None or price <= 0):
            raise HTTPException(400,"Limit order requires price")
        if data.order_type == "market" and price is not None:
            raise HTTPException(400,"Market order cannot have price")

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

        cur = db.execute("""
            INSERT INTO orders(account_id,symbol,side,order_type,quantity,remaining,price,status)
            VALUES(?,?,?,?,?,?,?,?)
        """,(account_id,symbol,data.side,data.order_type,str(qty),str(qty),
             str(price) if price is not None else None,"OPEN"))
        order_id = cur.lastrowid
        executed = []

        while True:
            if data.side == "buy":
                sql = """
                    SELECT * FROM orders WHERE symbol=? AND side='sell'
                    AND account_id<>? AND status IN ('OPEN','PARTIAL')
                    AND price IS NOT NULL
                    AND (? IS NULL OR CAST(price AS REAL)<=?)
                    ORDER BY CAST(price AS REAL),id LIMIT 1
                """
            else:
                sql = """
                    SELECT * FROM orders WHERE symbol=? AND side='buy'
                    AND account_id<>? AND status IN ('OPEN','PARTIAL')
                    AND price IS NOT NULL
                    AND (? IS NULL OR CAST(price AS REAL)>=?)
                    ORDER BY CAST(price AS REAL) DESC,id LIMIT 1
                """

            bound = float(price) if price is not None else None
            maker = db.execute(sql,(symbol,account_id,bound,bound)).fetchone()
            if maker is None:
                break

            taker = db.execute("SELECT * FROM orders WHERE id=?",(order_id,)).fetchone()
            taker_remaining = D(taker["remaining"])
            maker_remaining = D(maker["remaining"])
            trade_price = D(maker["price"])
            fill = min(taker_remaining,maker_remaining)

            if data.side == "buy" and data.order_type == "market":
                cash,_ = get_balance(db,account_id,"USD")
                fill = min(fill,cash/trade_price)
                if fill <= 0:
                    break

            if maker["side"] == "sell":
                buyer_id,seller_id = account_id,maker["account_id"]
                buy_id,sell_id = order_id,maker["id"]
            else:
                buyer_id,seller_id = maker["account_id"],account_id
                buy_id,sell_id = maker["id"],order_id

            cost = fill * trade_price

            buyer_asset,buyer_asset_locked = get_balance(db,buyer_id,symbol)
            seller_asset,seller_asset_locked = get_balance(db,seller_id,symbol)
            set_balance(db,buyer_id,symbol,buyer_asset+fill,buyer_asset_locked)
            set_balance(db,seller_id,symbol,seller_asset,seller_asset_locked-fill)

            buy_order = db.execute("SELECT * FROM orders WHERE id=?",(buy_id,)).fetchone()
            buyer_cash,buyer_locked = get_balance(db,buyer_id,"USD")

            if buy_order["order_type"] == "limit":
                limit_price = D(buy_order["price"])
                refund = (limit_price-trade_price)*fill
                set_balance(db,buyer_id,"USD",buyer_cash+refund,buyer_locked-limit_price*fill)
            else:
                set_balance(db,buyer_id,"USD",buyer_cash-cost,buyer_locked)

            seller_cash,seller_locked = get_balance(db,seller_id,"USD")
            set_balance(db,seller_id,"USD",seller_cash+cost,seller_locked)

            maker_left = maker_remaining-fill
            taker_left = taker_remaining-fill

            db.execute("UPDATE orders SET remaining=?,status=? WHERE id=?",
                (str(maker_left),"FILLED" if maker_left==0 else "PARTIAL",maker["id"]))
            db.execute("UPDATE orders SET remaining=?,status=? WHERE id=?",
                (str(taker_left),"FILLED" if taker_left==0 else "PARTIAL",order_id))

            db.execute("UPDATE assets SET last_price=? WHERE symbol=?",(str(trade_price),symbol))
            db.execute("""
                INSERT INTO trades(symbol,price,quantity,buy_order_id,sell_order_id,buyer_id,seller_id)
                VALUES(?,?,?,?,?,?,?)
            """,(symbol,str(trade_price),str(fill),buy_id,sell_id,buyer_id,seller_id))

            executed.append({"price":str(trade_price),"quantity":str(fill)})
            if taker_left == 0:
                break

        current = db.execute("SELECT * FROM orders WHERE id=?",(order_id,)).fetchone()
        remaining = D(current["remaining"])

        if remaining > 0 and data.order_type == "market":
            if data.side == "sell":
                av,locked = get_balance(db,account_id,symbol)
                set_balance(db,account_id,symbol,av+remaining,locked-remaining)
            db.execute("UPDATE orders SET status='CANCELLED' WHERE id=?",(order_id,))
        elif remaining > 0:
            db.execute("UPDATE orders SET status='OPEN' WHERE id=?",(order_id,))

        status = db.execute("SELECT status FROM orders WHERE id=?",(order_id,)).fetchone()["status"]
        return {"order_id":order_id,"status":status,"remaining":str(remaining),"trades":executed}

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
        return {"ok":True}

def seed():
    with transaction() as db:
        db.execute("INSERT OR IGNORE INTO accounts(username,api_key) VALUES('demo','demo-token')")
        account = db.execute("SELECT id FROM accounts WHERE username='demo'").fetchone()["id"]

        for symbol,name,price in [
            ("BTC","Bitcoin","65000"),("ETH","Ethereum","3200"),
            ("NVDA","NVIDIA Demo","140"),("AAPL","Apple Demo","230")
        ]:
            db.execute(
                "INSERT OR IGNORE INTO assets(symbol,name,last_price) VALUES(?,?,?)",
                (symbol,name,price)
            )

        for symbol,amount in [
            ("USD","100000"),("BTC","2"),("ETH","20"),("NVDA","1000"),("AAPL","1000")
        ]:
            av,locked = get_balance(db,account,symbol)
            if av == 0 and locked == 0:
                set_balance(db,account,symbol,D(amount),D("0"))
