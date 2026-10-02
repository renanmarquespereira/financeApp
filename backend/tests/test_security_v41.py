from datetime import datetime, timedelta, timezone
from sqlalchemy.orm import Session
from sqlalchemy import select
from app.models import User, Category, Workspace
from app.models.security_challenge import SecurityChallenge, DeletedWorkspace
from app.core.security import hash_password, create_access_token, create_refresh_token
from app.services.security_codes import digest
from test_workspaces import engine, client, headers, add_category
from test_workspace_management import create


def mailbox(monkeypatch):
    sent = []
    def send(recipient, code, subject, action): sent.append((recipient, code, subject, action))
    monkeypatch.setattr('app.services.email.send_security_code', send)
    monkeypatch.setattr('app.routers.password_reset.send_security_code', send)
    return sent


def archive(client, id):
    assert client.post(f'/workspaces/{id}/archive', headers=headers()).status_code == 200


def test_delete_requires_archived_owner_and_email_code(client, engine, monkeypatch):
    sent = mailbox(monkeypatch)
    row = create(client); id = row['id']
    own = add_category(engine, (1, id)); keep = add_category(engine, (1, 'default-1')); other = add_category(engine, (2, 'default-2'))
    path = f'/workspaces/{id}'
    assert client.post(path+'/delete-code', headers=headers()).status_code == 409
    archive(client,id)
    assert client.post(path+'/delete-code', headers=headers(2)).status_code == 404
    assert client.post('/workspaces/default-1/delete-code', headers=headers()).status_code == 409
    assert client.post(path+'/confirm-delete', headers=headers(), json={'code':'000000'}).status_code == 400
    assert client.post(path+'/delete-code', headers=headers()).status_code == 200
    assert sent[0][0] == 'one@example.org'
    assert client.post(path+'/delete-code', headers=headers()).status_code == 429
    result = client.post(path+'/confirm-delete', headers=headers(), json={'code':sent[0][1]})
    assert result.status_code == 200, result.text
    with Session(engine) as db:
        assert db.get(Category,own) is None
        assert db.get(Category,keep) and db.get(Category,other)
        assert db.get(User,1) and db.get(Workspace,id) is None
        assert db.get(DeletedWorkspace,id)
        assert db.get(SecurityChallenge,f'workspace-delete:1:{id}') is None
    assert client.post(path+'/confirm-delete', headers=headers(), json={'code':sent[0][1]}).status_code == 404
    assert client.post('/workspaces',headers=headers(),json={'client_id':id,'name':'Empresa','kind':'business'}).status_code == 409


def test_delete_code_bound_to_workspace_and_restore_invalidates(client, engine, monkeypatch):
    sent = mailbox(monkeypatch)
    a,b=create(client)['id'],create(client)['id']
    for id in (a,b):
        archive(client,id)
        assert client.post(f'/workspaces/{id}/delete-code',headers=headers()).status_code == 200
    # Deterministic wrong code (no random collision).
    with Session(engine) as db:
        db.get(SecurityChallenge,f'workspace-delete:1:{b}').code_hash=digest(f'workspace-delete:1:{b}','654321');db.commit()
    code = '123456'
    assert client.post(f'/workspaces/{b}/confirm-delete',headers=headers(),json={'code':code}).status_code == 400
    assert client.post(f'/workspaces/{a}/restore',headers=headers()).status_code == 200
    archive(client,a)
    assert client.post(f'/workspaces/{a}/confirm-delete',headers=headers(),json={'code':sent[0][1]}).status_code == 400


def test_reset_changes_password_consumes_code_and_revokes_sessions(client, engine, monkeypatch):
    sent=mailbox(monkeypatch)
    with Session(engine) as db:
        db.get(User,1).password_hash=hash_password('old-password');db.commit()
    old_access=headers(); old_refresh=create_refresh_token(1)
    assert client.post('/auth/forgot-password',json={'email':'ONE@example.org'}).status_code == 200
    assert sent[0][0]=='one@example.org'
    body={'email':'one@example.org','code':sent[0][1],'password':'new-password'}
    assert client.post('/auth/reset-password',json=body).status_code == 200
    assert client.post('/auth/reset-password',json=body).status_code == 400
    assert client.get('/workspaces',headers=old_access).status_code == 401
    assert client.post('/auth/refresh',json={'refresh_token':old_refresh}).status_code == 401
    assert client.post('/auth/login',json={'email':body['email'],'password':'old-password'}).status_code == 401
    login=client.post('/auth/login',json={'email':body['email'],'password':body['password']})
    assert login.status_code == 200,login.text
    assert client.get('/workspaces',headers={'Authorization':'Bearer '+login.json()['access_token']}).status_code == 200
    assert client.post('/auth/refresh',json={'refresh_token':login.json()['refresh_token']}).status_code == 200


def test_reset_generic_response_cooldown_attempts_expiry_and_resend(client,engine,monkeypatch):
    sent=mailbox(monkeypatch)
    generic=client.post('/auth/forgot-password',json={'email':'unknown@example.org'}).json()
    assert not sent
    assert client.post('/auth/forgot-password',json={'email':'one@example.org'}).json()==generic
    assert client.post('/auth/forgot-password',json={'email':'one@example.org'}).json()==generic
    assert len(sent)==1
    with Session(engine) as db:
        db.get(SecurityChallenge,'password:1').code_hash=digest('password:1','123456');db.commit()
    body={'email':'one@example.org','code':'000000','password':'new-pass'}
    for _ in range(5): assert client.post('/auth/reset-password',json=body).status_code==400
    body['code']='123456'
    assert client.post('/auth/reset-password',json=body).status_code==429
    with Session(engine) as db:
        db.get(SecurityChallenge,'password:1').created_at=datetime.now(timezone.utc)-timedelta(minutes=2);db.commit()
    client.post('/auth/forgot-password',json={'email':'one@example.org'})
    with Session(engine) as db:
        row=db.get(SecurityChallenge,'password:1');assert row.attempts==0
        row.expires_at=datetime.now(timezone.utc)-timedelta(seconds=1);db.commit()
    body['code']=sent[-1][1]
    assert client.post('/auth/reset-password',json=body).status_code==400


def test_smtp_failure_does_not_leave_valid_challenge(client,engine,monkeypatch):
    def fail(*a,**k): raise RuntimeError('private SMTP details')
    monkeypatch.setattr('app.services.email.send_security_code',fail)
    monkeypatch.setattr('app.routers.password_reset.send_security_code',fail)
    id=create(client)['id'];archive(client,id)
    result=client.post(f'/workspaces/{id}/delete-code',headers=headers())
    assert result.status_code==503 and 'private SMTP' not in result.text
    assert client.post('/auth/forgot-password',json={'email':'one@example.org'}).status_code==200
    with Session(engine) as db: assert db.query(SecurityChallenge).count()==0


def test_delete_preserves_data_on_provider_failure_then_retries(client,engine,monkeypatch):
    from app.models import OpenFinanceConnection, OpenFinanceSyncLog, BankAccount, Transaction
    from app.core.workspace import bind_workspace
    from sqlalchemy import text
    sent=mailbox(monkeypatch)
    id=create(client)['id']
    with engine.connect() as connection:
        connection.execute(text('PRAGMA foreign_keys=ON'))
    with Session(engine) as db:
        bind_workspace(db,1,id)
        account=BankAccount(user_id=1,institution_name='Teste')
        db.add(account);db.flush()
        db.add(Transaction(user_id=1,account_id=account.id,date=datetime.now(timezone.utc),description='Manter',amount=-5))
        link=OpenFinanceConnection(user_id=1,provider='belvo',external_connection_id='mock-link')
        db.add(link);db.flush()
        db.add(OpenFinanceSyncLog(user_id=1,connection_id=link.id,status='ok'))
        db.commit()
    archive(client,id)
    client.post(f'/workspaces/{id}/delete-code',headers=headers())
    class Provider:
        fail=True
        def delete_link(self,link):
            assert link=='mock-link'
            if self.fail: raise RuntimeError('unavailable')
            return True
    provider=Provider()
    monkeypatch.setattr('app.services.openfinance_provider.get_provider',lambda _:provider)
    path=f'/workspaces/{id}/confirm-delete';body={'code':sent[0][1]}
    assert client.post(path,headers=headers(),json=body).status_code==503
    with Session(engine) as db:
        assert db.get(Workspace,id) and db.query(Transaction).count()==1
        assert db.query(OpenFinanceConnection).count()==1
    provider.fail=False
    result=client.post(path,headers=headers(),json=body)
    assert result.status_code==200,result.text
    with Session(engine) as db:
        for model in (Transaction,BankAccount,OpenFinanceConnection,OpenFinanceSyncLog):
            assert db.query(model).count()==0


def test_additive_schema_upgrade_preserves_v40_data():
    from sqlalchemy import create_engine, inspect
    from app.database.database import Base
    e=create_engine('sqlite://')
    new={'security_challenges','auth_session_versions','deleted_workspaces'}
    Base.metadata.create_all(e,tables=[t for t in Base.metadata.sorted_tables if t.name not in new])
    with Session(e) as db:
        db.add(User(id=55,email='preserved@example.org'));db.commit()
    Base.metadata.create_all(e)
    Base.metadata.create_all(e)
    assert new.issubset(set(inspect(e).get_table_names()))
    with Session(e) as db:
        assert db.get(User,55).email=='preserved@example.org'
        assert db.get(Workspace,'default-55')
    e.dispose()
