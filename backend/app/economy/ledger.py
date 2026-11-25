"""Deterministic double-entry journal and conserved cash transfers.

Balances use a debit-positive convention.  Cash accounts are registered
explicitly; transferring between two cash accounts cannot create or destroy
money.  Initial issuance is explicit and leaves a matching non-cash
counter-entry in the journal.

This is an in-memory domain ledger, not yet the persistent accounting store
for the production API.  The SQLite adapter is a later integration step.
"""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation, ROUND_DOWN, ROUND_HALF_UP, localcontext
from typing import Dict, Optional, Set

D = Decimal
CENT = D("0.01")
QUANTITY_STEP = D("0.00000001")
UNIT_VALUE_STEP = D("0.00000001")


def _decimal(value: object, field: str) -> Decimal:
    try:
        result = D(str(value))
    except (InvalidOperation, ValueError, TypeError) as exc:
        raise ValueError(f"Invalid {field}") from exc
    if not result.is_finite():
        raise ValueError(f"{field} must be finite")
    return result


def money(value: object) -> Decimal:
    """Round a monetary value to cents using commercial half-up rounding."""
    amount = _decimal(value, "money")
    with localcontext() as ctx:
        ctx.prec = 60
        return amount.quantize(CENT, rounding=ROUND_HALF_UP)


def quantity(value: object) -> Decimal:
    """Round a physical/traded quantity down to the supported 8 decimals."""
    amount = _decimal(value, "quantity")
    with localcontext() as ctx:
        ctx.prec = 60
        return amount.quantize(QUANTITY_STEP, rounding=ROUND_DOWN)


def unit_value(value: object) -> Decimal:
    """Keep per-unit costs precise; only aggregate cash postings round to cents."""
    amount = _decimal(value, "unit value")
    with localcontext() as ctx:
        ctx.prec = 60
        return amount.quantize(UNIT_VALUE_STEP, rounding=ROUND_HALF_UP)


@dataclass(frozen=True)
class LedgerEntry:
    entry_id: int
    tick: int
    debit_account: str
    credit_account: str
    amount: Decimal
    description: str
    ref: Optional[str] = None


class Ledger:
    """In-memory journal with explicit money accounts and no silent minting."""

    def __init__(self) -> None:
        self.entries: list[LedgerEntry] = []
        self.balances: Dict[str, Decimal] = {}
        self.money_accounts: Set[str] = set()
        self.current_tick = 0
        # Persistent adapters hydrate a ledger without its history; ids continue after this offset.
        self.entry_offset = 0

    def register_account(self, account: str) -> None:
        if not isinstance(account, str) or not account.strip():
            raise ValueError("Account name cannot be empty")
        self.balances.setdefault(account, D("0"))

    def register_money_account(self, account: str) -> None:
        self.register_account(account)
        self.money_accounts.add(account)

    def set_tick(self, tick: int) -> None:
        if isinstance(tick, bool) or not isinstance(tick, int) or tick < self.current_tick:
            raise ValueError("Tick must be an integer and cannot move backwards")
        self.current_tick = tick

    def post(
        self,
        debit: str,
        credit: str,
        amount: object,
        description: str,
        ref: Optional[str] = None,
    ) -> LedgerEntry:
        value = money(amount)
        if value <= 0:
            raise ValueError("Journal amount must be greater than zero")
        if debit == credit:
            raise ValueError("Debit and credit accounts must be different")
        if not description:
            raise ValueError("Journal description cannot be empty")

        self.register_account(debit)
        self.register_account(credit)
        self.balances[debit] = money(self.balances[debit] + value)
        self.balances[credit] = money(self.balances[credit] - value)
        entry = LedgerEntry(
            entry_id=self.entry_offset + len(self.entries) + 1,
            tick=self.current_tick,
            debit_account=debit,
            credit_account=credit,
            amount=value,
            description=description,
            ref=ref,
        )
        self.entries.append(entry)
        return entry

    def issue_money(self, recipient_cash_account: str, amount: object, reason: str) -> LedgerEntry:
        """Explicitly create initial/base money; never used by ordinary sales."""
        value = money(amount)
        if value <= 0:
            raise ValueError("Issued amount must be greater than zero")
        self.register_money_account(recipient_cash_account)
        source = "system:monetary-issuance-counterpart"
        return self.post(
            debit=recipient_cash_account,
            credit=source,
            amount=value,
            description=f"Explicit monetary issuance: {reason}",
        )

    def transfer(
        self,
        sender_cash_account: str,
        recipient_cash_account: str,
        amount: object,
        description: str,
        ref: Optional[str] = None,
    ) -> LedgerEntry:
        """Move cash from sender to recipient without changing money supply."""
        value = money(amount)
        if value <= 0:
            raise ValueError("Transfer amount must be greater than zero")
        if sender_cash_account == recipient_cash_account:
            raise ValueError("Sender and recipient must be different")
        if sender_cash_account not in self.money_accounts:
            raise ValueError(f"Unregistered sender cash account: {sender_cash_account}")
        if recipient_cash_account not in self.money_accounts:
            raise ValueError(f"Unregistered recipient cash account: {recipient_cash_account}")
        if self.balance(sender_cash_account) < value:
            raise ValueError(f"Insufficient funds in {sender_cash_account}")
        # Debit recipient (cash asset increases), credit sender (cash asset falls).
        return self.post(
            debit=recipient_cash_account,
            credit=sender_cash_account,
            amount=value,
            description=description,
            ref=ref,
        )

    def balance(self, account: str) -> Decimal:
        return self.balances.get(account, D("0"))

    def money_supply(self) -> Decimal:
        """Sum explicitly registered cash balances; no deposits/reserves yet."""
        with localcontext() as ctx:
            ctx.prec = 60
            return money(sum((self.balances[a] for a in self.money_accounts), D("0")))

    def assert_balanced(self) -> None:
        """Every journal entry must leave total debit-normal balances at zero."""
        total = money(sum(self.balances.values(), D("0")))
        if total != D("0.00"):
            raise AssertionError(f"Journal is not balanced: net={total}")

    def assert_cash_accounts_non_negative(self) -> None:
        negatives = {a: self.balances[a] for a in self.money_accounts if self.balances[a] < 0}
        if negatives:
            raise AssertionError(f"Negative cash account balances: {negatives}")

    def assert_money_supply(self, expected: object) -> None:
        actual = self.money_supply()
        wanted = money(expected)
        if actual != wanted:
            raise AssertionError(f"Money supply changed: {actual} != {wanted}")
        self.assert_cash_accounts_non_negative()
        self.assert_balanced()
