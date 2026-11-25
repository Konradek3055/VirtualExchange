"""HTTP API for the persistent economy: authenticated reads, key-guarded admin/tick."""
import os
import secrets
from decimal import Decimal

from fastapi import APIRouter, Header, HTTPException, Query
from pydantic import BaseModel

from ..db import connect, transaction
from . import store

router = APIRouter(prefix="/economy", tags=["economy"])


def _require_account(key):
    if not key:
        raise HTTPException(401, "API key required")
    with connect() as db:
        if db.execute("SELECT 1 FROM accounts WHERE api_key=?", (key,)).fetchone() is None:
            raise HTTPException(401, "Invalid API key")


def _require_admin(key):
    expected = os.environ.get("VE_ECONOMY_ADMIN_KEY")
    if os.environ.get("VE_ECONOMY_ENABLED") != "1" or not expected:
        raise HTTPException(503, "Economy simulation is disabled (set VE_ECONOMY_ENABLED=1 and VE_ECONOMY_ADMIN_KEY)")
    if not key or not secrets.compare_digest(key, expected):
        raise HTTPException(403, "Invalid admin key")


def _read(fn, key, *args, **kw):
    _require_account(key)
    with connect() as db:
        return fn(db, *args, **kw)


def _write(fn, key, *args, **kw):
    _require_admin(key)
    try:
        with transaction() as db:
            return fn(db, *args, **kw)
    except store.EconomyError as exc:
        raise HTTPException(exc.status, str(exc))


class TickIn(BaseModel):
    tick: int | None = None
    hours: Decimal = Decimal(1)


@router.get("/status")
def economy_status(x_api_key: str | None = Header(default=None)):
    return _read(store.status, x_api_key)


@router.get("/firms")
def economy_firms(x_api_key: str | None = Header(default=None)):
    return _read(store.list_firms, x_api_key)


@router.get("/firms/{firm_id}")
def economy_firm(firm_id: str, x_api_key: str | None = Header(default=None)):
    firm = _read(store.get_firm, x_api_key, firm_id)
    if firm is None:
        raise HTTPException(404, "Firm not found")
    return firm


@router.get("/inventory")
def economy_inventory(firm_id: str | None = None, x_api_key: str | None = Header(default=None)):
    return _read(store.list_inventory, x_api_key, firm_id)


@router.get("/households")
def economy_households(x_api_key: str | None = Header(default=None)):
    return _read(store.list_households, x_api_key)


@router.get("/transactions")
def economy_transactions(after_id: int = Query(0, ge=0), limit: int = Query(100, ge=1, le=500),
                         firm_id: str | None = None, cash_only: bool = False,
                         x_api_key: str | None = Header(default=None)):
    return _read(store.list_transactions, x_api_key, after_id, limit, firm_id, cash_only)


@router.get("/ticks")
def economy_ticks(limit: int = Query(50, ge=1, le=200), x_api_key: str | None = Header(default=None)):
    return _read(store.list_ticks, x_api_key, limit)


@router.post("/admin/bootstrap-demo", status_code=201)
def admin_bootstrap(x_admin_key: str | None = Header(default=None)):
    return _write(store.bootstrap_demo, x_admin_key)


@router.post("/admin/link-companies")
def admin_link(x_admin_key: str | None = Header(default=None)):
    return _write(store.link_existing_companies, x_admin_key)


@router.post("/admin/tick")
def admin_tick(body: TickIn, x_admin_key: str | None = Header(default=None)):
    return _write(store.run_persistent_tick, x_admin_key, body.tick, body.hours)
