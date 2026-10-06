from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from app.database.database import Base, engine
from sqlalchemy import inspect, text
from app.routers import auth, users, accounts, transactions, categories, budgets, backup, openfinance, goals, user_data, credit_cards, forecast_state
from app.routers import sync as sync_router
from app import models
from app.core import workspace as workspace_boundary
from app.routers import workspaces
from app.routers import transaction_attachments
from app.database.workspace_migration import migrate_workspaces


def apply_compatibility_migrations():
    """Small idempotent migrations for existing installations."""
    inspector = inspect(engine)

    if engine.dialect.name == "postgresql":
        with engine.begin() as connection:
            connection.execute(
                text(
                    "ALTER TABLE transactions "
                    "ALTER COLUMN account_id DROP NOT NULL"
                )
            )

    user_columns = {
        column["name"]
        for column in inspector.get_columns("users")
    }

    with engine.begin() as connection:
        if "birth_date" not in user_columns:
            connection.execute(
                text(
                    "ALTER TABLE users "
                    "ADD COLUMN birth_date DATE NULL"
                )
            )

        if "sex" not in user_columns:
            connection.execute(
                text(
                    "ALTER TABLE users "
                    "ADD COLUMN sex VARCHAR(32) NULL"
                )
            )

        if "profile_photo" not in user_columns:
            connection.execute(
                text(
                    "ALTER TABLE users "
                    "ADD COLUMN profile_photo TEXT NULL"
                )
            )

        if "google_profile_photo" not in user_columns:
            connection.execute(text("ALTER TABLE users ADD COLUMN google_profile_photo TEXT NULL"))

        if "profile_photo_opt_out" not in user_columns:
            connection.execute(text("ALTER TABLE users ADD COLUMN profile_photo_opt_out BOOLEAN NOT NULL DEFAULT FALSE"))

        if "terms_version" not in user_columns:
            connection.execute(text("ALTER TABLE users ADD COLUMN terms_version VARCHAR(64) NULL"))
        if "privacy_version" not in user_columns:
            connection.execute(text("ALTER TABLE users ADD COLUMN privacy_version VARCHAR(64) NULL"))
        if "legal_accepted_at" not in user_columns:
            connection.execute(text("ALTER TABLE users ADD COLUMN legal_accepted_at TIMESTAMP WITH TIME ZONE NULL"))

    pending_columns = {column["name"] for column in inspector.get_columns("pending_registrations")} if inspector.has_table("pending_registrations") else set()
    if pending_columns:
        with engine.begin() as connection:
            if "terms_version" not in pending_columns:
                connection.execute(text("ALTER TABLE pending_registrations ADD COLUMN terms_version VARCHAR(64) NULL"))
            if "privacy_version" not in pending_columns:
                connection.execute(text("ALTER TABLE pending_registrations ADD COLUMN privacy_version VARCHAR(64) NULL"))
            if "legal_accepted_at" not in pending_columns:
                connection.execute(text("ALTER TABLE pending_registrations ADD COLUMN legal_accepted_at TIMESTAMP WITH TIME ZONE NULL"))

    transaction_columns = {
        column["name"]
        for column in inspector.get_columns("transactions")
    } if inspector.has_table("transactions") else set()

    transaction_additions = {
        "card_id": "INTEGER NULL",
        "installment_group": "VARCHAR(120) NULL",
        "installment_number": "INTEGER NULL",
        "installment_total": "INTEGER NULL",
        "purchase_date": "TIMESTAMP WITH TIME ZONE NULL",
    }

    with engine.begin() as connection:
        for column_name, sql_type in transaction_additions.items():
            if column_name not in transaction_columns:
                connection.execute(
                    text(
                        f"ALTER TABLE transactions "
                        f"ADD COLUMN {column_name} {sql_type}"
                    )
                )

    user_columns = {
        column["name"]
        for column in inspector.get_columns(
            "users"
        )
    } if inspector.has_table(
        "users"
    ) else set()

    if (
        inspector.has_table("users")
        and "cpf" not in user_columns
    ):
        with engine.begin() as connection:
            connection.execute(
                text(
                    "ALTER TABLE users "
                    "ADD COLUMN cpf "
                    "VARCHAR(11) NULL"
                )
            )
            connection.execute(
                text(
                    "CREATE INDEX IF NOT EXISTS "
                    "ix_users_cpf "
                    "ON users (cpf)"
                )
            )

    account_columns = {
        column["name"]
        for column in inspector.get_columns("bank_accounts")
    } if inspector.has_table("bank_accounts") else set()

    account_additions = {
        "current_balance": "NUMERIC(14, 2) NULL",
        "balance_updated_at":
            "TIMESTAMP WITH TIME ZONE NULL",
    }

    with engine.begin() as connection:
        for column_name, sql_type in (
            account_additions.items()
        ):
            if column_name not in account_columns:
                connection.execute(
                    text(
                        f"ALTER TABLE bank_accounts "
                        f"ADD COLUMN {column_name} "
                        f"{sql_type}"
                    )
                )


    card_columns = {
        column["name"]
        for column in inspector.get_columns("credit_cards")
    } if inspector.has_table("credit_cards") else set()

    if (
        inspector.has_table("credit_cards") and
        "active" not in card_columns
    ):
        with engine.begin() as connection:
            connection.execute(
                text(
                    "ALTER TABLE credit_cards "
                    "ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE"
                )
            )

    card_additions = {
        "credit_limit": "NUMERIC(14, 2) NULL",
        "closing_day": "INTEGER NULL",
        "due_day": "INTEGER NULL",
    }
    if inspector.has_table("credit_cards"):
        with engine.begin() as connection:
            for column_name, sql_type in card_additions.items():
                if column_name not in card_columns:
                    connection.execute(text(f"ALTER TABLE credit_cards ADD COLUMN {column_name} {sql_type}"))

app = FastAPI(title="Finance App API", version="43.0.0")
# Flutter Web runs from a browser origin (localhost/127.0.0.1 or a deployed host).
# Authentication uses bearer tokens, not cookies, so wildcard origin is safe here
# and prevents the browser from turning a reachable API into "Failed to fetch".
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)
from app.routers import guest_import
Base.metadata.create_all(bind=engine)
app.include_router(guest_import.router)
apply_compatibility_migrations()
migrate_workspaces(engine)
from app.routers import password_reset
app.include_router(password_reset.router)
app.include_router(workspaces.router)
app.include_router(auth.router, prefix="/auth", tags=["Auth"])
app.include_router(users.router, prefix="/users", tags=["Users"])
app.include_router(accounts.router, prefix="/accounts", tags=["Accounts"])
app.include_router(credit_cards.router)
app.include_router(transactions.router, prefix="/transactions", tags=["Transactions"])
app.include_router(transaction_attachments.router, tags=["Transaction attachments"])
app.include_router(categories.router)
app.include_router(budgets.router)
app.include_router(goals.router)
app.include_router(backup.router, prefix="/backup", tags=["Backup"])
app.include_router(openfinance.router, prefix="/openfinance", tags=["Open Finance"])
app.include_router(sync_router.router)
app.include_router(user_data.router)
app.include_router(forecast_state.router)
# Alias under /sync for clients/proxies that only expose the sync namespace.
app.include_router(forecast_state.router, prefix="/sync")

@app.get("/health")
def health(): return {"status": "ok", "version": "43.0.0", "forecast_sync_version": 3, "forecast_sync_routes": ["/sync/forecast-state", "/forecast-state"]}
