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

## Deliberate limitations

This is **not yet wired into the FastAPI application**, the current
`company_development` endpoints, SQLite, Android offline mode, or the player
portfolio. The ledger is in-memory. Initial money issuance is explicit but
there is not yet a monetary-policy model. Household groups are aggregate
buckets; payroll allocation is explicit or weighted by each group's employed
population. Per-unit costs retain extra precision while cash settlements round
to cents.

Do not replace `companies.py` hourly settlements with this prototype until a
transactional SQLite adapter, backward-compatible state migration, API
contract, and online/offline parity tests are implemented.
