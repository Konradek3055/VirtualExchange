from decimal import Decimal, ROUND_DOWN
from fastapi import HTTPException
from .db import transaction

D = Decimal
QUOTE = "USD"

def dec(v):
    return D(str(v))

def balance(db, account_id, symbol):
    row = db.execute(
        "SELECT available, locked FROM balances WHERE account_id=? AND symbol=?",
        (account_id, symbol)
    ).fetchone()
    if not row:
        db.execute("INSERT INTO balances(account_id,symbol,available,locked) VALUES(?,?,?,?)",
                   (account_id, symbol, "0", "0"))
        return D("0"), D("0")
    return dec(row["available"]), dec(row["locked"])

def set_balance(db, account_id, symbol, available, locked):
    db.execute("""INSERT INTO balances(account_id,symbol,available,locked) VALUES(?,?,?,?)
        ON CONFLICT(account_id,symbol) DO UPDATE SET available=excluded.available, locked=excluded.locked""",
        (account_id, symbol, str(available), str(locked)))

def place_order(account_id, data):
    with transaction() as db:
        asset = db.execute("SELECT symbol FROM assets WHERE symbol=?", (data.symbol.upper(),)).fetchone()
        if not asset:
            raise HTTPException(404, "Unknown asset")
        symbol = data.symbol.upper()
        qty = dec(data.quantity)
        price = dec(data.price) if data.price is not None else None
        if data.order_type == "limit" and price is None:
            raise HTTPException(400, "Limit order requires price")
        if data.order_type == "market" and price is not None:
            raise HTTPException(400, "Market order must not include price")

        if data.side == "buy":
            available, locked = balance(db, account_id, QUOTE)
            if data.order_type == "limit":
                reserve = qty * price
                if available < reserve:
                    raise HTTPException(400, "Insufficient USD")
                set_balance(db, account_id, QUOTE, available-reserve, locked+reserve)
            elif available <= 0:
                raise HTTPException(400, "No USD available")
        else:
            available, locked = balance(db, account_id, symbol)
            if available < qty:
                raise HTTPException(400, f"Insufficient {symbol}")
            set_balance(db, account_id, symbol, available-qty, locked+qty)

        cur = db.execute("""INSERT INTO orders(account_id,symbol,side,order_type,quantity,remaining,price,status)
            VALUES(?,?,?,?,?,?,?,?)""",
            (account_id, symbol, data.side, data.order_type, str(qty), str(qty),
             str(price) if price is not None else None, "OPEN"))
        order_id = cur.lastrowid
        trades = []

        while True:
            if data.side == "buy":
                sql = """SELECT * FROM orders WHERE symbol=? AND side='sell' AND status IN ('OPEN','PARTIAL')
                    AND (order_type='market' OR CAST(price AS REAL)<=?)
                    ORDER BY CASE WHEN price IS NULL THEN 0 ELSE 1 END, CAST(price AS REAL), id LIMIT 1"""
                args = (symbol, float(price) if price is not None else 1e100)
            else:
                sql = """SELECT * FROM orders WHERE symbol=? AND side='buy' AND status IN ('OPEN','PARTIAL')
                    AND (order_type='market' OR CAST(price AS REAL)>=?)
                    ORDER BY CASE WHEN price IS NULL THEN 0 ELSE 1 END, CAST(price AS REAL) DESC, id LIMIT 1"""
                args = (symbol, float(price) if price is not None else 0)
            maker = db.execute(sql, args).fetchone()
            if maker is None or maker["id"] == order_id:
                break
            taker = db.execute("SELECT * FROM orders WHERE id=?", (order_id,)).fetchone()
            if maker["account_id"] == account_id:
                break
            maker_remaining = dec(maker["remaining"])
            taker_remaining = dec(taker["remaining"])
            fill = min(maker_remaining, taker_remaining)
            maker_price = dec(maker["price"]) if maker["price"] is not None else (price or D("0"))
            if maker["side"] == "sell":
                buy_id, sell_id = order_id, maker["id"]
                buyer_id, seller_id = account_id, maker["account_id"]
                trade_price = maker_price
            else:
                buy_id, sell_id = maker["id"], order_id
                buyer_id, seller_id = maker["account_id"], account_id
                trade_price = maker_price
            cost = fill * trade_price

            # Rozliczenie aktywa
            b_av, b_lock = balance(db, buyer_id, symbol)
            s_av, s_lock = balance(db, seller_id, symbol)
            if maker["side"] == "sell":
                s_lock -= fill
            else:
                s_lock -= fill
            b_av += fill
            set_balance(db, buyer_id, symbol, b_av, b_lock)
            set_balance(db, seller_id, symbol, s_av, s_lock)

            # Rozliczenie USD; limit buyer ma środki zablokowane, market buyer płaci z dostępnych.
            buyer_order = db.execute("SELECT * FROM orders WHERE id=?", (buy_id,)).fetchone()
            if buyer_order["order_type"] == "limit":
                ba, bl = balance(db, buyer_id, QUOTE)
                reserved_unit = dec(buyer_order["price"])
                refund = (reserved_unit - trade_price) * fill
                set_balance(db, buyer_id, QUOTE, ba + refund, bl - reserved_unit*fill)
            else:
                ba, bl = balance(db, buyer_id, QUOTE)
                set_balance(db, buyer_id, QUOTE, ba-cost, bl)
            sa, sl = balance(db, seller_id, QUOTE)
            set_balance(db, seller_id, QUOTE, sa+cost, sl)

            new_maker = maker_remaining-fill
            new_taker = taker_remaining-fill
            db.execute("UPDATE orders SET remaining=?, status=? WHERE id=?",
                       (str(new_maker), "FILLED" if new_maker == 0 else "PARTIAL", maker["id"]))
            db.execute("UPDATE orders SET remaining=?, status=? WHERE id=?",
                       (str(new_taker), "FILLED" if new_taker == 0 else "PARTIAL", order_id))
            db.execute("UPDATE assets SET last_price=? WHERE symbol=?", (str(trade_price), symbol))
            db.execute("""INSERT INTO trades(symbol,price,quantity,buy_order_id,sell_order_id,buyer_id,seller_id)
                VALUES(?,?,?,?,?,?,?)""",
                (symbol, str(trade_price), str(fill), buy_id, sell_id, buyer_id, seller_id))
            trades.append({"symbol": symbol, "price": str(trade_price), "quantity": str(fill),
                           "buy_order_id": buy_id, "sell_order_id": sell_id})
            if new_taker == 0:
                break

        current = db.execute("SELECT * FROM orders WHERE id=?", (order_id,)).fetchone()
        remaining = dec(current["remaining"])
        if remaining > 0:
            # Market orders never rest in the book. Also cancel a limit taker
            # that would trade against its own resting order.
            own_cross = db.execute(
                "SELECT 1 FROM orders WHERE id<>? AND account_id=? AND symbol=? "
                "AND side<>? AND status IN ('OPEN','PARTIAL') LIMIT 1",
                (order_id, account_id, symbol, data.side)
            ).fetchone()
            should_cancel = data.order_type == "market" or own_cross is not None
            if should_cancel:
                if data.order_type == "limit" and data.side == "buy":
                    av, lock = balance(db, account_id, QUOTE)
                    release = remaining * price
                    set_balance(db, account_id, QUOTE, av+release, lock-release)
                elif data.side == "sell":
                    av, lock = balance(db, account_id, symbol)
                    set_balance(db, account_id, symbol, av+remaining, lock-remaining)
                db.execute("UPDATE orders SET status='CANCELLED' WHERE id=?", (order_id,))
            elif current["status"] not in ("FILLED", "CANCELLED"):
                db.execute("UPDATE orders SET status='OPEN' WHERE id=?", (order_id,))
        return {"order_id": order_id, "status": db.execute("SELECT status FROM orders WHERE id=?", (order_id,)).fetchone()["status"],
                "remaining": str(remaining), "trades": trades}

def cancel_order(account_id, order_id):
    with transaction() as db:
        order = db.execute("SELECT * FROM orders WHERE id=? AND account_id=?", (order_id, account_id)).fetchone()
        if not order:
            raise HTTPException(404, "Order not found")
        if order["status"] not in ("OPEN", "PARTIAL"):
            raise HTTPException(400, "Order is not open")
        remaining = dec(order["remaining"])
        if order["side"] == "buy":
            av, lock = balance(db, account_id, QUOTE)
            release = remaining * dec(order["price"])
            set_balance(db, account_id, QUOTE, av+release, lock-release)
        else:
            av, lock = balance(db, account_id, order["symbol"])
            set_balance(db, account_id, order["symbol"], av+remaining, lock-remaining)
        db.execute("UPDATE orders SET status='CANCELLED' WHERE id=?", (order_id,))
        return {"ok": True, "order_id": order_id}

def seed():
    with transaction() as db:
        db.execute("INSERT OR IGNORE INTO accounts(username,api_key) VALUES('demo','demo-token')")
        demo = db.execute("SELECT id FROM accounts WHERE username='demo'").fetchone()["id"]
        db.execute("INSERT OR IGNORE INTO assets(symbol,name,last_price) VALUES('BTC','Bitcoin','65000')")
        db.execute("INSERT OR IGNORE INTO assets(symbol,name,last_price) VALUES('ETH','Ethereum','3200')")
        db.execute("INSERT OR IGNORE INTO assets(symbol,name,last_price) VALUES('NVDA','NVIDIA','140')")
        db.execute("INSERT OR IGNORE INTO assets(symbol,name,last_price) VALUES('AAPL','Apple','230')")
        for sym, amount in [("USD","100000"),("BTC","2"),("ETH","20"),("NVDA","1000"),("AAPL","1000")]:
            av, lock = balance(db, demo, sym)
            if av == 0 and lock == 0:
                set_balance(db, demo, sym, dec(amount), D("0"))
