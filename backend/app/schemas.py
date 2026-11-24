
import re
from decimal import Decimal
from typing import Literal
from pydantic import BaseModel, Field, field_validator

class CreateAccount(BaseModel):
    username: str = Field(min_length=3, max_length=24, pattern=r"^[A-Za-z0-9_]+$")

class OrderIn(BaseModel):
    symbol: str
    side: Literal["buy","sell"]
    order_type: Literal["limit","market"]
    quantity: Decimal
    price: Decimal | None = None

    @field_validator("symbol")
    @classmethod
    def valid_symbol(cls, value):
        value = value.strip().upper()
        if not re.fullmatch(r"[A-Z][A-Z0-9]{1,9}", value):
            raise ValueError("Invalid ticker")
        return value

    @field_validator("quantity")
    @classmethod
    def valid_quantity(cls, value):
        if not value.is_finite() or value <= 0 or value > Decimal("1e24"):
            raise ValueError("Invalid quantity")
        return value

    @field_validator("price")
    @classmethod
    def valid_price(cls, value):
        if value is not None and (not value.is_finite() or value <= 0):
            raise ValueError("Invalid price")
        return value

class AssetCreate(BaseModel):
    symbol: str
    name: str = Field(min_length=2, max_length=40)
    description: str = Field(default="", max_length=500)
    total_supply: Decimal

    @field_validator("symbol")
    @classmethod
    def valid_symbol(cls, value):
        value = value.strip().upper()
        if value == "USD" or not re.fullmatch(r"[A-Z][A-Z0-9]{1,9}", value):
            raise ValueError("Invalid ticker")
        return value

    @field_validator("total_supply")
    @classmethod
    def valid_supply(cls, value):
        if not value.is_finite() or value <= 0 or value > Decimal("1e24"):
            raise ValueError("Invalid supply")
        return value
