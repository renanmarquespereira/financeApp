import unicodedata

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.core.workspace import get_workspace_db as get_db, scope_id
from app.core.security import get_current_user
from app.models.category import Category
from app.models.transaction import Transaction
from app.models.user import User
from app.schemas.category import (
    CategoryCreate,
    CategoryResponse,
    CategoryUpdate,
)

router = APIRouter(
    prefix="/categories",
    tags=["categories"],
)


def _category_key(value: str) -> str:
    normalized = unicodedata.normalize(
        "NFD",
        value.strip(),
    )
    without_accents = "".join(
        char
        for char in normalized
        if unicodedata.category(char) != "Mn"
    )
    return " ".join(
        without_accents.casefold().split()
    )


def _find_duplicate_category(
    db: Session,
    user_id: int,
    name: str,
    ignore_id: int | None = None,
):
    key = _category_key(name)

    query = db.query(Category).filter(
        Category.user_id == user_id
    )

    if ignore_id is not None:
        query = query.filter(
            Category.id != ignore_id
        )

    return next(
        (
            category
            for category in query.all()
            if _category_key(category.name) == key
        ),
        None,
    )


@router.get("", response_model=list[CategoryResponse])
def list_categories(
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    return (
        db.query(Category)
        .filter(Category.user_id == user.id)
        .order_by(Category.name.asc())
        .all()
    )


@router.post("", response_model=CategoryResponse)
def create_category(
    data: CategoryCreate,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    name = data.name.strip()

    if not name:
        raise HTTPException(
            status_code=400,
            detail="Informe o nome da categoria",
        )

    existing = _find_duplicate_category(
        db,
        user.id,
        name,
    )

    if existing:
        raise HTTPException(
            status_code=409,
            detail="Essa categoria já existe.",
        )

    obj = Category(
        user_id=user.id,
        name=name,
        icon=data.icon,
    )

    db.add(obj)
    db.commit()
    db.refresh(obj)

    return obj


@router.patch(
    "/{category_id}",
    response_model=CategoryResponse,
)
def update_category(
    category_id: int,
    data: CategoryUpdate,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    obj = (
        db.query(Category)
        .filter(
            Category.id == category_id,
            Category.user_id == user.id,
        )
        .first()
    )

    if not obj:
        raise HTTPException(
            status_code=404,
            detail="Categoria não encontrada",
        )

    name = data.name.strip()

    if not name:
        raise HTTPException(
            status_code=400,
            detail="Informe o nome da categoria",
        )

    duplicate = _find_duplicate_category(
        db,
        user.id,
        name,
        ignore_id=category_id,
    )

    if duplicate:
        raise HTTPException(
            status_code=409,
            detail="Essa categoria já existe.",
        )

    obj.name = name
    obj.icon = data.icon

    db.commit()
    db.refresh(obj)

    return obj


@router.delete("/{category_id}")
def delete_category(
    category_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    obj = (
        db.query(Category)
        .filter(
            Category.id == category_id,
            Category.user_id == user.id,
        )
        .first()
    )

    if not obj:
        raise HTTPException(
            status_code=404,
            detail="Categoria não encontrada",
        )

    (
        db.query(Transaction)
        .filter(
            Transaction.user_id == user.id,
            Transaction.category_id == category_id,
        )
        .update(
            {Transaction.category_id: None},
            synchronize_session=False,
        )
    )

    db.delete(obj)
    db.commit()

    return {
        "status": "deleted",
        "category_id": category_id,
    }