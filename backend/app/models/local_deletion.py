"""Short-lived, server-verified authorizations for the standalone Android app.

No local financial records or raw device credentials are uploaded/stored.
"""
from datetime import datetime
from sqlalchemy import DateTime, String
from sqlalchemy.orm import Mapped, mapped_column
from app.database.database import Base


class LocalDeletionDevice(Base):
    __tablename__ = 'local_deletion_devices'
    device_hash: Mapped[str] = mapped_column(String(64), primary_key=True)
    verified_email_hash: Mapped[str | None] = mapped_column(String(64), nullable=True)


class LocalDeletionChallenge(Base):
    __tablename__ = 'local_deletion_challenges'
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    device_hash: Mapped[str] = mapped_column(String(64), index=True)
    email_hash: Mapped[str] = mapped_column(String(64), index=True)
    plan_hash: Mapped[str] = mapped_column(String(64))
    code_hash: Mapped[str] = mapped_column(String(64))
    attempts: Mapped[int] = mapped_column(default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    used_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    sent: Mapped[bool] = mapped_column(default=False)


class LocalDeletionRate(Base):
    __tablename__ = 'local_deletion_rates'
    key: Mapped[str] = mapped_column(String(100), primary_key=True)
    count: Mapped[int] = mapped_column(default=0)
    bucket: Mapped[int] = mapped_column(index=True)
