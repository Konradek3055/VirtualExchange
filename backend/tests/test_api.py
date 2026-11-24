from fastapi.testclient import TestClient
from app.main import app

def test_health():
    c = TestClient(app)
    assert c.get("/health").json()["ok"] is True

def test_assets():
    c = TestClient(app)
    r = c.get("/assets")
    assert r.status_code == 200
    assert any(x["symbol"] == "BTC" for x in r.json())
