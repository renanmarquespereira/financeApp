from datetime import datetime, timezone
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from app.core.workspace import get_workspace_db as get_db, scope_id
from app.core.security import get_current_user
from app.models.user import User
from app.models.account import BankAccount
from app.models.transaction import Transaction
from app.models.financial_reset_marker import FinancialResetMarker
from app.schemas.backup import BackupPayload

router = APIRouter()

@router.get("/export", response_model=BackupPayload)
def export_backup(db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    accounts = db.query(BankAccount).filter(BankAccount.user_id == user.id).all()
    transactions = db.query(Transaction).filter(Transaction.user_id == user.id).all()
    return BackupPayload(
        workspace_id=scope_id(db), user_id=user.id, version=2,
        generated_at=datetime.now(timezone.utc),
        accounts=[{"local_id": str(a.id), "institution_name": a.institution_name, "institution_id": a.institution_id, "account_name": a.account_name, "masked_account": a.masked_account, "external_account_id": a.external_account_id} for a in accounts],
        transactions=[{"local_id": str(t.id), "external_transaction_id": t.external_transaction_id, "account_external_id": t.account.external_account_id if t.account else None, "date": t.date, "description": t.description, "amount": t.amount, "transaction_type": t.transaction_type, "status": t.status, "category_id": t.category_id} for t in transactions]
    )

@router.post("/import")
def import_backup(payload: BackupPayload, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    if payload.workspace_id is not None and (payload.workspace_id != scope_id(db) or payload.user_id != user.id):
        raise HTTPException(409, "Backup pertence a outro usuário/workspace")
    if payload.workspace_id is None and scope_id(db) != f"default-{user.id}":
        raise HTTPException(409, "Backup legado só pode ser restaurado no workspace padrão")
    reset_marker = db.get(FinancialResetMarker, (user.id, scope_id(db)))

    if (
        reset_marker is not None and
        payload.generated_at <= reset_marker.reset_at
    ):
        raise HTTPException(
            409,
            "Este backup é anterior ao seu reinício financeiro e não pode ser restaurado.",
        )

    accounts_by_external = {}
    for a in payload.accounts:
        account = None
        if a.external_account_id:
            account = db.query(BankAccount).filter(BankAccount.user_id == user.id, BankAccount.external_account_id == a.external_account_id).first()
        if not account:
            account = BankAccount(user_id=user.id, institution_name=a.institution_name, institution_id=a.institution_id, account_name=a.account_name, masked_account=a.masked_account, external_account_id=a.external_account_id)
            db.add(account); db.flush()
        if account.external_account_id: accounts_by_external[account.external_account_id] = account
    added = 0
    for t in payload.transactions:
        if t.external_transaction_id and db.query(Transaction).filter(Transaction.external_transaction_id == t.external_transaction_id).first():
            continue
        account = accounts_by_external.get(t.account_external_id)
        if not account:
            raise HTTPException(400, "Transação referencia uma conta inexistente no backup")
        db.add(Transaction(user_id=user.id, account_id=account.id, external_transaction_id=t.external_transaction_id, date=t.date, description=t.description, amount=t.amount, transaction_type=t.transaction_type, status=t.status, category_id=t.category_id)); added += 1
    db.commit()
    return {"imported_transactions": added}
