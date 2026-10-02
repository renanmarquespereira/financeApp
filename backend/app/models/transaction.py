from app.models.workspace import WorkspaceOwned
from datetime import datetime
from decimal import Decimal
from sqlalchemy import String, ForeignKey, Numeric, DateTime, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.database.database import Base

class Transaction(WorkspaceOwned, Base):
    __tablename__="transactions"
    __table_args__=(UniqueConstraint("account_id", "external_transaction_id", name="uq_transaction_account_external"),)
    id: Mapped[int]=mapped_column(primary_key=True)
    user_id: Mapped[int]=mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    account_id: Mapped[int|None]=mapped_column(ForeignKey("bank_accounts.id", ondelete="CASCADE"), index=True, nullable=True)
    category_id: Mapped[int|None]=mapped_column(ForeignKey("categories.id", ondelete="SET NULL"))
    card_id: Mapped[int|None]=mapped_column(ForeignKey("credit_cards.id", ondelete="SET NULL"), nullable=True, index=True)
    installment_group: Mapped[str|None]=mapped_column(String(120), nullable=True, index=True)
    installment_number: Mapped[int|None]=mapped_column(nullable=True)
    installment_total: Mapped[int|None]=mapped_column(nullable=True)
    purchase_date: Mapped[datetime|None]=mapped_column(DateTime(timezone=True), nullable=True)
    external_transaction_id: Mapped[str|None]=mapped_column(String(255), index=True)
    source: Mapped[str]=mapped_column(String(50), default="manual", index=True)
    date: Mapped[datetime]=mapped_column(DateTime(timezone=True))
    description: Mapped[str]=mapped_column(Text)
    amount: Mapped[Decimal]=mapped_column(Numeric(14,2))
    transaction_type: Mapped[str]=mapped_column(String(50), default="unknown")
    status: Mapped[str]=mapped_column(String(50), default="posted")
    synced_at: Mapped[datetime|None]=mapped_column(DateTime(timezone=True))
    user=relationship("User", back_populates="transactions")
    account=relationship("BankAccount", back_populates="transactions")
    category=relationship("Category", back_populates="transactions")
