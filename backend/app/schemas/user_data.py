from pydantic import BaseModel, Field


class UserDataDeleteOptions(BaseModel):
    transactions: bool = False
    categories: bool = False
    accounts: bool = False
    cards: bool = False
    budgets: bool = False
    goals: bool = False
    open_finance: bool = False
    workspace_ids: list[str] = Field(default_factory=list)
    account_ids: list[int] = Field(default_factory=list)
    delete_account: bool = False


class UserDataDeleteConfirm(BaseModel):
    code: str
    deletion_token: str | None = None
    options: UserDataDeleteOptions = Field(default_factory=UserDataDeleteOptions)
