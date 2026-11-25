"""Aggregated household demand and real cash-funded consumption."""
from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal
from typing import Dict, Iterable

from .ledger import D, Ledger, money, quantity, unit_value


@dataclass
class HouseholdGroup:
    group_id: str
    region: str
    population: int
    avg_wage: Decimal
    employed: int
    savings_rate: Decimal = D("0.15")
    cash_account: str = ""
    demand_weights: Dict[str, Decimal] = field(default_factory=lambda: {
        "food": D("0.25"),
        "goods": D("0.35"),
        "services": D("0.20"),
        "energy": D("0.10"),
        "other": D("0.10"),
    })

    def __post_init__(self) -> None:
        if not self.group_id or not self.region:
            raise ValueError("Household group id and region are required")
        if self.population < 0 or self.employed < 0 or self.employed > self.population:
            raise ValueError("Invalid household population/employment")
        self.savings_rate = D(str(self.savings_rate))
        if not D("0") <= self.savings_rate <= D("1"):
            raise ValueError("Savings rate must be between 0 and 1")
        self.demand_weights = {k: D(str(v)) for k, v in self.demand_weights.items()}
        if any(v < 0 for v in self.demand_weights.values()):
            raise ValueError("Demand weights cannot be negative")
        if self.demand_weights and sum(self.demand_weights.values(), D("0")) <= 0:
            raise ValueError("At least one demand weight must be positive")
        if not self.cash_account:
            self.cash_account = f"households:{self.group_id}:cash"

    def receive_wages(self, ledger: Ledger, amount: Decimal, source_cash_account: str) -> Decimal:
        value = money(amount)
        if value <= 0:
            return D("0")
        ledger.register_money_account(self.cash_account)
        ledger.register_money_account(source_cash_account)
        ledger.transfer(source_cash_account, self.cash_account, value, f"Wages to {self.group_id}")
        return value

    def consume(self, ledger: Ledger, offers: Iterable[object]) -> Dict[str, Decimal]:
        """Buy matching-category products from offers; budget is cash-constrained."""
        ledger.register_money_account(self.cash_account)
        cash = ledger.balance(self.cash_account)
        spendable = money(cash * (D("1") - self.savings_rate))
        if spendable <= 0:
            return {"quantity": D("0"), "value": D("0")}

        total_bought = D("0")
        total_value = D("0")
        by_offer: Dict[str, Decimal] = {}
        remaining = spendable
        ordered_offers = sorted(
            list(offers),
            key=lambda offer: (offer.category, offer.firm.firm_id, offer.sku),
        )
        for category, weight in sorted(self.demand_weights.items(), key=lambda item: (-item[1], item[0])):
            category_budget = money(spendable * weight)
            for offer in ordered_offers:
                if offer.category != category or offer.firm.region != self.region:
                    continue
                if category_budget <= 0 or remaining <= 0:
                    continue
                price = unit_value(offer.price)
                if price <= 0:
                    continue
                inv = offer.firm.inventory.get(offer.sku)
                if inv is None or inv.quantity <= 0:
                    continue
                max_qty = min(quantity(inv.quantity), quantity(category_budget / price), quantity(remaining / price))
                if max_qty <= 0:
                    continue
                sale = offer.firm.sell(
                    ledger,
                    offer.sku,
                    max_qty,
                    price,
                    self.cash_account,
                )
                bought_qty = sale["quantity"]
                spent = sale["revenue"]
                if bought_qty > 0 and spent > 0:
                    total_bought = quantity(total_bought + bought_qty)
                    total_value = money(total_value + spent)
                    offer_key = f"{offer.firm.firm_id}:{offer.sku}"
                    by_offer[offer_key] = quantity(by_offer.get(offer_key, D("0")) + bought_qty)
                    category_budget = max(D("0"), money(category_budget - spent))
                    remaining = max(D("0"), money(remaining - spent))
        return {"quantity": total_bought, "value": total_value, "by_offer": by_offer}
