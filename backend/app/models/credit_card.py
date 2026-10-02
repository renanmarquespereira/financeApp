from app.models.workspace import WorkspaceOwned
from sqlalchemy import Boolean, ForeignKey, String, Numeric, Integer
from sqlalchemy.orm import Mapped, mapped_column

from app.database.database import Base


class CreditCard(WorkspaceOwned, Base):
    __tablename__ = "credit_cards"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        index=True,
    )
    bank_name: Mapped[str] = mapped_column(String(255))
    brand: Mapped[str] = mapped_column(String(60))
    last_four: Mapped[str] = mapped_column(String(4))
    nickname: Mapped[str | None] = mapped_column(String(120), nullable=True)
    credit_limit: Mapped[float | None] = mapped_column(Numeric(14, 2), nullable=True)
    closing_day: Mapped[int | None] = mapped_column(Integer, nullable=True)
    due_day: Mapped[int | None] = mapped_column(Integer, nullable=True)
    active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
