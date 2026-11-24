
from fastapi.testclient import TestClient
from app.main import app

def test_health():
    with TestClient(app) as c:
        r = c.get("/health")
        assert r.status_code == 200
        assert r.json()["ok"]

def test_demo_assets():
    with TestClient(app) as c:
        r = c.get("/assets")
        assert r.status_code == 200
        assert any(x["symbol"]=="BTC" for x in r.json())

def test_create_asset():
    with TestClient(app) as c:
        r = c.post("/assets",headers={"X-API-Key":"demo-token"},json={
            "symbol":"VXTST","name":"Virtual Test","description":"Test",
            "total_supply":"100000"
        })
        assert r.status_code in (201,409)

def test_portfolio():
    with TestClient(app) as c:
        r = c.get("/portfolio",headers={"X-API-Key":"demo-token"})
        assert r.status_code == 200
