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

## Deliberate limitations

- The economy is an isolated sandbox: its cash accounts are NOT user `balances`; no bridge to
  production accounts, portfolios or `companies.py` settlements exists yet.
- Money paid to suppliers/energy accrues in `market:*` accounts and is not yet recirculated
  (no supplier owners, banks, taxes or dividends), so long runs drain household/firm cash.
- Shadow firms stay idle (no cash, no households in region `LEGACY`).
- Single region/household group in the demo world; no FX, banks, government, events or agents.
- Android is not yet connected to these endpoints.
