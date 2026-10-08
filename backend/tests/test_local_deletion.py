"""No real email, user data or network. Real FastAPI routes and temporary SQLite."""
import os
os.environ.setdefault('DATABASE_URL', 'sqlite://')
os.environ.setdefault('JWT_SECRET', 'test-only-secret-not-for-production-000000000000')
from datetime import datetime, timedelta, timezone
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, select, text
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool
from app.database.database import Base, get_db
from app.models.local_deletion import LocalDeletionChallenge, LocalDeletionRate
from app.routers import local_deletion as route

@pytest.fixture
def env(monkeypatch):
    engine = create_engine('sqlite://', connect_args={'check_same_thread': False}, poolclass=StaticPool)
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, expire_on_commit=False)
    with engine.begin() as db:
        db.execute(text('CREATE TABLE untouched_financial_data (id INTEGER PRIMARY KEY, value TEXT)'))
        db.execute(text("INSERT INTO untouched_financial_data VALUES (1, 'preserve')"))
    app = FastAPI()
    app.include_router(route.router, prefix='/user-data')
    def db_session():
        with Session() as session: yield session
    app.dependency_overrides[get_db] = db_session
    sent = []
    clock = [datetime(2026, 10, 8, 12, 0, tzinfo=timezone.utc)]
    monkeypatch.setattr(route, 'utcnow', lambda: clock[0])
    monkeypatch.setattr(route.secrets, 'randbelow', lambda n: 7)
    monkeypatch.setattr(route, 'send_code', lambda email, code, ref, counts: sent.append((email, code, ref, counts)))
    with TestClient(app) as client: yield client, sent, clock, Session
    engine.dispose()

REQUEST = {'device_secret': 'a' * 64, 'email': 'owner@example.com', 'plan_hash': 'b' * 64,
           'operation': 'selected', 'counts': {'transactions': 3}}

def issue(env, **changes):
    client, sent, clock, Session = env
    data = dict(REQUEST); data.update(changes)
    response = client.post('/user-data/local-deletion/code', json=data)
    return response

def confirm(env, issued, **changes):
    payload = {'device_secret': REQUEST['device_secret'], 'challenge_id': issued.json()['challenge_id'],
               'plan_hash': REQUEST['plan_hash'], 'code': '0007'}
    payload.update(changes)
    return env[0].post('/user-data/local-deletion/confirm', json=payload)

def test_four_digits_and_lifetime_and_no_code_disclosed(env):
    r = issue(env)
    assert r.status_code == 200
    assert env[1][0][1] == '0007'
    assert r.json()['expires_in_seconds'] == 300
    assert '0007' not in r.text
    with env[3]() as db:
        row = db.get(LocalDeletionChallenge, r.json()['challenge_id'])
        assert row.code_hash != '0007'
        assert len(row.code_hash) == 64

def test_success_is_bound_to_plan_and_never_deletes_server_data(env):
    r = issue(env); result = confirm(env, r)
    assert result.status_code == 200
    assert result.json()['authorized'] is True
    assert result.json()['plan_hash'] == REQUEST['plan_hash']
    with env[3]() as db:
        assert db.execute(text('SELECT value FROM untouched_financial_data')).scalar_one() == 'preserve'

def test_code_cannot_be_reused(env):
    r = issue(env)
    assert confirm(env, r).status_code == 200
    assert confirm(env, r).status_code == 400

def test_wrong_code_and_five_attempt_limit(env):
    r = issue(env)
    for _ in range(5): assert confirm(env, r, code='1234').status_code == 400
    assert confirm(env, r).status_code == 429
    with env[3]() as db: assert db.get(LocalDeletionChallenge, r.json()['challenge_id']).attempts == 5

@pytest.mark.parametrize('code', ['', '123', '12345', '1a34', '123456'])
def test_code_format(env, code):
    r = issue(env)
    assert confirm(env, r, code=code).status_code == 422

def test_expiry_at_exact_boundary(env):
    r = issue(env); env[2][0] += timedelta(seconds=300)
    assert confirm(env, r).status_code == 400

def test_different_plan_cannot_reuse_code(env):
    r = issue(env)
    assert confirm(env, r, plan_hash='c' * 64).status_code == 400
    assert confirm(env, r).status_code == 200

def test_different_installation_cannot_use_code(env):
    r = issue(env)
    assert confirm(env, r, device_secret='c' * 64).status_code == 400
    assert confirm(env, r).status_code == 200

def test_resend_cooldown_and_old_code_invalidated(env):
    r = issue(env)
    assert issue(env).status_code == 429
    env[2][0] += timedelta(seconds=61)
    new = issue(env)
    assert new.status_code == 200
    assert confirm(env, r).status_code == 400
    assert confirm(env, new).status_code == 200

def test_verified_email_cannot_be_silently_changed(env):
    r = issue(env); assert confirm(env, r).status_code == 200
    env[2][0] += timedelta(seconds=61)
    assert issue(env, email='other@example.com').status_code == 403

def test_no_global_fixed_pin_even_if_same_four_digits_generated(env):
    r = issue(env)
    with env[3]() as db: h1 = db.get(LocalDeletionChallenge, r.json()['challenge_id']).code_hash
    env[2][0] += timedelta(seconds=61)
    r2 = issue(env)
    with env[3]() as db: h2 = db.get(LocalDeletionChallenge, r2.json()['challenge_id']).code_hash
    assert h1 != h2

def test_email_send_failure_invalidates_code_and_hides_smtp_secrets(env, monkeypatch):
    def broken(*args): raise RuntimeError('smtp_password=never-expose-this')
    monkeypatch.setattr(route, 'send_code', broken)
    r = issue(env)
    assert r.status_code == 503
    assert 'never-expose' not in r.text
    with env[3]() as db:
        row = db.scalar(select(LocalDeletionChallenge))
        assert row.used_at is not None
        assert not row.sent
        cid = row.id
    r2 = env[0].post('/user-data/local-deletion/confirm', json={'device_secret': 'a'*64,
        'challenge_id': cid, 'plan_hash': 'b'*64, 'code': '0007'})
    assert r2.status_code == 400

def test_email_rate_limit_persists_across_new_database_sessions(env):
    for i in range(3):
        assert issue(env, device_secret=str(i+1)*64).status_code == 200
        env[2][0] += timedelta(seconds=61)
    assert issue(env, device_secret='4'*64).status_code == 429
    assert len(env[1]) == 3

@pytest.mark.parametrize('counts', [{'not_allowed':1}, {'cards':-1}, {'transactions':10_000_001}])
def test_count_validation(env, counts):
    assert issue(env, counts=counts).status_code == 400
    assert not env[1]

def test_no_client_bypass_flags(env):
    assert issue(env, verified=True).status_code == 422
