from app.models.workspace import WorkspaceOwned
from datetime import datetime, timezone
from sqlalchemy import String, ForeignKey, DateTime, Text, Integer
from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.database.database import Base

class OpenFinanceConnection(WorkspaceOwned, Base):
    __tablename__ = "open_finance_connections"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    provider: Mapped[str] = mapped_column(String(100))
    institution_id: Mapped[str | None] = mapped_column(String(255))
    institution_name: Mapped[str | None] = mapped_column(String(255))
    external_connection_id: Mapped[str | None] = mapped_column(String(255), unique=True)
    consent_id: Mapped[str | None] = mapped_column(String(255), unique=True)
    status: Mapped[str] = mapped_column(String(50), default="pending", index=True)
    authorization_url: Mapped[str | None] = mapped_column(Text)
    access_token_encrypted: Mapped[str | None] = mapped_column(Text)
    refresh_token_encrypted: Mapped[str | None] = mapped_column(Text)
    expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    last_sync_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    next_sync_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), index=True)
    sync_interval_minutes: Mapped[int] = mapped_column(Integer, default=1)
    mock_sequence: Mapped[int] = mapped_column(Integer, default=0)
    last_error: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    user = relationship("User")

class OpenFinanceSyncLog(WorkspaceOwned, Base):
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), nullable=False, index=True)
    __tablename__ = "open_finance_sync_logs"
    id: Mapped[int] = mapped_column(primary_key=True)
    connection_id: Mapped[int] = mapped_column(ForeignKey("open_finance_connections.id", ondelete="CASCADE"), index=True)
    status: Mapped[str] = mapped_column(String(50), index=True)
    imported_transactions: Mapped[int] = mapped_column(Integer, default=0)
    message: Mapped[str | None] = mapped_column(Text)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
