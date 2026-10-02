from copy import deepcopy
from uuid import uuid4
from sqlalchemy.orm import Session
from app.models import Workspace, Transaction, User, Category, BankAccount, FinancialGoal, GoalContribution
from app.routers.guest_import import GuestImportReceipt
from test_workspaces import engine, client, headers, add_category


def snapshot():
    return {'transferId':str(uuid4()),
        'accounts':[{'id':-1,'institutionName':'Carteira'}],
        'categories':[{'id':-2,'name':'Café'}],
        'cards':[{'id':-3,'bankName':'Manual','brand':'Visa','lastFour':'1234'}],
        'goals':[{'id':-4,'name':'Viagem','targetAmount':1000,'currentAmount':30,'targetDate':'2027-01-01'}],
        'contributions':[{'id':-5,'goalId':-4,'amount':10,'createdAt':'2026-09-06T12:00:00Z','clientKey':'guest-test'}],
        'budgets':[{'categoryId':-2,'amount':100}],
        'transactions':[{'id':-6,'accountId':-1,'categoryId':-2,'cardId':-3,'date':'2026-09-06T12:00:00Z',
            'description':'Café','amount':-12.5,'transactionType':'debit'}]}


def test_guest_transfer_preserves_references_and_existing_data(client,engine):
    existing=add_category(engine,(1,'default-1'),'Existente')
    data=snapshot()
    response=client.post('/guest-import',headers=headers(),json=data)
    assert response.status_code==200,response.text
    with Session(engine) as db:
        assert db.get(Category,existing).name=='Existente'
        transaction=db.query(Transaction).one()
        assert transaction.workspace_id==data['transferId']
        assert transaction.account_id>0 and transaction.category_id!=existing and transaction.card_id>0
        assert db.get(BankAccount,transaction.account_id).institution_name=='Carteira'
        assert db.query(FinancialGoal).one().current_amount==30
        assert db.query(GoalContribution).one().goal_id==db.query(FinancialGoal).one().id
        assert db.get(User,1)


def test_retry_is_idempotent_and_rejects_different_payload_or_owner(client,engine):
    data=snapshot()
    first=client.post('/guest-import',headers=headers(),json=data)
    assert first.status_code==200,first.text
    assert client.post('/guest-import',headers=headers(),json=data).json()==first.json()
    assert client.post('/guest-import',headers=headers(2),json=data).status_code==409
    changed=deepcopy(data);changed['transactions'][0]['amount']=-90
    assert client.post('/guest-import',headers=headers(),json=changed).status_code==409
    with Session(engine) as db:
        assert db.query(Transaction).count()==1
        assert db.query(GuestImportReceipt).count()==1


def test_invalid_reference_rolls_back_entire_transfer(client,engine):
    data=snapshot();data['transactions'][0]['categoryId']=-999
    assert client.post('/guest-import',headers=headers(),json=data).status_code==422
    with Session(engine) as db:
        assert db.get(Workspace,data['transferId']) is None
        assert db.query(Transaction).count()==0 and db.query(Category).count()==0
        assert db.query(GuestImportReceipt).count()==0


def test_transfer_requires_login_and_rejects_duplicate_ids(client):
    data=snapshot()
    assert client.post('/guest-import',json=data).status_code in (401,403)
    data['categories'].append(deepcopy(data['categories'][0]))
    assert client.post('/guest-import',headers=headers(),json=data).status_code==422


def test_archived_import_is_not_duplicated(client,engine):
    data=snapshot()
    assert client.post('/guest-import',headers=headers(),json=data).status_code==200
    assert client.post('/workspaces/'+data['transferId']+'/archive',headers=headers()).status_code==200
    assert client.post('/guest-import',headers=headers(),json=data).status_code==409
    with Session(engine) as db:assert db.query(Transaction).count()==1
