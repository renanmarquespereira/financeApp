"""Atomic, authenticated import of a visitor's frozen local snapshot."""
import hashlib
from datetime import datetime, date
from decimal import Decimal
from uuid import UUID
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field, ConfigDict
from sqlalchemy import select, String, ForeignKey
from sqlalchemy.orm import Mapped, mapped_column
from app.database.database import Base, get_db
from app.core.security import get_current_user
from app.core.workspace import bind_workspace
from app.models import (User, Workspace, Category, BankAccount, CreditCard, Transaction,
    FinancialGoal, GoalContribution, CategoryBudget)
from app.models.security_challenge import DeletedWorkspace


class GuestImportReceipt(Base):
    __tablename__ = 'guest_import_receipts'
    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey('users.id'), index=True)
    digest: Mapped[str] = mapped_column(String(64))


class LocalRow(BaseModel):
    model_config = ConfigDict(allow_inf_nan=False)
    id: int


class Cat(LocalRow):
    name: str = Field(min_length=1,max_length=100)
    icon: str | None = None


class Account(LocalRow):
    institutionName: str = Field(min_length=1,max_length=255)
    accountName: str | None = None
    maskedAccount: str | None = None


class Card(LocalRow):
    bankName: str
    brand: str
    lastFour: str = Field(pattern=r'^\d{4}$')
    nickname: str | None = None
    active: bool = True


class Goal(LocalRow):
    name: str
    targetAmount: Decimal = Field(gt=0)
    currentAmount: Decimal = Field(ge=0)
    targetDate: date | None = None


class Contribution(LocalRow):
    goalId: int
    amount: Decimal = Field(gt=0)
    createdAt: datetime
    clientKey: str


class Budget(BaseModel):
    categoryId: int
    amount: Decimal = Field(ge=0)


class Tx(LocalRow):
    accountId: int | None = None
    categoryId: int | None = None
    cardId: int | None = None
    date: datetime
    description: str
    amount: Decimal
    transactionType: str
    status: str = 'posted'
    installmentGroup: str | None = None
    installmentNumber: int | None = Field(default=None,ge=1,le=48)
    installmentTotal: int | None = Field(default=None,ge=1,le=48)
    purchaseDate: datetime | None = None


class Payload(BaseModel):
    transferId: UUID
    accounts: list[Account] = Field(default_factory=list,max_length=5000)
    categories: list[Cat] = Field(default_factory=list,max_length=5000)
    cards: list[Card] = Field(default_factory=list,max_length=5000)
    goals: list[Goal] = Field(default_factory=list,max_length=5000)
    contributions: list[Contribution] = Field(default_factory=list,max_length=50000)
    budgets: list[Budget] = Field(default_factory=list,max_length=5000)
    transactions: list[Tx] = Field(default_factory=list,max_length=100000)


router=APIRouter(prefix='/guest-import',tags=['Guest import'])


@router.post('')
def import_guest(data: Payload, db=Depends(get_db), user=Depends(get_current_user)):
    id=str(data.transferId)
    fingerprint=hashlib.sha256(data.model_dump_json().encode()).hexdigest()
    db.execute(select(User.id).where(User.id==user.id).with_for_update()).scalar_one()
    receipt=db.get(GuestImportReceipt,id)
    if receipt:
        if receipt.user_id!=user.id or receipt.digest!=fingerprint:
            raise HTTPException(409,'Transferência vinculada a outra conta ou conjunto de dados.')
        workspace=db.get(Workspace,id)
        if not workspace or workspace.archived_at is not None:
            raise HTTPException(409,'O workspace transferido foi arquivado ou excluído. Não será recriado.')
        return {'id':id,'user_id':user.id,'name':workspace.name,'kind':workspace.kind,'is_default':False,'archived_at':None}
    if db.get(Workspace,id) or db.get(DeletedWorkspace,id):
        raise HTTPException(409,'Identificador de transferência já utilizado.')
    for rows in (data.accounts,data.categories,data.cards,data.goals,data.contributions,data.transactions):
        if len({r.id for r in rows})!=len(rows): raise HTTPException(422,'Identificadores locais duplicados.')
    if len({r.categoryId for r in data.budgets})!=len(data.budgets): raise HTTPException(422,'Orçamentos duplicados.')
    ws=Workspace(id=id,user_id=user.id,name='Dados do visitante',kind='personal',is_default=False)
    db.add(ws);db.flush();bind_workspace(db,user.id,id)
    def put(cls,**fields):
        row=cls(user_id=user.id,**fields);db.add(row);db.flush();return row.id
    def ref(mapping,key):
        if key is None:return None
        if key not in mapping:raise HTTPException(422,'Referência local inválida. Nenhum dado foi transferido.')
        return mapping[key]
    cats={r.id:put(Category,name=r.name,icon=r.icon) for r in data.categories}
    accounts={r.id:put(BankAccount,institution_name=r.institutionName,account_name=r.accountName,masked_account=r.maskedAccount) for r in data.accounts}
    cards={r.id:put(CreditCard,bank_name=r.bankName,brand=r.brand,last_four=r.lastFour,nickname=r.nickname,active=r.active) for r in data.cards}
    goals={r.id:put(FinancialGoal,name=r.name,target_amount=r.targetAmount,current_amount=r.currentAmount,target_date=r.targetDate) for r in data.goals}
    for r in data.budgets:put(CategoryBudget,category_id=ref(cats,r.categoryId),amount=r.amount)
    for r in data.contributions:put(GoalContribution,goal_id=ref(goals,r.goalId),amount=r.amount,created_at=r.createdAt,client_key=r.clientKey)
    for r in data.transactions:
        put(Transaction,account_id=ref(accounts,r.accountId),category_id=ref(cats,r.categoryId),card_id=ref(cards,r.cardId),
            date=r.date,description=r.description,amount=r.amount,transaction_type=r.transactionType,status=r.status,
            source='manual',external_transaction_id=f'guest:{id}:{r.id}',installment_group=r.installmentGroup,
            installment_number=r.installmentNumber,installment_total=r.installmentTotal,purchase_date=r.purchaseDate)
    db.add(GuestImportReceipt(id=id,user_id=user.id,digest=fingerprint));db.commit()
    return {'id':id,'user_id':user.id,'name':ws.name,'kind':ws.kind,'is_default':False,'archived_at':None}
