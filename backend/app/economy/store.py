"""SQLite persistence and guarded tick runner for the economy core.

The economy lives in its own ``econ_*`` tables and its own cash accounts.  It is
deliberately NOT connected to ``balances`` / ``accounts`` / ``company_development``:
no tick, link or bootstrap here reads or writes production user money.
"""
from __future__ import annotations

import json
import sqlite3
from decimal import Decimal
from typing import Any, Dict, List, Optional, Tuple

from .engine_core import run_production_tick
from .households import HouseholdGroup
from .ledger import D, Ledger, money, quantity, unit_value
from .production import FirmProductionState, InventoryItem, Product

MAX_TICK_HOURS = D("24")
ISSUANCE_SOURCE = "system:monetary-issuance-counterpart"

_V1 = """
CREATE TABLE IF NOT EXISTS econ_state(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS econ_accounts(
    name TEXT PRIMARY KEY, is_money INTEGER NOT NULL DEFAULT 0, balance TEXT NOT NULL DEFAULT '0');
CREATE TABLE IF NOT EXISTS econ_journal(
    entry_id INTEGER PRIMARY KEY, tick INTEGER NOT NULL, debit_account TEXT NOT NULL,
    credit_account TEXT NOT NULL, amount TEXT NOT NULL, description TEXT NOT NULL, ref TEXT);
CREATE TABLE IF NOT EXISTS econ_firms(
    firm_id TEXT PRIMARY KEY, region TEXT NOT NULL, cash_account TEXT NOT NULL,
    employees INTEGER NOT NULL, wage_per_hour TEXT NOT NULL, productivity TEXT NOT NULL,
    capacity_per_hour TEXT NOT NULL, energy_cost_per_unit TEXT NOT NULL,
    energy_supplier_cash_account TEXT NOT NULL, sales_markup TEXT NOT NULL,
    payroll_shares TEXT NOT NULL DEFAULT '{}', procurement TEXT NOT NULL DEFAULT '{}',
    mode TEXT NOT NULL DEFAULT 'sandbox', linked_symbol TEXT);
CREATE TABLE IF NOT EXISTS econ_products(
    firm_id TEXT NOT NULL REFERENCES econ_firms(firm_id), sku TEXT NOT NULL, name TEXT NOT NULL,
    material_sku TEXT NOT NULL, material_units_per_unit TEXT NOT NULL,
    unit_labor_hours TEXT NOT NULL, quality INTEGER NOT NULL, category TEXT NOT NULL,
    PRIMARY KEY(firm_id, sku));
CREATE TABLE IF NOT EXISTS econ_inventory(
    firm_id TEXT NOT NULL REFERENCES econ_firms(firm_id), sku TEXT NOT NULL,
    quantity TEXT NOT NULL, avg_cost TEXT NOT NULL, PRIMARY KEY(firm_id, sku));
CREATE TABLE IF NOT EXISTS econ_materials(
    firm_id TEXT NOT NULL REFERENCES econ_firms(firm_id), material_sku TEXT NOT NULL,
    quantity TEXT NOT NULL, unit_cost TEXT NOT NULL, supplier_cash_account TEXT,
    PRIMARY KEY(firm_id, material_sku));
CREATE TABLE IF NOT EXISTS econ_households(
    group_id TEXT PRIMARY KEY, region TEXT NOT NULL, population INTEGER NOT NULL,
    avg_wage TEXT NOT NULL, employed INTEGER NOT NULL, savings_rate TEXT NOT NULL,
    cash_account TEXT NOT NULL, demand_weights TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS econ_ticks(
    tick INTEGER PRIMARY KEY, hours TEXT NOT NULL, money_supply_before TEXT NOT NULL,
    money_supply_after TEXT NOT NULL, entries_added INTEGER NOT NULL, stats TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE INDEX IF NOT EXISTS idx_econ_journal_tick ON econ_journal(tick, entry_id);
"""
MIGRATIONS = [(1, "economy_core_tables", _V1)]


class EconomyError(Exception):
    status = 400


class TickConflict(EconomyError):
    status = 409


class InvariantViolation(EconomyError):
    status = 500


# ---------------------------------------------------------------- migrations
def _backup_if_needed(db: sqlite3.Connection, version: int) -> Optional[str]:
    """Copy a populated DB before the first economy migration touches it."""
    from ..db import db_path
    has = db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='accounts'").fetchone()
    if not has or not db.execute("SELECT 1 FROM accounts LIMIT 1").fetchone():
        return None
    path = db_path()
    dest = path.with_name(f"{path.name}.pre-economy-v{version}.bak")
    if dest.exists():
        return str(dest)
    target = sqlite3.connect(dest)
    try:
        db.backup(target)
    finally:
        target.close()
    return str(dest)


def migrate(db: sqlite3.Connection) -> List[int]:
    """Additive, idempotent migrations. Never drops or rewrites existing tables."""
    db.execute("CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, "
               "name TEXT NOT NULL, applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)")
    db.commit()
    applied = {r[0] for r in db.execute("SELECT version FROM schema_migrations")}
    done = []
    for version, name, sql in MIGRATIONS:
        if version in applied:
            continue
        _backup_if_needed(db, version)
        try:
            db.executescript(f"BEGIN IMMEDIATE;\n{sql}\n"
                             f"INSERT INTO schema_migrations(version,name) VALUES({version},'{name}');\nCOMMIT;")
        except Exception:
            if db.in_transaction:
                db.rollback()
            raise
        done.append(version)
    return done


# ------------------------------------------------------------------- helpers
def get_state(db, key: str, default: str = "0") -> str:
    row = db.execute("SELECT value FROM econ_state WHERE key=?", (key,)).fetchone()
    return row[0] if row else default


def set_state(db, key: str, value: str) -> None:
    db.execute("INSERT INTO econ_state(key,value) VALUES(?,?) "
               "ON CONFLICT(key) DO UPDATE SET value=excluded.value", (key, value))


def jsonable(obj: Any) -> Any:
    if isinstance(obj, Decimal):
        return str(obj)
    if isinstance(obj, dict):
        return {str(k): jsonable(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [jsonable(v) for v in obj]
    return obj


def load_ledger(db) -> Ledger:
    ledger = Ledger()
    for r in db.execute("SELECT name,is_money,balance FROM econ_accounts ORDER BY name"):
        ledger.balances[r["name"]] = D(r["balance"])
        if r["is_money"]:
            ledger.money_accounts.add(r["name"])
    ledger.current_tick = int(get_state(db, "last_tick", "0"))
    ledger.entry_offset = db.execute("SELECT COALESCE(MAX(entry_id),0) FROM econ_journal").fetchone()[0]
    return ledger


def save_ledger(db, ledger: Ledger) -> None:
    for name, bal in sorted(ledger.balances.items()):
        db.execute("INSERT INTO econ_accounts(name,is_money,balance) VALUES(?,?,?) "
                   "ON CONFLICT(name) DO UPDATE SET is_money=excluded.is_money, balance=excluded.balance",
                   (name, 1 if name in ledger.money_accounts else 0, str(bal)))
    for e in ledger.entries:
        db.execute("INSERT INTO econ_journal(entry_id,tick,debit_account,credit_account,amount,description,ref) "
                   "VALUES(?,?,?,?,?,?,?)",
                   (e.entry_id, e.tick, e.debit_account, e.credit_account, str(e.amount), e.description, e.ref))
    ledger.entry_offset += len(ledger.entries)
    ledger.entries.clear()


def load_households(db) -> List[HouseholdGroup]:
    return [HouseholdGroup(
        group_id=r["group_id"], region=r["region"], population=r["population"],
        avg_wage=D(r["avg_wage"]), employed=r["employed"], savings_rate=D(r["savings_rate"]),
        cash_account=r["cash_account"],
        demand_weights={k: D(v) for k, v in json.loads(r["demand_weights"]).items()},
    ) for r in db.execute("SELECT * FROM econ_households ORDER BY group_id")]


def load_firms(db) -> Tuple[List[FirmProductionState], Dict[str, dict]]:
    firms, meta = [], {}
    for r in db.execute("SELECT * FROM econ_firms ORDER BY firm_id"):
        fid = r["firm_id"]
        f = FirmProductionState(
            firm_id=fid, region=r["region"], cash_account=r["cash_account"], employees=r["employees"],
            wage_per_hour=D(r["wage_per_hour"]), productivity=D(r["productivity"]),
            capacity_per_hour=D(r["capacity_per_hour"]), energy_cost_per_unit=D(r["energy_cost_per_unit"]),
            energy_supplier_cash_account=r["energy_supplier_cash_account"], sales_markup=D(r["sales_markup"]),
            payroll_shares={k: D(v) for k, v in json.loads(r["payroll_shares"]).items()})
        for p in db.execute("SELECT * FROM econ_products WHERE firm_id=? ORDER BY sku", (fid,)):
            f.products[p["sku"]] = Product(
                sku=p["sku"], name=p["name"], material_sku=p["material_sku"],
                material_units_per_unit=D(p["material_units_per_unit"]),
                unit_labor_hours=D(p["unit_labor_hours"]), quality=p["quality"], category=p["category"])
        for i in db.execute("SELECT * FROM econ_inventory WHERE firm_id=?", (fid,)):
            f.inventory[i["sku"]] = InventoryItem(i["sku"], D(i["quantity"]), D(i["avg_cost"]))
        for m in db.execute("SELECT * FROM econ_materials WHERE firm_id=?", (fid,)):
            f.materials_stock[m["material_sku"]] = D(m["quantity"])
            f.materials_unit_cost[m["material_sku"]] = D(m["unit_cost"])
            if m["supplier_cash_account"]:
                f.material_supplier_cash_accounts[m["material_sku"]] = m["supplier_cash_account"]
        firms.append(f)
        meta[fid] = {"mode": r["mode"], "linked_symbol": r["linked_symbol"],
                     "procurement": json.loads(r["procurement"])}
    return firms, meta


def save_firm_state(db, firm: FirmProductionState) -> None:
    fid = firm.firm_id
    db.execute("DELETE FROM econ_inventory WHERE firm_id=?", (fid,))
    for sku, i in sorted(firm.inventory.items()):
        db.execute("INSERT INTO econ_inventory VALUES(?,?,?,?)", (fid, sku, str(i.quantity), str(i.avg_cost)))
    db.execute("DELETE FROM econ_materials WHERE firm_id=?", (fid,))
    for sku, qty in sorted(firm.materials_stock.items()):
        db.execute("INSERT INTO econ_materials VALUES(?,?,?,?,?)",
                   (fid, sku, str(qty), str(firm.materials_unit_cost.get(sku, D("0"))),
                    firm.material_supplier_cash_accounts.get(sku)))


def insert_firm(db, firm: FirmProductionState, procurement: Optional[dict] = None,
                mode: str = "sandbox", linked_symbol: Optional[str] = None) -> None:
    db.execute("INSERT INTO econ_firms VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", (
        firm.firm_id, firm.region, firm.cash_account, firm.employees, str(firm.wage_per_hour),
        str(firm.productivity), str(firm.capacity_per_hour), str(firm.energy_cost_per_unit),
        firm.energy_supplier_cash_account, str(firm.sales_markup),
        json.dumps({k: str(v) for k, v in firm.payroll_shares.items()}),
        json.dumps(procurement or {}), mode, linked_symbol))
    for p in firm.products.values():
        db.execute("INSERT INTO econ_products VALUES(?,?,?,?,?,?,?,?)", (
            firm.firm_id, p.sku, p.name, p.material_sku, str(p.material_units_per_unit),
            str(p.unit_labor_hours), p.quality, p.category))
    save_firm_state(db, firm)


def insert_household(db, h: HouseholdGroup) -> None:
    db.execute("INSERT INTO econ_households VALUES(?,?,?,?,?,?,?,?)", (
        h.group_id, h.region, h.population, str(h.avg_wage), h.employed, str(h.savings_rate),
        h.cash_account, json.dumps({k: str(v) for k, v in h.demand_weights.items()})))


# ----------------------------------------------------------------- bootstrap
def demo_world(ledger: Ledger):
    """Self-contained sandbox world; all money comes from explicit issuance."""
    hh = HouseholdGroup(group_id="SIM-H1", region="SIM", population=1000, avg_wage=D("15"),
                        employed=600, savings_rate=D("0.10"))
    ledger.issue_money(hh.cash_account, D("30000"), "sandbox demo household opening cash")
    ledger.issue_money("firm:SIM-FIRM-1:cash", D("20000"), "sandbox demo firm opening capital")
    firm = FirmProductionState(
        firm_id="SIM-FIRM-1", region="SIM", cash_account="firm:SIM-FIRM-1:cash", employees=10,
        wage_per_hour=D("10"), capacity_per_hour=D("5"), energy_cost_per_unit=D("0.8"),
        energy_supplier_cash_account="market:energy:cash", payroll_shares={"SIM-H1": D("1")})
    firm.products["widget"] = Product(sku="widget", name="Widget", material_sku="steel",
                                      material_units_per_unit=D("1"), unit_labor_hours=D("0.5"),
                                      category="goods")
    policy = {"steel": {"target": "200", "unit_cost": "2",
                        "supplier": "market:materials:cash", "max_cash_share": "0.5"}}
    return [firm], [hh], {"SIM-FIRM-1": policy}


def bootstrap_demo(db) -> dict:
    if db.execute("SELECT 1 FROM econ_firms WHERE firm_id='SIM-FIRM-1'").fetchone():
        raise TickConflict("Demo world already exists")
    ledger = load_ledger(db)
    firms, households, policies = demo_world(ledger)
    for h in households:
        insert_household(db, h)
    for f in firms:
        insert_firm(db, f, policies[f.firm_id])
    save_ledger(db, ledger)
    set_state(db, "money_supply", str(ledger.money_supply()))
    return {"firms": [f.firm_id for f in firms], "households": [h.group_id for h in households],
            "money_supply": str(ledger.money_supply())}


def link_existing_companies(db) -> dict:
    """Create zero-cash shadow firms for legacy companies. Reads company_development
    only; moves no money and writes nothing to the legacy tables."""
    created = []
    for r in db.execute("SELECT symbol,employees,production_level,logistics_level "
                        "FROM company_development ORDER BY symbol").fetchall():
        fid = f"LEGACY-{r['symbol']}"
        if db.execute("SELECT 1 FROM econ_firms WHERE firm_id=?", (fid,)).fetchone():
            continue
        firm = FirmProductionState(
            firm_id=fid, region="LEGACY", cash_account=f"firm:{fid}:cash",
            employees=max(1, int(r["employees"])), wage_per_hour=D("10"),
            capacity_per_hour=D("5") + D(int(r["production_level"])) / D("2"),
            productivity=D("1") + D(int(r["logistics_level"])) / D("50"))
        firm.products["goods"] = Product(sku="goods", name=f"{r['symbol']} goods", category="goods",
                                         material_units_per_unit=D("0"))
        insert_firm(db, firm, mode="shadow", linked_symbol=r["symbol"])
        db.execute("INSERT OR IGNORE INTO econ_accounts(name,is_money,balance) VALUES(?,1,'0')",
                   (firm.cash_account,))
        created.append(fid)
    return {"linked": created}


# ---------------------------------------------------------------------- tick
def apply_procurement(ledger: Ledger, firm: FirmProductionState, policy: dict) -> Decimal:
    """Top up inputs toward target stock, spending at most max_cash_share of cash."""
    spent = D("0")
    for sku, rule in sorted(policy.items()):
        need = quantity(D(rule["target"]) - quantity(firm.materials_stock.get(sku, D("0"))))
        price = unit_value(rule["unit_cost"])
        budget = ledger.balance(firm.cash_account) * D(rule.get("max_cash_share", "0.5"))
        qty = quantity(min(need, budget / price)) if price > 0 else D("0")
        if qty <= 0 or money(qty * price) <= 0:
            continue
        firm.acquire_materials(ledger, sku, qty, price, rule["supplier"])
        spent += money(qty * price)
    return money(spent)


def run_persistent_tick(db, tick: Optional[int] = None, hours: Decimal = D("1")) -> dict:
    """Run one tick. Caller must hold a BEGIN IMMEDIATE transaction (db.transaction())."""
    hours = D(str(hours))
    if not hours.is_finite() or hours <= 0 or hours > MAX_TICK_HOURS:
        raise EconomyError("hours must be > 0 and <= 24")
    last = int(get_state(db, "last_tick", "0"))
    if tick is not None and tick != last + 1:
        raise TickConflict(f"Expected tick {last + 1}, got {tick}")
    nxt = last + 1
    ledger = load_ledger(db)
    firms, meta = load_firms(db)
    households = load_households(db)
    expected = D(get_state(db, "money_supply", "0"))
    ledger.assert_money_supply(expected)  # persisted state must already be sound

    active, skipped, purchases = [], [], D("0")
    regions = {h.region for h in households if h.employed > 0}
    for f in firms:
        by_id = {h.group_id: h for h in households}
        bad_shares = f.payroll_shares and any(
            g not in by_id or by_id[g].region != f.region for g in f.payroll_shares)
        if f.region not in regions or bad_shares:
            skipped.append({"firm_id": f.firm_id, "reason": "no payroll recipients in region"})
        else:
            active.append(f)
    ledger.set_tick(nxt)
    for f in active:
        purchases += apply_procurement(ledger, f, meta[f.firm_id]["procurement"])
    stats = run_production_tick(ledger, active, households, hours, tick=nxt)
    try:
        ledger.assert_money_supply(expected)
    except AssertionError as exc:
        raise InvariantViolation(str(exc)) from exc
    stats.update({"materials_purchased": money(purchases), "skipped_firms": skipped, "tick": nxt})
    added = len(ledger.entries)
    save_ledger(db, ledger)
    for f in active:
        save_firm_state(db, f)
    db.execute("INSERT INTO econ_ticks(tick,hours,money_supply_before,money_supply_after,entries_added,stats) "
               "VALUES(?,?,?,?,?,?)", (nxt, str(hours), str(expected), str(ledger.money_supply()),
                                       added, json.dumps(jsonable(stats))))
    set_state(db, "last_tick", str(nxt))
    return jsonable(stats)


# --------------------------------------------------------------------- reads
def _bal(db, name: str) -> str:
    r = db.execute("SELECT balance FROM econ_accounts WHERE name=?", (name,)).fetchone()
    return r[0] if r else "0"


def status(db) -> dict:
    n = lambda t: db.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]  # noqa: E731 (fixed names)
    return {"last_tick": int(get_state(db, "last_tick", "0")), "money_supply": get_state(db, "money_supply", "0"),
            "firms": n("econ_firms"), "households": n("econ_households"), "journal_entries": n("econ_journal"),
            "schema_version": db.execute("SELECT COALESCE(MAX(version),0) FROM schema_migrations").fetchone()[0]}


def list_firms(db) -> List[dict]:
    return [{"firm_id": r["firm_id"], "region": r["region"], "mode": r["mode"],
             "linked_symbol": r["linked_symbol"], "employees": r["employees"],
             "cash": _bal(db, r["cash_account"])} for r in db.execute("SELECT * FROM econ_firms ORDER BY firm_id")]


def get_firm(db, firm_id: str) -> Optional[dict]:
    r = db.execute("SELECT * FROM econ_firms WHERE firm_id=?", (firm_id,)).fetchone()
    if r is None:
        return None
    out = {k: r[k] for k in ("firm_id", "region", "mode", "linked_symbol", "employees", "wage_per_hour",
                             "capacity_per_hour", "productivity", "sales_markup")}
    out["cash"] = _bal(db, r["cash_account"])
    out["products"] = [dict(p) for p in db.execute(
        "SELECT sku,name,material_sku,unit_labor_hours,category FROM econ_products WHERE firm_id=? ORDER BY sku", (firm_id,))]
    out["inventory"] = list_inventory(db, firm_id)
    return out


def list_inventory(db, firm_id: Optional[str] = None) -> List[dict]:
    where, args = ("WHERE firm_id=?", (firm_id,)) if firm_id else ("", ())
    goods = [{"firm_id": r["firm_id"], "kind": "product", "sku": r["sku"], "quantity": r["quantity"],
              "unit_cost": r["avg_cost"]} for r in db.execute(f"SELECT * FROM econ_inventory {where} ORDER BY firm_id,sku", args)]
    mats = [{"firm_id": r["firm_id"], "kind": "material", "sku": r["material_sku"], "quantity": r["quantity"],
             "unit_cost": r["unit_cost"]} for r in db.execute(f"SELECT * FROM econ_materials {where} ORDER BY firm_id,material_sku", args)]
    return goods + mats


def list_households(db) -> List[dict]:
    return [{"group_id": r["group_id"], "region": r["region"], "population": r["population"],
             "employed": r["employed"], "savings_rate": r["savings_rate"],
             "cash": _bal(db, r["cash_account"])} for r in db.execute("SELECT * FROM econ_households ORDER BY group_id")]


def list_transactions(db, after_id: int = 0, limit: int = 100, firm_id: Optional[str] = None,
                      cash_only: bool = False) -> List[dict]:
    q = ("SELECT j.*, (d.is_money=1 AND c.is_money=1) AS is_cash FROM econ_journal j "
         "LEFT JOIN econ_accounts d ON d.name=j.debit_account LEFT JOIN econ_accounts c ON c.name=j.credit_account "
         "WHERE j.entry_id>?")
    args: list = [after_id]
    if firm_id:
        prefix = f"firm:{firm_id}:"
        q += " AND (instr(j.debit_account,?)=1 OR instr(j.credit_account,?)=1)"
        args += [prefix, prefix]
    if cash_only:
        q += " AND d.is_money=1 AND c.is_money=1"
    q += " ORDER BY j.entry_id LIMIT ?"
    args.append(max(1, min(limit, 500)))
    return [{"id": r["entry_id"], "tick": r["tick"], "debit": r["debit_account"], "credit": r["credit_account"],
             "amount": r["amount"], "description": r["description"], "ref": r["ref"],
             "kind": "cash" if r["is_cash"] else "accounting"} for r in db.execute(q, args)]


def list_ticks(db, limit: int = 50) -> List[dict]:
    return [{"tick": r["tick"], "hours": r["hours"], "money_supply_before": r["money_supply_before"],
             "money_supply_after": r["money_supply_after"], "entries_added": r["entries_added"],
             "stats": json.loads(r["stats"])} for r in db.execute(
        "SELECT * FROM econ_ticks ORDER BY tick DESC LIMIT ?", (max(1, min(limit, 200)),))]
