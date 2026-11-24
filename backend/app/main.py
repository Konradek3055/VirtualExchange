
from fastapi import FastAPI,Header,HTTPException,WebSocket,WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from .db import init_db,connect
from .schemas import CreateAccount,OrderIn,AssetCreate
from .engine import place_order,cancel_order,seed
from .assets import create_user_asset
import secrets

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
            db.execute("INSERT INTO balances(account_id,symbol,available,locked) VALUES(?,?,?,?,?)",
                (cur.lastrowid,"USD","10000","0"))
            db.commit()
    except Exception:
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
            SELECT symbol,name,last_price,description,total_supply,created_at
            FROM assets ORDER BY symbol
        """).fetchall()
    return [dict(row) for row in rows]

@app.get("/assets/{symbol}")
def asset_detail(symbol:str):
    with connect() as db:
        row = db.execute("SELECT * FROM assets WHERE symbol=?",(symbol.upper(),)).fetchone()
    if not row:
        raise HTTPException(404,"Asset not found")
    return dict(row)

@app.post("/assets",status_code=201)
def add_asset(body:AssetCreate,x_api_key:str|None=Header(default=None)):
    account = account_from_key(x_api_key)
    return create_user_asset(account["id"],body)

@app.post("/orders")
async def add_order(body:OrderIn,x_api_key:str|None=Header(default=None)):
    account = account_from_key(x_api_key)
    result = place_order(account["id"],body)
    for ws in list(sockets.get(body.symbol.upper(),set())):
        try:
            await ws.send_json({"type":"update","symbol":body.symbol.upper()})
        except Exception:
            sockets[body.symbol.upper()].discard(ws)
    return result

@app.delete("/orders/{order_id}")
def delete_order(order_id:int,x_api_key:str|None=Header(default=None)):
    account = account_from_key(x_api_key)
    return cancel_order(account["id"],order_id)

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
            SELECT side,price,SUM(CAST(remaining AS REAL)) quantity
            FROM orders WHERE symbol=? AND status IN ('OPEN','PARTIAL') AND price IS NOT NULL
            GROUP BY side,price
        """,(symbol.upper(),)).fetchall()
    bids = sorted([dict(r) for r in rows if r["side"]=="buy"],
                  key=lambda r:float(r["price"]),reverse=True)[:30]
    asks = sorted([dict(r) for r in rows if r["side"]=="sell"],
                  key=lambda r:float(r["price"]))[:30]
    return {"symbol":symbol.upper(),"bids":bids,"asks":asks}

@app.get("/trades/{symbol}")
def trades(symbol:str):
    with connect() as db:
        rows = db.execute(
            "SELECT * FROM trades WHERE symbol=? ORDER BY id DESC LIMIT 100",
            (symbol.upper(),)
        ).fetchall()
    return [dict(r) for r in rows]

@app.get("/candles/{symbol}")
def candles(symbol:str,limit:int=100):
    limit = max(1,min(limit,500))
    with connect() as db:
        rows = db.execute(
            "SELECT price,quantity,created_at FROM trades WHERE symbol=? ORDER BY id DESC LIMIT ?",
            (symbol.upper(),limit*60)
        ).fetchall()

    result = {}
    for row in reversed(rows):
        minute = row["created_at"][:16]
        p = float(row["price"])
        q = float(row["quantity"])
        if minute not in result:
            result[minute] = {"time":minute,"open":p,"high":p,"low":p,"close":p,"volume":q}
        else:
            c = result[minute]
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
