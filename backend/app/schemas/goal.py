from datetime import date, datetime
from decimal import Decimal

from pydantic import BaseModel, Field, field_validator


class GoalUpsert(BaseModel):
    name: str
    target_amount: Decimal = Field(gt=0)
    current_amount: Decimal = Field(default=0, ge=0)
    target_date: date | None = None

    @field_validator("name")
    @classmethod
    def validate_name(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("Informe o nome da meta")
        return value


class GoalResponse(BaseModel):
    workspace_id: str
    id: int
    name: str
    target_amount: Decimal
    current_amount: Decimal
    target_date: date | None

    class Config:
        from_attributes = True



class GoalContributionCreate(BaseModel):
    amount: Decimal = Field(gt=0)
    client_key: str


class GoalContributionResponse(BaseModel):
    workspace_id: str
    id: int
    goal_id: int
    amount: Decimal
    client_key: str
    created_at: datetime

    class Config:
        from_attributes = True
