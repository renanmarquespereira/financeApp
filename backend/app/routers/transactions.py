from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from app.core.workspace import get_workspace_db as get_db, scope_id
from app.core.security import get_current_user
from app.models.user import User
from app.models.account import BankAccount
from app.models.category import Category
from app.models.credit_card import CreditCard
from app.models.transaction import Transaction
from app.models.ignored_transaction import IgnoredTransaction
from app.schemas.transaction import TransactionCreate,TransactionResponse,TransactionUpdate
router=APIRouter()
@router.get("",response_model=list[TransactionResponse])
def list_transactions(db:Session=Depends(get_db),user:User=Depends(get_current_user)):
    return db.query(Transaction).filter(Transaction.user_id==user.id).order_by(Transaction.date.desc()).all()
@router.post("/sync")
def sync_transactions():
    return {"status":"use_openfinance","message":"Use /openfinance/connections/{id}/sync ou o worker automático."}

@router.get("/{transaction_id}",response_model=TransactionResponse)
def get_transaction(transaction_id:int,db:Session=Depends(get_db),user:User=Depends(get_current_user)):
    obj=db.query(Transaction).filter(Transaction.id==transaction_id,Transaction.user_id==user.id).first()
    if not obj: raise HTTPException(404,"Transação não encontrada")
    return obj
@router.post("",response_model=TransactionResponse)
def create_transaction(data:TransactionCreate,db:Session=Depends(get_db),user:User=Depends(get_current_user)):
    if data.source == "card_purchase" and data.card_id is None:
        raise HTTPException(422, "Compra de cartão exige um cartão vinculado")
    # Lançamentos manuais podem existir sem uma conta bancária vinculada.
    if data.account_id is not None:
        account=db.query(BankAccount).filter(BankAccount.id==data.account_id,BankAccount.user_id==user.id).first()
        if not account: raise HTTPException(404,"Conta não encontrada")
    if data.card_id is not None:
        card=db.query(CreditCard).filter(
            CreditCard.id==data.card_id,
            CreditCard.user_id==user.id,
        ).first()
        if not card:
            raise HTTPException(404,"Cartão não encontrado")
    if data.external_transaction_id:
        old=db.query(Transaction).filter(
            Transaction.user_id==user.id,
            Transaction.external_transaction_id==data.external_transaction_id,
        ).first()
        if old: return old
    obj=Transaction(user_id=user.id,**data.model_dump()); db.add(obj); db.commit(); db.refresh(obj); return obj



@router.patch("/{transaction_id}",response_model=TransactionResponse)
def update_transaction(
    transaction_id:int,
    data:TransactionUpdate,
    db:Session=Depends(get_db),
    user:User=Depends(get_current_user),
):
    obj=db.query(Transaction).filter(
        Transaction.id==transaction_id,
        Transaction.user_id==user.id,
    ).first()
    if not obj:
        raise HTTPException(404,"Transação não encontrada")

    fields=data.model_dump(exclude_unset=True)

    # Transações importadas preservam os dados bancários originais.
    # Nelas o usuário pode alterar somente a categoria.
    if obj.source=="open_finance":
        invalid=set(fields.keys())-{"category_id"}
        if invalid:
            raise HTTPException(
                409,
                "Transações bancárias só permitem alterar a categoria.",
            )

    if fields.get("account_id") is not None:
        account=db.query(BankAccount).filter(
            BankAccount.id==fields["account_id"],
            BankAccount.user_id==user.id,
        ).first()
        if not account:
            raise HTTPException(404,"Conta não encontrada")

    if fields.get("category_id") is not None:
        category=db.query(Category).filter(
            Category.id==fields["category_id"],
            Category.user_id==user.id,
        ).first()
        if not category:
            raise HTTPException(404,"Categoria não encontrada")

    for key,value in fields.items():
        setattr(obj,key,value)

    db.commit()
    db.refresh(obj)
    return obj

@router.delete("/{transaction_id}")
def delete_transaction(
    transaction_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    obj = (
        db.query(Transaction)
        .filter(
            Transaction.id == transaction_id,
            Transaction.user_id == user.id,
        )
        .first()
    )

    if not obj:
        raise HTTPException(404, "Transação não encontrada")

    # Para transações importadas, cria uma marca de exclusão para impedir
    # que a mesma transação seja recriada na próxima sincronização.
    if obj.source == "open_finance" and obj.external_transaction_id and obj.account_id is not None:
        ignored = (
            db.query(IgnoredTransaction)
            .filter(
                IgnoredTransaction.user_id == user.id,
                IgnoredTransaction.account_id == obj.account_id,
                IgnoredTransaction.external_transaction_id
                == obj.external_transaction_id,
            )
            .first()
        )

        if not ignored:
            db.add(
                IgnoredTransaction(
                    user_id=user.id,
                    account_id=obj.account_id,
                    external_transaction_id=obj.external_transaction_id,
                )
            )

    db.delete(obj)
    db.commit()

    return {
        "status": "deleted",
        "transaction_id": transaction_id,
        "source": obj.source,
    }
