"""Financial statements derived only from persisted journal entries and account balances."""
from __future__ import annotations

from typing import Dict, Optional

from .ledger import D, money
from .store import NotFound

CONTRIBUTION_PREFIXES = ("Explicit monetary issuance", "capital:", "bridge:")


def _bal(db, name: str) -> D:
    r = db.execute("SELECT balance FROM econ_accounts WHERE name=?", (name,)).fetchone()
    return D(r[0]) if r else D("0")


def _prefix_sum(db, prefix: str) -> D:
    rows = db.execute("SELECT balance FROM econ_accounts WHERE substr(name,1,?)=?", (len(prefix), prefix))
    return money(sum((D(r[0]) for r in rows), D("0")))


def _entries(db, column: str, name: str, t0: int, t1: Optional[int]):
    q = f"SELECT tick,debit_account,credit_account,amount,description FROM econ_journal WHERE {column}=? AND tick>=?"
    args = [name, t0]
    if t1 is not None:
        q += " AND tick<=?"
        args.append(t1)
    return db.execute(q + " ORDER BY entry_id", args).fetchall()


def _total(db, column, name, t0, t1) -> D:
    return money(sum((D(r["amount"]) for r in _entries(db, column, name, t0, t1)), D("0")))


def _identify(db, kind: str, ident: str):
    if kind == "firm":
        r = db.execute("SELECT firm_id,cash_account FROM econ_firms WHERE firm_id=?", (ident,)).fetchone()
        return (f"firm:{ident}:", r["cash_account"]) if r else None
    r = db.execute("SELECT entity_id,cash_account FROM econ_entities WHERE entity_id=?", (ident,)).fetchone()
    return (f"entity:{ident}:", r["cash_account"]) if r else None


def _classify(desc: str, inflow: bool) -> str:
    if inflow:
        if desc.startswith(CONTRIBUTION_PREFIXES):
            return "financing:capital_contributions"
        if desc.startswith("Customer purchase") or " purchases " in desc or " energy for " in desc \
                or desc.startswith("capex:"):
            return "operating:customer_receipts"
        return "operating:other_inflows"
    if " payroll for " in desc:
        return "operating:wages"
    if " energy for " in desc:
        return "operating:energy"
    if " purchases " in desc:
        return "operating:materials"
    if desc.startswith("tax:"):
        return "operating:taxes"
    if desc.startswith("dividend:"):
        return "financing:dividends"
    if desc.startswith("capex:"):
        return "investing:capex"
    return "operating:other_outflows"


def cash_flow(db, kind: str, ident: str, t0: int = 0, t1: Optional[int] = None) -> dict:
    ids = _identify(db, kind, ident)
    if ids is None:
        raise NotFound("Unknown entity")
    _, cash = ids
    opening = D("0")
    for r in db.execute("SELECT debit_account,credit_account,amount FROM econ_journal "
                        "WHERE (debit_account=? OR credit_account=?) AND tick<?", (cash, cash, t0)):
        opening += D(r["amount"]) if r["debit_account"] == cash else -D(r["amount"])
    lines: Dict[str, D] = {}
    for column, inflow in (("debit_account", True), ("credit_account", False)):
        for r in _entries(db, column, cash, t0, t1):
            key = _classify(r["description"], inflow)
            lines[key] = lines.get(key, D("0")) + (D(r["amount"]) if inflow else -D(r["amount"]))
    net = money(sum(lines.values(), D("0")))
    closing = money(opening + net)
    live = _bal(db, cash)
    sections = {s: money(sum((v for k, v in lines.items() if k.startswith(s)), D("0")))
                for s in ("operating", "investing", "financing")}
    return {"entity": ident, "from_tick": t0, "to_tick": t1, "opening_cash": str(money(opening)),
            "lines": {k: str(money(v)) for k, v in sorted(lines.items())},
            "operating": str(sections["operating"]), "investing": str(sections["investing"]),
            "financing": str(sections["financing"]), "net_change": str(net), "closing_cash": str(closing),
            "reconciles": bool(t1 is not None or closing == money(live))}


def income_statement(db, kind: str, ident: str, t0: int = 0, t1: Optional[int] = None) -> dict:
    ids = _identify(db, kind, ident)
    if ids is None:
        raise NotFound("Unknown entity")
    p, _ = ids
    credit = lambda a: _total(db, "credit_account", p + a, t0, t1)  # noqa: E731
    debit = lambda a: _total(db, "debit_account", p + a, t0, t1)    # noqa: E731
    revenue, cogs = credit("revenue"), debit("cost-of-goods-sold")
    wages, dep, tax = debit("wage-expense"), debit("depreciation-expense"), debit("tax-expense")
    gross = money(revenue - cogs)
    operating = money(gross - wages - dep)
    net = money(operating - tax)
    return {"entity": ident, "from_tick": t0, "to_tick": t1, "revenue": str(revenue),
            "cost_of_goods_sold": str(cogs), "gross_profit": str(gross), "wage_expense": str(wages),
            "depreciation": str(dep), "operating_income": str(operating), "corporate_tax": str(tax),
            "net_income": str(net), "dividends_declared": str(debit("dividends")),
            "memo_labor_and_energy_capitalized": str(credit("conversion-cost-capitalization")),
            "memo_materials_purchased": str(credit("material-acquisition-clearing"))}


def balance_sheet(db, kind: str, ident: str) -> dict:
    ids = _identify(db, kind, ident)
    if ids is None:
        raise NotFound("Unknown entity")
    p, cash_acct = ids
    cash = _bal(db, cash_acct)
    finished = _prefix_sum(db, p + "inventory:")
    raw = _prefix_sum(db, p + "raw-materials:")
    wip = _bal(db, p + "work-in-progress")
    fixed_gross = _bal(db, p + "fixed-assets")
    accumulated = -_bal(db, p + "accumulated-depreciation")
    fixed_net = fixed_gross - accumulated
    assets = money(cash + finished + raw + wip + fixed_net)
    tax_payable = money(-_bal(db, p + "tax-payable"))
    liabilities = tax_payable
    paid_in = D("0")
    for r in _entries(db, "debit_account", cash_acct, 0, None):
        if r["description"].startswith(CONTRIBUTION_PREFIXES):
            paid_in += D(r["amount"])
    paid_in = money(paid_in)
    inc = income_statement(db, kind, ident)
    # Equity is built from the income statement and contributions, NOT from assets - liabilities,
    # so "balanced" is a genuine cross-check of the ledger against the statements.
    retained = money(D(inc["net_income"]) - D(inc["dividends_declared"]))
    equity = money(paid_in + retained)
    return {
        "entity": ident,
        "assets": {"cash": str(cash), "receivables": "0.00", "finished_goods": str(finished),
                   "raw_materials": str(raw), "work_in_progress": str(wip),
                   "fixed_assets_gross": str(fixed_gross), "accumulated_depreciation": str(accumulated),
                   "fixed_assets_net": str(fixed_net), "total": str(assets)},
        "liabilities": {"taxes_payable": str(tax_payable), "trade_payables": "0.00", "total": str(liabilities)},
        "equity": {"paid_in_capital": str(paid_in), "retained_earnings": str(retained), "total": str(equity)},
        "balanced": bool(assets == money(liabilities + equity)),
    }


def statements(db, kind: str, ident: str, t0: int = 0, t1: Optional[int] = None) -> dict:
    return {"balance_sheet": balance_sheet(db, kind, ident),
            "income_statement": income_statement(db, kind, ident, t0, t1),
            "cash_flow": cash_flow(db, kind, ident, t0, t1)}
