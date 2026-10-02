from datetime import datetime, timezone
from sqlalchemy import String, ForeignKey, DateTime, BigInteger
from sqlalchemy.orm import Mapped, mapped_column
from app.database.database import Base
from app.models.workspace import WorkspaceOwned

class TransactionAttachment(WorkspaceOwned, Base):
    __tablename__ = "transaction_attachments"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    transaction_id: Mapped[int] = mapped_column(ForeignKey("transactions.id", ondelete="CASCADE"), index=True)
    original_name: Mapped[str] = mapped_column(String(255))
    content_type: Mapped[str] = mapped_column(String(120))
    size_bytes: Mapped[int] = mapped_column(BigInteger)
    storage_name: Mapped[str] = mapped_column(String(255), unique=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
