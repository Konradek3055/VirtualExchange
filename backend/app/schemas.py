from decimal import Decimal
from typing import Literal
from pydantic import BaseModel, Field, field_validator

class CreateAccount(BaseModel):
    username: str = Field(min_length=3, max_length=24)

class OrderIn(BaseModel):
    symbol: str
    side: Literal["buy", "sell"]
    order_type: Literal["limit", "market"]
    quantity: Decimal
    price: Decimal | None = None

    @field_validator("quantity")
    @classmethod
    def positive_quantity(cls, v):
        if not v.is_finite() or v <= 0:
            raise ValueError("quantity must be positive")
        return v

    @field_validator("price")
    @classmethod
    def positive_price(cls, v):
        if v is not None and (not v.is_finite() or v <= 0):
            raise ValueError("price must be positive")
        return v
