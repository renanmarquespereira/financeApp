import hashlib
import secrets
from datetime import datetime, timedelta, timezone
from fastapi import HTTPException
from app.core.config import settings
from app.models.security_challenge import SecurityChallenge


def utc(value):
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value


def digest(key, code):
    return hashlib.sha256(f'{settings.jwt_secret}:{key}:{code}'.encode()).hexdigest()


def issue(db, key, digits=6):
    # Caller serializes requests with the owning User row lock.
    now = datetime.now(timezone.utc)
    row = db.get(SecurityChallenge, key)
    if row and (now - utc(row.created_at)).total_seconds() < 60:
        raise HTTPException(429, 'Aguarde 1 minuto antes de solicitar outro código.')
    if row is None:
        row = SecurityChallenge(key=key)
        db.add(row)
    code = f'{secrets.randbelow(10 ** digits):0{digits}d}'
    row.code_hash = digest(key, code)
    row.attempts = 0
    row.created_at = now
    row.expires_at = now + timedelta(minutes=10)
    db.flush()
    return code


def verify(db, key, code):
    row = db.get(SecurityChallenge, key, with_for_update=True)
    if not row or datetime.now(timezone.utc) >= utc(row.expires_at):
        raise HTTPException(400, 'Código inválido ou expirado. Solicite outro código.')
    if row.attempts >= 5:
        raise HTTPException(429, 'Limite de tentativas atingido. Solicite outro código.')
    if not secrets.compare_digest(row.code_hash, digest(key, code)):
        row.attempts += 1
        db.commit()
        raise HTTPException(400, 'Código incorreto.')
    return row
