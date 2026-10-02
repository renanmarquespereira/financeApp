from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.core.security import get_current_user
from app.core.workspace import get_workspace_db as get_db, scope_id
from app.models.credit_card import CreditCard
from app.models.user import User
from app.schemas.credit_card import CreditCardCreate, CreditCardResponse, CreditCardUpdate

router = APIRouter(prefix="/cards", tags=["Cards"])


@router.get("", response_model=list[CreditCardResponse])
def list_cards(
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    return (
        db.query(CreditCard)
        .filter(CreditCard.user_id == user.id)
        .order_by(CreditCard.bank_name.asc(), CreditCard.last_four.asc())
        .all()
    )


@router.post("", response_model=CreditCardResponse)
def create_card(
    data: CreditCardCreate,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    obj = CreditCard(
        user_id=user.id,
        bank_name=data.bank_name.strip(),
        brand=data.brand.strip(),
        last_four=data.last_four,
        nickname=data.nickname.strip() if data.nickname else None,
        credit_limit=data.credit_limit,
        closing_day=data.closing_day,
        due_day=data.due_day,
    )
    db.add(obj)
    db.commit()
    db.refresh(obj)
    return obj


@router.patch(
    "/{card_id}",
    response_model=CreditCardResponse,
)
def update_card(
    card_id: int,
    data: CreditCardUpdate,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    obj = (
        db.query(CreditCard)
        .filter(
            CreditCard.id == card_id,
            CreditCard.user_id == user.id,
        )
        .first()
    )
    if not obj:
        raise HTTPException(
            404,
            "Cartão não encontrado",
        )

    obj.bank_name = data.bank_name.strip()
    obj.brand = data.brand.strip()
    obj.last_four = data.last_four
    obj.nickname = (
        data.nickname.strip()
        if data.nickname
        else None
    )
    obj.credit_limit = data.credit_limit
    obj.closing_day = data.closing_day
    obj.due_day = data.due_day

    db.commit()
    db.refresh(obj)
    return obj


@router.delete("/{card_id}")
def delete_card(
    card_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    obj = (
        db.query(CreditCard)
        .filter(
            CreditCard.id == card_id,
            CreditCard.user_id == user.id,
        )
        .first()
    )
    if not obj:
        raise HTTPException(404, "Cartão não encontrado")
    obj.active = False
    db.commit()
    return {
        "status": "removed",
        "card_id": card_id,
        "transactions_preserved": True,
    }
