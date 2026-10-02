"""Execute the exact Room migration SQL and DAO SQL using SQLite.

Complements (does not replace) the instrumented Room schema-validation tests.
"""
from pathlib import Path
import re
import sqlite3

ANDROID = Path(__file__).resolve().parents[2] / 'android/app/src'
LOCAL = ANDROID / 'main/java/com/financeapp/mobile/data/local'


def migrated():
    db = sqlite3.connect(':memory:')
    test = (ANDROID / 'androidTest/java/com/financeapp/mobile/data/local/WorkspaceMigrationTest.kt').read_text(encoding='utf-8')
    for sql in re.findall(r'legacy.execSQL\("([^"\n]+)"\)', test):
        db.execute(sql)
    for sql in re.findall(r'db.execSQL\("([^"\n]+)"', (LOCAL/'WorkspaceMigration.kt').read_text(encoding='utf-8')):
        db.execute(sql, (7, 'default-7') if '?' in sql else ())
    return db


def queries():
    return re.findall(r'@Query\("""([\s\S]*?)"""\)\s*(?:suspend )?fun (\w+)\(', (LOCAL/'Dao.kt').read_text())


def test_room_sql_preserves_pending_and_primary_keys():
    db = migrated()
    assert db.execute('SELECT userId,workspaceId,id,amount FROM transactions').fetchone() == (7,'default-7',-12,-25.5)
    assert db.execute('SELECT id,entityId FROM pending_sync_operations').fetchone() == (8,99)
    db.execute("INSERT INTO categories VALUES(-11,'Other',NULL,'PENDING_CREATE',7,'company')")
    assert db.execute('SELECT COUNT(*) FROM categories').fetchone()[0] == 2
    db.close()


def test_every_dao_query_parses_and_is_partitioned():
    db = migrated()
    for sql, name in queries():
        params = {key: 0 for key in re.findall(r':(\w+)', sql)}
        params.update(scopeUserId=7, scopeWorkspaceId='company')
        assert 'scopeUserId' in sql and 'scopeWorkspaceId' in sql, name
        # Compile all queries, including aggregates, remaps and bulk clears.
        db.execute('EXPLAIN '+sql, params).fetchall()
        if sql.lstrip().startswith('DELETE'):
            db.execute(sql, params)
    assert db.execute('SELECT COUNT(*) FROM transactions').fetchone()[0] == 1
    assert db.execute('SELECT COUNT(*) FROM pending_sync_operations').fetchone()[0] == 1
    db.close()
