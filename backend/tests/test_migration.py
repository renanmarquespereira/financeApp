from pathlib import Path
import pytest
from sqlalchemy import create_engine, text
from app.database.workspace_migration import migrate_workspaces, TABLES


def legacy_engine(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'legacy.db'}")
    sql = (Path(__file__).parent / 'fixtures/v38_schema.sql').read_text(encoding='utf-8')
    with engine.connect() as c:
        c.connection.driver_connection.executescript(sql)
        c.commit()
    with engine.begin() as c:
        c.execute(text("INSERT INTO users(id,email) VALUES(7,'legacy@example.org')"))
        c.execute(text("INSERT INTO categories(id,user_id,name) VALUES(3,7,'Histórico')"))
        c.execute(text("INSERT INTO bank_accounts(id,user_id,institution_name,external_account_id,current_balance) VALUES(2,7,'Banco','external-2',1234.56)"))
        c.execute(text("""INSERT INTO transactions(id,user_id,account_id,category_id,date,description,amount,source,transaction_type,status)
            VALUES(8,7,2,3,CURRENT_TIMESTAMP,'Preservar',-123.45,'manual','debit','posted')"""))
        c.execute(text("INSERT INTO financial_reset_markers(user_id,reset_at) VALUES(7,'2026-01-01')"))
        c.execute(text("INSERT INTO user_sync_states(user_id,last_full_sync_at,updated_at) VALUES(7,'2026-08-01','2026-08-01')"))
        c.execute(text("INSERT INTO ignored_accounts(id,user_id,external_account_id,ignored_at) VALUES(1,7,'removed','2026-08-01')"))
        c.execute(text("""INSERT INTO open_finance_connections(id,user_id,provider,status,sync_interval_minutes,mock_sequence,created_at)
             VALUES(5,7,'mock','active',1,0,'2026-08-01')"""))
        c.execute(text("INSERT INTO open_finance_sync_logs(id,connection_id,status,imported_transactions,started_at) VALUES(1,5,'success',1,'2026-08-01')"))
    return engine


def test_upgrade_existing_database_preserves_values_and_ids(tmp_path):
    e = legacy_engine(tmp_path)
    with e.connect() as c:
        before = {t: c.execute(text(f'SELECT * FROM {t}')).mappings().all() for t in TABLES}
    migrate_workspaces(e)
    migrate_workspaces(e)
    with e.connect() as c:
        for table, rows in before.items():
            after = c.execute(text(f'SELECT * FROM {table}')).mappings().all()
            assert len(after) == len(rows)
            for old, new in zip(rows, after):
                assert all(new[key] == value for key, value in old.items()), table
                assert new['workspace_id'] == 'default-7'
                assert new['user_id'] == 7
        assert c.execute(text("SELECT name FROM workspaces")).scalar_one() == 'Pessoal'
        assert not c.exec_driver_sql('PRAGMA foreign_key_check').all()
    e.dispose()


def test_invalid_reference_aborts_without_reassigning_data(tmp_path):
    e = legacy_engine(tmp_path)
    with e.begin() as c:
        c.execute(text("UPDATE transactions SET category_id=999"))
    with pytest.raises(RuntimeError, match='invalid transactions.category_id'):
        migrate_workspaces(e)
    with e.connect() as c:
        assert c.execute(text('SELECT category_id,amount FROM transactions')).one() == (999, -123.45)
        # SQLite DDL differs from PostgreSQL, but no migration may be marked complete.
        assert c.execute(text('SELECT COUNT(*) FROM finance_schema_migrations')).scalar_one() == 0
    e.dispose()
