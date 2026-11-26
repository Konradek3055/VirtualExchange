"""Player decisions on the economy. Every operation validates ownership, funds and resources
and runs inside the caller's transaction (atomic with production wallet changes)."""
from __future__ import annotations

import json
import os
from decimal import Decimal, InvalidOperation

from ..engine import get_balance, set_balance
from . import circulation, store
from .ledger import D, money, quantity
from .production import FirmProductionState, Product
from .store import EconomyError, Forbidden, NotFound

CATALOG = {
    "widget": dict(name="Widget", material="steel", per_unit="1", labor="0.5", category="goods"),
    "bread": dict(name="Bread", material="grain", per_unit="0.5", labor="0.3", category="food"),
}
MAX_FIRMS = 5
MAX_EMPLOYEES = 500
MAX_CAPACITY = D("500")
HIRE_CASH_COVER_HOURS = 24


def _amount(raw, maximum=D("1000000000")) -> Decimal:
    try:
        v = D(str(raw))
    except (InvalidOperation, ValueError, TypeError):
        raise EconomyError("Invalid amount")
    if not v.is_finite() or v <= 0 or v > maximum:
        raise EconomyError("Amount must be greater than zero and not exceed the limit")
    if v.as_tuple().exponent < -2:
        raise EconomyError("Amount supports at most 2 decimal places")
    return money(v)


def _bridge_on() -> None:
    if os.environ.get("VE_ECONOMY_BRIDGE_ENABLED") != "1":
        raise store.Disabled("Wallet bridge is disabled (VE_ECONOMY_BRIDGE_ENABLED=1 enables it)")


def _firm_row(db, account_id, firm_id):
    r = db.execute("SELECT * FROM econ_firms WHERE firm_id=?", (firm_id,)).fetchone()
    if r is None:
        raise NotFound("Firm not found")
    if r["owner_account_id"] != account_id:
        raise Forbidden("Only the firm owner can do this")
    return r


def _ensure_money(db, name):
    db.execute("INSERT OR IGNORE INTO econ_accounts(name,is_money,balance) VALUES(?,1,'0')", (name,))


def _supply(db, delta):
    store.set_state(db, "money_supply", str(money(D(store.get_state(db, "money_supply", "0")) + delta)))


def _bridge_log(db, account_id, kind, ref, amount):
    db.execute("INSERT INTO econ_bridge(account_id,kind,ref,amount) VALUES(?,?,?,?)",
               (account_id, kind, ref, str(amount)))


def _suppliers(db, region, kind, sku):
    out = []
    for r in db.execute("SELECT * FROM econ_entities WHERE region=? AND kind=? ORDER BY entity_id", (region, kind)):
        sells = json.loads(r["sells"])
        if sku in sells:
            out.append((r, D(sells[sku])))
    return sorted(out, key=lambda x: (x[1], x[0]["entity_id"]))


def _pick(db, region, kind, sku, supplier_id=None):
    options = _suppliers(db, region, kind, sku)
    if supplier_id:
        options = [o for o in options if o[0]["entity_id"] == supplier_id]
    if not options:
        raise EconomyError(f"No supplier of {sku} available in {region}")
    return options[0]


def found_firm(db, account_id, name, region, product, firm_id=None, linked_symbol=None) -> dict:
    name = (name or "").strip()
    spec = CATALOG.get(product)
    if not 3 <= len(name) <= 40:
        raise EconomyError("Firm name must have 3-40 characters")
    if spec is None:
        raise EconomyError(f"Unknown product. Available: {sorted(CATALOG)}")
    if not db.execute("SELECT 1 FROM econ_regions WHERE region=?", (region,)).fetchone():
        raise NotFound("Unknown region")
    owned = db.execute("SELECT COUNT(*) FROM econ_firms WHERE owner_account_id=?", (account_id,)).fetchone()[0]
    if owned >= MAX_FIRMS:
        raise EconomyError(f"At most {MAX_FIRMS} firms per player")
    energy, energy_price = _pick(db, region, "energy", "energy")
    _pick(db, region, "materials", spec["material"])
    if firm_id is None:
        n = owned + 1
        while db.execute("SELECT 1 FROM econ_firms WHERE firm_id=?", (f"P{account_id}-{n}",)).fetchone():
            n += 1
        firm_id = f"P{account_id}-{n}"
    elif db.execute("SELECT 1 FROM econ_firms WHERE firm_id=?", (firm_id,)).fetchone():
        raise EconomyError("Firm already exists")
    firm = FirmProductionState(
        firm_id=firm_id, region=region, cash_account=f"firm:{firm_id}:cash", employees=1,
        wage_per_hour=D("12"), capacity_per_hour=D("5"), energy_cost_per_unit=energy_price,
        energy_supplier_cash_account=energy["cash_account"], sales_markup=D("1.30"))
    firm.products[product] = Product(sku=product, name=spec["name"], material_sku=spec["material"],
                                     material_units_per_unit=D(spec["per_unit"]),
                                     unit_labor_hours=D(spec["labor"]), category=spec["category"])
    store.insert_firm(db, firm, {}, mode="player", linked_symbol=linked_symbol,
                      owner_account_id=account_id, display_name=name)
    _ensure_money(db, firm.cash_account)
    return {"firm_id": firm_id, "region": region, "product": product, "employees": 1}


def deposit(db, account_id, firm_id, raw_amount) -> dict:
    _bridge_on()
    row = _firm_row(db, account_id, firm_id)
    amount = _amount(raw_amount)
    available, locked = get_balance(db, account_id, "USD")
    if available < amount:
        raise EconomyError("Not enough USD")
    set_balance(db, account_id, "USD", available - amount, locked)
    ledger = store.load_ledger(db)
    ledger.register_money_account(row["cash_account"])
    ledger.issue_money(row["cash_account"], amount, f"bridge:deposit account {account_id} firm {firm_id}")
    store.save_ledger(db, ledger)
    _supply(db, amount)
    _bridge_log(db, account_id, "in", firm_id, amount)
    return {"firm_id": firm_id, "cash": str(ledger.balance(row["cash_account"]))}


def withdraw(db, account_id, raw_amount) -> dict:
    _bridge_on()
    amount = _amount(raw_amount)
    cash = f"player:{account_id}:cash"
    ledger = store.load_ledger(db)
    if ledger.balance(cash) < amount:
        raise EconomyError("Not enough economy cash (receive dividends first)")
    ledger.register_money_account(cash)
    ledger.redeem_money(cash, amount, f"bridge:withdraw account {account_id}")
    store.save_ledger(db, ledger)
    _supply(db, -amount)
    available, locked = get_balance(db, account_id, "USD")
    set_balance(db, account_id, "USD", available + amount, locked)
    _bridge_log(db, account_id, "out", cash, amount)
    return {"economy_cash": str(ledger.balance(cash)), "usd_available": str(available + amount)}


def pay_dividend(db, account_id, firm_id, raw_amount) -> dict:
    row = _firm_row(db, account_id, firm_id)
    amount = _amount(raw_amount)
    ledger = store.load_ledger(db)
    p, cash = f"firm:{firm_id}:", row["cash_account"]
    ledger.register_money_account(cash)
    payable = money(-ledger.balance(p + "tax-payable"))
    cap = min(circulation.retained(ledger, p), ledger.balance(cash) - payable)
    if amount > cap:
        raise EconomyError(f"Dividend exceeds distributable profit/cash (max {max(cap, D('0'))})")
    owner = f"player:{account_id}:cash"
    ledger.register_money_account(owner)
    ledger.transfer(cash, owner, amount, f"dividend:{firm_id}")
    ledger.post(p + "dividends", p + "dividends-clearing", amount, f"Record dividend {firm_id}")
    store.save_ledger(db, ledger)
    return {"paid": str(amount), "player_economy_cash": str(ledger.balance(owner))}


def buy_materials(db, account_id, firm_id, sku, raw_qty, supplier_id=None) -> dict:
    row = _firm_row(db, account_id, firm_id)
    firms, _ = store.load_firms(db, only=firm_id)
    firm = firms[0]
    if sku not in {p.material_sku for p in firm.products.values()}:
        raise EconomyError("This firm does not use that material")
    try:
        qty = quantity(D(str(raw_qty)))
    except Exception:
        raise EconomyError("Invalid quantity")
    if qty <= 0 or qty > D("1000000"):
        raise EconomyError("Quantity must be between 0 and 1,000,000")
    supplier, price = _pick(db, firm.region, "materials", sku, supplier_id)
    ledger = store.load_ledger(db)
    ledger.register_money_account(firm.cash_account)
    cost = money(qty * price)
    if ledger.balance(firm.cash_account) < cost:
        raise EconomyError(f"Not enough firm cash. Required: {cost}")
    firm.acquire_materials(ledger, sku, qty, price, supplier["cash_account"])
    circulation.recognize_receipt(ledger, supplier["entity_id"], cost)
    store.save_ledger(db, ledger)
    store.save_firm_state(db, firm)
    return {"bought": str(qty), "unit_price": str(price), "cost": str(cost), "supplier": supplier["entity_id"]}


def set_procurement(db, account_id, firm_id, sku, raw_target, supplier_id=None) -> dict:
    row = _firm_row(db, account_id, firm_id)
    firms, _ = store.load_firms(db, only=firm_id)
    if sku not in {p.material_sku for p in firms[0].products.values()}:
        raise EconomyError("This firm does not use that material")
    policy = json.loads(row["procurement"])
    target = quantity(D(str(raw_target)))
    if target < 0 or target > D("100000"):
        raise EconomyError("Target must be between 0 and 100,000")
    if target == 0:
        policy.pop(sku, None)
    else:
        supplier, price = _pick(db, row["region"], "materials", sku, supplier_id)
        policy[sku] = {"target": str(target), "unit_cost": str(price),
                       "supplier": supplier["cash_account"], "max_cash_share": "0.5"}
    db.execute("UPDATE econ_firms SET procurement=? WHERE firm_id=?", (json.dumps(policy), firm_id))
    return {"procurement": policy}


def staff(db, account_id, firm_id, action, count) -> dict:
    row = _firm_row(db, account_id, firm_id)
    if action not in ("hire", "fire") or isinstance(count, bool) or not isinstance(count, int) or not 1 <= count <= 50:
        raise EconomyError("Use hire/fire and an integer count between 1 and 50")
    employees = row["employees"]
    if action == "fire":
        if employees - count < 1:
            raise EconomyError("A firm must keep at least one employee")
        new = employees - count
    else:
        new = employees + count
        if new > MAX_EMPLOYEES:
            raise EconomyError(f"Maximum staff is {MAX_EMPLOYEES}")
        jobs = (db.execute("SELECT COALESCE(SUM(employees),0) FROM econ_firms WHERE region=?", (row["region"],)).fetchone()[0]
                + db.execute("SELECT COALESCE(SUM(employees),0) FROM econ_entities WHERE region=?", (row["region"],)).fetchone()[0])
        workforce = db.execute("SELECT COALESCE(SUM(employed),0) FROM econ_households WHERE region=?", (row["region"],)).fetchone()[0]
        if jobs + count > workforce:
            raise EconomyError("Not enough available workers in this region")
        cover = money(D(new) * D(row["wage_per_hour"]) * HIRE_CASH_COVER_HOURS)
        ledger = store.load_ledger(db)
        if ledger.balance(row["cash_account"]) < cover:
            raise EconomyError(f"Firm needs {cover} cash to cover {HIRE_CASH_COVER_HOURS}h of payroll")
    db.execute("UPDATE econ_firms SET employees=? WHERE firm_id=?", (new, firm_id))
    return {"employees": new}


def set_wage(db, account_id, firm_id, raw_wage) -> dict:
    _firm_row(db, account_id, firm_id)
    wage = _amount(raw_wage, D("500"))
    if wage < D("5"):
        raise EconomyError("Wage must be between 5 and 500 per hour")
    db.execute("UPDATE econ_firms SET wage_per_hour=? WHERE firm_id=?", (str(wage), firm_id))
    return {"wage_per_hour": str(wage)}


def set_price(db, account_id, firm_id, raw_markup) -> dict:
    _firm_row(db, account_id, firm_id)
    try:
        m = D(str(raw_markup))
    except (InvalidOperation, ValueError):
        raise EconomyError("Invalid markup")
    if not m.is_finite() or not D("1.0") <= m <= D("5.0") or m.as_tuple().exponent < -3:
        raise EconomyError("Markup over unit cost must be between 1.0 and 5.0")
    db.execute("UPDATE econ_firms SET sales_markup=? WHERE firm_id=?", (str(m), firm_id))
    return {"sales_markup": str(m)}


def capex(db, account_id, firm_id, raw_amount) -> dict:
    row = _firm_row(db, account_id, firm_id)
    amount = _amount(raw_amount)
    supplier, price = _pick(db, row["region"], "capital", "machinery")
    gain = quantity(amount / price)
    new_capacity = D(row["capacity_per_hour"]) + gain
    if amount < price / 10:
        raise EconomyError(f"Minimum investment is {money(price / 10)}")
    if new_capacity > MAX_CAPACITY:
        raise EconomyError("Maximum capacity reached")
    ledger = store.load_ledger(db)
    cash = row["cash_account"]
    ledger.register_money_account(cash)
    if ledger.balance(cash) < amount:
        raise EconomyError("Not enough firm cash")
    p = f"firm:{firm_id}:"
    ledger.transfer(cash, supplier["cash_account"], amount, f"capex:{firm_id} machinery")
    ledger.post(p + "fixed-assets", p + "capex-clearing", amount, f"Capitalize machinery {firm_id}")
    circulation.recognize_receipt(ledger, supplier["entity_id"], amount)
    store.save_ledger(db, ledger)
    db.execute("UPDATE econ_firms SET capacity_per_hour=? WHERE firm_id=?", (str(new_capacity), firm_id))
    return {"capacity_per_hour": str(new_capacity), "invested": str(amount)}


def activate_company(db, account_id, symbol, region, product, raw_amount) -> dict:
    """Move cash from the legacy company treasury into a real economy firm (a transfer, not a copy)."""
    _bridge_on()
    symbol = symbol.upper()
    asset = db.execute("SELECT creator_id FROM assets WHERE symbol=?", (symbol,)).fetchone()
    if asset is None:
        raise NotFound("Company not found")
    if asset["creator_id"] != account_id:
        raise Forbidden("Only the company owner can activate it")
    legacy = db.execute("SELECT cash FROM company_development WHERE symbol=?", (symbol,)).fetchone()
    if legacy is None:
        raise EconomyError("Company has no development record yet")
    amount = _amount(raw_amount)
    if D(legacy["cash"]) < amount:
        raise EconomyError("Company treasury does not have enough cash")
    firm_id = f"CO-{symbol}"
    if not db.execute("SELECT 1 FROM econ_firms WHERE firm_id=?", (firm_id,)).fetchone():
        found_firm(db, account_id, f"{symbol} Operations", region, product, firm_id=firm_id, linked_symbol=symbol)
    row = _firm_row(db, account_id, firm_id)
    db.execute("UPDATE company_development SET cash=?, updated_at=CURRENT_TIMESTAMP WHERE symbol=?",
               (str(money(D(legacy["cash"]) - amount)), symbol))
    ledger = store.load_ledger(db)
    ledger.register_money_account(row["cash_account"])
    ledger.issue_money(row["cash_account"], amount, f"bridge:legacy company {symbol}")
    store.save_ledger(db, ledger)
    _supply(db, amount)
    _bridge_log(db, account_id, "legacy_company", symbol, amount)
    return {"firm_id": firm_id, "cash": str(ledger.balance(row["cash_account"]))}


def list_player_firms(db, account_id) -> list:
    out = []
    ledger = store.load_ledger(db)
    for r in db.execute("SELECT * FROM econ_firms WHERE owner_account_id=? ORDER BY firm_id", (account_id,)):
        out.append({"firm_id": r["firm_id"], "name": r["display_name"], "region": r["region"],
                    "employees": r["employees"], "wage_per_hour": r["wage_per_hour"],
                    "capacity_per_hour": r["capacity_per_hour"], "sales_markup": r["sales_markup"],
                    "linked_symbol": r["linked_symbol"], "cash": str(ledger.balance(r["cash_account"])),
                    "procurement": json.loads(r["procurement"])})
    return out
