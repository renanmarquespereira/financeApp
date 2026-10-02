from app.models.workspace import WorkspaceOwned
from datetime import datetime, timezone
from sqlalchemy import String, ForeignKey, DateTime, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column
from app.database.database import Base


class IgnoredAccount(WorkspaceOwned, Base):
    __tablename__ = "ignored_accounts"
    __table_args__ = (
        UniqueConstraint(
            "user_id",
            "workspace_id",
            "external_account_id",
            name="uq_ignored_user_external_account",
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        index=True,
    )
    external_account_id: Mapped[str] = mapped_column(String(255), index=True)
    ignored_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
    )
