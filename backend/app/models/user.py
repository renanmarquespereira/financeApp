from datetime import date, datetime

from sqlalchemy import Boolean, Date, DateTime, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database.database import Base


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(primary_key=True)
    email: Mapped[str] = mapped_column(String(255), unique=True, index=True)
    name: Mapped[str | None] = mapped_column(String(255))
    cpf: Mapped[str | None] = mapped_column(
        String(11),
        nullable=True,
        index=True,
    )
    password_hash: Mapped[str | None] = mapped_column(String(255))
    google_id: Mapped[str | None] = mapped_column(String(255), unique=True)
    birth_date: Mapped[date | None] = mapped_column(Date, nullable=True)
    sex: Mapped[str | None] = mapped_column(String(32), nullable=True)
    profile_photo: Mapped[str | None] = mapped_column(Text, nullable=True)
    google_profile_photo: Mapped[str | None] = mapped_column(Text, nullable=True)
    profile_photo_opt_out: Mapped[bool] = mapped_column(default=False, nullable=False)
    terms_version: Mapped[str | None] = mapped_column(String(64), nullable=True)
    privacy_version: Mapped[str | None] = mapped_column(String(64), nullable=True)
    legal_accepted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    @property
    def has_google_profile_photo(self) -> bool:
        return bool(self.google_profile_photo)

    accounts = relationship("BankAccount", back_populates="user", cascade="all, delete-orphan")
    transactions = relationship("Transaction", back_populates="user", cascade="all, delete-orphan")
    categories = relationship("Category", back_populates="user", cascade="all, delete-orphan")
