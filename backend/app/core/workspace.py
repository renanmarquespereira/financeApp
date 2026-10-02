"""Request/worker workspace boundary. Raw SQL is reserved for migrations.

Every ORM SELECT (including relationships/aggregates) and bulk DELETE/UPDATE
receives BOTH ownership predicates. Inserts and references are checked at flush.
"""
from fastapi import Depends, Header, HTTPException
from sqlalchemy import event, inspect, select
from sqlalchemy.orm import Session, with_loader_criteria
from app.database.database import get_db
from app.core.security import get_current_user
from app.models.workspace import Workspace, WorkspaceOwned, default_workspace_id
from app.models.user import User


def ensure_default_workspace(db: Session, user_id: int) -> Workspace:
    # Lock the owning user, serializing first-login creation across API workers.
    db.execute(select(User.id).where(User.id == user_id).with_for_update()).scalar_one()
    row = db.get(Workspace, default_workspace_id(user_id))
    if row is not None and row.archived_at is None:
        return row
    # O workspace original pode ser arquivado ou excluído. Nesse caso, use outro
    # workspace ativo em vez de recriar silenciosamente o padrão apagado.
    fallback = db.scalars(select(Workspace).where(
        Workspace.user_id == user_id, Workspace.archived_at.is_(None)
    ).order_by(Workspace.created_at, Workspace.id)).first()
    if fallback is not None:
        return fallback
    if row is not None:
        # Não deve ocorrer pelas regras de arquivamento, mas mantém a conta utilizável.
        row.archived_at = None
        return row
    row = Workspace(id=default_workspace_id(user_id), user_id=user_id,
                    name="Pessoal", kind="personal", is_default=True)
    db.add(row)
    db.flush()
    return row


def bind_workspace(db: Session, user_id: int, workspace_id: str):
    old = db.info.get("workspace_scope")
    if old is not None and old != (user_id, workspace_id):
        raise HTTPException(409, "Não é permitido trocar o workspace durante uma operação")
    row = db.scalars(select(Workspace).where(Workspace.id == workspace_id, Workspace.user_id == user_id)
        .with_for_update(read=True).execution_options(populate_existing=True)).first()
    if row is None or row.user_id != user_id or row.archived_at is not None:
        raise HTTPException(404, "Workspace não encontrado ou arquivado")
    for cached in list(db.identity_map.values()):
        if isinstance(cached, WorkspaceOwned) and (cached.user_id, cached.workspace_id) != (user_id, workspace_id):
            db.expunge(cached)
    db.info["workspace_scope"] = (user_id, workspace_id)
    return row


def get_workspace_db(
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
    workspace_id: str | None = Header(default=None, alias="X-Workspace-Id"),
):
    if workspace_id is None:
        workspace_id = ensure_default_workspace(db, user.id).id
        db.commit()
    bind_workspace(db, user.id, workspace_id)
    return db


def scope_id(db: Session) -> str:
    return db.info["workspace_scope"][1]


@event.listens_for(Session, "do_orm_execute")
def _scope_statement(state):
    scope = state.session.info.get("workspace_scope")
    if scope is None:
        return  # Auth and trusted scheduler discovery use unscoped sessions.
    user_id, workspace_id = scope
    if state.is_select:
        state.statement = state.statement.options(with_loader_criteria(
            WorkspaceOwned,
            lambda cls: (cls.user_id == user_id) & (cls.workspace_id == workspace_id),
            include_aliases=True,
        ))
    elif state.is_update or state.is_delete:
        mapper = state.bind_mapper
        if mapper is not None and issubclass(mapper.class_, WorkspaceOwned):
            cls = mapper.class_
            state.statement = state.statement.where(cls.user_id == user_id, cls.workspace_id == workspace_id)
            if state.is_update:
                # Ownership is immutable, including bulk UPDATE statements.
                keys = {getattr(k, "key", k) for k in (state.statement._values or {})}
                if keys & {"user_id", "workspace_id"}:
                    raise HTTPException(409, "A titularidade dos dados é imutável")


@event.listens_for(Session, "before_flush")
def _check_writes(db, flush_context, instances):
    for obj in db.new | db.dirty | db.deleted:
        if not isinstance(obj, WorkspaceOwned):
            continue
        scope = db.info.get("workspace_scope")
        if scope is None:
            raise RuntimeError("Financial writes require an explicit workspace scope")
        user_id, workspace_id = scope
        if obj in db.new:
            if obj.workspace_id is None:
                obj.workspace_id = workspace_id
            if getattr(obj, "user_id", None) is None:
                obj.user_id = user_id
        if obj.user_id != user_id or obj.workspace_id != workspace_id:
            raise HTTPException(404, "Registro não pertence ao workspace")
        if obj not in db.new and any(inspect(obj).attrs[key].history.has_changes()
                                     for key in ("user_id", "workspace_id")):
            raise HTTPException(409, "A titularidade dos dados é imutável")
        if obj in db.deleted:
            continue
        # Also protects import/restore and caller-supplied category/card/account ids.
        for column in obj.__table__.columns:
            for fk in column.foreign_keys:
                table = fk.column.table
                if "workspace_id" not in table.c or table.name == "workspaces":
                    continue
                value = getattr(obj, column.key)
                if value is None:
                    continue
                found = db.connection().execute(select(table.c.user_id, table.c.workspace_id)
                    .where(fk.column == value)).first()
                if found is None or tuple(found) != scope:
                    raise HTTPException(422, "Referência financeira fora do workspace")



@event.listens_for(User, "after_insert")
def _new_user_workspace(mapper, connection, user):
    from datetime import datetime, timezone
    connection.execute(Workspace.__table__.insert().values(
        id=default_workspace_id(user.id), user_id=user.id, name="Pessoal",
        kind="personal", is_default=True, created_at=datetime.now(timezone.utc)))
