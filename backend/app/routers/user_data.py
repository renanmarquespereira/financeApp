import base64
import hashlib
import hmac
import json
import secrets
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.core.config import settings
from app.core.security import get_current_user
from app.core.workspace import get_workspace_db as get_db, scope_id
from app.models.account import BankAccount
from app.models.account_deletion_code import AccountDeletionCode
from app.models.budget import CategoryBudget
from app.models.category import Category
from app.models.credit_card import CreditCard
from app.models.goal import FinancialGoal
from app.models.goal_contribution import GoalContribution
from app.models.financial_reset_marker import FinancialResetMarker
from app.models.ignored_account import IgnoredAccount
from app.models.ignored_transaction import IgnoredTransaction
from app.models.openfinance import OpenFinanceConnection, OpenFinanceSyncLog
from app.models.transaction import Transaction
from app.models.user import User
from app.models.user_data_deletion_code import UserDataDeletionCode
from app.models.user_sync_state import UserSyncState
from app.models.workspace import Workspace
from app.models.refresh_token import RefreshToken
from app.schemas.user_data import UserDataDeleteConfirm
from app.services.email import send_user_data_deletion_code
from app.services.openfinance_provider import BelvoProvider, get_provider

router = APIRouter(
    prefix="/user-data",
    tags=["User data"],
)

# FINANCEAPP_DELETE_V1: standalone confirmation service; never deletes remote finances.
from app.routers.local_deletion import router as local_deletion_router
router.include_router(local_deletion_router)


def _code_hash(
    user_id: int,
    code: str,
) -> str:
    payload = (
        f"{settings.jwt_secret}:clear-user-data:{user_id}:{code}"
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _deletion_token(user_id: int, code: str, expires_at: datetime) -> str:
    payload = {"uid": user_id, "code_hash": _code_hash(user_id, code), "exp": int(expires_at.timestamp())}
    raw = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")
    encoded = base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")
    signature = hmac.new(settings.jwt_secret.encode("utf-8"), encoded.encode("ascii"), hashlib.sha256).hexdigest()
    return f"{encoded}.{signature}"


def _verify_deletion_token(token: str | None, user_id: int, code: str, now: datetime) -> bool:
    if not token or "." not in token:
        return False
    try:
        encoded, signature = token.split(".", 1)
        expected = hmac.new(settings.jwt_secret.encode("utf-8"), encoded.encode("ascii"), hashlib.sha256).hexdigest()
        if not secrets.compare_digest(signature, expected):
            return False
        padded = encoded + "=" * (-len(encoded) % 4)
        payload = json.loads(base64.urlsafe_b64decode(padded.encode("ascii")).decode("utf-8"))
        return (
            int(payload.get("uid", -1)) == user_id
            and int(payload.get("exp", 0)) >= int(now.timestamp())
            and secrets.compare_digest(str(payload.get("code_hash", "")), _code_hash(user_id, code))
        )
    except Exception:
        return False


@router.post("/delete-code")
def request_delete_all_data_code(
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    now = datetime.now(timezone.utc)

    latest = (
        db.query(UserDataDeletionCode)
        .filter(UserDataDeletionCode.user_id == user.id)
        .order_by(UserDataDeletionCode.created_at.desc())
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

    db.query(UserDataDeletionCode).filter(
        UserDataDeletionCode.user_id == user.id,
    ).delete(synchronize_session=False)

    code = f"{secrets.randbelow(10000):04d}"

    expires_at = now + timedelta(minutes=5)
    verification = UserDataDeletionCode(
        user_id=user.id,
        code_hash=_code_hash(user.id, code),
        attempts=0,
        expires_at=expires_at,
    )

    db.add(verification)
    db.commit()

    try:
        send_user_data_deletion_code(
            recipient=user.email,
            code=code,
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
        "expires_in_seconds": 300,
        "deletion_token": _deletion_token(user.id, code, expires_at),
    }


@router.post("/confirm-delete-all")
def confirm_delete_all_data(
    data: UserDataDeleteConfirm,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    code = data.code.strip()
    if len(code) != 4 or not code.isdigit():
        raise HTTPException(400, "Informe o código de 4 dígitos.")

    # O registro no banco e a fonte de verdade. Um token assinado isolado
    # nunca autoriza exclusao: ele poderia ser reutilizado apos o primeiro uso.
    verification = (db.query(UserDataDeletionCode)
        .filter(UserDataDeletionCode.user_id == user.id)
        .order_by(UserDataDeletionCode.created_at.desc())
        .with_for_update().first())
    now = datetime.now(timezone.utc)
    if verification is None:
        raise HTTPException(400, "Solicite um novo codigo de confirmacao.")
    expires = verification.expires_at
    if expires.tzinfo is None:
        expires = expires.replace(tzinfo=timezone.utc)
    if now >= expires:
        db.delete(verification)
        db.commit()
        raise HTTPException(400, "Codigo expirado. Solicite um novo codigo.")
    if verification.attempts >= 5:
        raise HTTPException(429, "Limite de tentativas atingido. Solicite outro codigo.")
    if not secrets.compare_digest(verification.code_hash, _code_hash(user.id, code)):
        verification.attempts += 1
        db.commit()
        raise HTTPException(400, "Codigo incorreto. Tente novamente.")

    o = data.options
    if not any([o.transactions, o.categories, o.accounts, o.cards, o.budgets,
                o.goals, o.open_finance, o.workspace_ids, o.account_ids, o.delete_account]):
        raise HTTPException(400, "Selecione pelo menos um item para apagar.")

    uid = user.id
    current_ws = scope_id(db)
    conn = db.connection()

    # Excluir a conta é a opção máxima: remove todos os dados de todos os
    # workspaces e, por último, o próprio login. A ordem respeita as FKs.
    if o.delete_account:
        # Revoga links externos conhecidos antes da remoção local.
        connections = db.query(OpenFinanceConnection).filter(OpenFinanceConnection.user_id == uid).all()
        for connection in connections:
            if connection.provider == "belvo" and connection.external_connection_id:
                try:
                    provider = get_provider("belvo")
                    if isinstance(provider, BelvoProvider):
                        provider.delete_link(connection.external_connection_id)
                except Exception:
                    pass
        tables = [
            OpenFinanceSyncLog.__table__, GoalContribution.__table__, CategoryBudget.__table__,
            IgnoredTransaction.__table__, Transaction.__table__, AccountDeletionCode.__table__,
            IgnoredAccount.__table__, OpenFinanceConnection.__table__, BankAccount.__table__,
            CreditCard.__table__, FinancialGoal.__table__, Category.__table__, UserSyncState.__table__,
            FinancialResetMarker.__table__, RefreshToken.__table__, UserDataDeletionCode.__table__
        ]
        for table in tables:
            if "user_id" in table.c:
                conn.execute(table.delete().where(table.c.user_id == uid))
            elif table is OpenFinanceSyncLog.__table__:
                # normalmente já cai por FK; mantido por compatibilidade de bancos antigos
                pass
        conn.execute(Workspace.__table__.delete().where(Workspace.__table__.c.user_id == uid))
        conn.execute(User.__table__.delete().where(User.__table__.c.id == uid))
        db.commit()
        return {"status": "account_deleted", "login_deleted": True}

    # Workspaces escolhidos: somente workspaces adicionais podem ser removidos.
    requested_ws = set(o.workspace_ids)
    if requested_ws:
        rows = conn.execute(Workspace.__table__.select().where(
            Workspace.__table__.c.user_id == uid,
            Workspace.__table__.c.id.in_(requested_ws)
        )).mappings().all()
        default_ids = {r["id"] for r in rows if r["is_default"]}
        if default_ids:
            raise HTTPException(400, "O workspace principal não pode ser excluído. Apague os dados dele pelas opções acima.")
        removable = {r["id"] for r in rows}
        child_tables = [GoalContribution.__table__, CategoryBudget.__table__, IgnoredTransaction.__table__,
                        Transaction.__table__, AccountDeletionCode.__table__, IgnoredAccount.__table__,
                        OpenFinanceConnection.__table__, BankAccount.__table__, CreditCard.__table__,
                        FinancialGoal.__table__, Category.__table__, UserSyncState.__table__,
                        FinancialResetMarker.__table__, RefreshToken.__table__, UserDataDeletionCode.__table__]
        for wid in removable:
            # logs dependem da conexão; FK/cascade cuida nas instalações atuais.
            for table in child_tables:
                if "workspace_id" in table.c:
                    conn.execute(table.delete().where(table.c.user_id == uid, table.c.workspace_id == wid))
            conn.execute(Workspace.__table__.delete().where(
                Workspace.__table__.c.user_id == uid, Workspace.__table__.c.id == wid))

    # Seleção granular vale para o workspace atualmente aberto.
    if o.transactions:
        conn.execute(Transaction.__table__.delete().where(Transaction.user_id == uid, Transaction.workspace_id == current_ws))
        conn.execute(IgnoredTransaction.__table__.delete().where(IgnoredTransaction.user_id == uid, IgnoredTransaction.workspace_id == current_ws))
    if o.budgets:
        conn.execute(CategoryBudget.__table__.delete().where(CategoryBudget.user_id == uid, CategoryBudget.workspace_id == current_ws))
    if o.goals:
        conn.execute(GoalContribution.__table__.delete().where(GoalContribution.user_id == uid, GoalContribution.workspace_id == current_ws))
        conn.execute(FinancialGoal.__table__.delete().where(FinancialGoal.user_id == uid, FinancialGoal.workspace_id == current_ws))
    if o.accounts or o.account_ids:
        # Excluir uma conta nunca apaga o historico financeiro. Primeiro
        # desvinculamos as transacoes e somente depois removemos a conta.
        account_filter = [BankAccount.user_id == uid, BankAccount.workspace_id == current_ws]
        if o.account_ids:
            account_filter.append(BankAccount.id.in_(set(o.account_ids)))
        selected_accounts = conn.execute(
            BankAccount.__table__.select().where(*account_filter)
        ).mappings().all()
        selected_ids = [row["id"] for row in selected_accounts]
        if selected_ids:
            conn.execute(Transaction.__table__.update().where(
                Transaction.user_id == uid, Transaction.workspace_id == current_ws,
                Transaction.account_id.in_(selected_ids)
            ).values(account_id=None))
            conn.execute(AccountDeletionCode.__table__.delete().where(
                AccountDeletionCode.user_id == uid, AccountDeletionCode.account_id.in_(selected_ids)))
            conn.execute(BankAccount.__table__.delete().where(
                BankAccount.user_id == uid, BankAccount.workspace_id == current_ws,
                BankAccount.id.in_(selected_ids)))
    if o.cards:
        # Cartoes podem ser apagados sem apagar compras/transacoes.
        card_ids = [r[0] for r in conn.execute(
            CreditCard.__table__.select().with_only_columns(CreditCard.id).where(
                CreditCard.user_id == uid, CreditCard.workspace_id == current_ws)
        ).all()]
        if card_ids:
            conn.execute(Transaction.__table__.update().where(
                Transaction.user_id == uid, Transaction.workspace_id == current_ws,
                Transaction.card_id.in_(card_ids), Transaction.source != "card_payment"
            ).values(source="card_purchase"))
            conn.execute(Transaction.__table__.update().where(
                Transaction.user_id == uid, Transaction.workspace_id == current_ws,
                Transaction.card_id.in_(card_ids)
            ).values(card_id=None))
        conn.execute(CreditCard.__table__.delete().where(CreditCard.user_id == uid, CreditCard.workspace_id == current_ws))
    if o.categories:
        conn.execute(CategoryBudget.__table__.delete().where(CategoryBudget.user_id == uid, CategoryBudget.workspace_id == current_ws))
        conn.execute(Category.__table__.delete().where(Category.user_id == uid, Category.workspace_id == current_ws))
    if o.open_finance:
        conns = db.query(OpenFinanceConnection).filter(OpenFinanceConnection.user_id == uid).all()
        for c in conns:
            if c.provider == "belvo" and c.external_connection_id:
                try:
                    provider = get_provider("belvo")
                    if isinstance(provider, BelvoProvider): provider.delete_link(c.external_connection_id)
                except Exception: pass
        conn.execute(OpenFinanceConnection.__table__.delete().where(OpenFinanceConnection.user_id == uid, OpenFinanceConnection.workspace_id == current_ws))

    # Marca uma exclusao financeira autoritativa. Outros dispositivos usam este
    # timestamp para descartar cache/fila anteriores e impedir que dados apagados
    # no Web reaparecam ou sejam reenviados pelo Android.
    marker = (db.query(FinancialResetMarker)
        .filter(FinancialResetMarker.user_id == uid, FinancialResetMarker.workspace_id == current_ws)
        .first())
    if marker is None:
        marker = FinancialResetMarker(user_id=uid, workspace_id=current_ws, reset_at=now)
        db.add(marker)
    else:
        marker.reset_at = now

    conn.execute(UserDataDeletionCode.__table__.delete().where(UserDataDeletionCode.user_id == uid))
    db.commit()
    return {"status": "selected_data_deleted", "login_deleted": False, "workspace_ids_deleted": list(requested_ws), "reset_at": now}
