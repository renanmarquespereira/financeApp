from app.models.workspace import WorkspaceOwned
from datetime import datetime, timezone
from decimal import Decimal

from sqlalchemy import DateTime, ForeignKey, Numeric, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.database.database import Base


class GoalContribution(WorkspaceOwned, Base):
    __tablename__ = "goal_contributions"
    __table_args__ = (
        UniqueConstraint(
            "user_id",
            "workspace_id",
            "client_key",
            name="uq_goal_contribution_user_client_key",
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        index=True,
    )
    goal_id: Mapped[int] = mapped_column(
        ForeignKey("financial_goals.id", ondelete="CASCADE"),
        index=True,
    )
    amount: Mapped[Decimal] = mapped_column(Numeric(14, 2))
    client_key: Mapped[str] = mapped_column(String(120), index=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
    )
