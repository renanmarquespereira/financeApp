from datetime import datetime, timezone
from sqlalchemy import String, ForeignKey, DateTime
from sqlalchemy.orm import Mapped, mapped_column
from app.database.database import Base


class SecurityChallenge(Base):
    __tablename__ = 'security_challenges'
    key: Mapped[str] = mapped_column(String(160), primary_key=True)
    code_hash: Mapped[str] = mapped_column(String(64))
    attempts: Mapped[int] = mapped_column(default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class AuthSessionVersion(Base):
    __tablename__ = 'auth_session_versions'
    user_id: Mapped[int] = mapped_column(ForeignKey('users.id', ondelete='CASCADE'), primary_key=True)
    version: Mapped[int] = mapped_column(default=0)


class DeletedWorkspace(Base):
    # Minimal tombstone prevents delayed create retries resurrecting a deleted ID.
    __tablename__ = 'deleted_workspaces'
    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey('users.id', ondelete='CASCADE'), index=True)
    deleted_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
