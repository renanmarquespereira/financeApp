from datetime import datetime
from decimal import Decimal
from pydantic import BaseModel, ConfigDict


class AccountCreate(BaseModel):
    institution_name: str
    institution_id: str | None = None
    account_name: str | None = None
    masked_account: str | None = None
    external_account_id: str | None = None


class AccountResponse(AccountCreate):
    workspace_id: str
    id: int
    connection_status: str = "manual"
    current_balance: Decimal | None = None
    balance_updated_at: datetime | None = None
    model_config = ConfigDict(from_attributes=True)


class AccountDeleteConfirm(BaseModel):
    code: str


class AccountUpdate(BaseModel):
    institution_name: str
    account_name: str | None = None
    masked_account: str | None = None
