# VirtualExchange economy core (domain prototype v1)

This package is a pure-Python domain layer. It is intentionally isolated from
FastAPI routes and SQLite persistence so its money, inventory and production
invariants can be tested before it is connected to user accounts or the
existing company-development subsystem.

## Implemented

- Explicit cash-account registration and explicit initial monetary issuance.
- Debit/credit journal entries; transfers between registered cash accounts
  preserve aggregate cash and cannot overdraw the sender.
- Material procurement from a funded firm to a supplier cash account.
- Production constrained by physical inputs, employee-hours, rated capacity
  and available operating cash.
- Actual payroll and energy payments to recipient cash accounts.
- Finished-goods inventory with weighted average unit cost.
- Sales only against existing inventory and a funded household buyer.
- Aggregated household demand budgets by product category and savings rate.
- Deterministic ordering and tick identifiers; no wall-clock or random calls.
- Household shopping and payroll remain region-local until the FX/settlement phase exists.

## Persistence and API (v2)

- `store.py`: SQLite adapter (`econ_*` tables, versioned `schema_migrations`, automatic
  `<db>.pre-economy-v1.bak` copy before the first migration of a populated DB).
- `api.py`: `GET /economy/{status,firms,firms/{id},inventory,households,transactions,ticks}`
  (need a valid `X-API-Key`); `POST /economy/admin/{bootstrap-demo,link-companies,tick}`.
- Admin/tick endpoints are **off by default**: set `VE_ECONOMY_ENABLED=1` and
  `VE_ECONOMY_ADMIN_KEY=<secret>` and send `X-Admin-Key`.
- A tick runs in one `BEGIN IMMEDIATE` transaction: tick number must be `last+1`
  (duplicates/gaps -> 409), money supply must equal the persisted expected supply before and
  after, any exception rolls back the whole tick. Procurement of inputs (policy per firm),
  payroll, energy, sales and household purchases are all real ledger transfers.
- Existing companies are mirrored as zero-cash `shadow` firms (`LEGACY-<SYMBOL>`); the link only
  reads `company_development` and moves no money.

## Circulation, accounting and player operations (v3)

- `circulation.py`: suppliers (`econ_entities`: materials/energy/capital) are businesses with owner,
  staff, revenue, wages, corporate tax and dividends. Households pay income tax; a regional
  government (`econ_regions`) returns 90% of its cash to households each tick as public transfers.
  Every flow is a ledger transfer, so supply is constant except explicit issuance/bridge.
- `accounting.py`: balance sheet, income statement and cash-flow statement derived only from the
  journal and account balances. Equity is built from P&L + contributions, so `balanced` is a real
  cross-check. Receivables/trade payables are always 0 (all trade is cash); taxes payable is real.
- `player.py` + `/economy/player/*`: found firm, deposit/withdraw (wallet bridge), buy materials,
  auto-procurement, hire/fire, wage, price markup, capex (depreciated 1/500 per tick), dividends,
  activation of a legacy company (cash is moved out of `company_development`, not copied).
- Wallet bridge is OFF unless `VE_ECONOMY_BRIDGE_ENABLED=1`. Bridged money is explicit issuance /
  redemption, logged in `econ_bridge`; economy supply == sandbox issuance + net bridge.
- Pre-v2 `market:*` accounts are adopted as `LEGACY-ENERGY` / `LEGACY-MATERIALS` suppliers.
- Migration v2 is additive (new tables, nullable/default columns, indexes) with an automatic backup.

## Automatic ticks

Set `VE_ECONOMY_ENABLED=1` and `VE_ECONOMY_AUTOTICK_SECONDS=<n>` (n >= 5) and the server advances
the economy by one simulated hour every n seconds (`scheduler.py`). Each tick is atomic; a failed
tick is rolled back, logged and retried. Without it, ticks only run via `POST /economy/admin/tick`.

## Known limitations

- Demo money concentrates in owners (dividends) and workers' cash trends down: no consumer credit,
  wage growth, investment demand or owner spending beyond the savings rate yet.
- Wages unpaid for lack of cash are not accrued; no loss carry-forward; no bankruptcy logic.
- One region in practice (SIM); no FX, banks, agents, events. Legacy `company_development` still
  creates its own cash in the old system; cash activated from it is imported as-is.
- Hiring checks region workforce but does not move `employed` between groups.
- Android is not connected.
