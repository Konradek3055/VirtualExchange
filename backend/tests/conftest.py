import pytest
from fastapi.testclient import TestClient


@pytest.fixture(autouse=True)
def isolated_db(tmp_path, monkeypatch):
    """Każdy test dostaje świeżą bazę SQLite (żadnych śladów po poprzednich testach)."""
    monkeypatch.setenv("VE_DB_PATH", str(tmp_path / "test.sqlite3"))


@pytest.fixture
def client():
    from app.main import app
    with TestClient(app) as c:
        yield c
