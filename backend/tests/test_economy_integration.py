import sqlite3
from decimal import Decimal as D

import pytest

from app import db as appdb
from app.db import connect, init_db, transaction
from app.economy import Ledger, run_production_tick, store
from tests.test_companies import account, company, create_company

ADMIN = {"X-Admin-Key": "secret-admin"}


@pytest.fixture
def econ(client, monkeypatch):
    monkeypatch.setenv("VE_ECONOMY_ENABLED", "1")
    monkeypatch.setenv("VE_ECONOMY_ADMIN_KEY", "secret-admin")
    return client


def snapshot_legacy():
    with connect() as db:
        return {t: [tuple(r) for r in db.execute(f"SELECT * FROM {t} ORDER BY 1,2")]
                for t in ("accounts", "balances", "assets", "orders", "trades", "company_development")}


def tick(client, **body):
    return client.post("/economy/admin/tick", headers=ADMIN, json=body)


def test_migration_is_idempotent_and_keeps_legacy_data(client):
    headers = account(client, "alice")
    before = snapshot_legacy()
    with connect() as db:
        assert store.migrate(db) == []
    init_db()
    init_db()
    assert snapshot_legacy() == before
    with connect() as db:
        assert [r[0] for r in db.execute("SELECT version FROM schema_migrations")] == [1]
    assert client.get("/economy/status", headers=headers).json()["schema_version"] == 1


def test_migration_of_populated_old_db_makes_backup(client):
    account(client, "alice")
    with connect() as db:   # simulate a pre-economy database
        for t in ("econ_ticks", "econ_products", "econ_inventory", "econ_materials", "econ_firms",
                  "econ_households", "econ_journal", "econ_accounts", "econ_state", "schema_migrations"):
            db.execute(f"DROP TABLE {t}")
        db.commit()
    before = snapshot_legacy()
    init_db()
    assert snapshot_legacy() == before
    n_accounts = len(before["accounts"])
    bak = appdb.db_path().with_name(appdb.db_path().name + ".pre-economy-v1.bak")
    assert bak.exists()
    with sqlite3.connect(bak) as b:
        assert b.execute("SELECT COUNT(*) FROM accounts").fetchone()[0] == n_accounts


def test_read_requires_key_and_tick_requires_admin(client, monkeypatch):
    assert client.get("/economy/firms").status_code == 401
    assert client.get("/economy/firms", headers={"X-API-Key": "bad"}).status_code == 401
    assert tick(client).status_code == 503                      # disabled by default
    monkeypatch.setenv("VE_ECONOMY_ENABLED", "1")
    monkeypatch.setenv("VE_ECONOMY_ADMIN_KEY", "secret-admin")
    assert client.post("/economy/admin/tick", json={}).status_code == 403
    assert client.post("/economy/admin/tick", headers={"X-Admin-Key": "x"}, json={}).status_code == 403
    assert tick(client, hours="0").status_code == 400
    assert tick(client, hours="25").status_code == 400


def test_multi_tick_economy_persists_and_conserves_money(econ):
    h = account(econ, "alice")
    assert econ.post("/economy/admin/bootstrap-demo", headers=ADMIN).status_code == 201
    assert econ.post("/economy/admin/bootstrap-demo", headers=ADMIN).status_code == 409
    for n in range(1, 6):
        r = tick(econ)
        assert r.status_code == 200, r.text
        assert r.json()["tick"] == n
        assert D(r.json()["money_supply_after"]) == D("50000.00")
    assert D(r.json()["wages_paid"]) > 0 and D(r.json()["consumption_value"]) > 0
    firm = econ.get("/economy/firms/SIM-FIRM-1", headers=h).json()
    inv = {i["kind"] + ":" + i["sku"]: D(i["quantity"]) for i in firm["inventory"]}
    assert inv["product:widget"] > 0 or D(firm["cash"]) != D("20000")
    assert econ.get("/economy/households", headers=h).json()[0]["employed"] == 600
    txs = econ.get("/economy/transactions?limit=500", headers=h).json()
    ids = [t["id"] for t in txs]
    assert ids == list(range(1, len(ids) + 1))
    assert any(t["kind"] == "cash" and "payroll" in t["description"] for t in txs)
    assert all(t["kind"] == "cash" for t in econ.get("/economy/transactions?cash_only=true", headers=h).json())
    assert len(econ.get("/economy/ticks", headers=h).json()) == 5
    with connect() as db:   # persisted ledger is balanced, non-negative, supply constant
        ledger = store.load_ledger(db)
        ledger.assert_money_supply(D("50000"))
        assert db.execute("SELECT COUNT(*) FROM econ_journal").fetchone()[0] == len(ids)


def test_persisted_run_matches_in_memory_run(econ):
    econ.post("/economy/admin/bootstrap-demo", headers=ADMIN)
    for _ in range(3):
        assert tick(econ).status_code == 200
    ledger = Ledger()
    firms, hhs, pol = store.demo_world(ledger)
    for n in range(1, 4):
        ledger.set_tick(n)
        store.apply_procurement(ledger, firms[0], pol["SIM-FIRM-1"])
        run_production_tick(ledger, firms, hhs, D(1), tick=n)
    with connect() as db:
        persisted = store.load_ledger(db)
    assert {k: v for k, v in persisted.balances.items()} == dict(ledger.balances)


def test_duplicate_or_gapped_tick_rejected(econ):
    econ.post("/economy/admin/bootstrap-demo", headers=ADMIN)
    assert tick(econ, tick=1).status_code == 200
    assert tick(econ, tick=1).status_code == 409
    assert tick(econ, tick=5).status_code == 409
    assert tick(econ, tick=2).status_code == 200


def test_failed_tick_rolls_back_everything(econ, monkeypatch):
    econ.post("/economy/admin/bootstrap-demo", headers=ADMIN)
    assert tick(econ).status_code == 200
    with connect() as db:
        before = [tuple(r) for r in db.execute("SELECT * FROM econ_accounts ORDER BY name")], store.status(db)

    def boom(ledger, firms, households, hours, tick=None):
        firms[0].materials_stock["steel"] = D("0")
        ledger.post("a", "b", "5", "half-done")
        raise RuntimeError("boom")
    monkeypatch.setattr(store, "run_production_tick", boom)
    with pytest.raises(RuntimeError):
        with transaction() as db:
            store.run_persistent_tick(db)
    with connect() as db:
        assert ([tuple(r) for r in db.execute("SELECT * FROM econ_accounts ORDER BY name")], store.status(db)) == before


def test_money_supply_tampering_is_detected(econ):
    econ.post("/economy/admin/bootstrap-demo", headers=ADMIN)
    with connect() as db:
        db.execute("UPDATE econ_accounts SET balance=balance+1 WHERE name='households:SIM-H1:cash'")
        db.commit()
    with pytest.raises(AssertionError):
        with transaction() as db:
            store.run_persistent_tick(db)


def test_legacy_companies_linked_without_touching_money(econ):
    h = account(econ, "owner")
    create_company(econ, h, "NOVA")
    company(econ, h, "NOVA")
    econ.post("/economy/admin/bootstrap-demo", headers=ADMIN)
    before = snapshot_legacy()
    r = econ.post("/economy/admin/link-companies", headers=ADMIN)
    assert r.json()["linked"] == ["LEGACY-NOVA"]
    assert econ.post("/economy/admin/link-companies", headers=ADMIN).json()["linked"] == []
    for _ in range(3):
        t = tick(econ)
        assert t.status_code == 200
    assert t.json()["skipped_firms"] == [{"firm_id": "LEGACY-NOVA", "reason": "no payroll recipients in region"}]
    firm = econ.get("/economy/firms/LEGACY-NOVA", headers=h).json()
    assert firm["mode"] == "shadow" and firm["linked_symbol"] == "NOVA" and firm["cash"] == "0"
    after = snapshot_legacy()
    after["company_development"] = before["company_development"]  # only legacy settle timestamps may move
    assert after == before


def test_legacy_system_unchanged_by_economy_activity(econ):
    h = account(econ, "owner")
    create_company(econ, h, "NOVA")
    before = snapshot_legacy()
    econ.post("/economy/admin/bootstrap-demo", headers=ADMIN)
    tick(econ)
    assert snapshot_legacy() == before
    assert econ.post("/companies/NOVA/capital", headers=h, json={"amount": "100"}).status_code == 200
    assert econ.get("/portfolio", headers=h).status_code == 200
