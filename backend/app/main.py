from fastapi import FastAPI, Header, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from .db import init_db, connect
from .schemas import CreateAccount, OrderIn
from .engine import place_order, cancel_order, seed
import secrets

app = FastAPI(title="VirtualExchange API", version="0.1.0")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])
clients: dict[str, set[WebSocket]] = {}

@app.on_event("startup")
def startup():
    init_db()
    seed()

def current_account(x_api_key: str | None):
    if not x_api_key:
        raise HTTPException(401, "X-API-Key required")
    with connect() as db:
        row = db.execute("SELECT id,username FROM accounts WHERE api_key=?", (x_api_key,)).fetchone()
    if not row:
        raise HTTPException(401, "Invalid API key")
    return dict(row)

@app.get("/health")
def health():
    return {"ok": True, "service": "VirtualExchange"}

@app.post("/accounts")
def create_account(body: CreateAccount):
    key = secrets.token_urlsafe(32)
    try:
        with connect() as db:
            cur = db.execute("INSERT INTO accounts(username,api_key) VALUES(?,?)", (body.username, key))
            account_id = cur.lastrowid
            db.execute("INSERT INTO balances(account_id,symbol,available,locked) VALUES(?,?,?,?)",
                       (account_id, "USD", "10000", "0"))
            db.commit()
    except Exception:
        raise HTTPException(409, "Username already exists")
    return {"id": account_id, "username": body.username, "api_key": key, "starting_usd": "10000"}

@app.get("/me")
def me(x_api_key: str | None = Header(default=None)):
    account = current_account(x_api_key)
    with connect() as db:
        balances = [dict(r) for r in db.execute("SELECT symbol,available,locked FROM balances WHERE account_id=?", (account["id"],))]
    return {**account, "balances": balances}

@app.get("/assets")
def assets():
    with connect() as db:
        return [dict(r) for r in db.execute("SELECT symbol,name,last_price FROM assets ORDER BY symbol")]

@app.post("/orders")
async def orders(body: OrderIn, x_api_key: str | None = Header(default=None)):
    account = current_account(x_api_key)
    result = place_order(account["id"], body)
    for ws in list(clients.get(body.symbol.upper(), set())):
        try:
            await ws.send_json({"type": "market_update", "symbol": body.symbol.upper()})
        except Exception:
            clients[body.symbol.upper()].discard(ws)
    return result

@app.delete("/orders/{order_id}")
def delete_order(order_id: int, x_api_key: str | None = Header(default=None)):
    account = current_account(x_api_key)
    return cancel_order(account["id"], order_id)

@app.get("/orders")
def my_orders(x_api_key: str | None = Header(default=None)):
    account = current_account(x_api_key)
    with connect() as db:
        return [dict(r) for r in db.execute("SELECT * FROM orders WHERE account_id=? ORDER BY id DESC LIMIT 100", (account["id"],))]

@app.get("/orderbook/{symbol}")
def orderbook(symbol: str):
    with connect() as db:
        rows = db.execute("""SELECT side,price,SUM(CAST(remaining AS REAL)) quantity FROM orders
            WHERE symbol=? AND status IN ('OPEN','PARTIAL') AND price IS NOT NULL
            GROUP BY side,price ORDER BY CAST(price AS REAL)""", (symbol.upper(),)).fetchall()
    return {"symbol": symbol.upper(),
            "bids": [dict(r) for r in rows if r["side"] == "buy"],
            "asks": [dict(r) for r in rows if r["side"] == "sell"]}

@app.get("/trades/{symbol}")
def trades(symbol: str):
    with connect() as db:
        return [dict(r) for r in db.execute("SELECT * FROM trades WHERE symbol=? ORDER BY id DESC LIMIT 100", (symbol.upper(),))]

@app.websocket("/ws/{symbol}")
async def websocket_market(ws: WebSocket, symbol: str):
    symbol = symbol.upper()
    await ws.accept()
    clients.setdefault(symbol, set()).add(ws)
    try:
        await ws.send_json({"type": "connected", "symbol": symbol})
        while True:
            await ws.receive_text()
    except WebSocketDisconnect:
        clients.get(symbol, set()).discard(ws)
