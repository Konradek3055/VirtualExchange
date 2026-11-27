"""Deterministic first production-consumption tick for the economy core."""
from __future__ import annotations

from decimal import Decimal
from typing import Dict, Iterable, List

from .households import HouseholdGroup
from .ledger import D, Ledger, money, quantity, unit_value
from .production import FirmProductionState


class MarketOffer:
    def __init__(self, firm: FirmProductionState, sku: str, price: Decimal, category: str):
        self.firm = firm
        self.sku = sku
        self.price = unit_value(price)
        self.category = category


def _payroll_recipients(
    firm: FirmProductionState,
    households: Iterable[HouseholdGroup],
) -> Dict[str, Decimal]:
    groups = {group.group_id: group for group in households}
    if firm.payroll_shares:
        unknown = set(firm.payroll_shares) - set(groups)
        if unknown:
            raise ValueError(f"Unknown payroll household groups: {sorted(unknown)}")
        if any(groups[group_id].region != firm.region for group_id in firm.payroll_shares):
            raise ValueError("Cross-region payroll requires the FX/settlement phase")
        shares = {groups[group_id].cash_account: D(str(share)) for group_id, share in firm.payroll_shares.items()}
        if any(value <= 0 for value in shares.values()):
            raise ValueError("Explicit payroll shares must be positive")
        return shares
    employed = {
        group.cash_account: D(group.employed)
        for group in groups.values()
        if group.employed > 0 and group.region == firm.region
    }
    if not employed:
        return {}
    return employed


def run_production_tick(
    ledger: Ledger,
    firms: List[FirmProductionState],
    households: List[HouseholdGroup],
    hours: Decimal = D("1"),
    tick: int | None = None,
) -> Dict[str, object]:
    """Run procurement-backed production, payroll, and household purchases.

    Order is deterministic: firms and products are processed by stable IDs.
    This function mutates only the supplied in-memory simulation objects.
    It does not yet persist to SQLite or expose an HTTP endpoint.
    """
    period = quantity(hours)
    if period <= 0:
        raise ValueError("Tick duration must be positive")
    if tick is not None:
        ledger.set_tick(tick)

    initial_supply = ledger.money_supply()
    household_by_id = {group.group_id: group for group in households}
    if len(household_by_id) != len(households):
        raise ValueError("Household group IDs must be unique")
    firm_ids = [firm.firm_id for firm in firms]
    if len(set(firm_ids)) != len(firm_ids):
        raise ValueError("Firm IDs must be unique")
    for firm in firms:
        for sku, product in firm.products.items():
            if sku != product.sku:
                raise ValueError(f"Product map key {sku} does not match Product.sku {product.sku}")

    stats: Dict[str, object] = {
        "produced": {},
        "sold": {},
        "wages_paid": D("0"),
        "energy_paid": D("0"),
        "consumption_value": D("0"),
        "consumption_units": D("0"),
        "money_supply_before": initial_supply,
    }

    for firm in sorted(firms, key=lambda item: item.firm_id):
        ledger.register_money_account(firm.cash_account)
        recipients = _payroll_recipients(firm, households)
        remaining_capacity = firm.available_capacity(period)
        remaining_labor_hours = D(firm.employees) * period
        for sku, product in sorted(firm.products.items()):
            if remaining_capacity <= 0 or remaining_labor_hours <= 0:
                break
            by_labor = quantity(remaining_labor_hours / D(str(product.unit_labor_hours)))
            planned = min(remaining_capacity, by_labor)
            entry_start = len(ledger.entries)
            produced = firm.produce(ledger, sku, planned, period, recipients)
            new_entries = ledger.entries[entry_start:]
            wages = sum((entry.amount for entry in new_entries
                         if entry.description == f"{firm.firm_id} payroll for {sku}"), D("0"))
            energy = sum((entry.amount for entry in new_entries
                          if entry.description == f"{firm.firm_id} energy for {sku}"), D("0"))
            stats["wages_paid"] = money(D(stats["wages_paid"]) + wages)
            stats["energy_paid"] = money(D(stats["energy_paid"]) + energy)
            stats["produced"][f"{firm.firm_id}:{sku}"] = produced
            remaining_capacity = max(D("0"), quantity(remaining_capacity - produced))
            remaining_labor_hours = max(
                D("0"), remaining_labor_hours - D(str(product.unit_labor_hours)) * produced
            )

    offers: List[MarketOffer] = []
    for firm in sorted(firms, key=lambda item: item.firm_id):
        for sku, inventory in sorted(firm.inventory.items()):
            if inventory.quantity <= 0:
                continue
            product = firm.products[sku]
            price = unit_value(inventory.avg_cost * D(str(firm.sales_markup)))
            if price > 0:
                offers.append(MarketOffer(firm, sku, price, product.category))

    for household in sorted(households, key=lambda item: item.group_id):
        purchased = household.consume(ledger, offers)
        stats["consumption_value"] = money(D(stats["consumption_value"]) + purchased["value"])
        stats["consumption_units"] = quantity(D(stats["consumption_units"]) + purchased["quantity"])
        for key, sold_quantity in purchased["by_offer"].items():
            stats["sold"][key] = quantity(D(stats["sold"].get(key, D("0"))) + sold_quantity)

    # Cash transfers are the monetary invariant. Accounting-only inventory
    # postings are also balanced by Ledger.post.
    ledger.assert_money_supply(initial_supply)
    stats["money_supply_after"] = ledger.money_supply()
    stats["journal_entries"] = len(ledger.entries)
    return stats
