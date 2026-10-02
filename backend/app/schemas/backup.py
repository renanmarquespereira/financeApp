from datetime import datetime
from decimal import Decimal
from pydantic import BaseModel

class BackupAccount(BaseModel):
    local_id: str | None = None
    institution_name: str
    institution_id: str | None = None
    account_name: str | None = None
    masked_account: str | None = None
    external_account_id: str | None = None

class BackupTransaction(BaseModel):
    local_id: str | None = None
    external_transaction_id: str | None = None
    account_external_id: str | None = None
    date: datetime
    description: str
    amount: Decimal
    transaction_type: str = "unknown"
    status: str = "posted"
    category_id: int | None = None

class BackupPayload(BaseModel):
    workspace_id: str | None = None
    user_id: int | None = None
    version: int = 1
    generated_at: datetime
    accounts: list[BackupAccount] = []
    transactions: list[BackupTransaction] = []
