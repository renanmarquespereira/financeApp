from app.models.workspace import WorkspaceOwned
from decimal import Decimal
from datetime import datetime
from sqlalchemy import String, ForeignKey, Numeric, DateTime, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.database.database import Base

class BankAccount(WorkspaceOwned, Base):
    __tablename__="bank_accounts"
    __table_args__ = (UniqueConstraint("user_id", "workspace_id", "external_account_id", name="uq_account_workspace_external"),)
    id: Mapped[int]=mapped_column(primary_key=True)
    user_id: Mapped[int]=mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    institution_name: Mapped[str]=mapped_column(String(255))
    institution_id: Mapped[str|None]=mapped_column(String(255))
    account_name: Mapped[str|None]=mapped_column(String(255))
    masked_account: Mapped[str|None]=mapped_column(String(100))
    external_account_id: Mapped[str|None]=mapped_column(String(255))
    current_balance: Mapped[Decimal|None]=mapped_column(
        Numeric(14, 2),
        nullable=True,
    )
    balance_updated_at: Mapped[datetime|None]=mapped_column(
        DateTime(timezone=True),
        nullable=True,
    )
    user=relationship("User", back_populates="accounts")
    transactions=relationship("Transaction", back_populates="account", cascade="all, delete-orphan")
