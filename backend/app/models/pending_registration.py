from datetime import date, datetime, timezone

from sqlalchemy import Date, DateTime, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from app.database.database import Base


class PendingRegistration(Base):
    __tablename__ = "pending_registrations"

    id: Mapped[int] = mapped_column(
        primary_key=True
    )
    email: Mapped[str] = mapped_column(
        String(255),
        unique=True,
        index=True,
    )
    name: Mapped[str] = mapped_column(
        String(255)
    )
    cpf: Mapped[str] = mapped_column(
        String(11),
        index=True,
    )
    password_hash: Mapped[str] = mapped_column(
        String(255)
    )
    birth_date: Mapped[date] = mapped_column(
        Date
    )
    sex: Mapped[str] = mapped_column(
        String(32)
    )
    code_hash: Mapped[str] = mapped_column(
        String(64)
    )
    attempts: Mapped[int] = mapped_column(
        Integer,
        default=0,
    )
    expires_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True)
    )
    terms_version: Mapped[str | None] = mapped_column(String(64), nullable=True)
    privacy_version: Mapped[str | None] = mapped_column(String(64), nullable=True)
    legal_accepted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda:
            datetime.now(timezone.utc),
    )
