import os
from decimal import Decimal, localcontext

from fastapi import HTTPException

from .db import transaction
from .engine import get_balance, set_balance

CREATION_FEE = Decimal(os.environ.get("VX_LISTING_FEE", "100"))   # wirtualne USD, "spalane" (anty-spam)
MAX_ASSETS_PER_USER = int(os.environ.get("VX_MAX_ASSETS_PER_USER", "3"))
FLAG_THRESHOLD = 3            # tyle unikalnych zgłoszeń = flaga do moderacji


def create_user_asset(account_id, data):
    with localcontext() as ctx:
        ctx.prec = 60
        with transaction() as db:
            if db.execute("SELECT 1 FROM assets WHERE symbol=?", (data.symbol,)).fetchone():
                raise HTTPException(409, "Ticker already exists")
            owned = db.execute("SELECT COUNT(*) c FROM assets WHERE creator_id=?", (account_id,)).fetchone()["c"]
            if owned >= MAX_ASSETS_PER_USER:
                raise HTTPException(400, f"Limit of {MAX_ASSETS_PER_USER} created assets per user")
            cash, locked = get_balance(db, account_id, "USD")
            if cash < CREATION_FEE:
                raise HTTPException(400, f"Listing fee is {CREATION_FEE} USD")
            set_balance(db, account_id, "USD", cash - CREATION_FEE, locked)

            # last_price = 0: twórca NIE ustala ceny - pierwsza cena powstaje z pierwszej transakcji
            db.execute("""
                INSERT INTO assets(symbol,name,last_price,description,creator_id,total_supply)
                VALUES(?,?,?,?,?,?)
            """, (data.symbol, data.name, "0", data.description, account_id, str(data.total_supply)))
            set_balance(db, account_id, data.symbol, data.total_supply, 0)

            return {"symbol": data.symbol, "name": data.name, "description": data.description,
                    "total_supply": str(data.total_supply), "creator_id": account_id,
                    "last_price": "0", "listing_fee": str(CREATION_FEE)}


def report_asset(account_id, symbol):
    with transaction() as db:
        if not db.execute("SELECT 1 FROM assets WHERE symbol=?", (symbol,)).fetchone():
            raise HTTPException(404, "Asset not found")
        db.execute("INSERT OR IGNORE INTO asset_reports(symbol,account_id) VALUES(?,?)", (symbol, account_id))
        n = db.execute("SELECT COUNT(*) c FROM asset_reports WHERE symbol=?", (symbol,)).fetchone()["c"]
        return {"reports": n, "flagged": n >= FLAG_THRESHOLD}


def asset_info(db, symbol):
    """Publiczne informacje o aktywie: twórca, podaż, posiadacze, kapitalizacja, zgłoszenia."""
    row = db.execute("""SELECT a.*, u.username AS creator FROM assets a
                        LEFT JOIN accounts u ON u.id=a.creator_id WHERE a.symbol=?""", (symbol,)).fetchone()
    if not row:
        raise HTTPException(404, "Asset not found")
    out = dict(row)
    out["holders"] = db.execute(
        "SELECT COUNT(*) c FROM balances WHERE symbol=? AND CAST(available AS REAL)+CAST(locked AS REAL)>0",
        (symbol,)).fetchone()["c"]
    out["market_cap"] = float(out["last_price"]) * float(out["total_supply"])
    n = db.execute("SELECT COUNT(*) c FROM asset_reports WHERE symbol=?", (symbol,)).fetchone()["c"]
    out["reports"], out["flagged"] = n, n >= FLAG_THRESHOLD
    return out
