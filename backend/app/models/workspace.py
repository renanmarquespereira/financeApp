from datetime import datetime, timezone
from sqlalchemy import Boolean, DateTime, ForeignKey, String, Index, text
from sqlalchemy.orm import Mapped, mapped_column
from app.database.database import Base


def default_workspace_id(user_id: int) -> str:
    # Stable on server and device, including the first offline launch after upgrade.
    return f"default-{user_id}"


class Workspace(Base):
    __tablename__ = "workspaces"
    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    name: Mapped[str] = mapped_column(String(160), default="Pessoal")
    kind: Mapped[str] = mapped_column(String(32), default="personal")
    is_default: Mapped[bool] = mapped_column(Boolean, default=False)
    archived_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    __table_args__ = (
        Index("uq_workspace_default_user", "user_id", unique=True,
              postgresql_where=text("is_default = true"), sqlite_where=text("is_default = 1")),
    )


class WorkspaceOwned:
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), nullable=False)
    workspace_id: Mapped[str] = mapped_column(ForeignKey("workspaces.id"), nullable=False, index=True)
