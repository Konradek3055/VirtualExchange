from decimal import Decimal as D
import unittest

from app.economy import (
    FirmProductionState,
    HouseholdGroup,
    InventoryItem,
    Ledger,
    Product,
    run_production_tick,
)


class EconomyCoreTests(unittest.TestCase):
    def make_world(self):
        ledger = Ledger()
        ledger.issue_money("firm:ACME:cash", D("10000"), "test opening capital")
        firm = FirmProductionState(
            firm_id="ACME",
            region="US",
            cash_account="firm:ACME:cash",
            employees=10,
            wage_per_hour=D("10"),
            productivity=D("1"),
            capacity_per_hour=D("5"),
            energy_cost_per_unit=D("0.8"),
            energy_supplier_cash_account="market:energy:cash",
            payroll_shares={"workers_us": D("1")},
        )
        firm.products["widget"] = Product(
            sku="widget",
            name="Widget",
            material_sku="steel",
            material_units_per_unit=D("1"),
            unit_labor_hours=D("0.5"),
            category="goods",
        )
        firm.acquire_materials(
            ledger,
            material_sku="steel",
            amount=D("100"),
            unit_cost=D("2"),
            supplier_cash_account="supplier:steel:cash",
            ref="steel-order-1",
        )
        household = HouseholdGroup(
            group_id="workers_us",
            region="US",
            population=1000,
            avg_wage=D("10"),
            employed=10,
            savings_rate=D("0.10"),
            demand_weights={"goods": D("1")},
        )
        ledger.register_money_account(household.cash_account)
        return ledger, firm, household

    def test_real_production_payroll_and_sales_conserve_cash(self):
        ledger, firm, household = self.make_world()
        before = ledger.money_supply()
        stats = run_production_tick(ledger, [firm], [household], hours=D("8"), tick=1)

        self.assertEqual(stats["produced"]["ACME:widget"], D("40.00000000"))
        self.assertGreater(stats["wages_paid"], D("0"))
        self.assertGreater(stats["energy_paid"], D("0"))
        self.assertGreater(stats["consumption_value"], D("0"))
        self.assertGreater(stats["sold"]["ACME:widget"], D("0"))
        self.assertGreater(ledger.balance("households:workers_us:cash"), D("0"))
        self.assertGreater(ledger.balance("supplier:steel:cash"), D("0"))
        self.assertGreater(ledger.balance("market:energy:cash"), D("0"))
        self.assertLess(firm.inventory["widget"].quantity, D("40"))
        self.assertEqual(ledger.money_supply(), before)
        ledger.assert_balanced()
        ledger.assert_cash_accounts_non_negative()

    def test_no_materials_means_no_production(self):
        ledger = Ledger()
        ledger.issue_money("firm:DRY:cash", D("1000"), "opening capital")
        firm = FirmProductionState("DRY", "US", "firm:DRY:cash")
        firm.products["widget"] = Product("widget", "Widget", "steel", D("1"), D("0.5"))
        result = firm.produce(
            ledger,
            "widget",
            D("10"),
            D("8"),
            {"households:workers:cash": D("1")},
        )
        self.assertEqual(result, D("0"))
        self.assertNotIn("widget", firm.inventory)

    def test_purchase_requires_funded_buyer_and_does_not_overdraft(self):
        ledger = Ledger()
        ledger.issue_money("firm:SHOP:cash", D("100"), "capital")
        ledger.register_money_account("households:poor:cash")
        firm = FirmProductionState("SHOP", "US", "firm:SHOP:cash")
        firm.products["book"] = Product("book", "Book", "paper", D("1"), D("0.1"))
        firm.materials_stock["paper"] = D("10")
        firm.materials_unit_cost["paper"] = D("1")
        # Give inventory through the production path.
        ledger.register_money_account("households:worker:cash")
        firm.produce(ledger, "book", D("1"), D("1"), {"households:worker:cash": D("1")})
        result = firm.sell(ledger, "book", D("1"), D("10"), "households:poor:cash")
        self.assertEqual(result["quantity"], D("0"))
        self.assertEqual(ledger.balance("households:poor:cash"), D("0"))

    def test_ledger_rejects_overspending_and_preserves_supply(self):
        ledger = Ledger()
        ledger.issue_money("a:cash", D("50"), "opening")
        ledger.register_money_account("b:cash")
        original = ledger.money_supply()
        with self.assertRaises(ValueError):
            ledger.transfer("a:cash", "b:cash", D("50.01"), "overspend")
        ledger.transfer("a:cash", "b:cash", D("12.34"), "valid transfer")
        self.assertEqual(ledger.money_supply(), original)
        ledger.assert_money_supply(original)


    def test_households_do_not_buy_foreign_goods_before_fx_exists(self):
        ledger = Ledger()
        ledger.issue_money("households:us:cash", D("100"), "consumer savings")
        buyer = HouseholdGroup(
            "us", "US", 100, D("10"), 10,
            savings_rate=D("0"), demand_weights={"goods": D("1")},
        )
        foreign_firm = FirmProductionState("EUCO", "EU", "firm:EUCO:cash", employees=0)
        foreign_firm.products["widget"] = Product("widget", "Widget", "raw", D("0"), D("0.1"))
        foreign_firm.inventory["widget"] = InventoryItem("widget", D("5"), D("2"))
        ledger.issue_money("firm:EUCO:cash", D("10"), "settlement liquidity")
        stats = run_production_tick(ledger, [foreign_firm], [buyer], hours=D("1"), tick=1)
        self.assertEqual(stats["consumption_value"], D("0.00"))
        self.assertEqual(foreign_firm.inventory["widget"].quantity, D("5"))
        ledger.assert_balanced()


    def test_total_capacity_is_shared_across_multiple_products(self):
        ledger = Ledger()
        ledger.issue_money("firm:MULTI:cash", D("1000"), "capital")
        firm = FirmProductionState(
            "MULTI", "US", "firm:MULTI:cash", employees=10,
            wage_per_hour=D("1"), energy_cost_per_unit=D("0"),
            payroll_shares={"workers": D("1")}, capacity_per_hour=D("5"),
        )
        firm.products["a"] = Product("a", "Product A", "raw-a", D("1"), D("0.1"), category="goods")
        firm.products["b"] = Product("b", "Product B", "raw-b", D("1"), D("0.1"), category="goods")
        firm.acquire_materials(ledger, "raw-a", D("10"), D("1"), "supplier:a:cash")
        firm.acquire_materials(ledger, "raw-b", D("10"), D("1"), "supplier:b:cash")
        workers = HouseholdGroup(
            "workers", "US", 100, D("1"), 10,
            demand_weights={"food": D("1")},
        )
        stats = run_production_tick(ledger, [firm], [workers], hours=D("1"), tick=1)
        produced = sum(stats["produced"].values(), D("0"))
        self.assertEqual(produced, D("5.00000000"))
        ledger.assert_balanced()

    def test_same_sku_from_two_firms_is_not_overwritten(self):
        ledger = Ledger()
        ledger.issue_money("firm:A:cash", D("500"), "capital A")
        ledger.issue_money("firm:B:cash", D("500"), "capital B")
        households = HouseholdGroup(
            "buyers", "US", 100, D("10"), 10,
            savings_rate=D("0"), demand_weights={"goods": D("1")},
        )
        ledger.issue_money(households.cash_account, D("100"), "consumer opening savings")
        firms = []
        for firm_id in ("A", "B"):
            firm = FirmProductionState(
                firm_id, "US", f"firm:{firm_id}:cash", employees=10,
                wage_per_hour=D("1"), energy_cost_per_unit=D("0"),
                energy_supplier_cash_account=f"market:energy:{firm_id}:cash",
                payroll_shares={"buyers": D("1")},
            )
            firm.products["widget"] = Product("widget", "Widget", "raw", D("1"), D("0.1"))
            firm.acquire_materials(ledger, "raw", D("5"), D("1"), f"supplier:{firm_id}:cash")
            firms.append(firm)
        stats = run_production_tick(ledger, firms, [households], hours=D("1"), tick=1)
        self.assertIn("A:widget", stats["produced"])
        self.assertIn("B:widget", stats["produced"])
        self.assertGreater(stats["consumption_value"], D("0"))
        self.assertGreater(stats["sold"].get("A:widget", D("0")), D("0"))
        self.assertGreater(stats["sold"].get("B:widget", D("0")), D("0"))
        ledger.assert_balanced()


if __name__ == "__main__":
    unittest.main()
