from app.models.workspace import WorkspaceOwned
from decimal import Decimal

from sqlalchemy import ForeignKey, Numeric, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.database.database import Base


class CategoryBudget(WorkspaceOwned, Base):
    __tablename__ = "category_budgets"
    __table_args__ = (
        UniqueConstraint(
            "user_id",
            "workspace_id",
            "category_id",
            name="uq_category_budget_user_category",
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        index=True,
    )
    category_id: Mapped[int] = mapped_column(
        ForeignKey("categories.id", ondelete="CASCADE"),
        index=True,
    )
    amount: Mapped[Decimal] = mapped_column(Numeric(14, 2))
