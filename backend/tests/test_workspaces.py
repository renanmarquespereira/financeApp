"""v39 isolation, offline protocol, reset and migration regression tests.

Run: DATABASE_URL=sqlite:// JWT_SECRET=test python -m pytest tests -q
"""
import os
os.environ.setdefault("DATABASE_URL", "sqlite://")
os.environ.setdefault("JWT_SECRET", "test-workspace-secret")

from datetime import datetime, timezone
from decimal import Decimal
import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, select, text, func
from sqlalchemy.orm import Session, aliased
from sqlalchemy.pool import StaticPool
from app.main import app
from app.database.database import Base, get_db
from app.database.workspace_migration import migrate_workspaces, TABLES
from app.core.security import create_access_token
from app.core.workspace import bind_workspace
from app.models import (User, Workspace, Category, BankAccount, Transaction,
    CreditCard, FinancialGoal, GoalContribution, CategoryBudget, UserSyncState,
    FinancialResetMarker, OpenFinanceConnection, OpenFinanceSyncLog)


@pytest.fixture
def engine():
    e = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    Base.metadata.create_all(e)
    migrate_workspaces(e)
    with Session(e) as db:
        db.add_all([User(id=1, email="one@example.org"), User(id=2, email="two@example.org")])
        db.commit()
        db.add(Workspace(id="company", user_id=1, name="Empresa", kind="business"))
        db.commit()
    yield e
    e.dispose()


@pytest.fixture
def client(engine):
    def dependency():
        with Session(engine) as db:
            yield db
    app.dependency_overrides[get_db] = dependency
    with TestClient(app) as c:
        yield c
    app.dependency_overrides.clear()


def headers(user=1, workspace=None):
    h = {"Authorization": f"Bearer {create_access_token(user)}"}
    if workspace is not None:
        h["X-Workspace-Id"] = workspace
    return h


def add_category(engine, scope, name="Teste"):
    with Session(engine) as db:
        bind_workspace(db, *scope)
        row = Category(user_id=scope[0], name=name)
        db.add(row); db.commit()
        return row.id


def test_default_creation_and_legacy_protocol(client, engine):
    rows = client.get("/workspaces", headers=headers()).json()
    assert {w["id"] for w in rows} == {"default-1", "company"}
    assert len(client.get("/workspaces", headers=headers(2)).json()) == 1
    response = client.post("/categories", headers=headers(), json={"name": "Pessoal"})
    assert response.status_code == 200, response.text
    with Session(engine) as db:
        assert db.query(Category).one().workspace_id == "default-1"


def test_list_aggregate_alias_get_and_bulk_delete_isolated(engine):
    personal = add_category(engine, (1, "default-1"))
    company = add_category(engine, (1, "company"))
    foreign = add_category(engine, (2, "default-2"))
    with Session(engine) as db:
        bind_workspace(db, 1, "company")
        assert [r.id for r in db.query(Category)] == [company]
        assert db.get(Category, personal) is None
        assert db.get(Category, foreign) is None
        assert db.query(func.count(Category.id)).scalar() == 1
        alias = aliased(Category)
        assert db.scalars(select(alias.id)).all() == [company]
        assert db.query(Category).update({"name": "Alterada"}) == 1
        assert db.query(Category).delete(synchronize_session=False) == 1
        db.commit()
    with Session(engine) as db:
        assert {c.id for c in db.query(Category)} == {personal, foreign}


def test_invalid_workspace_and_archived_fail_closed(client, engine):
    for workspace in ("default-2", "missing", ""):
        assert client.get("/categories", headers=headers(workspace=workspace)).status_code == 404
    with Session(engine) as db:
        db.get(Workspace, "company").archived_at = datetime.now(timezone.utc)
        db.commit()
    assert client.get("/categories", headers=headers(workspace="company")).status_code == 404


def test_cross_workspace_reference_and_owner_update_rejected(engine):
    cat = add_category(engine, (1, "company"))
    with Session(engine) as db:
        bind_workspace(db, 1, "default-1")
        db.add(Transaction(user_id=1, category_id=cat, date=datetime.now(timezone.utc),
                           description="Invalid", amount=Decimal("4")))
        with pytest.raises(HTTPException):
            db.flush()
        db.rollback()
    personal = add_category(engine, (1, "default-1"))
    with Session(engine) as db:
        bind_workspace(db, 1, "default-1")
        db.get(Category, personal).workspace_id = "company"
        with pytest.raises(HTTPException):
            db.flush()


def test_db_guard_rejects_raw_cross_workspace_reference(engine):
    cat = add_category(engine, (1, "company"))
    with engine.begin() as c:
        with pytest.raises(Exception):
            c.execute(text("""INSERT INTO transactions
              (user_id, workspace_id, category_id, source, date, description, amount, transaction_type, status)
              VALUES (1, 'default-1', :cat, 'manual', CURRENT_TIMESTAMP, 'invalid', 1, 'debit', 'posted')"""), {"cat": cat})


def test_sync_state_partitioned(client, engine):
    for workspace in ("default-1", "company", "default-1", "company"):
        r = client.get("/sync/snapshot", headers=headers(workspace=workspace))
        assert r.status_code == 200, r.text
        assert r.json()["workspace_id"] == workspace
    with Session(engine) as db:
        assert db.query(UserSyncState).count() == 2


def test_backup_scope_checked(client):
    backup = client.get("/backup/export", headers=headers()).json()
    assert backup["workspace_id"] == "default-1"
    assert client.post("/backup/import", headers=headers(workspace="company"), json=backup).status_code == 409
    backup.pop("workspace_id"); backup.pop("user_id")
    assert client.post("/backup/import", headers=headers(workspace="company"), json=backup).status_code == 409


def test_mock_sync_stays_in_connection_workspace(client, engine):
    for workspace in ("default-1", "company"):
        r = client.post("/openfinance/mock/connect", headers=headers(workspace=workspace))
        assert r.status_code == 200, r.text
    with Session(engine) as db:
        assert db.query(OpenFinanceConnection).count() == 2
        assert db.query(BankAccount).count() == 4
        for conn in db.query(OpenFinanceConnection):
            assert db.query(OpenFinanceSyncLog).filter_by(connection_id=conn.id).one().workspace_id == conn.workspace_id
    for workspace in ("default-1", "company"):
        snap = client.get("/sync/snapshot", headers=headers(workspace=workspace)).json()
        assert len(snap["accounts"]) == 2
        assert len(snap["transactions"]) > 0


def test_migration_rerun_preserves_values(engine):
    cat = add_category(engine, (1, "default-1"), "Não alterar")
    migrate_workspaces(engine)
    migrate_workspaces(engine)
    with Session(engine) as db:
        assert db.get(Category, cat).name == "Não alterar"
        assert db.query(Workspace).count() == 3


def test_accounts_response_and_cross_workspace_update(client):
    r = client.post("/accounts", headers=headers(), json={"institution_name": "Conta pessoal"})
    assert r.status_code == 200, r.text
    account_id = r.json()["id"]
    assert r.json()["workspace_id"] == "default-1"
    assert client.get("/accounts", headers=headers()).status_code == 200
    assert client.get("/accounts", headers=headers(workspace="company")).json() == []
    r = client.patch(f"/accounts/{account_id}", headers=headers(workspace="company"), json={"institution_name": "Intruso"})
    assert r.status_code == 404


def test_goals_budgets_and_idempotency_are_partitioned(client):
    for workspace in ("default-1", "company"):
        h = headers(workspace=workspace)
        cat = client.post("/categories", headers=h, json={"name": "Mesma categoria"}).json()
        budget = client.put(f'/budgets/{cat["id"]}', headers=h, json={"amount": 500})
        assert budget.status_code == 200, budget.text
        goal = client.post("/goals", headers=h, json={"name": "Reserva", "target_amount": 1000}).json()
        for _ in range(2):
            contribution = client.post(f'/goals/{goal["id"]}/contributions', headers=h,
                json={"amount": 50, "client_key": "same-offline-key"})
            assert contribution.status_code == 200, contribution.text
        assert len(client.get(f'/goals/{goal["id"]}/contributions', headers=h).json()) == 1
    assert client.put(f'/budgets/{cat["id"]}', headers=headers(), json={"amount": 20}).status_code == 404


def test_reset_deletes_only_current_workspace(client, engine):
    from app.models.user_data_deletion_code import UserDataDeletionCode
    from app.routers.user_data import _code_hash
    from datetime import timedelta
    personal = add_category(engine, (1, "default-1"))
    company = add_category(engine, (1, "company"))
    with Session(engine) as db:
        bind_workspace(db, 1, "company")
        db.add(UserDataDeletionCode(user_id=1, code_hash=_code_hash(1, "123456"),
            expires_at=datetime.now(timezone.utc)+timedelta(minutes=10)))
        db.commit()
    # No email is sent: the test seeds its own verification code.
    r = client.post('/user-data/confirm-delete-all', headers=headers(workspace="company"), json={"code":"123456"})
    assert r.status_code == 200, r.text
    with Session(engine) as db:
        assert db.get(Category, personal) is not None
        assert db.get(Category, company) is None
        assert db.query(FinancialResetMarker).one().workspace_id == "company"


def test_scheduler_derives_owner_from_persisted_connection(engine, monkeypatch):
    from app.workers import tasks
    calls = []
    monkeypatch.setattr(tasks, 'SessionLocal', lambda: Session(engine))
    monkeypatch.setattr(tasks.sync_openfinance, 'delay', lambda id: calls.append(id))
    for workspace in ("default-1", "company"):
        with Session(engine) as db:
            bind_workspace(db, 1, workspace)
            db.add(OpenFinanceConnection(user_id=1, provider="belvo", status="active"))
            db.commit()
    result = tasks.enqueue_due_syncs()
    assert result['queued'] == 2
    assert len(calls) == 2
    assert tasks.enqueue_due_syncs()['queued'] == 0


def test_belvo_link_from_another_workspace_is_rejected(client, monkeypatch):
    from app.routers import openfinance
    from app.services.openfinance_provider import BelvoProvider
    class FakeProvider(BelvoProvider):
        def __init__(self): pass
        def get_link(self, link_id):
            return {"external_id": "user_1_workspace_company"}
    monkeypatch.setattr(openfinance, 'get_provider', lambda name: FakeProvider())
    r = client.post('/openfinance/belvo/register-link', headers=headers(), json={"link_id":"test-only"})
    assert r.status_code == 409
