from uuid import uuid4
from sqlalchemy.orm import Session
from app.models import Workspace, Category
from app.core.workspace import bind_workspace
from test_workspaces import engine, client, headers


def create(client, name="Empresa", kind="business", user=1, id=None):
    response = client.post('/workspaces', headers=headers(user), json={
        'client_id': id or str(uuid4()), 'name': name, 'kind': kind})
    assert response.status_code == 201, response.text
    return response.json()


def test_create_edit_and_select_isolated_finances(client):
    row = create(client)
    assert row['user_id'] == 1 and not row['is_default']
    h = headers(workspace=row['id'])
    assert client.get('/sync/snapshot', headers=h).json()['counts']['transactions'] == 0
    cat = client.post('/categories', headers=h, json={'name': 'Empresa'}).json()
    assert cat['workspace_id'] == row['id']
    assert client.get('/categories', headers=headers()).json() == []
    changed = client.patch('/workspaces/'+row['id'], headers=headers(), json={'name':'Esposa','kind':'family'})
    assert changed.status_code == 200
    assert changed.json()['name'] == 'Esposa'
    assert client.get('/categories', headers=h).json()[0]['id'] == cat['id']


def test_creation_retry_returns_same_workspace(client):
    id = str(uuid4())
    first = create(client, id=id)
    second = create(client, id=id)
    assert first == second
    assert len([w for w in client.get('/workspaces', headers=headers()).json() if w['id'] == id]) == 1
    assert client.post('/workspaces', headers=headers(), json={'client_id':id,'name':'Outro','kind':'other'}).status_code == 409


def test_archive_restore_preserves_records_and_rejects_financial_access(client, engine):
    row = create(client)
    h = headers(workspace=row['id'])
    cat = client.post('/categories', headers=h, json={'name':'Manter'}).json()
    for _ in range(2):
        assert client.post('/workspaces/'+row['id']+'/archive', headers=headers()).status_code == 200
    assert client.get('/categories', headers=h).status_code == 404
    assert client.post('/categories', headers=h, json={'name':'Negar'}).status_code == 404
    with Session(engine) as db:
        assert db.get(Category, cat['id']).name == 'Manter'
    assert client.post('/workspaces/'+row['id']+'/restore', headers=headers()).status_code == 200
    assert client.get('/categories', headers=h).json()[0]['id'] == cat['id']


def test_default_cannot_be_archived_but_can_be_renamed(client):
    assert client.post('/workspaces/default-1/archive', headers=headers()).status_code == 409
    assert client.patch('/workspaces/default-1', headers=headers(), json={'name':'Renan - Pessoal','kind':'personal'}).status_code == 200


def test_other_user_cannot_manage_workspace(client):
    row = create(client)
    h = headers(2)
    assert all(w['id'] != row['id'] for w in client.get('/workspaces', headers=h).json())
    assert client.patch('/workspaces/'+row['id'], headers=h, json={'name':'Intruso','kind':'other'}).status_code == 404
    for action in ('archive','restore'):
        assert client.post('/workspaces/'+row['id']+'/'+action, headers=h).status_code == 404
    assert client.post('/workspaces', headers=h, json={'client_id':row['id'],'name':'Empresa','kind':'business'}).status_code == 404


def test_invalid_inputs_and_archived_edit(client):
    for name,kind in [('   ','personal'), ('X'*161,'business'), ('Nome','invalid')]:
        assert client.post('/workspaces', headers=headers(), json={'client_id':str(uuid4()),'name':name,'kind':kind}).status_code == 422
    row=create(client, name='  Família  ',kind='family')
    assert row['name']=='Família'
    client.post('/workspaces/'+row['id']+'/archive', headers=headers())
    assert client.patch('/workspaces/'+row['id'], headers=headers(), json={'name':'Novo','kind':'other'}).status_code == 409
