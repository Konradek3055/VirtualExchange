from decimal import Decimal as D

import pytest

from app.db import connect, transaction
from app.economy import Ledger, accounting, store, world
from tests.test_companies import account, company, create_company, usd

ADMIN = {"X-Admin-Key": "secret-admin"}
SUPPLY = D("50000")


@pytest.fixture
def econ(client, monkeypatch):
    monkeypatch.setenv("VE_ECONOMY_ENABLED", "1")
    monkeypatch.setenv("VE_ECONOMY_ADMIN_KEY", "secret-admin")
    monkeypatch.setenv("VE_ECONOMY_BRIDGE_ENABLED", "1")
    return client


def run_ticks(n):
    for _ in range(n):
        with transaction() as db:
            world.run_persistent_tick(db)


def bootstrap():
    with transaction() as db:
        world.bootstrap_demo(db)


def all_subjects(db):
    return ([("firm", r[0]) for r in db.execute("SELECT firm_id FROM econ_firms")]
            + [("entity", r[0]) for r in db.execute("SELECT entity_id FROM econ_entities")])


def assert_sound(db, supply=SUPPLY):
    ledger = store.load_ledger(db)
    ledger.assert_money_supply(supply)                       # supply, >=0 cash, journal balanced
    ids = [r[0] for r in db.execute("SELECT entry_id FROM econ_journal ORDER BY entry_id")]
    assert ids == list(range(1, len(ids) + 1))               # contiguous, unique ids
    assert not [n for n in ledger.balances if n.startswith("market:") and ledger.balances[n] != 0]
    for kind, ident in all_subjects(db):
        sheet = accounting.statements(db, kind, ident)
        assert sheet["balance_sheet"]["balanced"], (ident, sheet["balance_sheet"])
        assert sheet["cash_flow"]["reconciles"], (ident, sheet["cash_flow"])
        assert D(sheet["balance_sheet"]["liabilities"]["taxes_payable"]) >= 0


def test_100_ticks_full_circulation(econ):
    bootstrap()
    for block in range(10):
        run_ticks(10)
        with connect() as db:
            assert_sound(db)
    with connect() as db:
        ledger = store.load_ledger(db)
        flows = {}
        for r in db.execute("SELECT description,amount FROM econ_journal"):
            key = r["description"].split(":")[0] if r["description"].startswith(("tax:", "dividend:", "transfer:")) \
                else ("payroll" if " payroll for " in r["description"] else "other")
            flows[key] = flows.get(key, D("0")) + D(r["amount"])
        assert flows["payroll"] > 0 and flows["tax"] > 0 and flows["dividend"] > 0 and flows["transfer"] > 0
        # suppliers are real businesses: they earned revenue, paid staff and paid their owner
        for ent in ("SIM-STEEL", "SIM-ENERGY"):
            inc = accounting.income_statement(db, "entity", ent)
            assert D(inc["revenue"]) > 0 and D(inc["wage_expense"]) > 0 and D(inc["dividends_declared"]) > 0
        assert ledger.balance("households:SIM-OWN:cash") > 0     # owners were paid
        assert ledger.balance("households:SIM-H1:cash") > 0      # workers still solvent after 100 ticks
        assert db.execute("SELECT MAX(tick) FROM econ_ticks").fetchone()[0] == 100


def test_revenue_equals_customer_receipts_no_double_counting(econ):
    bootstrap()
    run_ticks(30)
    with connect() as db:
        for kind, ident in all_subjects(db):
            sheet = accounting.statements(db, kind, ident)
            lines = sheet["cash_flow"]["lines"]
            assert D(sheet["income_statement"]["revenue"]) == D(lines.get("operating:customer_receipts", "0"))
        inc = accounting.income_statement(db, "firm", "SIM-FIRM-1")
        assert D(inc["revenue"]) > 0 and D(inc["cost_of_goods_sold"]) > 0
        assert D(inc["net_income"]) == D(inc["revenue"]) - D(inc["cost_of_goods_sold"]) - D(inc["depreciation"]) \
            - D(inc["corporate_tax"])


def test_restart_resumes_exactly_like_uninterrupted_run(econ):
    bootstrap()
    run_ticks(15)
    with connect() as db:                      # "process restart": nothing survives except the file
        store.load_world = None
        mid = dict(store.load_ledger(db).balances)
    run_ticks(15)
    twin = world.demo_world(Ledger())
    for n in range(1, 16):
        world.tick_world(twin, n, D(1))
    assert dict(twin.ledger.balances) == mid
    for n in range(16, 31):
        world.tick_world(twin, n, D(1))
    with connect() as db:
        assert dict(store.load_ledger(db).balances) == dict(twin.ledger.balances)
        firms, _ = store.load_firms(db)
        assert {k: v.quantity for k, v in firms[0].inventory.items()} == \
               {k: v.quantity for k, v in twin.firms[0].inventory.items()}


def test_failed_tick_after_circulation_rolls_back(econ, monkeypatch):
    bootstrap()
    run_ticks(3)
    with connect() as db:
        before = ([tuple(r) for r in db.execute("SELECT * FROM econ_accounts ORDER BY name")],
                  db.execute("SELECT COUNT(*) FROM econ_journal").fetchone()[0], store.status(db))

    def boom(*a, **k):
        raise RuntimeError("settlement crashed")
    monkeypatch.setattr(world.circulation, "settle", boom)
    with pytest.raises(RuntimeError):
        run_ticks(1)
    with connect() as db:
        assert ([tuple(r) for r in db.execute("SELECT * FROM econ_accounts ORDER BY name")],
                db.execute("SELECT COUNT(*) FROM econ_journal").fetchone()[0], store.status(db)) == before


def test_legacy_market_accounts_are_adopted_without_creating_money(econ):
    bootstrap()
    run_ticks(2)
    with connect() as db:   # simulate a pre-v2 world: 500 sits in an anonymous market:* account
        db.execute("UPDATE econ_accounts SET balance=CAST(balance AS REAL)-500 WHERE name='households:SIM-H1:cash'")
        db.execute("INSERT INTO econ_accounts(name,is_money,balance) VALUES('market:energy:cash',1,'500')")
        db.execute("UPDATE econ_accounts SET balance='0' WHERE name='market:energy:cash' AND 0")
        db.commit()
        cash = D(db.execute("SELECT balance FROM econ_accounts WHERE name='households:SIM-H1:cash'").fetchone()[0])
        db.execute("UPDATE econ_accounts SET balance=? WHERE name='households:SIM-H1:cash'", (str(cash),))
        db.commit()
    run_ticks(5)
    with connect() as db:
        assert db.execute("SELECT 1 FROM econ_entities WHERE entity_id='LEGACY-ENERGY'").fetchone()
        ledger = store.load_ledger(db)
        assert ledger.balance("market:energy:cash") < D("500")   # it is being paid out to staff/owner/government
        ledger.assert_money_supply(SUPPLY)


def test_tampered_account_blocks_tick_and_changes_nothing(econ):
    bootstrap()
    run_ticks(2)
    with connect() as db:
        db.execute("UPDATE econ_accounts SET balance=balance+1 WHERE name='entity:SIM-STEEL:cash'")
        db.commit()
    with pytest.raises(AssertionError):
        run_ticks(1)


def api(client, headers, method, path, **body):
    return getattr(client, method)(path, headers=headers, json=body) if body or method == "post" \
        else getattr(client, method)(path, headers=headers)


def test_player_builds_and_runs_a_firm_end_to_end(econ):
    bootstrap()
    h = account(econ, "founder")
    other = account(econ, "other")
    r = econ.post("/economy/player/firms", headers=h, json={"name": "Bakery One", "region": "SIM", "product": "bread"})
    assert r.status_code == 201, r.text
    fid = r.json()["firm_id"]
    base = f"/economy/player/firms/{fid}"
    wallet = usd(econ, h)
    # validation: funds, permissions, bounds
    assert econ.post(f"{base}/materials", headers=h, json={"sku": "grain", "quantity": "10"}).status_code == 400
    assert econ.post(f"{base}/deposit", headers=other, json={"amount": "100"}).status_code == 403
    assert econ.post(f"{base}/deposit", headers=h, json={"amount": "999999"}).status_code == 400
    assert econ.post(f"{base}/price", headers=h, json={"markup": "9"}).status_code == 400
    assert econ.post(f"{base}/staff", headers=h, json={"action": "hire", "count": 5}).status_code == 400  # no cash
    assert econ.post("/economy/player/firms", headers=h, json={"name": "x", "region": "SIM", "product": "bread"}).status_code == 400
    # fund through the bridge: USD leaves the wallet, the same amount of economy money appears
    assert econ.post(f"{base}/deposit", headers=h, json={"amount": "6000"}).status_code == 200
    assert usd(econ, h) == wallet - D("6000")
    assert econ.get("/economy/status", headers=h).json()["money_supply"] == "56000.00"
    assert econ.post(f"{base}/materials", headers=h, json={"sku": "grain", "quantity": "100"}).json()["cost"] == "100.00"
    assert econ.post(f"{base}/procurement", headers=h, json={"sku": "grain", "target": "300"}).status_code == 200
    assert econ.post(f"{base}/staff", headers=h, json={"action": "hire", "count": 9}).json()["employees"] == 10
    assert econ.post(f"{base}/wage", headers=h, json={"wage_per_hour": "14"}).status_code == 200
    assert econ.post(f"{base}/price", headers=h, json={"markup": "1.4"}).status_code == 200
    assert econ.post(f"{base}/capex", headers=h, json={"amount": "1000"}).json()["capacity_per_hour"] == "6.00000000"
    run_ticks(40)
    with connect() as db:
        assert_sound(db, D("56000"))
        inc = accounting.income_statement(db, "firm", fid)
        assert D(inc["revenue"]) > 0 and D(inc["depreciation"]) > 0
        assert D(accounting.income_statement(db, "entity", "SIM-MACHINES")["revenue"]) == D("1000")
        assert D(accounting.income_statement(db, "entity", "SIM-GRAIN")["revenue"]) > 0
    # dividends and withdrawal return money to the wallet; bridge keeps total supply consistent
    paid = D(inc["net_income"]) - D(inc["dividends_declared"])
    assert econ.post(f"{base}/dividend", headers=h, json={"amount": str(paid + 1000)}).status_code == 400
    if paid >= D("1"):
        assert econ.post(f"{base}/dividend", headers=h, json={"amount": "1.00"}).status_code == 200
        assert econ.post("/economy/player/withdraw", headers=h, json={"amount": "1.00"}).status_code == 200
        assert usd(econ, h) == wallet - D("6000") + D("1.00")
    assert econ.post("/economy/player/withdraw", headers=h, json={"amount": "99999"}).status_code == 400
    assert len(econ.get("/economy/player/firms", headers=h).json()) == 1
    assert econ.get("/economy/player/firms", headers=other).json() == []
    sheet = econ.get(f"/economy/firms/{fid}/statements", headers=h).json()
    assert sheet["balance_sheet"]["balanced"] and sheet["cash_flow"]["reconciles"]


def test_two_player_firms_share_suppliers_and_stay_consistent(econ):
    bootstrap()
    a, b = account(econ, "player_a"), account(econ, "player_b")
    ids = []
    for h, prod in ((a, "widget"), (b, "bread")):
        fid = econ.post("/economy/player/firms", headers=h, json={"name": f"Firm {prod}", "region": "SIM", "product": prod}).json()["firm_id"]
        ids.append(fid)
        assert econ.post(f"/economy/player/firms/{fid}/deposit", headers=h, json={"amount": "3000"}).status_code == 200
        sku = "steel" if prod == "widget" else "grain"
        assert econ.post(f"/economy/player/firms/{fid}/procurement", headers=h, json={"sku": sku, "target": "100"}).status_code == 200
    run_ticks(25)
    with connect() as db:
        assert_sound(db, SUPPLY + D("6000"))
        for fid in ids:
            assert D(accounting.income_statement(db, "firm", fid)["revenue"]) > 0


def test_bridge_disabled_by_default_and_wallet_untouched(client, monkeypatch):
    monkeypatch.setenv("VE_ECONOMY_ENABLED", "1")
    monkeypatch.setenv("VE_ECONOMY_ADMIN_KEY", "secret-admin")
    bootstrap()
    h = account(client, "founder")
    fid = client.post("/economy/player/firms", headers=h, json={"name": "Bakery", "region": "SIM", "product": "bread"}).json()["firm_id"]
    before = usd(client, h)
    assert client.post(f"/economy/player/firms/{fid}/deposit", headers=h, json={"amount": "10"}).status_code == 503
    assert client.post("/economy/player/withdraw", headers=h, json={"amount": "10"}).status_code == 503
    assert usd(client, h) == before


def test_legacy_company_activation_moves_cash_without_duplicating_it(econ):
    bootstrap()
    h = account(econ, "owner")
    create_company(econ, h, "NOVA")
    assert econ.post("/companies/NOVA/capital", headers=h, json={"amount": "1000"}).status_code == 200
    wallet, legacy_cash = usd(econ, h), D(company(econ, h, "NOVA")["cash"])
    other = account(econ, "thief")
    body = {"region": "SIM", "product": "widget", "amount": "400"}
    assert econ.post("/economy/player/companies/NOVA/activate", headers=other, json=body).status_code == 403
    assert econ.post("/economy/player/companies/NOVA/activate", headers=h, json={**body, "amount": "99999"}).status_code == 400
    r = econ.post("/economy/player/companies/NOVA/activate", headers=h, json=body)
    assert r.status_code == 200, r.text
    assert r.json()["firm_id"] == "CO-NOVA" and r.json()["cash"] == "400.00"
    assert usd(econ, h) == wallet                                         # wallet untouched
    assert D(company(econ, h, "NOVA")["cash"]) == legacy_cash - D("400")  # moved, not copied
    assert econ.get("/economy/status", headers=h).json()["money_supply"] == "50400.00"
    with connect() as db:
        assert db.execute("SELECT amount,kind FROM econ_bridge").fetchone()[:] == ("400.00", "legacy_company")
    run_ticks(5)
    with connect() as db:
        assert_sound(db, SUPPLY + D("400"))
