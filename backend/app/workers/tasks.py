from datetime import datetime, timedelta, timezone
from app.workers.celery_app import celery_app
from app.database.database import SessionLocal
from app.models.openfinance import OpenFinanceConnection
from app.models.workspace import Workspace
from app.core.workspace import bind_workspace
from app.services.openfinance_sync import sync_connection


@celery_app.task(name="app.workers.tasks.sync_openfinance", autoretry_for=(Exception,), retry_backoff=True, retry_kwargs={"max_retries": 3})
def sync_openfinance(connection_id: int):
    with SessionLocal() as db:
        return sync_connection(db, connection_id)


def _discover(mock: bool):
    with SessionLocal() as db:
        query = db.query(OpenFinanceConnection.id, OpenFinanceConnection.user_id, OpenFinanceConnection.workspace_id).join(
            Workspace, Workspace.id == OpenFinanceConnection.workspace_id
        ).filter(OpenFinanceConnection.status == "active", Workspace.archived_at.is_(None))
        query = query.filter(OpenFinanceConnection.provider == "mock" if mock else OpenFinanceConnection.provider != "mock")
        return query.all()


@celery_app.task(name="app.workers.tasks.enqueue_due_syncs")
def enqueue_due_syncs():
    now = datetime.now(timezone.utc)
    ids = []
    for connection_id, user_id, workspace_id in _discover(mock=False):
        with SessionLocal() as db:
            bind_workspace(db, user_id, workspace_id)
            row = db.query(OpenFinanceConnection).filter(
                OpenFinanceConnection.id == connection_id,
                OpenFinanceConnection.status == "active",
                OpenFinanceConnection.next_sync_at.is_(None) | (OpenFinanceConnection.next_sync_at <= now),
            ).with_for_update(skip_locked=True).first()
            if row is None:
                continue
            row.next_sync_at = now + timedelta(minutes=max(int(row.sync_interval_minutes or 60), 5))
            db.commit()
            ids.append(row.id)
        sync_openfinance.delay(connection_id)
    return {"queued": len(ids), "connections": ids}


@celery_app.task(name="app.workers.tasks.generate_mock_activity")
def generate_mock_activity():
    count = 0
    for connection_id, user_id, workspace_id in _discover(mock=True):
        with SessionLocal() as db:
            bind_workspace(db, user_id, workspace_id)
            row = db.query(OpenFinanceConnection).filter(
                OpenFinanceConnection.id == connection_id,
                OpenFinanceConnection.status == "active",
            ).with_for_update(skip_locked=True).first()
            if row is None:
                continue
            row.mock_sequence = int(row.mock_sequence or 0) + 1
            db.commit()
        sync_openfinance.delay(connection_id)
        count += 1
    return {"generated": count}
