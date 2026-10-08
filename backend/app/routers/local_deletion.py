"""Email confirmation for local-only deletion. These endpoints never delete data.

The Android client freezes the exact selection, submits its SHA-256 digest, then
executes that same plan only after this service consumes the four-digit OTP.
The public route is intentional: standalone/guest mode has no login. A random
256-bit installation credential, per-device email pinning and persistent limits
are used instead of pretending that a guest has a logged-in user account.
"""
from datetime import datetime, timedelta, timezone
from email.message import EmailMessage
import hashlib
import hmac
import secrets
import smtplib
import uuid
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, ConfigDict, EmailStr, Field
from sqlalchemy import delete, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.core.config import settings
from app.database.database import get_db
from app.models.local_deletion import LocalDeletionChallenge, LocalDeletionDevice, LocalDeletionRate

router = APIRouter(prefix='/local-deletion', tags=['Local data deletion'])
COUNT_KEYS = {'transactions', 'accounts', 'cards', 'categories', 'budgets',
              'goals', 'contributions', 'scenarios', 'plans', 'debts', 'workspaces'}
LABELS = {'transactions': 'Transacoes', 'accounts': 'Contas bancarias', 'cards': 'Cartoes',
          'categories': 'Categorias', 'budgets': 'Limites', 'goals': 'Metas',
          'contributions': 'Aportes', 'scenarios': 'Cenarios', 'plans': 'Planos',
          'debts': 'Dividas', 'workspaces': 'Workspaces'}


class CodeRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    device_secret: str = Field(pattern=r'^[a-f0-9]{64}$')
    email: EmailStr
    plan_hash: str = Field(pattern=r'^[a-f0-9]{64}$')
    operation: Literal['selected', 'workspaces', 'all_financial']
    counts: dict[str, int] = Field(default_factory=dict, max_length=11)


class ConfirmRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    device_secret: str = Field(pattern=r'^[a-f0-9]{64}$')
    challenge_id: str = Field(pattern=r'^[a-f0-9-]{36}$')
    plan_hash: str = Field(pattern=r'^[a-f0-9]{64}$')
    code: str = Field(pattern=r'^[0-9]{4}$')


def utcnow():
    return datetime.now(timezone.utc)


def utc(value):
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value


def digest(*parts: str) -> str:
    # Use a keyed HMAC. A leaked table cannot be brute-forced over 10,000 codes.
    return hmac.new(settings.jwt_secret.encode(), '\x00'.join(parts).encode(), hashlib.sha256).hexdigest()


def rate(db: Session, key: str, limit: int, now: datetime):
    bucket = int(now.timestamp()) // 3600
    full_key = digest('rate', key) + ':' + str(bucket)
    if db.get(LocalDeletionRate, full_key) is None:
        try:
            with db.begin_nested():
                db.add(LocalDeletionRate(key=full_key, count=0, bucket=bucket))
                db.flush()
        except IntegrityError:
            pass  # Another worker inserted the same bucket.
    result = db.execute(update(LocalDeletionRate).where(
        LocalDeletionRate.key == full_key, LocalDeletionRate.count < limit
    ).values(count=LocalDeletionRate.count + 1))
    if result.rowcount != 1:
        db.commit()
        raise HTTPException(429, 'Limite de solicitacoes atingido. Aguarde uma hora e tente novamente.')


def send_code(recipient: str, code: str, reference: str, counts: dict[str, int]):
    if not (settings.smtp_host and settings.smtp_username and settings.smtp_password):
        raise RuntimeError('SMTP_NOT_CONFIGURED')
    message = EmailMessage()
    message['Subject'] = 'Confirmar exclusao de dados locais - FinanceApp'
    message['From'] = settings.smtp_from or settings.smtp_username
    message['To'] = recipient
    details = '\n'.join(f'{LABELS[k]}: {counts[k]}' for k in sorted(counts) if counts[k])
    message.set_content(
        f'Foi solicitada uma exclusao no FinanceApp deste aparelho.\n\n'
        f'Codigo: {code}\nValidade: 5 minutos. Uso unico.\n'
        f'Referencia da previa: {reference}\n\n{details}\n\n'
        'Confira a selecao no aplicativo. O codigo autoriza somente essa selecao.\n'
        'O servico de e-mail nao apaga dados nem recebe suas transacoes.\n'
        'Backups ja salvos no Google Drive nao serao apagados.\n'
        'Nao solicitou? Nao compartilhe este codigo.\n'
    )
    if settings.smtp_port == 465:
        with smtplib.SMTP_SSL(settings.smtp_host, settings.smtp_port, timeout=20) as smtp:
            smtp.login(settings.smtp_username, settings.smtp_password)
            smtp.send_message(message)
    else:
        with smtplib.SMTP(settings.smtp_host, settings.smtp_port, timeout=20) as smtp:
            smtp.ehlo()
            if settings.smtp_use_tls:
                smtp.starttls()
                smtp.ehlo()
            smtp.login(settings.smtp_username, settings.smtp_password)
            smtp.send_message(message)


@router.post('/code')
def request_code(data: CodeRequest, request: Request, db: Session = Depends(get_db)):
    if any(k not in COUNT_KEYS or v < 0 or v > 10_000_000 for k, v in data.counts.items()):
        raise HTTPException(400, 'Previa de exclusao invalida.')
    now = utcnow()
    dh = digest('device', data.device_secret)
    eh = digest('email', str(data.email).strip().lower())
    # Do not trust X-Forwarded-For supplied by the client.
    ip = request.client.host if request.client else 'unknown'
    for key, limit in sorted([(f'send-device:{dh}', 5), (f'send-email:{eh}', 3), (f'send-ip:{ip}', 20)]):
        rate(db, key, limit, now)
    device = db.get(LocalDeletionDevice, dh, with_for_update=True)
    if device is None:
        device = LocalDeletionDevice(device_hash=dh)
        db.add(device)
        db.flush()
    if device.verified_email_hash and not secrets.compare_digest(device.verified_email_hash, eh):
        db.commit()
        raise HTTPException(403, 'Use o e-mail de confirmacao ja verificado neste aparelho.')
    latest = db.scalar(select(LocalDeletionChallenge).where(
        LocalDeletionChallenge.device_hash == dh).order_by(LocalDeletionChallenge.created_at.desc()).limit(1))
    if latest and (now - utc(latest.created_at)).total_seconds() < 60:
        db.commit()
        raise HTTPException(429, 'Aguarde 60 segundos antes de reenviar o codigo.')
    # Invalidate all previous codes for this installation, regardless of selection.
    db.execute(update(LocalDeletionChallenge).execution_options(synchronize_session=False).where(
        LocalDeletionChallenge.device_hash == dh, LocalDeletionChallenge.used_at.is_(None)
    ).values(used_at=now))
    cid = str(uuid.uuid4())
    code = f'{secrets.randbelow(10000):04d}'
    row = LocalDeletionChallenge(id=cid, device_hash=dh, email_hash=eh,
        plan_hash=data.plan_hash, code_hash=digest('otp', cid, dh, data.plan_hash, code),
        created_at=now, expires_at=now + timedelta(minutes=5), attempts=0, sent=False)
    db.add(row)
    db.execute(delete(LocalDeletionChallenge).execution_options(synchronize_session=False).where(LocalDeletionChallenge.created_at < now - timedelta(days=2)))
    db.execute(delete(LocalDeletionRate).where(LocalDeletionRate.bucket < int(now.timestamp()) // 3600 - 48))
    db.commit()
    try:
        send_code(str(data.email), code, data.plan_hash[:8], data.counts)
    except Exception:
        # No code accepted when sending failed; never return raw SMTP exceptions.
        db.execute(update(LocalDeletionChallenge).execution_options(synchronize_session=False).where(LocalDeletionChallenge.id == cid).values(used_at=utcnow()))
        db.commit()
        configured = bool(settings.smtp_host and settings.smtp_username and settings.smtp_password)
        raise HTTPException(503, 'Nao foi possivel enviar o e-mail. Verifique o SMTP no backend.' if configured
                            else 'Envio de e-mail nao configurado no backend (SMTP). Nenhum dado foi apagado.')
    db.execute(update(LocalDeletionChallenge).execution_options(synchronize_session=False).where(LocalDeletionChallenge.id == cid).values(sent=True))
    db.commit()
    return {'challenge_id': cid, 'plan_hash': data.plan_hash, 'expires_in_seconds': 300,
            'retry_after_seconds': 60, 'email': str(data.email)}


@router.post('/confirm')
def confirm_code(data: ConfirmRequest, db: Session = Depends(get_db)):
    now = utcnow()
    dh = digest('device', data.device_secret)
    rate(db, 'verify-device:' + dh, 30, now)
    row = db.get(LocalDeletionChallenge, data.challenge_id, with_for_update=True)
    if not row or not secrets.compare_digest(row.device_hash, dh) or not secrets.compare_digest(row.plan_hash, data.plan_hash):
        db.commit()
        raise HTTPException(400, 'Confirmacao invalida. Volte a previa e solicite outro codigo.')
    if not row.sent or row.used_at is not None or now >= utc(row.expires_at):
        db.commit()
        raise HTTPException(400, 'Codigo expirado, cancelado ou ja utilizado. Solicite outro codigo.')
    if row.attempts >= 5:
        db.commit()
        raise HTTPException(429, 'Limite de cinco tentativas atingido. Solicite outro codigo.')
    expected = digest('otp', row.id, dh, row.plan_hash, data.code)
    if not secrets.compare_digest(row.code_hash, expected):
        db.execute(update(LocalDeletionChallenge).execution_options(synchronize_session=False).where(
            LocalDeletionChallenge.id == row.id, LocalDeletionChallenge.used_at.is_(None)
        ).values(attempts=LocalDeletionChallenge.attempts + 1))
        db.commit()
        raise HTTPException(400, 'Codigo incorreto. Nenhum dado foi apagado.')
    # Conditional UPDATE also prevents concurrent replay on SQLite (FOR UPDATE is ignored there).
    consumed = db.execute(update(LocalDeletionChallenge).execution_options(synchronize_session=False).where(
        LocalDeletionChallenge.id == row.id, LocalDeletionChallenge.used_at.is_(None),
        LocalDeletionChallenge.attempts < 5, LocalDeletionChallenge.expires_at > now,
        LocalDeletionChallenge.sent.is_(True)
    ).values(used_at=now))
    if consumed.rowcount != 1:
        db.commit()
        raise HTTPException(409, 'Este codigo ja foi utilizado. Solicite outro codigo.')
    device = db.get(LocalDeletionDevice, dh, with_for_update=True)
    if device is None or (device.verified_email_hash and device.verified_email_hash != row.email_hash):
        db.commit()
        raise HTTPException(403, 'E-mail de confirmacao alterado. Solicite outro codigo.')
    device.verified_email_hash = row.email_hash
    db.commit()
    return {'authorized': True, 'challenge_id': data.challenge_id, 'plan_hash': data.plan_hash}
