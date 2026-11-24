"""Testy silnika zleceń przez publiczne API (prawdziwa baza SQLite w katalogu tymczasowym)."""
import random
from collections import defaultdict
from decimal import Decimal as D

from app.db import connect


def signup(c, name):
    r = c.post("/accounts", json={"username": name})
    assert r.status_code == 200, r.text
    return {"X-API-Key": r.json()["api_key"]}


def make_asset(c, h, sym, supply=100):
    r = c.post("/assets", headers=h, json={"symbol": sym, "name": f"Token {sym}", "total_supply": str(supply)})
    assert r.status_code == 201, r.text


def order(c, h, sym, side, typ, qty, price=None):
    body = {"symbol": sym, "side": side, "order_type": typ, "quantity": str(qty)}
    if price is not None:
        body["price"] = str(price)
    return c.post("/orders", headers=h, json=body)


def bal(c, h, sym):
    for row in c.get("/portfolio", headers=h).json():
        if row["symbol"] == sym:
            return D(row["available"]), D(row["locked"])
    return D(0), D(0)


def check_invariants(c):
    """Podaż USD i aktywów użytkowników jest stała, brak ujemnych sald, blokady == otwarte zlecenia."""
    with connect() as db:
        n = db.execute("SELECT COUNT(*) FROM accounts WHERE username<>'demo'").fetchone()[0]
        totals, locks = defaultdict(D), defaultdict(D)
        for r in db.execute("SELECT account_id,symbol,available,locked FROM balances"):
            a, l = D(r["available"]), D(r["locked"])
            assert a >= 0 and l >= 0
            totals[r["symbol"]] += a + l
            if l:
                locks[(r["account_id"], r["symbol"])] = l
        assert totals["USD"] == 100000 + 10000 * n
        for r in db.execute("SELECT symbol,total_supply FROM assets WHERE creator_id IS NOT NULL"):
            assert totals[r["symbol"]] == D(r["total_supply"]), r["symbol"]
        expected = defaultdict(D)
        for o in db.execute("SELECT * FROM orders WHERE status IN ('OPEN','PARTIAL')"):
            rem = D(o["remaining"])
            if o["side"] == "sell":
                expected[(o["account_id"], o["symbol"])] += rem
            else:
                expected[(o["account_id"], "USD")] += D(o["price"]) * rem
        assert {k: v for k, v in expected.items() if v} == dict(locks)


def world(c):
    owner, buyer = signup(c, "owner"), signup(c, "buyer")
    make_asset(c, owner, "BTCX", 100)
    return owner, buyer


# ---------------------------------------------------------------- konta
def test_registration_works_and_rejects_duplicates(client):
    r = client.post("/accounts", json={"username": "alice"})
    assert r.status_code == 200 and r.json()["starting_usd"] == "10000"
    assert client.post("/accounts", json={"username": "alice"}).status_code == 409
    h = {"X-API-Key": r.json()["api_key"]}
    assert bal(client, h, "USD") == (D(10000), D(0))


# ---------------------------------------------------------------- dopasowanie
def test_cross_executes_at_maker_price(client):
    owner, buyer = world(client)
    order(client, owner, "BTCX", "sell", "limit", 1, 100)
    r = order(client, buyer, "BTCX", "buy", "limit", 1, 100).json()
    assert r["status"] == "FILLED" and r["trades"] == [{"price": "100", "quantity": "1"}]
    assert bal(client, buyer, "USD") == (D(9900), D(0)) and bal(client, buyer, "BTCX") == (D(1), D(0))
    assert bal(client, owner, "USD") == (D(10100), D(0)) and bal(client, owner, "BTCX") == (D(99), D(0))
    check_invariants(client)


def test_price_improvement_is_refunded(client):
    owner, buyer = world(client)
    order(client, owner, "BTCX", "sell", "limit", 1, 100)
    order(client, buyer, "BTCX", "buy", "limit", 1, 120)
    assert bal(client, buyer, "USD") == (D(9900), D(0))
    check_invariants(client)


def test_no_trade_when_prices_do_not_cross(client):
    owner, buyer = world(client)
    order(client, owner, "BTCX", "sell", "limit", 1, 101)
    r = order(client, buyer, "BTCX", "buy", "limit", 1, 100).json()
    assert r["trades"] == [] and r["status"] == "OPEN"
    assert client.get("/trades/BTCX").json() == []


def test_time_priority_at_same_price(client):
    owner, b1 = world(client)
    b2 = signup(client, "buyer2")
    first = order(client, owner, "BTCX", "sell", "limit", 1, 100).json()["order_id"]
    second = order(client, owner, "BTCX", "sell", "limit", 1, 100).json()["order_id"]
    order(client, b1, "BTCX", "buy", "limit", 1, 100)
    order(client, b2, "BTCX", "buy", "limit", 1, 100)
    t = client.get("/trades/BTCX").json()          # najnowsze pierwsze
    assert [x["sell_order_id"] for x in t] == [second, first]


def test_best_price_has_priority(client):
    owner, buyer = world(client)
    order(client, owner, "BTCX", "sell", "limit", 1, 105)
    order(client, owner, "BTCX", "sell", "limit", 1, 100)
    r = order(client, buyer, "BTCX", "buy", "limit", 1, 110).json()
    assert r["trades"][0]["price"] == "100"


def test_partial_fill_rests_with_partial_status(client):
    owner, buyer = world(client)
    order(client, owner, "BTCX", "sell", "limit", 1, 100)
    r = order(client, buyer, "BTCX", "buy", "limit", 3, 100).json()
    assert r["status"] == "PARTIAL" and r["remaining"] == "2"
    assert client.get("/orderbook/BTCX").json()["bids"] == [{"price": "100", "quantity": "2"}]
    assert bal(client, buyer, "USD") == (D(9700), D(200))
    check_invariants(client)


def test_market_buy_walks_the_book(client):
    owner, buyer = world(client)
    order(client, owner, "BTCX", "sell", "limit", 1, 100)
    order(client, owner, "BTCX", "sell", "limit", 1, 101)
    r = order(client, buyer, "BTCX", "buy", "market", "1.5").json()
    assert r["trades"] == [{"price": "100", "quantity": "1"}, {"price": "101", "quantity": "0.5"}]
    assert bal(client, buyer, "USD")[0] == D("9849.5")
    assert client.get("/orderbook/BTCX").json()["asks"] == [{"price": "101", "quantity": "0.5"}]
    check_invariants(client)


def test_market_buy_limited_by_funds_never_overspends(client):
    owner, _ = world(client)
    poor = signup(client, "poor")
    order(client, owner, "BTCX", "sell", "limit", 3, 3)          # 1/3 -> nieskończony ułamek
    # poor ma 10000 USD, więc najpierw wydaj prawie wszystko na inne aktywo
    make_asset(client, owner, "FILLER", 100)
    order(client, owner, "FILLER", "sell", "limit", 99, "100.01")
    order(client, poor, "FILLER", "buy", "limit", 99, "100.01")  # 9900.99
    r = order(client, poor, "BTCX", "buy", "market", 3)
    assert r.status_code == 200
    usd = bal(client, poor, "USD")[0]
    assert usd >= 0
    check_invariants(client)


def test_market_buy_without_liquidity_is_cancelled(client):
    _, buyer = world(client)
    r = order(client, buyer, "BTCX", "buy", "market", 1).json()
    assert r["status"] == "CANCELLED" and r["trades"] == []
    assert bal(client, buyer, "USD") == (D(10000), D(0))


def test_market_sell_hits_bids_and_releases_remainder(client):
    owner, buyer = world(client)
    order(client, buyer, "BTCX", "buy", "limit", 1, 50)
    r = order(client, owner, "BTCX", "sell", "market", 3).json()
    assert r["trades"] == [{"price": "50", "quantity": "1"}] and r["status"] == "CANCELLED"
    assert bal(client, owner, "BTCX") == (D(99), D(0))
    check_invariants(client)


# ---------------------------------------------------------------- środki
def test_cannot_spend_what_you_do_not_have(client):
    owner, buyer = world(client)
    assert order(client, buyer, "BTCX", "buy", "limit", 101, 100).status_code == 400   # 10100 > 10000
    assert order(client, buyer, "BTCX", "sell", "limit", 1, 100).status_code == 400    # brak BTCX
    assert order(client, owner, "BTCX", "sell", "limit", 101, 100).status_code == 400
    order(client, buyer, "BTCX", "buy", "limit", 90, 100)                              # blokuje 9000
    assert order(client, buyer, "BTCX", "buy", "limit", 20, 100).status_code == 400    # brak podwójnego wydania
    check_invariants(client)


def test_cancel_releases_funds_and_checks_owner(client):
    owner, buyer = world(client)
    oid = order(client, buyer, "BTCX", "buy", "limit", 2, 100).json()["order_id"]
    assert client.delete(f"/orders/{oid}", headers=owner).status_code == 404
    assert client.delete(f"/orders/{oid}", headers=buyer).status_code == 200
    assert bal(client, buyer, "USD") == (D(10000), D(0))
    assert client.delete(f"/orders/{oid}", headers=buyer).status_code == 400
    check_invariants(client)


def test_self_trade_is_prevented_without_crossed_book(client):
    owner, _ = world(client)
    order(client, owner, "BTCX", "sell", "limit", 1, 100)
    r = order(client, owner, "BTCX", "buy", "limit", 1, 100)
    assert r.status_code == 400 or r.json()["status"] == "CANCELLED"   # owner ma 10000 USD -> przejdzie i anuluje
    book = client.get("/orderbook/BTCX").json()
    assert book["bids"] == [] and client.get("/trades/BTCX").json() == []
    check_invariants(client)


# ---------------------------------------------------------------- walidacja
def test_validation(client):
    owner, buyer = world(client)
    bad = [
        ("buy", "limit", -1, 1), ("buy", "limit", 0, 1), ("buy", "limit", "0.123456789", 1),
        ("buy", "limit", 1, None), ("buy", "limit", 1, 0), ("buy", "limit", 1, "1.123456789"),
        ("buy", "limit", 1, "20000000000"), ("buy", "market", 1, 5),
    ]
    for side, typ, qty, price in bad:
        r = order(client, buyer, "BTCX", side, typ, qty, price)
        assert r.status_code in (400, 422), (side, typ, qty, price, r.status_code)
    assert order(client, buyer, "NOPE", "buy", "limit", 1, 1).status_code == 404
    assert client.post("/orders", json={}).status_code in (401, 422)


def test_prices_are_compared_exactly_not_as_floats(client):
    owner, buyer = world(client)
    make_asset(client, owner, "PREC", 10)
    qty = "0.00000001"
    a = order(client, owner, "PREC", "sell", "limit", qty, "9999999999.99999999").json()["order_id"]
    b = order(client, owner, "PREC", "sell", "limit", qty, "9999999999.99999998").json()["order_id"]
    r = order(client, buyer, "PREC", "buy", "limit", qty, "9999999999.99999999").json()
    assert r["trades"][0]["price"] == "9999999999.99999998"
    check_invariants(client)


# ---------------------------------------------------------------- aktywa / świece / WS
def test_user_asset_rules(client):
    a, b = signup(client, "aaa"), signup(client, "bbb")
    make_asset(client, a, "PIZZA", 1000000)
    assert bal(client, a, "PIZZA") == (D(1000000), D(0))
    dup = client.post("/assets", headers=b, json={"symbol": "pizza", "name": "Dup", "total_supply": "5"})
    assert dup.status_code == 409
    for sym in ("USD", "A", "TOOLONGTICKER1", "BA D"):
        r = client.post("/assets", headers=b, json={"symbol": sym, "name": "Bad", "total_supply": "5"})
        assert r.status_code in (409, 422), sym
    check_invariants(client)


def test_candles_come_from_real_trades_only(client):
    owner, buyer = world(client)
    order(client, owner, "BTCX", "sell", "limit", 1, 100)
    assert client.get("/candles/BTCX").json() == []
    order(client, buyer, "BTCX", "buy", "limit", 1, 100)
    c = client.get("/candles/BTCX").json()
    assert len(c) == 1 and c[0]["open"] == c[0]["close"] == 100 and c[0]["volume"] == 1


def test_websocket_notifies_on_order_and_cancel(client):
    owner, _ = world(client)
    with client.websocket_connect("/ws/BTCX") as ws:
        assert ws.receive_json()["type"] == "connected"
        oid = order(client, owner, "BTCX", "sell", "limit", 1, 100).json()["order_id"]
        assert ws.receive_json()["type"] == "update"
        client.delete(f"/orders/{oid}", headers=owner)
        assert ws.receive_json()["type"] == "update"


# ---------------------------------------------------------------- losowy stres
def test_random_trading_keeps_books_consistent(client):
    rnd = random.Random(7)
    users = [signup(client, f"user{i}") for i in range(4)]
    syms = []
    for i, h in enumerate(users[:3]):
        syms.append(f"T{i}X")
        make_asset(client, h, f"T{i}X", 100)
    my_orders = defaultdict(list)
    for _ in range(250):
        i = rnd.randrange(len(users))
        h = users[i]
        if rnd.random() < 0.15 and my_orders[i]:
            client.delete(f"/orders/{my_orders[i].pop(rnd.randrange(len(my_orders[i])))}", headers=h)
            continue
        kind = rnd.choice(["limit", "limit", "market"])
        r = order(client, h, rnd.choice(syms), rnd.choice(["buy", "sell"]), kind,
                  D(rnd.randint(1, 10)) / 2, D(rnd.randint(180, 220)) / 2 if kind == "limit" else None)
        assert r.status_code < 500, r.text
        if r.status_code == 200:
            my_orders[i].append(r.json()["order_id"])
    check_invariants(client)
    assert sum(len(client.get(f"/trades/{s}").json()) for s in syms) > 10
