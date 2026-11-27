"""World assembly, full-circulation tick, bootstrap and the guarded persistent tick runner."""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from decimal import Decimal
from typing import Dict, List, Optional

from . import circulation, store
from .circulation import Entity, Region
from .engine_core import run_production_tick
from .households import HouseholdGroup
from .ledger import D, Ledger, money, quantity, unit_value
from .production import FirmProductionState, Product


@dataclass
class World:
    ledger: Ledger
    firms: List[FirmProductionState]
    meta: Dict[str, dict]
    households: List[HouseholdGroup]
    entities: List[Entity] = field(default_factory=list)
    regions: Dict[str, Region] = field(default_factory=dict)
    new_entities: List[Entity] = field(default_factory=list)
    new_regions: List[Region] = field(default_factory=list)


def insert_entity(db, e: Entity) -> None:
    db.execute("INSERT OR IGNORE INTO econ_entities VALUES(?,?,?,?,?,?,?,?,?)", (
        e.entity_id, e.kind, e.region, e.cash_account, e.owner_group, e.employees, str(e.wage_per_hour),
        json.dumps({k: str(v) for k, v in e.sells.items()}), str(e.dividend_payout)))
    db.execute("INSERT OR IGNORE INTO econ_accounts(name,is_money,balance) VALUES(?,1,'0')", (e.cash_account,))


def insert_region(db, r: Region) -> None:
    db.execute("INSERT OR IGNORE INTO econ_regions VALUES(?,?,?,?,?,?)", (
        r.region, "USD", str(r.income_tax), str(r.corporate_tax), str(r.spend_ratio), r.gov_account))
    db.execute("INSERT OR IGNORE INTO econ_accounts(name,is_money,balance) VALUES(?,1,'0')", (r.gov_account,))


def load_world(db) -> World:
    ledger = store.load_ledger(db)
    firms, meta = store.load_firms(db)
    entities = [Entity(
        entity_id=r["entity_id"], kind=r["kind"], region=r["region"], cash_account=r["cash_account"],
        owner_group=r["owner_group"], employees=r["employees"], wage_per_hour=D(r["wage_per_hour"]),
        sells={k: D(v) for k, v in json.loads(r["sells"]).items()}, dividend_payout=D(r["dividend_payout"]))
        for r in db.execute("SELECT * FROM econ_entities ORDER BY entity_id")]
    regions = {r["region"]: Region(r["region"], D(r["income_tax_rate"]), D(r["corporate_tax_rate"]),
                                   D(r["gov_spend_ratio"]), r["gov_cash_account"])
               for r in db.execute("SELECT * FROM econ_regions ORDER BY region")}
    return World(ledger, firms, meta, store.load_households(db), entities, regions)


def save_world(db, w: World, active: List[FirmProductionState]) -> None:
    for r in w.new_regions:
        insert_region(db, r)
    for e in w.new_entities:
        insert_entity(db, e)
    store.save_ledger(db, w.ledger)
    for f in active:
        store.save_firm_state(db, f)


def adopt_market_accounts(w: World) -> None:
    """Pre-v2 worlds paid suppliers into anonymous market:* accounts. Give each a real owner,
    staff and region so the money it already holds re-enters circulation (no money is created)."""
    owner = min(w.households, key=lambda h: h.group_id, default=None)
    if owner is None:
        return
    known = {e.cash_account for e in w.entities}
    for name, kind in (("market:energy:cash", "energy"), ("market:materials:cash", "materials")):
        if name in w.ledger.balances and name not in known:
            e = Entity(f"LEGACY-{kind.upper()}", kind, owner.region, name, owner.group_id, employees=3)
            w.entities.append(e)
            w.new_entities.append(e)
            w.ledger.register_money_account(name)


def ensure_regions(w: World) -> None:
    for region in sorted({h.region for h in w.households} | {e.region for e in w.entities}):
        if region not in w.regions:
            r = Region(region)
            w.regions[region] = r
            w.new_regions.append(r)
    for e in w.entities:
        w.ledger.register_money_account(e.cash_account)
    for r in w.regions.values():
        w.ledger.register_money_account(r.gov_account)
    for h in w.households:
        w.ledger.register_money_account(h.cash_account)


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


def tick_world(w: World, tick: int, hours: Decimal) -> dict:
    """Pure in-memory tick: procurement -> production/payroll/energy/sales -> circulation."""
    ledger = w.ledger
    adopt_market_accounts(w)
    ensure_regions(w)
    ledger.set_tick(tick)
    mark = len(ledger.entries)
    by_id = {h.group_id: h for h in w.households}
    workers = {h.region for h in w.households if h.employed > 0}
    active, skipped = [], []
    for f in w.firms:
        bad = f.payroll_shares and any(g not in by_id or by_id[g].region != f.region for g in f.payroll_shares)
        if f.region not in workers or bad:
            skipped.append({"firm_id": f.firm_id, "reason": "no payroll recipients in region"})
        else:
            active.append(f)
    purchases = D("0")
    for f in active:
        purchases += apply_procurement(ledger, f, w.meta[f.firm_id]["procurement"])
    stats = run_production_tick(ledger, active, w.households, hours, tick=tick)
    stats.update(circulation.settle(ledger, active, w.meta, w.entities, w.households, w.regions, mark, hours))
    stats.update({"materials_purchased": money(purchases), "skipped_firms": skipped, "tick": tick})
    w._active = active  # type: ignore[attr-defined]
    return stats


def run_persistent_tick(db, tick: Optional[int] = None, hours: Decimal = D("1")) -> dict:
    """One tick in the caller's BEGIN IMMEDIATE transaction; any error aborts the whole tick."""
    hours = D(str(hours))
    if not hours.is_finite() or hours <= 0 or hours > store.MAX_TICK_HOURS:
        raise store.EconomyError("hours must be > 0 and <= 24")
    last = int(store.get_state(db, "last_tick", "0"))
    if tick is not None and tick != last + 1:
        raise store.TickConflict(f"Expected tick {last + 1}, got {tick}")
    w = load_world(db)
    expected = D(store.get_state(db, "money_supply", "0"))
    w.ledger.assert_money_supply(expected)   # persisted state must already be sound
    stats = tick_world(w, last + 1, hours)
    try:
        w.ledger.assert_money_supply(expected)
    except AssertionError as exc:
        raise store.InvariantViolation(str(exc)) from exc
    added = len(w.ledger.entries)
    save_world(db, w, w._active)  # type: ignore[attr-defined]
    stats = store.jsonable(stats)
    db.execute("INSERT INTO econ_ticks(tick,hours,money_supply_before,money_supply_after,entries_added,stats) "
               "VALUES(?,?,?,?,?,?)", (last + 1, str(hours), str(expected), str(w.ledger.money_supply()),
                                       added, json.dumps(stats)))
    store.set_state(db, "last_tick", str(last + 1))
    return stats


def demo_world(ledger: Ledger) -> World:
    """Self-contained sandbox: workers, owners, 3 supplier businesses, 1 producer, a government."""
    workers = HouseholdGroup("SIM-H1", "SIM", population=1000, avg_wage=D("15"), employed=600,
                             savings_rate=D("0.10"))
    owners = HouseholdGroup("SIM-OWN", "SIM", population=20, avg_wage=D("0"), employed=0,
                            savings_rate=D("0.50"))
    ledger.issue_money(workers.cash_account, D("30000"), "sandbox demo household opening cash")
    ledger.issue_money("firm:SIM-FIRM-1:cash", D("20000"), "sandbox demo firm opening capital")
    firm = FirmProductionState(
        firm_id="SIM-FIRM-1", region="SIM", cash_account="firm:SIM-FIRM-1:cash", employees=100,
        wage_per_hour=D("10"), capacity_per_hour=D("50"), energy_cost_per_unit=D("0.8"),
        energy_supplier_cash_account="entity:SIM-ENERGY:cash", payroll_shares={"SIM-H1": D("1")})
    firm.products["widget"] = Product(sku="widget", name="Widget", material_sku="steel",
                                      material_units_per_unit=D("1"), unit_labor_hours=D("0.5"),
                                      category="goods")
    meta = {"SIM-FIRM-1": {
        "mode": "sandbox", "linked_symbol": None, "owner_group": "SIM-OWN", "owner_account_id": None,
        "display_name": "Sim Widgets", "dividend_payout": D("0.5"), "cash_reserve": D("0"),
        "procurement": {"steel": {"target": "200", "unit_cost": "2",
                                  "supplier": "entity:SIM-STEEL:cash", "max_cash_share": "0.5"}}}}
    entities = [
        Entity("SIM-STEEL", "materials", "SIM", "entity:SIM-STEEL:cash", "SIM-OWN", 1, D("10"), {"steel": D("2")}),
        Entity("SIM-GRAIN", "materials", "SIM", "entity:SIM-GRAIN:cash", "SIM-OWN", 1, D("10"), {"grain": D("1")}),
        Entity("SIM-ENERGY", "energy", "SIM", "entity:SIM-ENERGY:cash", "SIM-OWN", 1, D("12"), {"energy": D("0.8")}),
        Entity("SIM-MACHINES", "capital", "SIM", "entity:SIM-MACHINES:cash", "SIM-OWN", 1, D("12"),
               {"machinery": D("1000")}),
    ]
    w = World(ledger, [firm], meta, [workers, owners], entities, {})
    ensure_regions(w)
    return w


def bootstrap_demo(db) -> dict:
    if db.execute("SELECT 1 FROM econ_firms WHERE firm_id='SIM-FIRM-1'").fetchone():
        raise store.TickConflict("Demo world already exists")
    ledger = store.load_ledger(db)
    w = demo_world(ledger)
    for h in w.households:
        store.insert_household(db, h)
    for f in w.firms:
        m = w.meta[f.firm_id]
        store.insert_firm(db, f, m["procurement"], owner_group=m["owner_group"], display_name=m["display_name"])
    w.new_entities, w.new_regions = list(w.entities), list(w.regions.values())
    save_world(db, w, [])
    store.set_state(db, "money_supply", str(ledger.money_supply()))
    return {"firms": [f.firm_id for f in w.firms], "households": [h.group_id for h in w.households],
            "suppliers": [e.entity_id for e in w.entities], "money_supply": str(ledger.money_supply())}
