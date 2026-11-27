"""Optional background ticker so the economy keeps running while nobody plays.

Enabled only when VE_ECONOMY_ENABLED=1 and VE_ECONOMY_AUTOTICK_SECONDS >= 5. Each tick advances
the world by one simulated hour and runs in its own atomic transaction; a failing tick is rolled
back, logged and retried at the next interval (it never takes the API down).
"""
from __future__ import annotations

import asyncio
import logging
import os
from decimal import Decimal
from typing import Optional

from ..db import transaction
from . import store, world

log = logging.getLogger("virtualexchange.economy")
MIN_INTERVAL = 5.0


def interval_seconds() -> float:
    if os.environ.get("VE_ECONOMY_ENABLED") != "1":
        return 0.0
    try:
        value = float(os.environ.get("VE_ECONOMY_AUTOTICK_SECONDS", "0"))
    except ValueError:
        return 0.0
    return value if value >= MIN_INTERVAL else 0.0


def tick_once(hours: Decimal = Decimal(1)) -> Optional[dict]:
    try:
        with transaction() as db:
            return world.run_persistent_tick(db, None, hours)
    except store.EconomyError as exc:
        log.warning("Economy tick skipped: %s", exc)
    except Exception:  # noqa: BLE001 - the loop must survive; the transaction was rolled back
        log.exception("Economy tick failed and was rolled back")
    return None


async def run_loop(seconds: float) -> None:
    while True:
        await asyncio.sleep(seconds)
        await asyncio.to_thread(tick_once)


def bootstrap_if_requested() -> bool:
    """VE_ECONOMY_AUTO_BOOTSTRAP=1: create the sandbox world once (regions, suppliers, households,
    government) so players can found firms without an admin call. Never touches existing worlds."""
    if os.environ.get("VE_ECONOMY_ENABLED") != "1" or os.environ.get("VE_ECONOMY_AUTO_BOOTSTRAP") != "1":
        return False
    try:
        with transaction() as db:
            world.bootstrap_demo(db)
        log.info("Economy sandbox world created")
        return True
    except store.TickConflict:
        return False   # already exists
    except Exception:  # noqa: BLE001
        log.exception("Economy bootstrap failed and was rolled back")
        return False
