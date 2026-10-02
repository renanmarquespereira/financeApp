from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, EmailStr, Field
from sqlalchemy import select
from app.database.database import get_db
from app.models.user import User
from app.models.pending_registration import PendingRegistration
from app.models.security_challenge import AuthSessionVersion
from app.core.security import hash_password
from app.services.security_codes import issue, verify
from app.services.email import send_security_code

router = APIRouter(prefix='/auth')
GENERIC = {'message': 'Se houver uma conta com esse e-mail, você receberá um código. Confira também o spam.'}


class ResetRequest(BaseModel):
    email: EmailStr


class ResetConfirm(ResetRequest):
    code: str = Field(pattern=r'^[0-9]{6}$')
    password: str = Field(min_length=6, max_length=128)


@router.post('/forgot-password')
def forgot(data: ResetRequest, db=Depends(get_db)):
    user = db.scalars(select(User).where(User.email == str(data.email).strip().lower()).with_for_update()).first()
    if user:
        try:
            code = issue(db, f'password:{user.id}')
            send_security_code(user.email, code, 'Redefinir senha', 'definir uma nova senha para sua conta')
            db.commit()
        except Exception:
            # Same response for unknown email, cooldown and SMTP failure.
            db.rollback()
    return GENERIC


@router.post('/reset-password')
def reset(data: ResetConfirm, db=Depends(get_db)):
    user = db.scalars(select(User).where(User.email == str(data.email).strip().lower()).with_for_update()).first()
    if not user:
        raise HTTPException(400, 'Código inválido ou expirado. Solicite outro código.')
    challenge = verify(db, f'password:{user.id}', data.code)
    user.password_hash = hash_password(data.password)
    version = db.get(AuthSessionVersion, user.id)
    if version is None:
        version = AuthSessionVersion(user_id=user.id, version=0)
        db.add(version)
    version.version += 1
    # Pending older registration links must not replace the new password.
    db.query(PendingRegistration).filter(PendingRegistration.email == user.email).delete(synchronize_session=False)
    db.delete(challenge)
    db.commit()
    return {'message': 'Senha alterada. Entre com sua nova senha.'}
