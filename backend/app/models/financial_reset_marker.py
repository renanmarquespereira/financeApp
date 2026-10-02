from app.models.workspace import WorkspaceOwned
from datetime import datetime, timezone

from sqlalchemy import DateTime, ForeignKey
from sqlalchemy.orm import Mapped, mapped_column

from app.database.database import Base


class FinancialResetMarker(WorkspaceOwned, Base):
    """
    Marca o instante em que o usuário escolheu recomeçar a vida financeira.

    O login continua existindo, mas dados bancários anteriores a este
    instante não podem ser reimportados por Open Finance ou backup antigo.
    """

    __tablename__ = "financial_reset_markers"

    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        primary_key=True,
    )

    reset_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        nullable=False,
    )
    workspace_id: Mapped[str] = mapped_column(ForeignKey("workspaces.id"), primary_key=True)
