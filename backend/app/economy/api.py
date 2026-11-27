"""HTTP API for the persistent economy: authenticated reads, key-guarded admin/tick."""
import os
import secrets
from decimal import Decimal

from fastapi import APIRouter, Header, HTTPException, Query
from pydantic import BaseModel

from ..db import connect, transaction
from . import accounting, player, store, world

router = APIRouter(prefix="/economy", tags=["economy"])


def _require_account(key):
    if not key:
        raise HTTPException(401, "API key required")
    with connect() as db:
        row = db.execute("SELECT id FROM accounts WHERE api_key=?", (key,)).fetchone()
        if row is None:
            raise HTTPException(401, "Invalid API key")
    return row["id"]


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
    return _write(world.bootstrap_demo, x_admin_key)


@router.post("/admin/link-companies")
def admin_link(x_admin_key: str | None = Header(default=None)):
    return _write(store.link_existing_companies, x_admin_key)


@router.post("/admin/tick")
def admin_tick(body: TickIn, x_admin_key: str | None = Header(default=None)):
    return _write(world.run_persistent_tick, x_admin_key, body.tick, body.hours)


def _play(fn, key, *args):
    account_id = _require_account(key)
    try:
        with transaction() as db:
            return fn(db, account_id, *args)
    except store.EconomyError as exc:
        raise HTTPException(exc.status, str(exc))


class FoundIn(BaseModel):
    name: str
    region: str
    product: str


class AmountIn(BaseModel):
    amount: Decimal


class MaterialsIn(BaseModel):
    sku: str
    quantity: Decimal
    supplier_id: str | None = None


class ProcurementIn(BaseModel):
    sku: str
    target: Decimal
    supplier_id: str | None = None


class StaffIn(BaseModel):
    action: str
    count: int


class WageIn(BaseModel):
    wage_per_hour: Decimal


class PriceIn(BaseModel):
    markup: Decimal


class ActivateIn(BaseModel):
    region: str
    product: str
    amount: Decimal


@router.get("/regions")
def economy_regions(x_api_key: str | None = Header(default=None)):
    return _read(lambda db: [dict(r) for r in db.execute("SELECT * FROM econ_regions ORDER BY region")], x_api_key)


@router.get("/entities")
def economy_entities(x_api_key: str | None = Header(default=None)):
    return _read(lambda db: [dict(r) for r in db.execute("SELECT * FROM econ_entities ORDER BY entity_id")], x_api_key)


@router.get("/{kind}/{ident}/statements")
def economy_statements(kind: str, ident: str, from_tick: int = Query(0, ge=0), to_tick: int | None = None,
                       x_api_key: str | None = Header(default=None)):
    if kind not in ("firms", "entities"):
        raise HTTPException(404, "Unknown statement subject")
    try:
        return _read(accounting.statements, x_api_key, "firm" if kind == "firms" else "entity", ident,
                     from_tick, to_tick)
    except store.EconomyError as exc:
        raise HTTPException(exc.status, str(exc))


@router.get("/player/firms")
def player_firms(x_api_key: str | None = Header(default=None)):
    return _play(player.list_player_firms, x_api_key)


@router.post("/player/firms", status_code=201)
def player_found(body: FoundIn, x_api_key: str | None = Header(default=None)):
    return _play(player.found_firm, x_api_key, body.name, body.region, body.product)


@router.post("/player/firms/{firm_id}/deposit")
def player_deposit(firm_id: str, body: AmountIn, x_api_key: str | None = Header(default=None)):
    return _play(player.deposit, x_api_key, firm_id, body.amount)


@router.post("/player/firms/{firm_id}/materials")
def player_materials(firm_id: str, body: MaterialsIn, x_api_key: str | None = Header(default=None)):
    return _play(player.buy_materials, x_api_key, firm_id, body.sku, body.quantity, body.supplier_id)


@router.post("/player/firms/{firm_id}/procurement")
def player_procurement(firm_id: str, body: ProcurementIn, x_api_key: str | None = Header(default=None)):
    return _play(player.set_procurement, x_api_key, firm_id, body.sku, body.target, body.supplier_id)


@router.post("/player/firms/{firm_id}/staff")
def player_staff(firm_id: str, body: StaffIn, x_api_key: str | None = Header(default=None)):
    return _play(player.staff, x_api_key, firm_id, body.action, body.count)


@router.post("/player/firms/{firm_id}/wage")
def player_wage(firm_id: str, body: WageIn, x_api_key: str | None = Header(default=None)):
    return _play(player.set_wage, x_api_key, firm_id, body.wage_per_hour)


@router.post("/player/firms/{firm_id}/price")
def player_price(firm_id: str, body: PriceIn, x_api_key: str | None = Header(default=None)):
    return _play(player.set_price, x_api_key, firm_id, body.markup)


@router.post("/player/firms/{firm_id}/capex")
def player_capex(firm_id: str, body: AmountIn, x_api_key: str | None = Header(default=None)):
    return _play(player.capex, x_api_key, firm_id, body.amount)


@router.post("/player/firms/{firm_id}/dividend")
def player_dividend(firm_id: str, body: AmountIn, x_api_key: str | None = Header(default=None)):
    return _play(player.pay_dividend, x_api_key, firm_id, body.amount)


@router.post("/player/withdraw")
def player_withdraw(body: AmountIn, x_api_key: str | None = Header(default=None)):
    return _play(player.withdraw, x_api_key, body.amount)


@router.post("/player/companies/{symbol}/activate")
def player_activate(symbol: str, body: ActivateIn, x_api_key: str | None = Header(default=None)):
    return _play(player.activate_company, x_api_key, symbol, body.region, body.product, body.amount)
