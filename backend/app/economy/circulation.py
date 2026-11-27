"""Money circulation: suppliers as real businesses, wages, taxes, dividends, public transfers.

Every flow is a ledger transfer between two registered cash accounts, so aggregate money
supply is unchanged.  Revenue/expense/tax/dividend postings are balanced accounting pairs
on per-entity accounts, which the accounting module turns into statements.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal
from typing import Dict, List, Optional

from .households import HouseholdGroup
from .ledger import D, Ledger, money
from .production import FirmProductionState

DEPRECIATION_LIFE_TICKS = D("500")   # straight line, per tick
RECEIPT_MARKERS = (" purchases ", " energy for ")


@dataclass
class Entity:
    """Supplier of materials / energy / capital goods: owned, staffed, taxed."""
    entity_id: str
    kind: str
    region: str
    cash_account: str
    owner_group: Optional[str] = None
    employees: int = 0
    wage_per_hour: Decimal = D("10")
    sells: Dict[str, Decimal] = field(default_factory=dict)
    dividend_payout: Decimal = D("0.5")

    @property
    def prefix(self) -> str:
        return f"entity:{self.entity_id}:"


@dataclass
class Region:
    region: str
    income_tax: Decimal = D("0.10")
    corporate_tax: Decimal = D("0.20")
    spend_ratio: Decimal = D("0.90")
    gov_account: str = ""

    def __post_init__(self) -> None:
        if not self.gov_account:
            self.gov_account = f"government:{self.region}:cash"


def _sum(entries, pred) -> Decimal:
    return money(sum((e.amount for e in entries if pred(e)), D("0")))


def retained(ledger: Ledger, p: str) -> Decimal:
    b = ledger.balance
    return money(-b(p + "revenue") - b(p + "cost-of-goods-sold") - b(p + "wage-expense")
                 - b(p + "depreciation-expense") - b(p + "tax-expense") - b(p + "dividends"))


def _tax_and_dividend(ledger, p, cash, ident, profit, corp_rate, gov_account, owner_cash,
                      payout, reserve, stats) -> None:
    if profit > 0:
        tax = money(profit * corp_rate)
        if tax > 0:
            ledger.post(p + "tax-expense", p + "tax-payable", tax, f"Accrue corporate tax {ident}")
    payable = money(-ledger.balance(p + "tax-payable"))
    pay = min(payable, ledger.balance(cash))
    if pay > 0:
        ledger.transfer(cash, gov_account, pay, f"tax:corporate {ident}")
        ledger.post(p + "tax-payable", p + "tax-settlement-clearing", pay, f"Settle corporate tax {ident}")
        stats["taxes_collected"] += pay
    if owner_cash and payout > 0:
        payable = money(-ledger.balance(p + "tax-payable"))
        free = ledger.balance(cash) - reserve - payable
        distributable = min(retained(ledger, p), free)
        div = money(distributable * payout) if distributable > 0 else D("0")
        if div > 0:
            ledger.register_money_account(owner_cash)
            ledger.transfer(cash, owner_cash, div, f"dividend:{ident}")
            ledger.post(p + "dividends", p + "dividends-clearing", div, f"Record dividend {ident}")
            stats["dividends_paid"] += div


def _owner_cash(owner_group, owner_account_id, by_id) -> Optional[str]:
    if owner_group and owner_group in by_id:
        return by_id[owner_group].cash_account
    if owner_account_id:
        return f"player:{owner_account_id}:cash"
    return None


def recognize_receipt(ledger: Ledger, entity_id: str, amount: Decimal) -> None:
    """Book revenue for a supplier payment received outside a tick (player purchases)."""
    p = f"entity:{entity_id}:"
    ledger.post(p + "sales-recognition-clearing", p + "revenue", amount, f"Recognize revenue {entity_id}")


def settle_entity(ledger, e: Entity, households, regions, mark, hours, stats) -> None:
    p, cash = e.prefix, e.cash_account
    ledger.register_money_account(cash)
    new = ledger.entries[mark:]
    receipts = _sum(new, lambda x: x.debit_account == cash and (
        any(m in x.description for m in RECEIPT_MARKERS)))
    if receipts > 0:
        ledger.post(p + "sales-recognition-clearing", p + "revenue", receipts, f"Recognize revenue {e.entity_id}")
    recipients = {h.cash_account: D(h.employed) for h in households if h.region == e.region and h.employed > 0}
    bill = money(D(e.employees) * e.wage_per_hour * hours)
    pay = min(bill, ledger.balance(cash)) if recipients else D("0")
    if pay > 0:
        for acct, portion in FirmProductionState._allocate_money(pay, recipients).items():
            ledger.transfer(cash, acct, portion, f"{e.entity_id} payroll for services")
        ledger.post(p + "wage-expense", p + "wage-clearing", pay, f"Wage expense {e.entity_id}")
        stats["supplier_wages"] += pay
    rg = regions[e.region]
    by_id = {h.group_id: h for h in households}
    _tax_and_dividend(ledger, p, cash, e.entity_id, receipts - pay, rg.corporate_tax, rg.gov_account,
                      _owner_cash(e.owner_group, None, by_id), e.dividend_payout, money(bill * 2), stats)


def settle_firm(ledger, f: FirmProductionState, meta, households, regions, mark, hours, stats) -> None:
    p, cash = f"firm:{f.firm_id}:", f.cash_account
    new = ledger.entries[mark:]
    revenue = _sum(new, lambda x: x.credit_account == p + "revenue")
    cogs = _sum(new, lambda x: x.debit_account == p + "cost-of-goods-sold")
    gross = ledger.balance(p + "fixed-assets")
    accumulated = -ledger.balance(p + "accumulated-depreciation")
    dep = D("0")
    if gross > 0:
        dep = min(money(gross / DEPRECIATION_LIFE_TICKS), money(gross - accumulated))
        if dep > 0:
            ledger.post(p + "depreciation-expense", p + "accumulated-depreciation", dep,
                        f"Depreciation {f.firm_id}")
    rg = regions.get(f.region)
    if rg is None:
        return
    by_id = {h.group_id: h for h in households}
    reserve = money(D(f.employees) * f.wage_per_hour * hours * 3) + meta["cash_reserve"]
    _tax_and_dividend(ledger, p, cash, f.firm_id, revenue - cogs - dep, rg.corporate_tax, rg.gov_account,
                      _owner_cash(meta["owner_group"], meta["owner_account_id"], by_id),
                      meta["dividend_payout"], reserve, stats)


def settle_households_and_government(ledger, households, regions, mark, stats) -> None:
    new = ledger.entries[mark:]
    for h in sorted(households, key=lambda x: x.group_id):
        rg = regions.get(h.region)
        if rg is None:
            continue
        income = _sum(new, lambda x: x.debit_account == h.cash_account and (
            " payroll for " in x.description or x.description.startswith("dividend:")))
        tax = min(money(income * rg.income_tax), ledger.balance(h.cash_account))
        if tax > 0:
            ledger.transfer(h.cash_account, rg.gov_account, tax, f"tax:income {h.group_id}")
            stats["taxes_collected"] += tax
    for name in sorted(regions):
        rg = regions[name]
        ledger.register_money_account(rg.gov_account)
        spend = money(ledger.balance(rg.gov_account) * rg.spend_ratio)
        weights = {h.cash_account: D(h.population) for h in households
                   if h.region == name and h.population > 0}
        if spend > 0 and weights:
            for acct, portion in FirmProductionState._allocate_money(spend, weights).items():
                ledger.transfer(rg.gov_account, acct, portion, f"transfer:public {name}")
            stats["public_transfers"] += spend


def settle(ledger, firms, meta, entities, households, regions, mark, hours) -> Dict[str, Decimal]:
    stats = {"taxes_collected": D("0"), "dividends_paid": D("0"),
             "supplier_wages": D("0"), "public_transfers": D("0")}
    for e in sorted(entities, key=lambda x: x.entity_id):
        if e.region in regions:
            settle_entity(ledger, e, households, regions, mark, hours, stats)
    for f in sorted(firms, key=lambda x: x.firm_id):
        settle_firm(ledger, f, meta[f.firm_id], households, regions, mark, hours, stats)
    settle_households_and_government(ledger, households, regions, mark, stats)
    return {k: money(v) for k, v in stats.items()}
