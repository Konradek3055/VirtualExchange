
from fastapi import FastAPI,Header,HTTPException,WebSocket,WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from .db import init_db,connect
from .schemas import CreateAccount,OrderIn,AssetCreate
from .engine import place_order,cancel_order,seed
from .assets import asset_info, create_user_asset, report_asset
from .companies import (get_company_development, upgrade_company, fund_company, withdraw_company, manage_company_staff)
import calendar
import secrets
import time
import sqlite3
from decimal import Decimal

app = FastAPI(title="VirtualExchange API",version="1.0")
app.add_middleware(CORSMiddleware,allow_origins=["*"],allow_methods=["*"],allow_headers=["*"])
sockets = {}

@app.on_event("startup")
def startup():
    init_db()
    seed()

def account_from_key(key):
    if not key:
        raise HTTPException(401,"API key required")
    with connect() as db:
        row = db.execute("SELECT id,username FROM accounts WHERE api_key=?",(key,)).fetchone()
    if row is None:
        raise HTTPException(401,"Invalid API key")
    return dict(row)

@app.get("/health")
def health():
    return {"ok":True,"version":"1.0"}

@app.post("/accounts")
def create_account(body:CreateAccount):
    key = secrets.token_urlsafe(32)
    try:
        with connect() as db:
            cur = db.execute("INSERT INTO accounts(username,api_key) VALUES(?,?)",(body.username,key))
            db.execute("INSERT INTO balances(account_id,symbol,available,locked) VALUES(?,?,?,?)",
                (cur.lastrowid,"USD","10000","0"))
            db.commit()
    except sqlite3.IntegrityError:
        raise HTTPException(409,"Username already exists")
    return {"id":cur.lastrowid,"username":body.username,"api_key":key,"starting_usd":"10000"}

@app.get("/me")
def me(x_api_key:str|None=Header(default=None)):
    return account_from_key(x_api_key)

@app.get("/portfolio")
def portfolio(x_api_key:str|None=Header(default=None)):
    account = account_from_key(x_api_key)
    with connect() as db:
        rows = db.execute("""
            SELECT b.symbol,b.available,b.locked,a.last_price,a.name
            FROM balances b LEFT JOIN assets a ON a.symbol=b.symbol
            WHERE b.account_id=? ORDER BY b.symbol
        """,(account["id"],)).fetchall()
    return [dict(row) for row in rows]

@app.get("/assets")
def assets():
    with connect() as db:
        rows = db.execute("""
            SELECT symbol,name,last_price,description,total_supply,created_at,creator_id
            FROM assets ORDER BY symbol
        """).fetchall()
    return [dict(row) for row in rows]

@app.get("/stats")
def stats():
    """Statystyki aktywów z transakcji z ostatnich 24 godzin."""
    with connect() as db:
        symbols = [r["symbol"] for r in db.execute("SELECT symbol FROM assets")]
        rows = db.execute("""
            SELECT symbol,price,quantity FROM trades
            WHERE created_at >= datetime('now','-1 day')
            ORDER BY id
        """).fetchall()

        holders = {
            r["symbol"]: r["n"]
            for r in db.execute("""
                SELECT symbol,COUNT(*) AS n FROM balances
                WHERE CAST(available AS REAL) + CAST(locked AS REAL) > 0
                GROUP BY symbol
            """)
        }

    acc = {
        symbol: {
            "open": None,
            "last": None,
            "high": None,
            "low": None,
            "volume": Decimal(0),
            "trades": 0,
        }
        for symbol in symbols
    }

    for row in rows:
        item = acc.get(row["symbol"])
        if item is None:
            continue

        price = Decimal(row["price"])
        quantity = Decimal(row["quantity"])

        if item["open"] is None:
            item["open"] = price

        item["last"] = price
        item["high"] = price if item["high"] is None else max(item["high"], price)
        item["low"] = price if item["low"] is None else min(item["low"], price)
        item["volume"] += price * quantity
        item["trades"] += 1

    result = {}

    for symbol, item in acc.items():
        change = Decimal(0)

        if item["open"] is not None and item["open"] > 0:
            change = (
                (item["last"] - item["open"])
                / item["open"]
                * Decimal(100)
            )

        result[symbol] = {
            "change_pct": round(float(change), 4),
            "volume_usd": (
                format(item["volume"].normalize(), "f")
                if item["trades"] else "0"
            ),
            "high": str(item["high"]) if item["high"] is not None else None,
            "low": str(item["low"]) if item["low"] is not None else None,
            "trades": item["trades"],
            "holders": holders.get(symbol, 0),
        }

    return result


@app.get("/assets/{symbol}")
def asset_detail(symbol:str):
    with connect() as db:
        return asset_info(db,symbol.upper())

@app.post("/assets/{symbol}/report")
def report(symbol:str,x_api_key:str|None=Header(default=None)):
    account = account_from_key(x_api_key)
    return report_asset(account["id"],symbol.upper())

@app.post("/assets",status_code=201)
def add_asset(body:AssetCreate,x_api_key:str|None=Header(default=None)):
    account = account_from_key(x_api_key)
    return create_user_asset(account["id"],body)

@app.get("/companies/{symbol}/development")
def company_development(symbol:str,x_api_key:str|None=Header(default=None)):
    account = account_from_key(x_api_key)
    return get_company_development(account["id"],symbol.upper())

@app.post("/companies/{symbol}/upgrade")
def company_upgrade(symbol:str,body:dict,x_api_key:str|None=Header(default=None)):
    account = account_from_key(x_api_key)
    return upgrade_company(account["id"],symbol.upper(),str(body.get("branch", "")))

@app.post("/companies/{symbol}/capital")
def company_capital(symbol:str,body:dict,x_api_key:str|None=Header(default=None)):
    account = account_from_key(x_api_key)
    return fund_company(account["id"],symbol.upper(),body.get("amount"))

@app.post("/companies/{symbol}/dividend")
def company_dividend(symbol:str,body:dict,x_api_key:str|None=Header(default=None)):
    account = account_from_key(x_api_key)
    return withdraw_company(account["id"],symbol.upper(),body.get("amount"))

@app.post("/companies/{symbol}/staff")
def company_staff(symbol:str,body:dict,x_api_key:str|None=Header(default=None)):
    account = account_from_key(x_api_key)
    return manage_company_staff(account["id"],symbol.upper(),str(body.get("action", "")),body.get("count", 1))

async def notify(symbol):
    for ws in list(sockets.get(symbol,set())):
        try:
            await ws.send_json({"type":"update","symbol":symbol})
        except Exception:
            sockets[symbol].discard(ws)

@app.post("/orders")
async def add_order(body:OrderIn,x_api_key:str|None=Header(default=None)):
    account = account_from_key(x_api_key)
    result = place_order(account["id"],body)
    await notify(result["symbol"])
    return result

@app.delete("/orders/{order_id}")
async def delete_order(order_id:int,x_api_key:str|None=Header(default=None)):
    account = account_from_key(x_api_key)
    result = cancel_order(account["id"],order_id)
    await notify(result["symbol"])
    return result

@app.get("/orders")
def my_orders(x_api_key:str|None=Header(default=None)):
    account = account_from_key(x_api_key)
    with connect() as db:
        rows = db.execute(
            "SELECT * FROM orders WHERE account_id=? ORDER BY id DESC LIMIT 100",
            (account["id"],)
        ).fetchall()
    return [dict(row) for row in rows]

@app.get("/orderbook/{symbol}")
def orderbook(symbol:str):
    with connect() as db:
        rows = db.execute("""
            SELECT side,price,price_i,remaining FROM orders
            WHERE symbol=? AND status IN ('OPEN','PARTIAL') AND price_i IS NOT NULL
        """,(symbol.upper(),)).fetchall()
    levels = {"buy":{}, "sell":{}}
    for r in rows:
        lvl = levels[r["side"]].setdefault(r["price_i"],{"price":r["price"],"qty":Decimal(0)})
        lvl["qty"] += Decimal(r["remaining"])
    def out(side,reverse):
        return [{"price":v["price"],"quantity":str(v["qty"])}
                for k,v in sorted(levels[side].items(),reverse=reverse)[:30]]
    return {"symbol":symbol.upper(),"bids":out("buy",True),"asks":out("sell",False)}

@app.get("/trades/{symbol}")
def trades(symbol:str):
    with connect() as db:
        rows = db.execute(
            "SELECT * FROM trades WHERE symbol=? ORDER BY id DESC LIMIT 100",
            (symbol.upper(),)
        ).fetchall()
    return [dict(r) for r in rows]

INTERVALS={"1m":60,"3m":180,"5m":300,"15m":900,"30m":1800,"1h":3600,"4h":14400,"1d":86400}

@app.get("/candles/{symbol}")
def candles(symbol:str,limit:int=100,interval:str="1m"):
    """Świece OHLCV z faktycznych transakcji. interval: 1m, 3m, 5m, 15m, 30m, 1h, 4h, 1d."""
    if interval not in INTERVALS:
        raise HTTPException(400,"interval must be one of 1m, 3m, 5m, 15m, 30m, 1h, 4h, 1d")
    sec=INTERVALS[interval]
    limit = max(1,min(limit,500))
    with connect() as db:
        rows = db.execute(
            "SELECT price,quantity,created_at FROM trades WHERE symbol=? ORDER BY id DESC LIMIT ?",
            (symbol.upper(),min(limit*60*(sec//60),50000))
        ).fetchall()

    result = {}
    for row in reversed(rows):
        ts = calendar.timegm(time.strptime(row["created_at"],"%Y-%m-%d %H:%M:%S"))
        label = time.strftime("%Y-%m-%d %H:%M",time.gmtime(ts//sec*sec))
        p = float(row["price"])
        q = float(row["quantity"])
        if label not in result:
            result[label] = {"time":label,"open":p,"high":p,"low":p,"close":p,"volume":q}
        else:
            c = result[label]
            c["high"] = max(c["high"],p)
            c["low"] = min(c["low"],p)
            c["close"] = p
            c["volume"] += q
    return list(result.values())[-limit:]

@app.websocket("/ws/{symbol}")
async def market_ws(ws:WebSocket,symbol:str):
    symbol = symbol.upper()
    await ws.accept()
    sockets.setdefault(symbol,set()).add(ws)
    try:
        await ws.send_json({"type":"connected","symbol":symbol})
        while True:
            await ws.receive_text()
    except WebSocketDisconnect:
        sockets.get(symbol,set()).discard(ws)
