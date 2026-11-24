from decimal import Decimal as D

from app import assets


def user(c, name):
    r = c.post("/accounts", json={"username": name}).json()
    return {"X-API-Key": r["api_key"]}


def usd(c, h):
    return D({p["symbol"]: p for p in c.get("/portfolio", headers=h).json()}["USD"]["available"])


def test_listing_fee_is_charged_and_burned(client, monkeypatch):
    monkeypatch.setattr(assets, "CREATION_FEE", D(100))
    h = user(client, "alice")
    r = client.post("/assets", headers=h, json={"symbol": "KOT", "name": "Kot", "total_supply": "1000"})
    assert r.status_code == 201 and r.json()["last_price"] == "0"
    assert usd(client, h) == D(9900)


def test_cannot_list_without_funds_for_fee(client, monkeypatch):
    monkeypatch.setattr(assets, "CREATION_FEE", D(20000))
    h = user(client, "poor")
    r = client.post("/assets", headers=h, json={"symbol": "KOT", "name": "Kot", "total_supply": "1000"})
    assert r.status_code == 400
    assert client.get("/assets/KOT").status_code == 404  # rollback: aktywo nie powstało


def test_per_user_limit(client, monkeypatch):
    monkeypatch.setattr(assets, "MAX_ASSETS_PER_USER", 2)
    h = user(client, "spammer")
    for s in ("AAA", "BBB"):
        assert client.post("/assets", headers=h, json={"symbol": s, "name": "x" * 3, "total_supply": "10"}).status_code == 201
    assert client.post("/assets", headers=h, json={"symbol": "CCC", "name": "xxx", "total_supply": "10"}).status_code == 400


def test_public_info_and_reports_flag(client, monkeypatch):
    monkeypatch.setattr(assets, "FLAG_THRESHOLD", 2)
    a, b, c = user(client, "alice"), user(client, "bob"), user(client, "carl")
    client.post("/assets", headers=a, json={"symbol": "SCAM", "name": "Scam", "total_supply": "5000"})
    info = client.get("/assets/SCAM").json()
    assert info["creator"] == "alice" and info["holders"] == 1 and info["reports"] == 0 and info["last_price"] == "0"
    assert client.post("/assets/SCAM/report", headers=b).json() == {"reports": 1, "flagged": False}
    assert client.post("/assets/SCAM/report", headers=b).json()["reports"] == 1   # duplikat się nie liczy
    assert client.post("/assets/SCAM/report", headers=c).json() == {"reports": 2, "flagged": True}
    assert client.get("/assets/SCAM").json()["flagged"] is True
    assert client.post("/assets/NOPE/report", headers=b).status_code == 404
    assert client.post("/assets/SCAM/report").status_code == 401


def test_candle_intervals_aggregate_real_trades(client):
    demo = {"X-API-Key": "demo-token"}
    buyer = user(client, "buyer")
    for p in ("65000", "65100"):
        client.post("/orders", headers=demo, json={"symbol": "BTC", "side": "sell", "order_type": "limit", "quantity": "0.1", "price": p})
    assert client.get("/candles/BTC?interval=5m").json() == []          # brak transakcji = brak świec
    client.post("/orders", headers=buyer, json={"symbol": "BTC", "side": "buy", "order_type": "market", "quantity": "0.15"})
    c1 = client.get("/candles/BTC?interval=1m").json()
    c5 = client.get("/candles/BTC?interval=5m").json()
    ch = client.get("/candles/BTC?interval=1h").json()
    assert len(c1) >= 1 and len(c5) >= 1 and len(ch) == 1
    assert abs(sum(c["volume"] for c in c1) - 0.15) < 1e-9 and abs(ch[0]["volume"] - 0.15) < 1e-9
    assert (ch[0]["open"], ch[0]["high"], ch[0]["low"], ch[0]["close"]) == (65000, 65100, 65000, 65100)
    assert client.get("/candles/BTC?interval=2m").status_code == 400
