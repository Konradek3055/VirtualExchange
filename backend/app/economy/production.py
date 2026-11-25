"""Firm production, inventory, procurement and customer sales."""
from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal, localcontext
from typing import Dict, Mapping, Optional

from .ledger import D, Ledger, QUANTITY_STEP, money, quantity, unit_value


@dataclass(frozen=True)
class Product:
    sku: str
    name: str
    material_sku: str = "raw"
    material_units_per_unit: Decimal = D("1")
    unit_labor_hours: Decimal = D("0.5")
    quality: int = 50
    category: str = "goods"

    def __post_init__(self) -> None:
        if not self.sku or not self.name or not self.category:
            raise ValueError("Product SKU, name and category are required")
        material_units = quantity(self.material_units_per_unit)
        labor_hours = quantity(self.unit_labor_hours)
        if material_units < 0 or labor_hours <= 0:
            raise ValueError("Material usage must be non-negative and labor hours positive")
        if isinstance(self.quality, bool) or not 0 <= self.quality <= 100:
            raise ValueError("Product quality must be between 0 and 100")


@dataclass
class InventoryItem:
    sku: str
    quantity: Decimal = D("0")
    avg_cost: Decimal = D("0")


@dataclass
class FirmProductionState:
    firm_id: str
    region: str
    cash_account: str
    products: Dict[str, Product] = field(default_factory=dict)
    inventory: Dict[str, InventoryItem] = field(default_factory=dict)
    employees: int = 10
    wage_per_hour: Decimal = D("15")
    productivity: Decimal = D("1")
    capacity_per_hour: Decimal = D("5")
    energy_cost_per_unit: Decimal = D("0.8")
    energy_supplier_cash_account: str = "market:energy:cash"
    materials_stock: Dict[str, Decimal] = field(default_factory=dict)
    materials_unit_cost: Dict[str, Decimal] = field(default_factory=dict)
    material_supplier_cash_accounts: Dict[str, str] = field(default_factory=dict)
    # Optional explicit allocation of this firm's payroll across household groups.
    payroll_shares: Dict[str, Decimal] = field(default_factory=dict)
    sales_markup: Decimal = D("1.25")

    def __post_init__(self) -> None:
        if not self.firm_id or not self.region or not self.cash_account:
            raise ValueError("Firm id, region and cash account are required")
        if isinstance(self.employees, bool) or self.employees < 0:
            raise ValueError("Employee count cannot be negative")
        if quantity(self.wage_per_hour) < 0 or quantity(self.productivity) <= 0:
            raise ValueError("Wage must be non-negative and productivity positive")
        if quantity(self.capacity_per_hour) <= 0 or quantity(self.energy_cost_per_unit) < 0:
            raise ValueError("Capacity must be positive and energy cost non-negative")
        if D(str(self.sales_markup)) < 1:
            raise ValueError("Sales markup must be at least 1.0")

    def available_capacity(self, hours: Decimal) -> Decimal:
        period = quantity(hours)
        if period <= 0:
            return D("0")
        with localcontext() as ctx:
            ctx.prec = 60
            return quantity(
                D(str(self.capacity_per_hour)) * period * D(str(self.productivity))
                * D(self.employees) / D("10")
            )

    def _inventory_account(self, sku: str) -> str:
        return f"firm:{self.firm_id}:inventory:{sku}"

    def _raw_material_account(self, material_sku: str) -> str:
        return f"firm:{self.firm_id}:raw-materials:{material_sku}"

    def acquire_materials(
        self,
        ledger: Ledger,
        material_sku: str,
        amount: Decimal,
        unit_cost: Decimal,
        supplier_cash_account: str,
        ref: Optional[str] = None,
    ) -> Decimal:
        """Purchase physical inputs from a supplier and maintain weighted cost."""
        qty = quantity(amount)
        price = unit_value(unit_cost)
        if qty <= 0 or price <= 0:
            raise ValueError("Material quantity and unit cost must be positive")
        if not material_sku or not supplier_cash_account:
            raise ValueError("Material SKU and supplier cash account are required")
        ledger.register_money_account(self.cash_account)
        ledger.register_money_account(supplier_cash_account)
        total_cost = money(qty * price)
        ledger.transfer(
            self.cash_account,
            supplier_cash_account,
            total_cost,
            f"{self.firm_id} purchases {material_sku} x{qty}",
            ref=ref,
        )

        old_qty = quantity(self.materials_stock.get(material_sku, D("0")))
        old_cost = unit_value(self.materials_unit_cost.get(material_sku, D("0")))
        new_qty = quantity(old_qty + qty)
        with localcontext() as ctx:
            ctx.prec = 60
            weighted_cost = unit_value((old_qty * old_cost + total_cost) / new_qty)
        self.materials_stock[material_sku] = new_qty
        self.materials_unit_cost[material_sku] = weighted_cost
        self.material_supplier_cash_accounts[material_sku] = supplier_cash_account
        ledger.post(
            debit=self._raw_material_account(material_sku),
            credit=f"firm:{self.firm_id}:material-acquisition-clearing",
            amount=total_cost,
            description=f"Capitalize purchased input {material_sku}",
            ref=ref,
        )
        return qty

    @staticmethod
    def _allocate_money(total: Decimal, recipients: Mapping[str, Decimal]) -> Dict[str, Decimal]:
        """Allocate cents deterministically; allocations always sum to total."""
        total = money(total)
        if total == 0:
            return {}
        weights = {str(k): D(str(v)) for k, v in recipients.items() if D(str(v)) > 0}
        if not weights:
            raise ValueError("Payroll recipients are required when wages are due")
        weight_sum = sum(weights.values(), D("0"))
        if weight_sum <= 0:
            raise ValueError("Payroll weights must sum to a positive value")
        total_cents = int(total * 100)
        floors: Dict[str, int] = {}
        remainders = []
        for account in sorted(weights):
            exact = D(total_cents) * weights[account] / weight_sum
            base = int(exact)
            floors[account] = base
            remainders.append((account, exact - D(base)))
        left = total_cents - sum(floors.values())
        for account, _ in sorted(remainders, key=lambda item: (-item[1], item[0]))[:left]:
            floors[account] += 1
        return {account: D(cents) / D("100") for account, cents in floors.items() if cents > 0}

    def produce(
        self,
        ledger: Ledger,
        sku: str,
        requested_quantity: Decimal,
        hours: Decimal,
        payroll_recipients: Mapping[str, Decimal],
    ) -> Decimal:
        """Produce only within capacity, available inputs, labor and cash limits."""
        if sku not in self.products:
            raise ValueError(f"Unknown product: {sku}")
        product = self.products[sku]
        wanted = quantity(requested_quantity)
        period = quantity(hours)
        if wanted <= 0 or period <= 0 or self.employees <= 0:
            return D("0")

        ledger.register_money_account(self.cash_account)
        material_sku = product.material_sku
        material_per_unit = quantity(product.material_units_per_unit)
        available_material = quantity(self.materials_stock.get(material_sku, D("0")))
        if material_per_unit > 0:
            wanted = min(wanted, quantity(available_material / material_per_unit))
        labor_capacity = quantity(D(self.employees) * period / D(str(product.unit_labor_hours)))
        wanted = min(wanted, labor_capacity, self.available_capacity(period))
        if wanted <= 0:
            return D("0")

        wage_per_unit = D(str(self.wage_per_hour)) * D(str(product.unit_labor_hours))
        energy_per_unit = D(str(self.energy_cost_per_unit))
        cash_cost_per_unit = wage_per_unit + energy_per_unit
        available_cash = ledger.balance(self.cash_account)
        if cash_cost_per_unit > 0:
            # Find the largest 8-decimal quantity whose separately rounded wage
            # and energy payments fit the firm's actual cash balance.
            low = 0
            high = int(wanted / QUANTITY_STEP)
            while low < high:
                mid = (low + high + 1) // 2
                candidate = D(mid) * QUANTITY_STEP
                candidate_wages = money(candidate * D(str(product.unit_labor_hours)) * D(str(self.wage_per_hour)))
                candidate_energy = money(candidate * D(str(self.energy_cost_per_unit)))
                if money(candidate_wages + candidate_energy) <= available_cash:
                    low = mid
                else:
                    high = mid - 1
            wanted = min(wanted, D(low) * QUANTITY_STEP)
        if wanted <= 0:
            return D("0")

        required_material = quantity(wanted * material_per_unit)
        material_unit_cost = unit_value(self.materials_unit_cost.get(material_sku, D("0")))
        material_cost = money(required_material * material_unit_cost)
        labor_hours = D(str(product.unit_labor_hours)) * wanted
        wage_bill = money(labor_hours * D(str(self.wage_per_hour)))
        energy_bill = money(wanted * D(str(self.energy_cost_per_unit)))

        # Re-check rounded cash requirements before any mutation.
        total_cash_cost = money(wage_bill + energy_bill)
        if total_cash_cost > ledger.balance(self.cash_account):
            raise ValueError("Insufficient firm cash for payroll and energy")
        if wage_bill > 0 and not payroll_recipients:
            raise ValueError("Payroll recipients are required to pay workers")

        # Pay real recipients. These transfers conserve the registered cash supply.
        for recipient, portion in self._allocate_money(wage_bill, payroll_recipients).items():
            ledger.register_money_account(recipient)
            ledger.transfer(
                self.cash_account,
                recipient,
                portion,
                f"{self.firm_id} payroll for {sku}",
            )
        if energy_bill > 0:
            ledger.register_money_account(self.energy_supplier_cash_account)
            ledger.transfer(
                self.cash_account,
                self.energy_supplier_cash_account,
                energy_bill,
                f"{self.firm_id} energy for {sku}",
            )

        if material_per_unit > 0:
            self.materials_stock[material_sku] = quantity(available_material - required_material)
            if material_cost > 0:
                ledger.post(
                    debit=f"firm:{self.firm_id}:work-in-progress",
                    credit=self._raw_material_account(material_sku),
                    amount=material_cost,
                    description=f"Consume {material_sku} for {sku}",
                )

        conversion_cost = money(wage_bill + energy_bill)
        if conversion_cost > 0:
            ledger.post(
                debit=f"firm:{self.firm_id}:work-in-progress",
                credit=f"firm:{self.firm_id}:conversion-cost-capitalization",
                amount=conversion_cost,
                description=f"Capitalize labor and energy for {sku}",
            )

        total_cost = money(material_cost + conversion_cost)
        if total_cost > 0:
            ledger.post(
                debit=self._inventory_account(sku),
                credit=f"firm:{self.firm_id}:work-in-progress",
                amount=total_cost,
                description=f"Complete production of {sku} x{wanted}",
            )

        previous = self.inventory.get(sku)
        previous_qty = quantity(previous.quantity) if previous else D("0")
        previous_value = money(previous_qty * previous.avg_cost) if previous else D("0")
        new_qty = quantity(previous_qty + wanted)
        new_value = money(previous_value + total_cost)
        with localcontext() as ctx:
            ctx.prec = 60
            average_cost = unit_value(new_value / new_qty) if new_qty > 0 else D("0")
        self.inventory[sku] = InventoryItem(sku=sku, quantity=new_qty, avg_cost=average_cost)
        return wanted

    def sell(
        self,
        ledger: Ledger,
        sku: str,
        requested_quantity: Decimal,
        price: Decimal,
        buyer_cash_account: str,
        ref: Optional[str] = None,
    ) -> Dict[str, Decimal]:
        """Sell existing stock to a funded buyer; never creates demand or cash."""
        if sku not in self.inventory:
            return {"quantity": D("0"), "revenue": D("0"), "cogs": D("0"), "profit": D("0")}
        unit_price = unit_value(price)
        wanted = quantity(requested_quantity)
        if unit_price <= 0:
            raise ValueError("Sale price must be positive")
        if wanted <= 0:
            return {"quantity": D("0"), "revenue": D("0"), "cogs": D("0"), "profit": D("0")}

        ledger.register_money_account(self.cash_account)
        ledger.register_money_account(buyer_cash_account)
        buyer_cash = ledger.balance(buyer_cash_account)
        inv = self.inventory[sku]
        # ROUND_HALF_UP permits at most half a cent of exact-value headroom;
        # subtract a tiny epsilon before flooring to avoid a cent overdraft.
        max_affordable = quantity((buyer_cash + D("0.004999999999")) / unit_price)
        actual_qty = min(wanted, quantity(inv.quantity), max_affordable)
        if actual_qty <= 0:
            return {"quantity": D("0"), "revenue": D("0"), "cogs": D("0"), "profit": D("0")}

        revenue = money(actual_qty * unit_price)
        # Guard the cash invariant even at the exact half-cent boundary.
        if revenue > buyer_cash:
            actual_qty = quantity(buyer_cash / unit_price)
            revenue = money(actual_qty * unit_price)
        if actual_qty <= 0 or revenue <= 0:
            return {"quantity": D("0"), "revenue": D("0"), "cogs": D("0"), "profit": D("0")}

        cogs = money(actual_qty * D(str(inv.avg_cost)))
        ledger.transfer(
            buyer_cash_account,
            self.cash_account,
            revenue,
            f"Customer purchase {sku} from {self.firm_id}",
            ref=ref,
        )
        if cogs > 0:
            ledger.post(
                debit=f"firm:{self.firm_id}:cost-of-goods-sold",
                credit=self._inventory_account(sku),
                amount=cogs,
                description=f"Recognize COGS for {sku}",
                ref=ref,
            )
        ledger.post(
            debit=f"firm:{self.firm_id}:sales-recognition-clearing",
            credit=f"firm:{self.firm_id}:revenue",
            amount=revenue,
            description=f"Recognize sales revenue for {sku}",
            ref=ref,
        )

        inv.quantity = quantity(inv.quantity - actual_qty)
        if inv.quantity == 0:
            inv.avg_cost = D("0")
        return {
            "quantity": actual_qty,
            "revenue": revenue,
            "cogs": cogs,
            "profit": money(revenue - cogs),
        }
