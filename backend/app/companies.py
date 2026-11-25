
from decimal import Decimal

from fastapi import HTTPException

from .db import transaction
from .engine import get_balance, set_balance

MAX_LEVEL = 10
BASE_COST = 500
BRANCHES = {"office": "office_level", "research": "research_level", "marketing": "marketing_level"}


def _company_row(db, account_id, symbol):
    asset = db.execute("SELECT symbol,creator_id FROM assets WHERE symbol=?", (symbol.upper(),)).fetchone()
    if asset is None:
        raise HTTPException(404, "Company not found")
    if asset["creator_id"] != account_id:
        raise HTTPException(403, "Only the company owner can develop it")
    db.execute("INSERT OR IGNORE INTO company_development(symbol,owner_id) VALUES(?,?)", (symbol.upper(), account_id))
    return db.execute("SELECT * FROM company_development WHERE symbol=?", (symbol.upper(),)).fetchone()


def _payload(row):
    office = int(row["office_level"])
    research = int(row["research_level"])
    marketing = int(row["marketing_level"])
    return {
        "symbol": row["symbol"],
        "office_level": office,
        "research_level": research,
        "marketing_level": marketing,
        "company_level": 1 + office + research + marketing,
        "development_index": 100 + office * 20 + research * 30 + marketing * 25,
        "invested": str(row["invested"]),
        "next_costs": {
            "office": BASE_COST * (office + 1) ** 2 if office < MAX_LEVEL else 0,
            "research": BASE_COST * (research + 1) ** 2 if research < MAX_LEVEL else 0,
            "marketing": BASE_COST * (marketing + 1) ** 2 if marketing < MAX_LEVEL else 0,
        },
        "max_level": MAX_LEVEL,
    }


def get_company_development(account_id, symbol):
    with transaction() as db:
        return _payload(_company_row(db, account_id, symbol))


def upgrade_company(account_id, symbol, branch):
    if branch not in BRANCHES:
        raise HTTPException(400, "Unknown development branch")
    with transaction() as db:
        row = _company_row(db, account_id, symbol)
        field = BRANCHES[branch]
        level = int(row[field])
        if level >= MAX_LEVEL:
            raise HTTPException(400, "This development branch is already at maximum level")
        cost = Decimal(BASE_COST * (level + 1) ** 2)
        cash, locked = get_balance(db, account_id, "USD")
        if cash < cost:
            raise HTTPException(400, f"Not enough USD. Required: {cost}")
        set_balance(db, account_id, "USD", cash - cost, locked)
        invested = Decimal(row["invested"]) + cost
        db.execute(
            f"UPDATE company_development SET {field}=?, invested=?, updated_at=CURRENT_TIMESTAMP WHERE symbol=?",
            (level + 1, str(invested), symbol.upper()),
        )
        updated = db.execute("SELECT * FROM company_development WHERE symbol=?", (symbol.upper(),)).fetchone()
        return _payload(updated)
