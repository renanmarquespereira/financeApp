from datetime import datetime, timedelta, timezone

from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jose import JWTError, jwt
from pwdlib import PasswordHash
from sqlalchemy.orm import Session

from app.core.config import settings
from app.database.database import get_db
from app.models.user import User
from app.models.security_challenge import AuthSessionVersion

def session_version(db, user_id):
    row = db.get(AuthSessionVersion, int(user_id))
    return row.version if row else 0


password_hash = PasswordHash.recommended()
bearer_scheme = HTTPBearer(auto_error=False)


def hash_password(password: str) -> str:
    return password_hash.hash(password)


def verify_password(password: str, hashed_password: str) -> bool:
    return password_hash.verify(password, hashed_password)


def _create_token(subject: int | str, token_type: str, expires_delta: timedelta, version: int = 0) -> str:
    expire = datetime.now(timezone.utc) + expires_delta
    payload = {"sub": str(subject), "type": token_type, "exp": expire, "ver": version}
    return jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)


def create_access_token(subject: int | str, version: int = 0) -> str:
    return _create_token(
        subject,
        "access",
        timedelta(minutes=settings.access_token_expire_minutes), version,
    )


def create_refresh_token(subject: int | str, version: int = 0) -> str:
    return _create_token(
        subject,
        "refresh",
        timedelta(days=settings.refresh_token_expire_days), version,
    )


def rotate_refresh_token(refresh_token: str, db=None) -> str:
    """Validate a refresh JWT and return its subject (user id)."""
    try:
        payload = jwt.decode(
            refresh_token,
            settings.jwt_secret,
            algorithms=[settings.jwt_algorithm],
        )
    except JWTError as exc:
        raise HTTPException(status_code=401, detail="Refresh token inválido ou expirado") from exc

    if payload.get("type") != "refresh" or not payload.get("sub"):
        raise HTTPException(status_code=401, detail="Refresh token inválido")

    if db is not None and payload.get("ver", 0) != session_version(db, payload["sub"]):
        raise HTTPException(401, "Sessão encerrada. Entre novamente.")
    return str(payload["sub"])


def get_current_user(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
    db: Session = Depends(get_db),
) -> User:
    if not credentials:
        raise HTTPException(status_code=401, detail="Token não informado")

    try:
        payload = jwt.decode(
            credentials.credentials,
            settings.jwt_secret,
            algorithms=[settings.jwt_algorithm],
        )
    except JWTError as exc:
        raise HTTPException(status_code=401, detail="Token inválido ou expirado") from exc

    if payload.get("type") != "access" or not payload.get("sub"):
        raise HTTPException(status_code=401, detail="Token de acesso inválido")

    try:
        user_id = int(payload["sub"])
    except (TypeError, ValueError) as exc:
        raise HTTPException(status_code=401, detail="Token inválido") from exc

    user = db.get(User, user_id)
    if not user:
        raise HTTPException(status_code=401, detail="Usuário não encontrado")

    if payload.get("ver", 0) != session_version(db, user.id):
        raise HTTPException(401, "Sessão encerrada. Entre novamente.")
    return user
