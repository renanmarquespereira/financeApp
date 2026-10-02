"""Transactional, repeatable v39 migration. Run with API/worker/beat stopped.

PostgreSQL is the production target. SQLite support exists for migration tests.
No financial values, IDs, or deletion markers are rewritten.
"""
from sqlalchemy import inspect, text
from sqlalchemy.schema import CreateTable, CreateIndex
from app.database.database import Base
from app.models.workspace import Workspace


TABLES = (
    "bank_accounts", "credit_cards", "categories", "transactions",
    "financial_goals", "goal_contributions", "category_budgets",
    "open_finance_connections", "open_finance_sync_logs", "ignored_accounts",
    "ignored_transactions", "account_deletion_codes", "user_data_deletion_codes",
    "user_sync_states", "financial_reset_markers",
)
COMPOSITE = ("user_sync_states", "financial_reset_markers")
UNIQUE_CHANGES = {
    "bank_accounts": ("external_account_id", ["user_id", "workspace_id", "external_account_id"]),
    "goal_contributions": ("client_key", ["user_id", "workspace_id", "client_key"]),
    "ignored_accounts": ("external_account_id", ["user_id", "workspace_id", "external_account_id"]),
    "category_budgets": ("category_id", ["user_id", "workspace_id", "category_id"]),
}


def migrate_workspaces(engine):
    if engine.dialect.name not in ("postgresql", "sqlite"):
        raise RuntimeError("v39 requires PostgreSQL (or SQLite for tests)")
    with engine.connect() as c:
        sqlite = engine.dialect.name == "sqlite"
        if sqlite:
            c.exec_driver_sql("PRAGMA foreign_keys=OFF")
            c.commit()
        try:
            with c.begin():
                if not sqlite:
                    c.execute(text("SELECT pg_advisory_xact_lock(3900382)"))
                c.execute(text("CREATE TABLE IF NOT EXISTS finance_schema_migrations (version INTEGER PRIMARY KEY)"))
                if c.execute(text("SELECT version FROM finance_schema_migrations WHERE version=39")).first():
                    return
                Workspace.__table__.create(c, checkfirst=True)
                c.execute(text("""INSERT INTO workspaces (id, user_id, name, kind, is_default, created_at)
                    SELECT 'default-' || CAST(id AS VARCHAR), id, 'Pessoal', 'personal', TRUE, CURRENT_TIMESTAMP
                    FROM users WHERE NOT EXISTS (SELECT 1 FROM workspaces w WHERE w.id = 'default-' || CAST(users.id AS VARCHAR))"""))
                for name in TABLES:
                    cols = {col["name"] for col in inspect(c).get_columns(name)}
                    if "workspace_id" not in cols:
                        c.execute(text(f'ALTER TABLE "{name}" ADD COLUMN workspace_id VARCHAR(64)'))
                    if name == "open_finance_sync_logs":
                        if "user_id" not in cols:
                            c.execute(text(f'ALTER TABLE "{name}" ADD COLUMN user_id INTEGER'))
                        c.execute(text("""UPDATE open_finance_sync_logs SET
                            user_id = (SELECT user_id FROM open_finance_connections p WHERE p.id=connection_id),
                            workspace_id = (SELECT workspace_id FROM open_finance_connections p WHERE p.id=connection_id)
                            WHERE workspace_id IS NULL"""))
                    else:
                        c.execute(text(f'''UPDATE "{name}" SET workspace_id = 'default-' || CAST(user_id AS VARCHAR)
                            WHERE workspace_id IS NULL'''))
                    invalid = c.execute(text(f'''SELECT COUNT(*) FROM "{name}" t LEFT JOIN workspaces w
                        ON w.id=t.workspace_id AND w.user_id=t.user_id WHERE w.id IS NULL''')).scalar_one()
                    if invalid:
                        raise RuntimeError(f"v39: {invalid} orphan rows in {name}; restore/fix ownership before migration")

                # Validate all old cross-entity ownership before installing constraints.
                for name in TABLES:
                    for col, parent, target in references(name):
                        invalid = c.execute(text(f'''SELECT COUNT(*) FROM "{name}" t LEFT JOIN "{parent}" p
                            ON p."{target}"=t."{col}" AND p.user_id=t.user_id AND p.workspace_id=t.workspace_id
                            WHERE t."{col}" IS NOT NULL AND p."{target}" IS NULL''')).scalar_one()
                        if invalid:
                            raise RuntimeError(f"v39: invalid {name}.{col} references ({invalid}); no changes committed")

                if sqlite:
                    # Copy every column into the exact new model schema, preserving IDs.
                    for name in TABLES:
                        table = Base.metadata.tables[name]
                        temp = name + "_v39"
                        ddl = str(CreateTable(table).compile(dialect=c.dialect))
                        ddl = ddl.replace(f"CREATE TABLE {name} ", f"CREATE TABLE {temp} ", 1)
                        c.exec_driver_sql(ddl)
                        columns = ", ".join(f'"{col.name}"' for col in table.columns)
                        c.exec_driver_sql(f'INSERT INTO "{temp}" ({columns}) SELECT {columns} FROM "{name}"')
                        c.exec_driver_sql(f'DROP TABLE "{name}"')
                        c.exec_driver_sql(f'ALTER TABLE "{temp}" RENAME TO "{name}"')
                        for index in table.indexes:
                            c.execute(CreateIndex(index))
                    for name in TABLES:
                        install_sqlite_guards(c, name)
                    errors = c.exec_driver_sql("PRAGMA foreign_key_check").fetchall()
                    if errors:
                        raise RuntimeError(f"v39 foreign key check failed: {errors[:5]}")
                else:
                    c.execute(text('CREATE UNIQUE INDEX IF NOT EXISTS uq_workspace_owner_id ON workspaces (user_id, id)'))
                    for name in TABLES:
                        c.execute(text(f'ALTER TABLE "{name}" ALTER COLUMN workspace_id SET NOT NULL'))
                        if name == "open_finance_sync_logs":
                            c.execute(text(f'ALTER TABLE "{name}" ALTER COLUMN user_id SET NOT NULL'))
                        c.execute(text(f'CREATE INDEX IF NOT EXISTS "ix_{name}_owner_workspace" ON "{name}" (user_id, workspace_id)'))
                        if name in COMPOSITE:
                            pk = inspect(c).get_pk_constraint(name)
                            if pk["constrained_columns"] != ["user_id", "workspace_id"]:
                                c.execute(text(f'ALTER TABLE "{name}" DROP CONSTRAINT "{pk["name"]}"'))
                                c.execute(text(f'ALTER TABLE "{name}" ADD PRIMARY KEY (user_id, workspace_id)'))
                        if name in UNIQUE_CHANGES:
                            key, columns = UNIQUE_CHANGES[name]
                            for uq in inspect(c).get_unique_constraints(name):
                                if key in uq["column_names"] and "workspace_id" not in uq["column_names"]:
                                    c.execute(text(f'ALTER TABLE "{name}" DROP CONSTRAINT "{uq["name"]}"'))
                            c.execute(text(f'CREATE UNIQUE INDEX IF NOT EXISTS "uq_{name}_v39" ON "{name}" ({", ".join(columns)})'))
                        if not any(fk.get("name") == f"fk_{name}_workspace_owner" for fk in inspect(c).get_foreign_keys(name)):
                            c.execute(text(f'ALTER TABLE "{name}" ADD CONSTRAINT "fk_{name}_workspace_owner" FOREIGN KEY (user_id, workspace_id) REFERENCES workspaces (user_id, id)'))
                        install_pg_guards(c, name)
                c.execute(text("INSERT INTO finance_schema_migrations(version) VALUES (39)"))
        finally:
            if sqlite:
                c.exec_driver_sql("PRAGMA foreign_keys=ON")
                c.commit()


def references(name):
    for column in Base.metadata.tables[name].columns:
        for fk in column.foreign_keys:
            if fk.column.table.name in TABLES:
                yield column.name, fk.column.table.name, fk.column.name


def install_pg_guards(c, name):
    checks = ""
    for col, parent, target in references(name):
        checks += f'''IF NEW."{col}" IS NOT NULL AND NOT EXISTS
            (SELECT 1 FROM "{parent}" WHERE "{target}"=NEW."{col}" AND user_id=NEW.user_id AND workspace_id=NEW.workspace_id)
            THEN RAISE EXCEPTION 'cross-workspace reference: {name}.{col}'; END IF;\n'''
    c.execute(text(f'''CREATE OR REPLACE FUNCTION guard_{name}_workspace() RETURNS trigger AS $$
        BEGIN
          IF TG_OP = 'UPDATE' AND (OLD.user_id <> NEW.user_id OR OLD.workspace_id <> NEW.workspace_id)
            THEN RAISE EXCEPTION 'immutable workspace ownership'; END IF;
          IF NOT EXISTS (SELECT 1 FROM workspaces WHERE id=NEW.workspace_id AND user_id=NEW.user_id)
            THEN RAISE EXCEPTION 'invalid workspace owner'; END IF;
          {checks}
          RETURN NEW;
        END; $$ LANGUAGE plpgsql'''))
    c.execute(text(f'DROP TRIGGER IF EXISTS guard_workspace ON "{name}"'))
    c.execute(text(f'''CREATE TRIGGER guard_workspace BEFORE INSERT OR UPDATE ON "{name}"
        FOR EACH ROW EXECUTE FUNCTION guard_{name}_workspace()'''))


def install_sqlite_guards(c, name):
    for action in ("INSERT", "UPDATE"):
        checks = ""
        if action == "UPDATE":
            checks += "SELECT CASE WHEN OLD.user_id <> NEW.user_id OR OLD.workspace_id <> NEW.workspace_id THEN RAISE(ABORT, 'immutable workspace ownership') END;"
        checks += "SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM workspaces WHERE id=NEW.workspace_id AND user_id=NEW.user_id) THEN RAISE(ABORT, 'invalid workspace owner') END;"
        for col, parent, target in references(name):
            checks += f'''SELECT CASE WHEN NEW."{col}" IS NOT NULL AND NOT EXISTS
                (SELECT 1 FROM "{parent}" WHERE "{target}"=NEW."{col}" AND user_id=NEW.user_id AND workspace_id=NEW.workspace_id)
                THEN RAISE(ABORT, 'cross-workspace reference') END;'''
        c.exec_driver_sql(f'CREATE TRIGGER guard_{name}_{action.lower()} BEFORE {action} ON "{name}" BEGIN {checks} END')


if __name__ == "__main__":
    from app.database.database import engine
    from app import models
    Base.metadata.create_all(engine)
    migrate_workspaces(engine)
    print("v39 migration completed")
