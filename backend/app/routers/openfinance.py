from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.orm import Session
import httpx

from app.core.config import settings
from app.database.database import get_db as get_discovery_db
from app.core.workspace import get_workspace_db as get_db, scope_id
from app.core.security import get_current_user
from app.models.user import User
from app.models.transaction import Transaction
from app.models.openfinance import OpenFinanceConnection, OpenFinanceSyncLog
from app.schemas.openfinance import (
    ConnectRequest,
    ConnectionResponse,
    WidgetTokenRequest,
    RegisterBelvoLinkRequest,
)
from app.services.openfinance_provider import get_provider, BelvoProvider
from app.workers.tasks import sync_openfinance
from app.services.openfinance_sync import sync_connection

router = APIRouter()


def _provider_error(exc: Exception) -> HTTPException:
    if isinstance(exc, httpx.HTTPStatusError):
        try:
            detail = exc.response.json()
        except Exception:
            detail = exc.response.text
        return HTTPException(exc.response.status_code, detail=detail)
    message = str(exc).strip()

    if not message:
        message = (
            "Falha ao comunicar com o provedor Open Finance."
        )

    return HTTPException(
        400,
        detail=message,
    )


@router.post("/connect", response_model=ConnectionResponse)
def connect(
    data: ConnectRequest,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Rota legada da V4. Para Belvo, prefira /belvo/widget-token."""
    row = OpenFinanceConnection(
        user_id=user.id,
        provider=data.provider,
        institution_id=data.institution_id,
        institution_name=data.institution_name,
        status="pending",
    )
    db.add(row)
    db.flush()
    try:
        result = get_provider(data.provider).create_consent(row)
        row.status = result.get("status", "pending")
        row.authorization_url = result.get("authorization_url")
    except Exception as exc:
        db.rollback()
        raise _provider_error(exc)
    db.commit()
    db.refresh(row)
    return row


@router.post("/mock/connect", response_model=ConnectionResponse)
def mock_connect(
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Cria uma conexão Open Finance simulada e agenda a primeira importação."""
    row = OpenFinanceConnection(
        user_id=user.id, provider="mock", institution_id="mockbank_br",
        institution_name="Mock Bank Brasil", status="active",
        external_connection_id=f"mock-user-{user.id}-{scope_id(db)}", next_sync_at=None,
        sync_interval_minutes=1, mock_sequence=0,
    )
    existing = db.query(OpenFinanceConnection).filter(
        OpenFinanceConnection.user_id == user.id,
        OpenFinanceConnection.provider == "mock",
    ).first()
    if existing:
        if existing.status != "active":
            existing.status = "active"
            existing.next_sync_at = None
            db.commit()

        # Mock é local: sincroniza na própria requisição para o app
        # receber contas/transações imediatamente.
        sync_connection(
            db,
            existing.id
        )
        db.refresh(existing)
        return existing

    db.add(row)
    db.commit()
    db.refresh(row)

    # Mock é local e determinístico. Não há motivo para depender da fila
    # Celery na primeira carga: isso eliminava o "Sincronização iniciada"
    # que nunca aparecia no app quando o refresh ocorria cedo demais.
    sync_connection(
        db,
        row.id
    )

    db.refresh(row)
    return row


@router.post("/mock/connections/{connection_id}/generate-transaction")
def mock_generate_transaction(
    connection_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Simula uma nova movimentação bancária e agenda sua importação."""
    row = db.query(OpenFinanceConnection).filter(
        OpenFinanceConnection.id == connection_id,
        OpenFinanceConnection.user_id == user.id,
        OpenFinanceConnection.provider == "mock",
    ).first()
    if not row:
        raise HTTPException(404, "Conexão mock não encontrada")
    row.mock_sequence = int(row.mock_sequence or 0) + 1
    row.next_sync_at = None
    db.commit()
    task = sync_openfinance.delay(row.id)
    return {"status": "queued", "sequence": row.mock_sequence, "task_id": task.id}



@router.get("/mock/transactions")
def mock_imported_transactions(
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Lista somente transações importadas automaticamente pelo Open Finance."""
    rows = (
        db.query(Transaction)
        .filter(
            Transaction.user_id == user.id,
            Transaction.source == "open_finance",
        )
        .order_by(Transaction.date.desc(), Transaction.id.desc())
        .all()
    )
    return [
        {
            "id": row.id,
            "account_id": row.account_id,
            "external_transaction_id": row.external_transaction_id,
            "source": row.source,
            "date": row.date,
            "description": row.description,
            "amount": str(row.amount),
            "transaction_type": row.transaction_type,
            "status": row.status,
            "synced_at": row.synced_at,
        }
        for row in rows
    ]


@router.post("/belvo/widget-token")
async def belvo_widget_token(
    data: WidgetTokenRequest,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Gera token EFÊMERO para o Hosted Widget. Nunca retorna as chaves secretas da Belvo."""
    try:
        provider = get_provider("belvo")
        assert isinstance(provider, BelvoProvider)
        if (
            not user.cpf
            or not user.name
        ):
            raise HTTPException(
                status_code=422,
                detail=(
                    "Complete nome e CPF "
                    "antes de conectar o banco."
                ),
            )

        result = await provider.create_widget_token(
            user_id=user.id,
            workspace_id=scope_id(db),
            cpf=user.cpf,
            name=user.name,
        )
        access_token = result.get("access") or result.get("access_token") or result.get("token")
        return {
            "access_token": access_token,
            "expires_in": result.get("expires_in"),
            "widget_base_url": settings.belvo_widget_url,
            "external_id": result.get("external_id"),
            "hosted_widget_url": (
                f"{settings.belvo_widget_url.rstrip('/')}?access_token={access_token}"
                f"&external_id={result.get('external_id')}"
                "&locale=pt&integration_type=openfinance&country_codes=BR&access_mode=recurrent"
                if access_token
                else None
            ),
            "raw": result if not access_token else None,
        }
    except Exception as exc:
        raise _provider_error(exc)


@router.post("/belvo/register-link", response_model=ConnectionResponse)
def register_belvo_link(
    data: RegisterBelvoLinkRequest,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Salva o link_id devolvido pelo Hosted Widget após o consentimento."""
    try:
        provider = get_provider("belvo")
        assert isinstance(provider, BelvoProvider)
        link = provider.get_link(data.link_id)
    except Exception as exc:
        raise _provider_error(exc)

    expected_external_id = f"user_{user.id}_workspace_{scope_id(db)}"
    if link.get("external_id") != expected_external_id:
        raise HTTPException(409, "Conexão não pertence a esta sessão de workspace. Abra o widget novamente.")

    existing = (
        db.query(OpenFinanceConnection)
        .filter(
            OpenFinanceConnection.user_id == user.id,
            OpenFinanceConnection.provider == "belvo",
            OpenFinanceConnection.external_connection_id == data.link_id,
        )
        .first()
    )
    if existing:
        return existing

    institution = link.get("institution") or {}
    if isinstance(institution, str):
        institution_id = data.institution_id or institution
        institution_name = data.institution_name or institution
    else:
        institution_id = data.institution_id or institution.get("name") or institution.get("id")
        institution_name = data.institution_name or institution.get("display_name") or institution.get("name")

    row = OpenFinanceConnection(
        user_id=user.id,
        provider="belvo",
        institution_id=institution_id,
        institution_name=institution_name,
        external_connection_id=data.link_id,
        status="active",
        next_sync_at=None,
    )
    db.add(row)
    db.commit()
    db.refresh(row)

    # O histórico pode ainda estar sendo preparado. Se estiver pronto, importa;
    # se não, o webhook/scheduler tentará novamente sem bloquear o cadastro.
    sync_openfinance.delay(row.id)
    return row


@router.get("/connections", response_model=list[ConnectionResponse])
def connections(
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    return (
        db.query(OpenFinanceConnection)
        .filter(OpenFinanceConnection.user_id == user.id)
        .order_by(OpenFinanceConnection.id.desc())
        .all()
    )


@router.post("/connections/{connection_id}/sync")
def sync_now(
    connection_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    row = (
        db.query(OpenFinanceConnection)
        .filter(
            OpenFinanceConnection.id == connection_id,
            OpenFinanceConnection.user_id == user.id,
        )
        .first()
    )
    if not row:
        raise HTTPException(404, "Conexão não encontrada")
    if row.status != "active":
        raise HTTPException(
            409,
            "A sincronização desta conexão está pausada. Reconecte o banco para continuar.",
        )
    if row.provider == "mock":
        result = sync_connection(
            db,
            row.id
        )

        return {
            "status": "completed",
            "task_id": None,
            "message": "Sincronização de teste concluída.",
            "imported": result.get("imported", 0),
        }

    task = sync_openfinance.delay(row.id)

    return {
        "status": "queued",
        "task_id": task.id,
        "message":
            "Sincronização enviada para o worker.",
    }


@router.delete("/connections/{connection_id}")
def disconnect(
    connection_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    row = (
        db.query(OpenFinanceConnection)
        .filter(
            OpenFinanceConnection.id == connection_id,
            OpenFinanceConnection.user_id == user.id,
        )
        .first()
    )
    if not row:
        raise HTTPException(404, "Conexão não encontrada")
    try:
        if row.provider == "belvo" and row.external_connection_id:
            provider = get_provider("belvo")
            assert isinstance(provider, BelvoProvider)
            provider.delete_link(row.external_connection_id)
    except Exception as exc:
        raise _provider_error(exc)
    db.delete(row)
    db.commit()
    return {"status": "disconnected"}


@router.get("/connections/{connection_id}/sync-logs")
def sync_logs(
    connection_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    row = (
        db.query(OpenFinanceConnection)
        .filter(
            OpenFinanceConnection.id == connection_id,
            OpenFinanceConnection.user_id == user.id,
        )
        .first()
    )
    if not row:
        raise HTTPException(404, "Conexão não encontrada")
    logs = (
        db.query(OpenFinanceSyncLog)
        .filter(OpenFinanceSyncLog.connection_id == row.id)
        .order_by(OpenFinanceSyncLog.id.desc())
        .limit(50)
        .all()
    )
    return [
        {
            "id": x.id,
            "status": x.status,
            "imported_transactions": x.imported_transactions,
            "message": x.message,
            "started_at": x.started_at,
            "finished_at": x.finished_at,
        }
        for x in logs
    ]


@router.post("/webhook/belvo")
async def belvo_webhook(request: Request, db: Session = Depends(get_discovery_db)):
    """Recebe webhooks de agregação da Belvo e agenda nova sincronização.

    Se BELVO_WEBHOOK_TOKEN estiver configurado, exige Authorization: Bearer <token>.
    Configure o MESMO token no dashboard da Belvo.
    """
    if settings.belvo_webhook_token:
        expected = f"Bearer {settings.belvo_webhook_token}"
        if request.headers.get("authorization") != expected:
            raise HTTPException(401, "Webhook não autorizado")

    payload = await request.json()
    data = payload.get("data") if isinstance(payload.get("data"), dict) else {}
    link_value = (
        payload.get("link")
        or payload.get("link_id")
        or payload.get("connection_id")
        or payload.get("external_connection_id")
        or data.get("link")
        or data.get("link_id")
    )
    if isinstance(link_value, dict):
        link_value = link_value.get("id")

    if not link_value:
        # Alguns eventos não são relativos a um link específico. Aceitamos o
        # evento para evitar retries desnecessários da Belvo.
        return {"status": "accepted", "queued": 0}

    row = (
        db.query(OpenFinanceConnection)
        .filter(
            OpenFinanceConnection.provider == "belvo",
            OpenFinanceConnection.external_connection_id == str(link_value),
        )
        .first()
    )
    if not row:
        return {"status": "accepted", "queued": 0}

    # Webhook não deve reativar uma conexão pausada pelo usuário.
    if row.status != "active":
        return {
            "status": "accepted",
            "queued": 0,
            "reason": f"connection_{row.status}",
        }

    sync_openfinance.delay(row.id)
    return {"status": "accepted", "queued": 1}
