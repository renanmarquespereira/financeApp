from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.core.security import get_current_user
from app.core.workspace import get_workspace_db as get_db, scope_id
from app.models.credit_card import CreditCard
from app.models.transaction import Transaction
from app.models.user import User
from app.schemas.credit_card import CreditCardCreate, CreditCardResponse, CreditCardUpdate

router = APIRouter(prefix="/cards", tags=["Cards"])


def _norm(value: str | None) -> str:
    return " ".join((value or "").strip().lower().split())


def _card_dedupe_key(card: CreditCard) -> tuple[str, str, str, str]:
    return (
        _norm(card.bank_name),
        _norm(card.brand),
        (card.last_four or "").strip(),
        _norm(card.nickname),
    )


def _merge_duplicate_cards(db: Session, user_id: int) -> None:
    rows = (
        db.query(CreditCard)
        .filter(CreditCard.user_id == user_id)
        .order_by(CreditCard.id.asc())
        .all()
    )
    groups: dict[tuple[str, str, str, str], list[CreditCard]] = {}
    for row in rows:
        key = _card_dedupe_key(row)
        if not key[0] or not key[2]:
            continue
        groups.setdefault(key, []).append(row)

    changed = False
    for group in groups.values():
        if len(group) < 2:
            continue
        keeper = next((row for row in group if row.active), group[0])
        for duplicate in group:
            if duplicate.id == keeper.id:
                continue
            db.query(Transaction).filter(
                Transaction.user_id == user_id,
                Transaction.card_id == duplicate.id,
            ).update(
                {Transaction.card_id: keeper.id},
                synchronize_session=False,
            )
            if keeper.credit_limit is None and duplicate.credit_limit is not None:
                keeper.credit_limit = duplicate.credit_limit
            if keeper.closing_day is None and duplicate.closing_day is not None:
                keeper.closing_day = duplicate.closing_day
            if keeper.due_day is None and duplicate.due_day is not None:
                keeper.due_day = duplicate.due_day
            keeper.active = keeper.active or duplicate.active
            db.delete(duplicate)
            changed = True
    if changed:
        db.commit()


@router.get("", response_model=list[CreditCardResponse])
def list_cards(
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    _merge_duplicate_cards(db, user.id)
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
    _merge_duplicate_cards(db, user.id)
    candidate = CreditCard(
        user_id=user.id,
        bank_name=data.bank_name.strip(),
        brand=data.brand.strip(),
        last_four=data.last_four.strip(),
        nickname=data.nickname.strip() if data.nickname else None,
        credit_limit=data.credit_limit,
        closing_day=data.closing_day,
        due_day=data.due_day,
    )
    key = _card_dedupe_key(candidate)
    existing = next(
        (row for row in db.query(CreditCard).filter(CreditCard.user_id == user.id).all()
         if _card_dedupe_key(row) == key),
        None,
    )
    if existing is not None:
        existing.active = True
        if data.credit_limit is not None:
            existing.credit_limit = data.credit_limit
        if data.closing_day is not None:
            existing.closing_day = data.closing_day
        if data.due_day is not None:
            existing.due_day = data.due_day
        db.commit()
        db.refresh(existing)
        return existing

    obj = candidate
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
