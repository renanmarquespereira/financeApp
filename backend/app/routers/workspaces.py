from datetime import datetime, timezone
from typing import Literal
from uuid import UUID
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field, field_validator
from sqlalchemy import select
from sqlalchemy.orm import Session
from app.database.database import get_db
from app.core.security import get_current_user
from app.core.workspace import ensure_default_workspace
from app.models.workspace import Workspace
from app.models.user import User
from app.models.security_challenge import DeletedWorkspace, SecurityChallenge

router = APIRouter(prefix="/workspaces", tags=["Workspaces"])


class WorkspaceEdit(BaseModel):
    name: str = Field(min_length=1, max_length=160)
    kind: Literal["personal", "business", "family", "other"] = "personal"

    @field_validator("name")
    @classmethod
    def clean_name(cls, value):
        value = value.strip()
        if not value:
            raise ValueError("Informe o nome do workspace")
        return value


class WorkspaceCreate(WorkspaceEdit):
    client_id: UUID  # Stable across retries, preventing duplicate workspaces.


def public(row):
    return {"id": row.id, "user_id": row.user_id, "name": row.name, "kind": row.kind,
            "is_default": row.is_default, "archived_at": row.archived_at}


def owned(db, user_id, workspace_id):
    row = db.scalars(select(Workspace).where(Workspace.id == workspace_id,
        Workspace.user_id == user_id).with_for_update()).first()
    if row is None:
        raise HTTPException(404, "Workspace não encontrado")
    return row


@router.get("")
def list_workspaces(db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    ensure_default_workspace(db, user.id)
    db.commit()
    rows = db.query(Workspace).filter(Workspace.user_id == user.id).order_by(Workspace.created_at, Workspace.id).all()
    return [public(w) for w in rows]


@router.post("", status_code=201)
def create_workspace(data: WorkspaceCreate, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    # Serializes retries by the same owner before checking the client-generated ID.
    db.execute(select(User.id).where(User.id == user.id).with_for_update()).scalar_one()
    id = str(data.client_id)
    if db.get(DeletedWorkspace, id):
        raise HTTPException(409, "Este workspace foi excluído. Crie outro workspace.")
    existing = db.get(Workspace, id)
    if existing:
        if existing.user_id != user.id:
            raise HTTPException(404, "Workspace não encontrado")
        if existing.name != data.name or existing.kind != data.kind or existing.archived_at is not None:
            raise HTTPException(409, "Esta solicitação já criou um workspace. Atualize a lista antes de tentar novamente.")
        return public(existing)
    row = Workspace(id=id, user_id=user.id, name=data.name, kind=data.kind, is_default=False)
    db.add(row)
    db.commit()
    db.refresh(row)
    return public(row)


@router.patch("/{workspace_id}")
def edit_workspace(workspace_id: str, data: WorkspaceEdit, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    row = owned(db, user.id, workspace_id)
    if row.archived_at is not None:
        raise HTTPException(409, "Restaure o workspace antes de editá-lo")
    row.name, row.kind = data.name, data.kind
    db.commit()
    return public(row)


@router.post("/{workspace_id}/archive")
def archive_workspace(workspace_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    row = owned(db, user.id, workspace_id)
    active_count = db.query(Workspace).filter(
        Workspace.user_id == user.id, Workspace.archived_at.is_(None)
    ).count()
    if row.archived_at is None and active_count <= 1:
        raise HTTPException(409, "Você precisa manter pelo menos um workspace ativo.")
    # default-<user_id> nao e especial: pode ser arquivado se outro ativo permanecer.
    # No financial row or consent is deleted. The workspace lock waits for imports
    # already holding a shared workspace lock; later imports reject archived scopes.
    if row.archived_at is None:
        row.archived_at = datetime.now(timezone.utc)
    db.commit()
    return public(row)


@router.post("/{workspace_id}/restore")
def restore_workspace(workspace_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    row = owned(db, user.id, workspace_id)
    challenge = db.get(SecurityChallenge, f"workspace-delete:{user.id}:{workspace_id}")
    if challenge:
        db.delete(challenge)
    row.archived_at = None
    db.commit()
    return public(row)


class DeleteConfirm(BaseModel):
    code: str = Field(pattern=r'^[0-9]{4}$')


def deletable(db, user_id, workspace_id):
    # Same lock order as workspace creation (user, then workspace).
    db.execute(select(User.id).where(User.id == user_id).with_for_update()).scalar_one()
    return owned(db, user_id, workspace_id)


def ensure_archived_for_delete(rows):
    if any(row.archived_at is None for row in rows):
        raise HTTPException(409, 'Arquive o workspace antes de excluí-lo definitivamente.')


def ensure_remaining_active(db, user_id: int, rows):
    # Exclusão definitiva é permitida somente para workspaces já arquivados.
    # Mantemos esta checagem como defesa adicional caso a regra mude no futuro.
    deleting_active = sum(1 for row in rows if row.archived_at is None)
    active_count = db.query(Workspace).filter(
        Workspace.user_id == user_id, Workspace.archived_at.is_(None)
    ).count()
    if active_count - deleting_active < 1:
        raise HTTPException(409, 'Você precisa manter pelo menos um workspace ativo.')


class WorkspaceBatchDelete(BaseModel):
    # O workspace legado/padrão usa id como "default-<user_id>", enquanto os
    # workspaces novos usam UUID. A exclusão em lote precisa aceitar os dois.
    workspace_ids: list[str] = Field(min_length=1, max_length=50)

    @field_validator("workspace_ids")
    @classmethod
    def clean_workspace_ids(cls, values):
        cleaned = [str(value).strip() for value in values]
        if any(not value for value in cleaned):
            raise ValueError("Workspace inválido")
        return cleaned


def _batch_ids(data: WorkspaceBatchDelete):
    return sorted(set(data.workspace_ids))


def _batch_key(user_id: int, ids: list[str]):
    import hashlib
    fingerprint = hashlib.sha256(','.join(ids).encode()).hexdigest()[:32]
    return f'workspace-batch-delete:{user_id}:{fingerprint}'


def _delete_workspace_records(db, user_id: int, row, challenge=None):
    from app.models.openfinance import OpenFinanceConnection
    from app.services.openfinance_provider import get_provider
    from app.database.database import Base
    import httpx
    connections = db.scalars(select(OpenFinanceConnection).where(
        OpenFinanceConnection.user_id == user_id, OpenFinanceConnection.workspace_id == row.id)).all()
    for connection in connections:
        if connection.provider == 'belvo' and connection.external_connection_id:
            try:
                if not get_provider('belvo').delete_link(connection.external_connection_id):
                    raise RuntimeError('Revogação não confirmada')
            except httpx.HTTPStatusError as exc:
                if exc.response.status_code != 404:
                    raise HTTPException(503, 'Não foi possível encerrar a conexão bancária. Tente novamente.') from exc
            except Exception as exc:
                raise HTTPException(503, 'Não foi possível encerrar a conexão bancária. Tente novamente.') from exc
    for table in reversed(Base.metadata.sorted_tables):
        if 'workspace_id' in table.c and 'user_id' in table.c:
            db.execute(table.delete().where(table.c.user_id == user_id, table.c.workspace_id == row.id))
    db.delete(row)
    db.add(DeletedWorkspace(id=row.id, user_id=user_id))


@router.post('/batch-delete-code')
def batch_delete_code(data: WorkspaceBatchDelete, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    from app.services.security_codes import issue
    from app.services.email import send_security_code
    ids = _batch_ids(data)
    db.execute(select(User.id).where(User.id == user.id).with_for_update()).scalar_one()
    rows = [deletable(db, user.id, workspace_id) for workspace_id in ids]
    ensure_archived_for_delete(rows)
    ensure_remaining_active(db, user.id, rows)
    key = _batch_key(user.id, ids)
    code = issue(db, key, digits=4)
    names = ', '.join(f'"{row.name}"' for row in rows)
    try:
        send_security_code(user.email, code, 'Excluir workspaces definitivamente',
            f'excluir definitivamente {len(rows)} workspace(s): {names}')
    except Exception as exc:
        db.rollback()
        raise HTTPException(503, 'Não foi possível enviar o e-mail. Tente novamente.') from exc
    db.commit()
    return {'message': f'Código enviado para excluir {len(rows)} workspace(s). Válido por 10 minutos.'}


@router.post('/batch-confirm-delete')
def batch_confirm_delete(data: WorkspaceBatchDelete, code: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    from app.services.security_codes import verify
    ids = _batch_ids(data)
    db.execute(select(User.id).where(User.id == user.id).with_for_update()).scalar_one()
    rows = [deletable(db, user.id, workspace_id) for workspace_id in ids]
    ensure_archived_for_delete(rows)
    ensure_remaining_active(db, user.id, rows)
    challenge = verify(db, _batch_key(user.id, ids), code)
    for row in rows:
        _delete_workspace_records(db, user.id, row)
    db.delete(challenge)
    db.commit()
    return {'message': f'{len(rows)} workspace(s) excluído(s) definitivamente.'}


@router.post('/{workspace_id}/delete-code')
def delete_code(workspace_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    from app.services.security_codes import issue
    from app.services.email import send_security_code
    row = deletable(db, user.id, workspace_id)
    ensure_archived_for_delete([row])
    ensure_remaining_active(db, user.id, [row])
    code = issue(db, f'workspace-delete:{user.id}:{workspace_id}', digits=4)
    try:
        send_security_code(user.email, code, 'Excluir workspace definitivamente',
            f'excluir definitivamente o workspace "{row.name}" e todos os seus dados financeiros')
    except Exception as exc:
        db.rollback()
        raise HTTPException(503, 'Não foi possível enviar o e-mail. Tente novamente.') from exc
    db.commit()
    return {'message': 'Código enviado ao e-mail da sua conta. Válido por 10 minutos.'}


@router.post('/{workspace_id}/confirm-delete')
def confirm_delete(workspace_id: str, data: DeleteConfirm, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    from app.services.security_codes import verify
    from app.models.openfinance import OpenFinanceConnection
    from app.services.openfinance_provider import get_provider
    from app.database.database import Base
    import httpx
    row = deletable(db, user.id, workspace_id)
    ensure_archived_for_delete([row])
    ensure_remaining_active(db, user.id, [row])
    challenge = verify(db, f'workspace-delete:{user.id}:{workspace_id}', data.code)
    connections = db.scalars(select(OpenFinanceConnection).where(
        OpenFinanceConnection.user_id == user.id, OpenFinanceConnection.workspace_id == workspace_id)).all()
    # Fail closed: preserve local records when external revocation cannot finish.
    for connection in connections:
        if connection.provider == 'belvo' and connection.external_connection_id:
            try:
                if not get_provider('belvo').delete_link(connection.external_connection_id):
                    raise RuntimeError('Revogação não confirmada')
            except httpx.HTTPStatusError as exc:
                if exc.response.status_code != 404:
                    raise HTTPException(503, 'Não foi possível encerrar a conexão bancária. Tente novamente.') from exc
            except Exception as exc:
                raise HTTPException(503, 'Não foi possível encerrar a conexão bancária. Tente novamente.') from exc
    # Child-first deletion, explicit owner + workspace for every financial table.
    # Includes snapshots, tombstones, codes, logs and future WorkspaceOwned tables.
    for table in reversed(Base.metadata.sorted_tables):
        if 'workspace_id' in table.c and 'user_id' in table.c:
            db.execute(table.delete().where(table.c.user_id == user.id, table.c.workspace_id == workspace_id))
    db.delete(challenge)
    db.delete(row)
    db.add(DeletedWorkspace(id=workspace_id, user_id=user.id))
    db.commit()
    return {'message': 'Workspace excluído definitivamente.'}
