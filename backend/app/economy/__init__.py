"""Pure-Python economic simulation core for VirtualExchange.

This package is deliberately isolated from FastAPI and SQLite.  It provides
small, deterministic domain primitives that can be integrated behind the
existing online/offline adapters after their invariants are tested.
"""

from .ledger import Ledger, LedgerEntry, money, quantity, unit_value
from .production import FirmProductionState, InventoryItem, Product
from .households import HouseholdGroup
from .engine_core import run_production_tick

__all__ = [
    "Ledger", "LedgerEntry", "money", "quantity", "unit_value",
    "FirmProductionState", "InventoryItem", "Product",
    "HouseholdGroup", "run_production_tick",
]
