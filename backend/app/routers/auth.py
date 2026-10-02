import base64
import hashlib
import secrets
from urllib.request import Request as UrlRequest, urlopen
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import HTMLResponse
from sqlalchemy.orm import Session, object_session
from app.core.security import session_version

from app.core.config import settings
from app.core.security import (
    create_access_token,
    create_refresh_token,
    hash_password,
    rotate_refresh_token,
    verify_password,
)
from app.database.database import get_db
from app.models.user import User
from app.models.pending_registration import PendingRegistration
from app.schemas.auth import (
    EmailVerificationResend,
    GoogleLoginRequest,
    LoginRequest,
    RefreshRequest,
    TokenResponse,
)
from app.schemas.user import UserCreate, UserResponse
from app.services.email import send_registration_verification_link

router = APIRouter()


def _google_picture_data_url(url: str | None) -> str | None:
    if not url or not url.startswith("https://"):
        return None
    try:
        request = UrlRequest(url, headers={"User-Agent": "FinanceApp/1.0"})
        with urlopen(request, timeout=4) as response:
            content_type = response.headers.get_content_type() or "image/jpeg"
            raw = response.read(750_001)
        if not raw or len(raw) > 750_000 or not content_type.startswith("image/"):
            return None
        return f"data:{content_type};base64,{base64.b64encode(raw).decode('ascii')}"
    except Exception:
        return None



def _registration_token_hash(
    email: str,
    token: str,
) -> str:
    payload = (
        f"{settings.jwt_secret}:register:{email}:{token}"
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _registration_public_base_url(
    request: Request,
) -> str:
    configured = (
        settings.public_api_url
        or ""
    ).strip().rstrip("/")

    if configured:
        return configured

    return str(
        request.base_url
    ).rstrip("/")


def _send_pending_registration_link(
    pending: PendingRegistration,
    db: Session,
    request: Request,
) -> None:
    now = datetime.now(timezone.utc)
    token = secrets.token_urlsafe(32)

    pending.code_hash = (
        _registration_token_hash(
            pending.email,
            token,
        )
    )
    pending.attempts = 0
    pending.expires_at = (
        now + timedelta(hours=24)
    )
    pending.created_at = now

    db.commit()

    base_url = (
        _registration_public_base_url(
            request
        )
    )

    from urllib.parse import quote

    verification_url = (
        f"{base_url}/auth/verify-email-link"
        f"?email={quote(pending.email)}"
        f"&token={quote(token)}"
    )

    try:
        send_registration_verification_link(
            recipient=pending.email,
            verification_url=
                verification_url,
        )
    except Exception as exc:
        raise HTTPException(
            503,
            "Não foi possível enviar o e-mail "
            f"de confirmação: {exc}",
        ) from exc


def _complete_pending_registration(
    pending: PendingRegistration,
    db: Session,
) -> User:
    existing = (
        db.query(User)
        .filter(
            User.email == pending.email
        )
        .first()
    )

    duplicate_cpf = (
        db.query(User)
        .filter(
            User.cpf == pending.cpf
        )
        .first()
    )

    if (
        duplicate_cpf
        and (
            not existing
            or duplicate_cpf.id !=
                existing.id
        )
    ):
        raise HTTPException(
            409,
            "CPF já cadastrado em outra conta.",
        )

    if existing:
        existing.name = pending.name
        existing.cpf = pending.cpf
        existing.password_hash = (
            pending.password_hash
        )
        existing.birth_date = (
            pending.birth_date
        )
        existing.sex = pending.sex
        existing.terms_version = pending.terms_version
        existing.privacy_version = pending.privacy_version
        existing.legal_accepted_at = pending.legal_accepted_at
        user = existing
    else:
        user = User(
            email=pending.email,
            name=pending.name,
            cpf=pending.cpf,
            password_hash=
                pending.password_hash,
            birth_date=
                pending.birth_date,
            sex=pending.sex,
            terms_version=pending.terms_version,
            privacy_version=pending.privacy_version,
            legal_accepted_at=pending.legal_accepted_at,
        )
        db.add(user)

    db.delete(pending)
    db.commit()
    db.refresh(user)

    return user


def tokens(user: User) -> TokenResponse:
    return TokenResponse(
        access_token=create_access_token(user.id, session_version(object_session(user), user.id)),
        refresh_token=create_refresh_token(user.id, session_version(object_session(user), user.id)),
        expires_in=settings.access_token_expire_minutes * 60,
    )


@router.post("/register")
def register(
    data: UserCreate,
    request: Request,
    db: Session = Depends(get_db),
):
    if not data.legal_accepted:
        raise HTTPException(status_code=400, detail="É necessário aceitar os Termos de Uso e a Política de Privacidade")

    email = (
        str(data.email)
        .strip()
        .lower()
    )

    existing = (
        db.query(User)
        .filter(User.email == email)
        .first()
    )

    if (
        existing
        and existing.password_hash
    ):
        raise HTTPException(
            status_code=409,
            detail="E-mail já cadastrado",
        )

    existing_cpf = (
        db.query(User)
        .filter(User.cpf == data.cpf)
        .first()
    )

    if (
        existing_cpf
        and (
            not existing
            or existing_cpf.id != existing.id
        )
    ):
        raise HTTPException(
            status_code=409,
            detail="CPF já cadastrado em outra conta",
        )

    pending_cpf = (
        db.query(PendingRegistration)
        .filter(
            PendingRegistration.cpf ==
                data.cpf,
            PendingRegistration.email !=
                email,
        )
        .first()
    )

    if pending_cpf:
        raise HTTPException(
            status_code=409,
            detail=(
                "CPF já está sendo usado "
                "em outro cadastro."
            ),
        )

    pending = (
        db.query(PendingRegistration)
        .filter(
            PendingRegistration.email ==
                email
        )
        .first()
    )

    if pending is None:
        pending = PendingRegistration(
            email=email,
            name=data.name.strip(),
            cpf=data.cpf,
            password_hash=
                hash_password(data.password),
            birth_date=data.birth_date,
            sex=data.sex,
            terms_version=data.terms_version,
            privacy_version=data.privacy_version,
            legal_accepted_at=datetime.now(timezone.utc),
            code_hash="",
            attempts=0,
            expires_at=
                datetime.now(timezone.utc),
        )
        db.add(pending)
        db.flush()
    else:
        pending.name = data.name.strip()
        pending.cpf = data.cpf
        pending.password_hash = (
            hash_password(data.password)
        )
        pending.birth_date = (
            data.birth_date
        )
        pending.sex = data.sex
        pending.terms_version = data.terms_version
        pending.privacy_version = data.privacy_version
        pending.legal_accepted_at = datetime.now(timezone.utc)

    _send_pending_registration_link(
        pending,
        db,
        request,
    )

    return {
        "status": "verification_required",
        "email": email,
        "expires_in_seconds": 86400,
        "max_attempts": 0,
    }


@router.get(
    "/verify-email-link",
    response_class=HTMLResponse,
    name="verify_email_link",
)
def verify_email_link(
    email: str,
    token: str,
    db: Session = Depends(get_db),
):
    normalized_email = (
        email.strip().lower()
    )

    pending = (
        db.query(PendingRegistration)
        .filter(
            PendingRegistration.email ==
                normalized_email
        )
        .first()
    )

    if not pending:
        return HTMLResponse(
            content="""<!doctype html>
<html><body style="font-family:Arial,sans-serif;padding:32px;text-align:center;">
<h2>Link inválido ou já utilizado</h2>
<p>Se você já confirmou seu e-mail, volte ao Finance App e faça login normalmente.</p>
</body></html>""",
            status_code=400,
        )

    now = datetime.now(timezone.utc)
    expires = pending.expires_at

    if expires.tzinfo is None:
        expires = expires.replace(
            tzinfo=timezone.utc
        )

    if now > expires:
        return HTMLResponse(
            content="""<!doctype html>
<html><body style="font-family:Arial,sans-serif;padding:32px;text-align:center;">
<h2>Link expirado</h2>
<p>Volte ao Finance App e solicite um novo e-mail de confirmação.</p>
</body></html>""",
            status_code=400,
        )

    expected = (
        _registration_token_hash(
            normalized_email,
            token,
        )
    )

    if not secrets.compare_digest(
        pending.code_hash,
        expected,
    ):
        return HTMLResponse(
            content="""<!doctype html>
<html><body style="font-family:Arial,sans-serif;padding:32px;text-align:center;">
<h2>Link de confirmação inválido</h2>
<p>Solicite um novo e-mail de confirmação pelo Finance App.</p>
</body></html>""",
            status_code=400,
        )

    _complete_pending_registration(
        pending,
        db,
    )

    return HTMLResponse(
        content="""<!doctype html>
<html>
<body style="font-family:Arial,sans-serif;background:#f6f7f9;padding:32px;text-align:center;">
<div style="max-width:520px;margin:auto;background:white;border-radius:16px;padding:32px;">
<h2 style="color:#15803d;">E-mail confirmado!</h2>
<p>Sua conta do Finance App foi confirmada com sucesso.</p>
<p>Agora você já pode voltar ao aplicativo e entrar com seu e-mail e senha.</p>
</div>
</body>
</html>""",
        status_code=200,
    )


@router.post("/resend-verification")
def resend_verification(
    data: EmailVerificationResend,
    request: Request,
    db: Session = Depends(get_db),
):
    email = (
        str(data.email)
        .strip()
        .lower()
    )

    pending = (
        db.query(PendingRegistration)
        .filter(
            PendingRegistration.email ==
                email
        )
        .first()
    )

    if not pending:
        raise HTTPException(
            400,
            "Cadastro pendente não encontrado.",
        )

    now = datetime.now(timezone.utc)
    created = pending.created_at

    if created.tzinfo is None:
        created = created.replace(
            tzinfo=timezone.utc
        )

    if (
        now - created
    ).total_seconds() < 60:
        raise HTTPException(
            429,
            "Aguarde 1 minuto antes de "
            "solicitar outro código.",
        )

    _send_pending_registration_link(
        pending,
        db,
        request,
    )

    return {
        "status": "code_sent",
        "email": email,
        "expires_in_seconds": 86400,
        "max_attempts": 0,
    }


@router.post("/login", response_model=TokenResponse)
def login(data: LoginRequest, db: Session = Depends(get_db)):
    email = str(data.email).strip().lower()

    pending = (
        db.query(PendingRegistration)
        .filter(
            PendingRegistration.email ==
                email
        )
        .first()
    )

    if (
        pending
        and verify_password(
            data.password,
            pending.password_hash,
        )
    ):
        raise HTTPException(
            status_code=403,
            detail=(
                "E-mail não confirmado. "
                "Por favor, confirme seu e-mail "
                "para acessar."
            ),
        )

    user = db.query(User).filter(User.email == email).first()

    if (
        not user
        or not user.password_hash
        or not verify_password(data.password, user.password_hash)
    ):
        raise HTTPException(status_code=401, detail="E-mail ou senha inválidos")

    return tokens(user)


@router.post("/google", response_model=TokenResponse)
def google_login(data: GoogleLoginRequest, db: Session = Depends(get_db)):
    if not settings.google_client_id:
        raise HTTPException(status_code=503, detail="GOOGLE_CLIENT_ID não configurado")

    try:
        from google.auth.transport import requests
        from google.oauth2 import id_token

        info = id_token.verify_oauth2_token(
            data.id_token,
            requests.Request(),
            settings.google_client_id,
        )
    #except Exception as exc:
    #   raise HTTPException(status_code=401, detail="Token Google inválido") from exc
    except Exception as exc:

        raise HTTPException(
            status_code=401,
            detail=f"Token Google inválido: {exc}"
        ) from exc

    google_id = info.get("sub")
    email = info.get("email")
    name = info.get("name")
    google_picture = _google_picture_data_url(info.get("picture"))

    if email:
        email = email.strip().lower()

    if not google_id or not email or not info.get("email_verified"):
        raise HTTPException(status_code=401, detail="Conta Google não verificada")

    user = (
        db.query(User).filter(User.google_id == google_id).first()
        or db.query(User).filter(User.email == email).first()
    )

    if not user:
        user = User(email=email, name=name, google_id=google_id, google_profile_photo=google_picture, profile_photo=google_picture)
        db.add(user)
        db.commit()
        db.refresh(user)
    else:
        changed = False
        if not user.google_id:
            user.google_id = google_id
            changed = True
        if google_picture:
            user.google_profile_photo = google_picture
            changed = True
            if not user.profile_photo and not user.profile_photo_opt_out:
                user.profile_photo = google_picture
        if changed:
            db.commit()

    return tokens(user)


@router.post("/refresh", response_model=TokenResponse)
def refresh(data: RefreshRequest, db: Session = Depends(get_db)):
    user_id = rotate_refresh_token(data.refresh_token, db)
    user = db.get(User, int(user_id))

    if not user:
        raise HTTPException(status_code=401, detail="Usuário não encontrado")

    return tokens(user)
