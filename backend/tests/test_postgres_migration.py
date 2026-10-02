"""Optional PostgreSQL 17 integration: set V39_TEST_POSTGRES_URL to a TEST server.

Uses a unique isolated schema, never the public schema, and removes only that schema.
"""
import os
from pathlib import Path
from uuid import uuid4
import pytest
from sqlalchemy import create_engine, text, inspect
from app.database.workspace_migration import migrate_workspaces


@pytest.mark.skipif(not os.getenv('V39_TEST_POSTGRES_URL'), reason='PostgreSQL test server not configured')
def test_postgres_upgrade_and_constraints():
    schema = 'v39_test_' + uuid4().hex
    admin = create_engine(os.environ['V39_TEST_POSTGRES_URL'])
    assert admin.dialect.name == 'postgresql'
    with admin.begin() as c:
        c.execute(text(f'CREATE SCHEMA "{schema}"'))
    test_engine = create_engine(os.environ['V39_TEST_POSTGRES_URL'],
        connect_args={'options': f'-csearch_path={schema}'})
    try:
        fixture = (Path(__file__).parent/'fixtures/v38_postgres_schema.sql').read_text()
        with test_engine.begin() as c:
            for statement in fixture.split(';'):
                if statement.strip(): c.execute(text(statement))
            c.execute(text("INSERT INTO users(id,email) VALUES(7,'legacy@example.org')"))
            c.execute(text("INSERT INTO categories(id,user_id,name) VALUES(3,7,'Original')"))
            c.execute(text("INSERT INTO bank_accounts(id,user_id,institution_name,current_balance) VALUES(2,7,'Banco',1234.56)"))
            c.execute(text("INSERT INTO user_sync_states(user_id,updated_at) VALUES(7,CURRENT_TIMESTAMP)"))
        migrate_workspaces(test_engine)
        migrate_workspaces(test_engine)
        with test_engine.begin() as c:
            assert c.execute(text('SELECT workspace_id FROM bank_accounts')).scalar_one() == 'default-7'
            assert str(c.execute(text('SELECT current_balance FROM bank_accounts')).scalar_one()) == '1234.56'
            assert set(inspect(c).get_pk_constraint('user_sync_states')['constrained_columns']) == {'user_id','workspace_id'}
            c.execute(text("INSERT INTO workspaces(id,user_id,name,kind,is_default,created_at) VALUES('company',7,'Empresa','business',FALSE,CURRENT_TIMESTAMP)"))
        with pytest.raises(Exception):
            with test_engine.begin() as c:
                c.execute(text("""INSERT INTO transactions(user_id,workspace_id,category_id,date,description,amount,source,transaction_type,status)
                   VALUES(7,'company',3,CURRENT_TIMESTAMP,'invalid',1,'manual','debit','posted')"""))
        with pytest.raises(Exception):
            with test_engine.begin() as c:
                c.execute(text("UPDATE categories SET workspace_id='company' WHERE id=3"))
    finally:
        test_engine.dispose()
        with admin.begin() as c:
            c.execute(text(f'DROP SCHEMA "{schema}" CASCADE'))
        admin.dispose()
