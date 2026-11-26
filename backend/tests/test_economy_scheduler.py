from decimal import Decimal as D

import pytest

from app.db import connect, transaction
from app.economy import scheduler, store, world


def test_interval_requires_flag_and_sane_value(monkeypatch):
    monkeypatch.delenv("VE_ECONOMY_ENABLED", raising=False)
    monkeypatch.setenv("VE_ECONOMY_AUTOTICK_SECONDS", "30")
    assert scheduler.interval_seconds() == 0.0
    monkeypatch.setenv("VE_ECONOMY_ENABLED", "1")
    assert scheduler.interval_seconds() == 30.0
    for bad in ("1", "0", "abc", "-5", ""):
        monkeypatch.setenv("VE_ECONOMY_AUTOTICK_SECONDS", bad)
        assert scheduler.interval_seconds() == 0.0


def test_tick_once_advances_world_and_keeps_supply(client):
    with transaction() as db:
        world.bootstrap_demo(db)
    for _ in range(3):
        assert scheduler.tick_once()["tick"] > 0
    with connect() as db:
        assert store.status(db)["last_tick"] == 3
        store.load_ledger(db).assert_money_supply(D("50000"))


def test_failed_tick_is_swallowed_and_rolled_back(client, monkeypatch):
    with transaction() as db:
        world.bootstrap_demo(db)
    scheduler.tick_once()
    monkeypatch.setattr(world, "run_production_tick", lambda *a, **k: (_ for _ in ()).throw(RuntimeError("boom")))
    assert scheduler.tick_once() is None
    with connect() as db:
        assert store.status(db)["last_tick"] == 1
