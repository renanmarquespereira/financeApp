from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.core.security import get_current_user
from app.core.workspace import get_workspace_db as get_db, scope_id
from app.models.budget import CategoryBudget
from app.models.category import Category
from app.models.user import User
from app.schemas.budget import BudgetResponse, BudgetUpsert

router = APIRouter(
    prefix="/budgets",
    tags=["budgets"],
)


@router.get("", response_model=list[BudgetResponse])
def list_budgets(
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    return (
        db.query(CategoryBudget)
        .filter(CategoryBudget.user_id == user.id)
        .order_by(CategoryBudget.category_id.asc())
        .all()
    )


@router.put("/{category_id}", response_model=BudgetResponse)
def set_budget(
    category_id: int,
    data: BudgetUpsert,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    category = (
        db.query(Category)
        .filter(
            Category.id == category_id,
            Category.user_id == user.id,
        )
        .first()
    )
    if not category:
        raise HTTPException(404, "Categoria não encontrada")

    obj = (
        db.query(CategoryBudget)
        .filter(
            CategoryBudget.user_id == user.id,
            CategoryBudget.category_id == category_id,
        )
        .first()
    )

    if obj is None:
        obj = CategoryBudget(
            user_id=user.id,
            category_id=category_id,
            amount=data.amount,
        )
        db.add(obj)
    else:
        obj.amount = data.amount

    db.commit()
    db.refresh(obj)
    return obj


@router.delete("/{category_id}")
def delete_budget(
    category_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    obj = (
        db.query(CategoryBudget)
        .filter(
            CategoryBudget.user_id == user.id,
            CategoryBudget.category_id == category_id,
        )
        .first()
    )

    if obj is None:
        return {"status": "not_found", "category_id": category_id}

    db.delete(obj)
    db.commit()
    return {"status": "deleted", "category_id": category_id}
