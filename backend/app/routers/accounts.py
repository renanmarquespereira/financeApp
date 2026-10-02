import hashlib
import secrets
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.core.workspace import get_workspace_db as get_db, scope_id
from app.core.security import get_current_user
from app.models.user import User
from app.models.account import BankAccount
from app.models.transaction import Transaction
from app.models.openfinance import OpenFinanceConnection
from app.models.ignored_account import IgnoredAccount
from app.models.account_deletion_code import AccountDeletionCode
from app.schemas.account import AccountCreate, AccountResponse, AccountDeleteConfirm, AccountUpdate
from app.workers.tasks import sync_openfinance
from app.services.email import send_account_deletion_code
from app.core.config import settings

router = APIRouter()


def _status_for(account: BankAccount, connections: list[OpenFinanceConnection]) -> str:
    if not account.external_account_id:
        return "manual"

    matching = [
        c for c in connections
        if account.institution_id and c.institution_id == account.institution_id
    ]

    if any(c.status == "active" for c in matching):
        return "connected"

    if matching:
        return "disconnected"

    return "disconnected"


@router.get("", response_model=list[AccountResponse])
def list_accounts(
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    accounts = (
        db.query(BankAccount)
        .filter(BankAccount.user_id == user.id)
        .order_by(BankAccount.id.asc())
        .all()
    )
    connections = (
        db.query(OpenFinanceConnection)
        .filter(OpenFinanceConnection.user_id == user.id)
        .all()
    )

    return [
        AccountResponse(
        workspace_id=scope_id(db),
            id=a.id,
            institution_name=a.institution_name,
            institution_id=a.institution_id,
            account_name=a.account_name,
            masked_account=a.masked_account,
            external_account_id=a.external_account_id,
            connection_status=_status_for(a, connections),
        )
        for a in accounts
    ]


@router.post("", response_model=AccountResponse)
def create_account(
    data: AccountCreate,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    obj = BankAccount(user_id=user.id, **data.model_dump())
    db.add(obj)
    db.commit()
    db.refresh(obj)

    return AccountResponse(
        workspace_id=scope_id(db),
        **data.model_dump(),
        id=obj.id,
        connection_status="manual",
    )




@router.patch(
    "/{account_id}",
    response_model=AccountResponse,
)
def update_account(
    account_id: int,
    data: AccountUpdate,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    account = (
        db.query(BankAccount)
        .filter(
            BankAccount.id == account_id,
            BankAccount.user_id == user.id,
        )
        .first()
    )
    if not account:
        raise HTTPException(
            404,
            "Conta não encontrada",
        )

    account.institution_name = (
        data.institution_name.strip()
    )
    account.account_name = (
        data.account_name.strip()
        if data.account_name
        else None
    )
    account.masked_account = (
        "".join(
            ch
            for ch in data.masked_account
            if ch.isdigit()
        )[-8:]
        if data.masked_account
        else None
    )

    db.commit()
    db.refresh(account)

    connections = (
        db.query(OpenFinanceConnection)
        .filter(
            OpenFinanceConnection.user_id ==
                user.id
        )
        .all()
    )

    return AccountResponse(
        workspace_id=scope_id(db),
        id=account.id,
        institution_name=
            account.institution_name,
        institution_id=
            account.institution_id,
        account_name=
            account.account_name,
        masked_account=
            account.masked_account,
        external_account_id=
            account.external_account_id,
        connection_status=
            _status_for(
                account,
                connections,
            ),
        current_balance=
            account.current_balance,
        balance_updated_at=
            account.balance_updated_at,
    )


def _connections_for_account(
    db: Session,
    user_id: int,
    account: BankAccount,
) -> list[OpenFinanceConnection]:
    if not account.external_account_id:
        return []

    query = db.query(OpenFinanceConnection).filter(
        OpenFinanceConnection.user_id == user_id,
    )

    if account.institution_id:
        query = query.filter(
            OpenFinanceConnection.institution_id == account.institution_id
        )

    return query.order_by(OpenFinanceConnection.id.desc()).all()


@router.post("/{account_id}/disconnect")
def pause_account_sync(
    account_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Pausa novas importações sem apagar a conta nem o histórico."""
    account = (
        db.query(BankAccount)
        .filter(
            BankAccount.id == account_id,
            BankAccount.user_id == user.id,
        )
        .first()
    )
    if not account:
        raise HTTPException(404, "Conta não encontrada")

    if not account.external_account_id:
        raise HTTPException(
            400,
            "Contas manuais não possuem sincronização bancária para pausar.",
        )

    connections = _connections_for_account(db, user.id, account)
    if not connections:
        raise HTTPException(
            404,
            "Conexão Open Finance desta conta não foi encontrada.",
        )

    paused = 0
    for connection in connections:
        if connection.status == "active":
            connection.status = "paused"
            connection.next_sync_at = None
            paused += 1

    db.commit()

    return {
        "status": "disconnected",
        "account_id": account.id,
        "connections_paused": paused,
        "history_preserved": True,
    }


@router.post("/{account_id}/reconnect")
def resume_account_sync(
    account_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Retoma uma conexão pausada e agenda atualização imediata."""
    account = (
        db.query(BankAccount)
        .filter(
            BankAccount.id == account_id,
            BankAccount.user_id == user.id,
        )
        .first()
    )
    if not account:
        raise HTTPException(404, "Conta não encontrada")

    if not account.external_account_id:
        raise HTTPException(
            400,
            "Contas manuais não possuem conexão bancária.",
        )

    connections = _connections_for_account(db, user.id, account)
    if not connections:
        raise HTTPException(
            409,
            "A conexão bancária original não existe mais. Conecte o banco novamente pelo Open Finance.",
        )

    resumed_ids: list[int] = []
    for connection in connections:
        # 'paused' é nosso estado de pausa. Também aceitamos 'disconnected'
        # para compatibilidade com dados de testes anteriores.
        if connection.status in {"paused", "disconnected"}:
            connection.status = "active"
            connection.next_sync_at = None
            connection.last_error = None
            resumed_ids.append(connection.id)

    db.commit()

    # Agenda atualização fora do commit. Se já estava ativo, também sincroniza.
    if not resumed_ids:
        resumed_ids = [
            connection.id
            for connection in connections
            if connection.status == "active"
        ]

    for connection_id in resumed_ids:
        sync_openfinance.delay(connection_id)

    return {
        "status": "connected",
        "account_id": account.id,
        "connections_resumed": len(resumed_ids),
        "sync_queued": bool(resumed_ids),
        "history_preserved": True,
    }


def _delete_account_permanently(
    db: Session,
    user: User,
    account: BankAccount,
) -> dict:
    if account.external_account_id:
        ignored = (
            db.query(IgnoredAccount)
            .filter(
                IgnoredAccount.user_id == user.id,
                IgnoredAccount.external_account_id == account.external_account_id,
            )
            .first()
        )
        if not ignored:
            db.add(
                IgnoredAccount(
                    user_id=user.id,
                    external_account_id=account.external_account_id,
                )
            )

    account_id = account.id
    # A conta pode ser removida sem destruir o historico: as transacoes
    # permanecem e apenas deixam de apontar para este banco.
    db.query(Transaction).filter(
        Transaction.user_id == user.id,
        Transaction.account_id == account_id,
    ).update({Transaction.account_id: None}, synchronize_session=False)
    db.delete(account)
    db.commit()

    return {
        "status": "deleted",
        "account_id": account_id,
        "transactions_deleted": False,
    }


def _delete_code_hash(user_id: int, account_id: int, code: str) -> str:
    payload = (
        f"{settings.jwt_secret}:{user_id}:{account_id}:{code}"
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


@router.post("/{account_id}/delete-code")
def request_account_delete_code(
    account_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    account = (
        db.query(BankAccount)
        .filter(
            BankAccount.id == account_id,
            BankAccount.user_id == user.id,
        )
        .first()
    )
    if not account:
        raise HTTPException(404, "Conta não encontrada")

    now = datetime.now(timezone.utc)

    latest = (
        db.query(AccountDeletionCode)
        .filter(
            AccountDeletionCode.user_id == user.id,
            AccountDeletionCode.account_id == account_id,
        )
        .order_by(AccountDeletionCode.created_at.desc())
        .first()
    )

    if latest and latest.created_at:
        created = latest.created_at
        if created.tzinfo is None:
            created = created.replace(tzinfo=timezone.utc)

        if (now - created).total_seconds() < 60:
            raise HTTPException(
                429,
                "Aguarde 1 minuto antes de solicitar outro código.",
            )

    db.query(AccountDeletionCode).filter(
        AccountDeletionCode.user_id == user.id,
        AccountDeletionCode.account_id == account_id,
    ).delete(synchronize_session=False)

    code = f"{secrets.randbelow(10000):04d}"

    verification = AccountDeletionCode(
        user_id=user.id,
        account_id=account.id,
        code_hash=_delete_code_hash(
            user.id,
            account.id,
            code,
        ),
        attempts=0,
        expires_at=now + timedelta(minutes=10),
    )
    db.add(verification)
    db.commit()

    try:
        send_account_deletion_code(
            recipient=user.email,
            code=code,
            institution_name=(
                f"{account.institution_name} - "
                f"{account.account_name or 'Conta'}"
            ),
        )
    except Exception as exc:
        db.delete(verification)
        db.commit()
        raise HTTPException(
            503,
            f"Não foi possível enviar o código por e-mail: {exc}",
        ) from exc

    return {
        "status": "code_sent",
        "email": user.email,
        "expires_in_seconds": 600,
    }


@router.post("/{account_id}/confirm-delete")
def confirm_account_delete(
    account_id: int,
    data: AccountDeleteConfirm,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    account = (
        db.query(BankAccount)
        .filter(
            BankAccount.id == account_id,
            BankAccount.user_id == user.id,
        )
        .first()
    )
    if not account:
        raise HTTPException(404, "Conta não encontrada")

    code = data.code.strip()

    if len(code) != 4 or not code.isdigit():
        raise HTTPException(
            400,
            "Informe o código de 4 dígitos.",
        )

    verification = (
        db.query(AccountDeletionCode)
        .filter(
            AccountDeletionCode.user_id == user.id,
            AccountDeletionCode.account_id == account_id,
        )
        .order_by(AccountDeletionCode.created_at.desc())
        .first()
    )

    if not verification:
        raise HTTPException(
            400,
            "Solicite um novo código de confirmação.",
        )

    now = datetime.now(timezone.utc)
    expires = verification.expires_at
    if expires.tzinfo is None:
        expires = expires.replace(tzinfo=timezone.utc)

    if now > expires:
        db.delete(verification)
        db.commit()
        raise HTTPException(
            400,
            "Código expirado. Solicite um novo código.",
        )

    if verification.attempts >= 5:
        db.delete(verification)
        db.commit()
        raise HTTPException(
            429,
            "Número máximo de tentativas atingido. Solicite outro código.",
        )

    expected = _delete_code_hash(
        user.id,
        account.id,
        code,
    )

    if not secrets.compare_digest(
        verification.code_hash,
        expected,
    ):
        verification.attempts += 1
        remaining = max(
            0,
            5 - verification.attempts,
        )

        if remaining == 0:
            db.delete(verification)
            db.commit()
            raise HTTPException(
                429,
                "Código incorreto. Limite de 5 tentativas atingido. "
                "Solicite um novo código.",
            )

        db.commit()
        raise HTTPException(
            400,
            f"Código incorreto. Tentativas restantes: {remaining}.",
        )

    db.delete(verification)
    db.flush()

    return _delete_account_permanently(
        db=db,
        user=user,
        account=account,
    )


@router.delete("/{account_id}")
def delete_account_without_confirmation(
    account_id: int,
    user: User = Depends(get_current_user),
):
    raise HTTPException(
        403,
        "A exclusão de banco exige confirmação por código enviado ao e-mail.",
    )

