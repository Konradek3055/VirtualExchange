import pytest
from fastapi.testclient import TestClient


@pytest.fixture(autouse=True)
def isolated_db(tmp_path, monkeypatch):
    """Każdy test dostaje świeżą bazę SQLite (żadnych śladów po poprzednich testach)."""
    monkeypatch.setenv("VE_DB_PATH", str(tmp_path / "test.sqlite3"))


@pytest.fixture(autouse=True)
def no_listing_fee(monkeypatch):
    """Domyślnie testy silnika nie płacą za emisję aktywa; reguły opłaty testuje test_assets.py."""
    from app import assets
    monkeypatch.setattr(assets, "CREATION_FEE", assets.Decimal(0))
    monkeypatch.setattr(assets, "MAX_ASSETS_PER_USER", 1000)


@pytest.fixture
def client():
    from app.main import app
    with TestClient(app) as c:
        yield c
