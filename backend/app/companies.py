from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
import time

from fastapi import HTTPException

from .db import transaction
from .engine import get_balance, set_balance

MAX_LEVEL = 10
BASE_COST = 500
MAX_EMPLOYEES = 200
MAX_SETTLEMENT_HOURS = 24 * 7
BRANCHES = {
    "office": "office_level",
    "research": "research_level",
    "marketing": "marketing_level",
    "production": "production_level",
    "logistics": "logistics_level",
}
D = Decimal
CENT = D("0.01")


def _money(value):
    return value.quantize(CENT, rounding=ROUND_HALF_UP)


def _amount(raw):
    try:
        value = D(str(raw))
    except (InvalidOperation, ValueError, TypeError):
        raise HTTPException(400, "Invalid amount")
    if not value.is_finite() or value <= 0 or value > D("1000000000"):
        raise HTTPException(400, "Amount must be greater than zero and no more than 1,000,000,000 USD")
    if value.normalize().as_tuple().exponent < -2:
        raise HTTPException(400, "Amount supports at most 2 decimal places")
    return _money(value)


def _company_row(db, account_id, symbol):
    symbol = symbol.upper()
    asset = db.execute("SELECT symbol,creator_id FROM assets WHERE symbol=?", (symbol,)).fetchone()
    if asset is None:
        raise HTTPException(404, "Company not found")
    if asset["creator_id"] != account_id:
        raise HTTPException(403, "Only the company owner can manage it")
    db.execute("INSERT OR IGNORE INTO company_development(symbol,owner_id) VALUES(?,?)", (symbol, account_id))
    row = db.execute("SELECT * FROM company_development WHERE symbol=?", (symbol,)).fetchone()
    if int(row["last_settlement"] or 0) <= 0:
        db.execute("UPDATE company_development SET last_settlement=? WHERE symbol=?", (int(time.time()), symbol))
        row = db.execute("SELECT * FROM company_development WHERE symbol=?", (symbol,)).fetchone()
    return row


def _hourly_rates(row):
    office = int(row["office_level"])
    research = int(row["research_level"])
    marketing = int(row["marketing_level"])
    production = int(row["production_level"])
    logistics = int(row["logistics_level"])
    employees = max(1, int(row["employees"]))
    reputation = D(str(row["reputation"])).max(D("0")).min(D("100"))

    productivity = (D("1") + D(office) * D("0.035") + D(research) * D("0.09")
                    + D(production) * D("0.16") + D(logistics) * D("0.06"))
    demand = D("1") + D(marketing) * D("0.11") + (reputation - D("50")) / D("1000")
    gross = (D("10") + D(employees) * D("1.4") + D(production) * D("5")
             + D(research) * D("1.8")) * productivity * demand
    costs = (D(employees) * D("1.1") + D(office) * D("1.3") + D(research) * D("2.2")
             + D(marketing) * D("1.5") + D(production) * D("2.5")
             + D(logistics) * D("1.4") + D("2"))
    return _money(gross), _money(costs)


def _settle(db, row):
    now = int(time.time())
    last = int(row["last_settlement"] or now)
    hours = min(max(0, (now - last) // 3600), MAX_SETTLEMENT_HOURS)
    if hours <= 0:
        return row

    hourly_revenue, hourly_cost = _hourly_rates(row)
    revenue = _money(hourly_revenue * hours)
    expected_cost = _money(hourly_cost * hours)
    cash_before = D(row["cash"])
    available = cash_before + revenue
    paid_cost = min(expected_cost, available)
    cash_after = _money(available - paid_cost)
    reputation = D(str(row["reputation"]))
    if revenue >= expected_cost:
        reputation = min(D("100"), reputation + D("0.04") * hours)
    else:
        reputation = max(D("0"), reputation - D("0.12") * hours)
    employees = int(row["employees"])
    customers = int((D(25 + employees * 10 + int(row["marketing_level"]) * 12
                       + int(row["production_level"]) * 8) * (D("0.5") + reputation / D("100")))
                    .to_integral_value(rounding=ROUND_HALF_UP))

    db.execute("""
        UPDATE company_development SET cash=?, revenue_total=?, expense_total=?,
            last_revenue=?, last_expense=?, reputation=?, customers=?,
            last_settlement=?, updated_at=CURRENT_TIMESTAMP WHERE symbol=?
    """, (
        str(cash_after), str(_money(D(row["revenue_total"]) + revenue)),
        str(_money(D(row["expense_total"]) + paid_cost)), str(revenue), str(paid_cost),
        float(reputation), customers, last + hours * 3600, row["symbol"],
    ))
    return db.execute("SELECT * FROM company_development WHERE symbol=?", (row["symbol"],)).fetchone()


def _payload(row):
    hourly_revenue, hourly_cost = _hourly_rates(row)
    office = int(row["office_level"])
    research = int(row["research_level"])
    marketing = int(row["marketing_level"])
    production = int(row["production_level"])
    logistics = int(row["logistics_level"])
    levels = (office, research, marketing, production, logistics)
    return {
        "symbol": row["symbol"],
        "office_level": office,
        "research_level": research,
        "marketing_level": marketing,
        "production_level": production,
        "logistics_level": logistics,
        "company_level": 1 + sum(levels),
        "development_index": 100 + office * 18 + research * 28 + marketing * 22 + production * 32 + logistics * 16,
        "invested": str(row["invested"]),
        "cash": str(row["cash"]),
        "revenue_total": str(row["revenue_total"]),
        "expense_total": str(row["expense_total"]),
        "dividends_total": str(row["dividends_total"]),
        "last_revenue": str(row["last_revenue"]),
        "last_expense": str(row["last_expense"]),
        "hourly_revenue": str(hourly_revenue),
        "hourly_expenses": str(hourly_cost),
        "hourly_profit": str(_money(hourly_revenue - hourly_cost)),
        "employees": int(row["employees"]),
        "reputation": round(float(row["reputation"]), 1),
        "customers": int(row["customers"]),
        "product_quality": min(100, 40 + research * 4 + production * 6),
        "status": "OPERATING" if D(row["cash"]) > 0 else "LOW_CAPITAL",
        "next_costs": {
            key: BASE_COST * (int(row[field]) + 1) ** 2 if int(row[field]) < MAX_LEVEL else 0
            for key, field in BRANCHES.items()
        },
        "max_level": MAX_LEVEL,
        "max_employees": MAX_EMPLOYEES,
    }


def get_company_development(account_id, symbol):
    with transaction() as db:
        row = _company_row(db, account_id, symbol)
        return _payload(_settle(db, row))


def upgrade_company(account_id, symbol, branch):
    if branch not in BRANCHES:
        raise HTTPException(400, "Unknown development branch")
    with transaction() as db:
        row = _settle(db, _company_row(db, account_id, symbol))
        field = BRANCHES[branch]
        level = int(row[field])
        if level >= MAX_LEVEL:
            raise HTTPException(400, "This development branch is already at maximum level")
        cost = D(BASE_COST * (level + 1) ** 2)
        company_cash = D(row["cash"])
        if company_cash < cost:
            raise HTTPException(400, f"Company needs {cost} USD in its treasury for this upgrade")
        invested = D(row["invested"]) + cost
        db.execute(
            f"UPDATE company_development SET {field}=?, cash=?, invested=?, updated_at=CURRENT_TIMESTAMP WHERE symbol=?",
            (level + 1, str(_money(company_cash - cost)), str(invested), symbol.upper()),
        )
        return _payload(db.execute("SELECT * FROM company_development WHERE symbol=?", (symbol.upper(),)).fetchone())


def fund_company(account_id, symbol, raw_amount):
    amount = _amount(raw_amount)
    with transaction() as db:
        row = _settle(db, _company_row(db, account_id, symbol))
        available, locked = get_balance(db, account_id, "USD")
        if available < amount:
            raise HTTPException(400, "Not enough USD to fund the company")
        set_balance(db, account_id, "USD", available - amount, locked)
        db.execute("UPDATE company_development SET cash=?, updated_at=CURRENT_TIMESTAMP WHERE symbol=?",
                   (str(_money(D(row["cash"]) + amount)), symbol.upper()))
        return _payload(db.execute("SELECT * FROM company_development WHERE symbol=?", (symbol.upper(),)).fetchone())


def withdraw_company(account_id, symbol, raw_amount):
    amount = _amount(raw_amount)
    with transaction() as db:
        row = _settle(db, _company_row(db, account_id, symbol))
        cash = D(row["cash"])
        if cash < amount:
            raise HTTPException(400, "Company treasury does not have enough cash")

        # Dywidenda jest rozdzielana według udziałów dostępnych i zablokowanych na zleceniach.
        raw_holders = db.execute(
            "SELECT account_id,available,locked FROM balances WHERE symbol=?",
            (symbol.upper(),),
        ).fetchall()
        holders = []
        for holder in raw_holders:
            shares = D(holder["available"]) + D(holder["locked"])
            if shares > 0:
                holders.append((int(holder["account_id"]), shares))
        total_shares = sum((shares for _, shares in holders), D("0"))
        if total_shares <= 0:
            raise HTTPException(400, "Company has no shareholders")

        # Największe reszty dostają pozostałe centy, więc suma wypłat jest dokładnie równa kwocie.
        total_cents = int(amount * 100)
        allocations = []
        for holder_id, shares in holders:
            exact_cents = D(total_cents) * shares / total_shares
            base_cents = int(exact_cents.to_integral_value(rounding="ROUND_DOWN"))
            allocations.append((holder_id, base_cents, exact_cents - D(base_cents)))
        cents_left = total_cents - sum(base for _, base, _ in allocations)
        priority = sorted(allocations, key=lambda item: (-item[2], item[0]))
        extra_ids = {holder_id for holder_id, _, _ in priority[:cents_left]}
        for holder_id, base_cents, _ in allocations:
            payout = D(base_cents + (1 if holder_id in extra_ids else 0)) / D("100")
            if payout <= 0:
                continue
            available, locked = get_balance(db, holder_id, "USD")
            set_balance(db, holder_id, "USD", available + payout, locked)

        dividends = _money(D(row["dividends_total"]) + amount)
        db.execute("UPDATE company_development SET cash=?, dividends_total=?, updated_at=CURRENT_TIMESTAMP WHERE symbol=?",
                   (str(_money(cash - amount)), str(dividends), symbol.upper()))
        return _payload(db.execute("SELECT * FROM company_development WHERE symbol=?", (symbol.upper(),)).fetchone())


def manage_company_staff(account_id, symbol, action, raw_count=1):
    try:
        count = int(raw_count)
    except (TypeError, ValueError):
        raise HTTPException(400, "Invalid employee count")
    if action not in ("hire", "fire") or count < 1 or count > 20:
        raise HTTPException(400, "Use hire/fire and a count between 1 and 20")
    with transaction() as db:
        row = _settle(db, _company_row(db, account_id, symbol))
        employees = int(row["employees"])
        cash = D(row["cash"])
        if action == "hire":
            if employees + count > MAX_EMPLOYEES:
                raise HTTPException(400, f"Maximum staff is {MAX_EMPLOYEES}")
            fee = D(count * 75)
            if cash < fee:
                raise HTTPException(400, f"Company needs {fee} USD for recruitment")
            cash -= fee
            employees += count
        else:
            if employees - count < 1:
                raise HTTPException(400, "A company must keep at least one employee")
            severance = D(count * 35)
            if cash < severance:
                raise HTTPException(400, f"Company needs {severance} USD for severance")
            cash -= severance
            employees -= count
        db.execute("UPDATE company_development SET employees=?, cash=?, updated_at=CURRENT_TIMESTAMP WHERE symbol=?",
                   (employees, str(_money(cash)), symbol.upper()))
        return _payload(db.execute("SELECT * FROM company_development WHERE symbol=?", (symbol.upper(),)).fetchone())
