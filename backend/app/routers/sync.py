from datetime import datetime, timezone
import logging
from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session
from app.core.workspace import get_workspace_db as get_db, scope_id
from app.core.security import get_current_user
from app.models.user import User
from app.models.account import BankAccount
from app.models.transaction import Transaction
from app.models.category import Category
from app.models.user_sync_state import UserSyncState
from app.models.openfinance import OpenFinanceConnection
from app.models.financial_reset_marker import FinancialResetMarker

router = APIRouter(prefix="/sync", tags=["Sync"])
logger = logging.getLogger("financeapp.sync")

def _get_or_create_state(db: Session, user_id: int) -> UserSyncState:
    state = db.get(UserSyncState, (user_id, scope_id(db)))
    if not state:
        state = UserSyncState(user_id=user_id)
        db.add(state)
        db.flush()
    return state

@router.get("/snapshot")
def get_snapshot(db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    accounts = db.query(BankAccount).filter(BankAccount.user_id == user.id).order_by(BankAccount.id.asc()).all()
    transactions = db.query(Transaction).filter(Transaction.user_id == user.id).order_by(Transaction.date.desc(), Transaction.id.desc()).all()
    categories = db.query(Category).filter(Category.user_id == user.id).order_by(Category.id.asc()).all()
    state = _get_or_create_state(db, user.id)
    now = datetime.now(timezone.utc)
    state.last_full_sync_at = now
    db.commit()
    connections = (
        db.query(OpenFinanceConnection)
        .filter(OpenFinanceConnection.user_id == user.id)
        .all()
    )
    reset_marker = (db.query(FinancialResetMarker)
        .filter(FinancialResetMarker.user_id == user.id, FinancialResetMarker.workspace_id == scope_id(db))
        .first())

    def account_status(a):
        if not a.external_account_id:
            return "manual"
        matching = [
            c for c in connections
            if a.institution_id and c.institution_id == a.institution_id
        ]
        if any(c.status == "active" for c in matching):
            return "connected"
        return "disconnected"

    return {
        "workspace_id": scope_id(db),
        "user_id": user.id,
        "server_time": now,
        "last_full_sync_at": state.last_full_sync_at,
        "reset_at": reset_marker.reset_at if reset_marker else None,
        "counts": {"accounts": len(accounts), "transactions": len(transactions), "categories": len(categories)},
        "accounts": [{
            "workspace_id": a.workspace_id,
            "id": a.id,
            "institution_name": a.institution_name,
            "institution_id": a.institution_id,
            "account_name": a.account_name,
            "masked_account": a.masked_account,
            "external_account_id": a.external_account_id,
            "connection_status": account_status(a),
            "current_balance": (
                float(a.current_balance)
                if a.current_balance is not None
                else None
            ),
            "balance_updated_at": a.balance_updated_at,
        } for a in accounts],
        "transactions": [{
            "workspace_id": tr.workspace_id,
            "id": tr.id,
            "account_id": tr.account_id,
            "category_id": tr.category_id,
            "card_id": tr.card_id,
            "installment_group": tr.installment_group,
            "installment_number": tr.installment_number,
            "installment_total": tr.installment_total,
            "purchase_date": tr.purchase_date,
            "external_transaction_id": tr.external_transaction_id,
            "date": tr.date,
            "description": tr.description,
            "amount": float(tr.amount),
            "transaction_type": tr.transaction_type,
            "status": tr.status,
            "source": tr.source,
            "synced_at": tr.synced_at,
        } for tr in transactions],
        "categories": [{"workspace_id": c.workspace_id, "id":c.id,"name":c.name,"icon":c.icon} for c in categories],
    }

@router.get("/status")
def get_sync_status(db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    state = db.get(UserSyncState, (user.id, scope_id(db)))
    return {"last_full_sync_at": state.last_full_sync_at if state else None}

@router.post("/import-local")
def import_local_snapshot(payload: dict, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Idempotently reconciles legacy Android rows that were marked SYNCED locally.

    This endpoint intentionally works in the currently selected workspace.  It is
    used only as a bridge for installations created before server-first sync was
    reliable. Existing server rows are matched before inserts, so reopening the
    app does not multiply data.
    """
    from decimal import Decimal
    from datetime import datetime as _dt, date as _date
    from app.models.credit_card import CreditCard
    from app.models.goal import FinancialGoal
    from app.models.goal_contribution import GoalContribution
    from app.models.budget import CategoryBudget

    wsid = scope_id(db)
    created = {"accounts": 0, "categories": 0, "cards": 0, "goals": 0, "budgets": 0, "contributions": 0, "transactions": 0}
    received = {
        key: len(payload.get(key, []) or [])
        for key in ("accounts", "categories", "cards", "goals", "budgets", "contributions", "transactions")
    }
    payload_workspace_id = payload.get("workspaceId") or payload.get("workspace_id")
    logger.warning(
        "SYNC_IMPORT_LOCAL_START user_id=%s server_workspace_id=%s payload_workspace_id=%s received=%s",
        user.id, wsid, payload_workspace_id, received,
    )

    def norm(value):
        return (value or "").strip().casefold()

    def dt_value(value):
        if value is None:
            return None
        if isinstance(value, _dt):
            return value
        return _dt.fromisoformat(str(value).replace("Z", "+00:00"))

    def dt_key(value):
        value = dt_value(value)
        return value.replace(tzinfo=None).isoformat(timespec="seconds") if value else ""

    local_to_account = {}
    remote_accounts = db.query(BankAccount).filter(BankAccount.user_id == user.id, BankAccount.workspace_id == wsid).all()
    for row in payload.get("accounts", []):
        match = next((x for x in remote_accounts if norm(x.institution_name) == norm(row.get("institutionName"))
                      and norm(x.account_name) == norm(row.get("accountName"))
                      and norm(x.masked_account) == norm(row.get("maskedAccount"))), None)
        if match is None:
            match = BankAccount(user_id=user.id, workspace_id=wsid, institution_name=row.get("institutionName") or "Conta",
                                account_name=row.get("accountName"), masked_account=row.get("maskedAccount"))
            db.add(match); db.flush(); remote_accounts.append(match); created["accounts"] += 1
        local_to_account[row.get("id")] = match.id

    local_to_category = {}
    remote_categories = db.query(Category).filter(Category.user_id == user.id, Category.workspace_id == wsid).all()
    for row in payload.get("categories", []):
        match = next((x for x in remote_categories if norm(x.name) == norm(row.get("name"))), None)
        if match is None:
            match = Category(user_id=user.id, workspace_id=wsid, name=row.get("name") or "Categoria", icon=row.get("icon"))
            db.add(match); db.flush(); remote_categories.append(match); created["categories"] += 1
        local_to_category[row.get("id")] = match.id

    local_to_card = {}
    remote_cards = db.query(CreditCard).filter(CreditCard.user_id == user.id, CreditCard.workspace_id == wsid).all()
    for row in payload.get("cards", []):
        match = next((x for x in remote_cards if norm(x.bank_name) == norm(row.get("bankName"))
                      and norm(x.brand) == norm(row.get("brand")) and x.last_four == row.get("lastFour")), None)
        if match is None:
            match = CreditCard(user_id=user.id, workspace_id=wsid, bank_name=row.get("bankName") or "Banco", brand=row.get("brand") or "Outro",
                               last_four=row.get("lastFour") or "0000", nickname=row.get("nickname"),
                               credit_limit=row.get("creditLimit"), closing_day=row.get("closingDay"), due_day=row.get("dueDay"),
                               active=row.get("active", True))
            db.add(match); db.flush(); remote_cards.append(match); created["cards"] += 1
        local_to_card[row.get("id")] = match.id

    local_to_goal = {}
    remote_goals = db.query(FinancialGoal).filter(FinancialGoal.user_id == user.id, FinancialGoal.workspace_id == wsid).all()
    for row in payload.get("goals", []):
        match = next((x for x in remote_goals if norm(x.name) == norm(row.get("name"))
                      and Decimal(str(x.target_amount)) == Decimal(str(row.get("targetAmount", 0)))), None)
        if match is None:
            match = FinancialGoal(user_id=user.id, workspace_id=wsid, name=row.get("name") or "Meta", target_amount=row.get("targetAmount") or 0,
                                  current_amount=row.get("currentAmount") or 0, target_date=_date.fromisoformat(row["targetDate"][:10]) if row.get("targetDate") else None)
            db.add(match); db.flush(); remote_goals.append(match); created["goals"] += 1
        local_to_goal[row.get("id")] = match.id

    for row in payload.get("budgets", []):
        cid = local_to_category.get(row.get("categoryId"))
        if cid is None: continue
        match = db.query(CategoryBudget).filter(CategoryBudget.user_id == user.id, CategoryBudget.workspace_id == wsid, CategoryBudget.category_id == cid).first()
        if match is None:
            db.add(CategoryBudget(user_id=user.id, workspace_id=wsid, category_id=cid, amount=row.get("amount") or 0)); created["budgets"] += 1

    for row in payload.get("contributions", []):
        gid = local_to_goal.get(row.get("goalId"))
        if gid is None: continue
        key = row.get("clientKey")
        match = db.query(GoalContribution).filter(GoalContribution.user_id == user.id, GoalContribution.workspace_id == wsid, GoalContribution.client_key == key).first() if key else None
        if match is None:
            db.add(GoalContribution(user_id=user.id, workspace_id=wsid, goal_id=gid, amount=row.get("amount") or 0,
                                    created_at=dt_value(row.get("createdAt")), client_key=key)); created["contributions"] += 1

    remote_transactions = db.query(Transaction).filter(Transaction.user_id == user.id, Transaction.workspace_id == wsid).all()
    for row in payload.get("transactions", []):
        aid = local_to_account.get(row.get("accountId")) if row.get("accountId") is not None else None
        cid = local_to_category.get(row.get("categoryId")) if row.get("categoryId") is not None else None
        cardid = local_to_card.get(row.get("cardId")) if row.get("cardId") is not None else None
        amount = Decimal(str(row.get("amount", 0)))
        match = next((x for x in remote_transactions if dt_key(x.date) == dt_key(row.get("date"))
                      and norm(x.description) == norm(row.get("description")) and Decimal(str(x.amount)) == amount
                      and norm(x.transaction_type) == norm(row.get("transactionType"))
                      and x.account_id == aid and x.category_id == cid and x.card_id == cardid), None)
        if match is None:
            match = Transaction(user_id=user.id, workspace_id=wsid, account_id=aid, category_id=cid, card_id=cardid,
                                date=dt_value(row.get("date")), description=row.get("description") or "Lançamento", amount=amount,
                                transaction_type=row.get("transactionType") or "expense", status=row.get("status") or "posted",
                                source=row.get("source") or "manual", external_transaction_id=row.get("externalTransactionId"),
                                installment_group=row.get("installmentGroup"), installment_number=row.get("installmentNumber"),
                                installment_total=row.get("installmentTotal"), purchase_date=dt_value(row.get("purchaseDate")))
            db.add(match); db.flush(); remote_transactions.append(match); created["transactions"] += 1

    db.commit()

    server_counts = {
        "accounts": db.query(BankAccount).filter(BankAccount.user_id == user.id, BankAccount.workspace_id == wsid).count(),
        "categories": db.query(Category).filter(Category.user_id == user.id, Category.workspace_id == wsid).count(),
        "cards": db.query(CreditCard).filter(CreditCard.user_id == user.id, CreditCard.workspace_id == wsid).count(),
        "goals": db.query(FinancialGoal).filter(FinancialGoal.user_id == user.id, FinancialGoal.workspace_id == wsid).count(),
        "budgets": db.query(CategoryBudget).filter(CategoryBudget.user_id == user.id, CategoryBudget.workspace_id == wsid).count(),
        "contributions": db.query(GoalContribution).filter(GoalContribution.user_id == user.id, GoalContribution.workspace_id == wsid).count(),
        "transactions": db.query(Transaction).filter(Transaction.user_id == user.id, Transaction.workspace_id == wsid).count(),
    }
    ignored_or_matched = {key: max(received[key] - created[key], 0) for key in received}
    diagnostic = {
        "user_id": user.id,
        "server_workspace_id": wsid,
        "payload_workspace_id": payload_workspace_id,
        "workspace_matches": payload_workspace_id is None or str(payload_workspace_id) == str(wsid),
        "received": received,
        "created": created,
        "matched_or_ignored": ignored_or_matched,
        "server_counts_after": server_counts,
        # Local Android IDs can come from an older/offline database. Returning
        # the authoritative server IDs lets the client remap pending rows before
        # it posts them through the normal endpoints.
        "id_mappings": {
            "accounts": {str(k): v for k, v in local_to_account.items() if k is not None},
            "categories": {str(k): v for k, v in local_to_category.items() if k is not None},
            "cards": {str(k): v for k, v in local_to_card.items() if k is not None},
            "goals": {str(k): v for k, v in local_to_goal.items() if k is not None},
        },
    }
    logger.warning("SYNC_IMPORT_LOCAL_RESULT %s", diagnostic)
    return diagnostic
