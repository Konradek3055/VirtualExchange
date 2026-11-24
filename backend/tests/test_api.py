from fastapi.testclient import TestClient
from app.main import app

def test_health():
    with TestClient(app) as client:
        response = client.get("/health")
        assert response.status_code == 200
        assert response.json()["ok"] is True

def test_assets():
    with TestClient(app) as client:
        response = client.get("/assets")
        assert response.status_code == 200
        assert any(asset["symbol"] == "BTC" for asset in response.json())
