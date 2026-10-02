from decimal import Decimal

from pydantic import BaseModel, Field


class BudgetUpsert(BaseModel):
    amount: Decimal = Field(gt=0)


class BudgetResponse(BaseModel):
    workspace_id: str
    id: int
    category_id: int
    amount: Decimal

    class Config:
        from_attributes = True
