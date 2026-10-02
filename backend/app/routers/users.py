from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.core.security import get_current_user
from app.database.database import get_db
from app.models.user import User
from app.schemas.user import (
    OpenFinanceProfileUpdate,
    PersonalProfileUpdate,
    ProfilePhotoUpdate,
    UserResponse,
)

router = APIRouter()


@router.get(
    "/me",
    response_model=UserResponse,
)
def me(
    user: User = Depends(
        get_current_user
    ),
):
    return user


@router.patch("/me/personal", response_model=UserResponse)
def update_personal_profile(
    data: PersonalProfileUpdate,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    duplicate = db.query(User).filter(User.cpf == data.cpf, User.id != user.id).first()
    if duplicate:
        raise HTTPException(status_code=409, detail="CPF já cadastrado em outra conta")
    user.name = data.name
    user.cpf = data.cpf
    user.birth_date = data.birth_date
    user.sex = data.sex
    db.commit()
    db.refresh(user)
    return user


@router.patch(
    "/me/open-finance-profile",
    response_model=UserResponse,
)
def update_open_finance_profile(
    data: OpenFinanceProfileUpdate,
    db: Session = Depends(get_db),
    user: User = Depends(
        get_current_user
    ),
):
    duplicate = (
        db.query(User)
        .filter(
            User.cpf == data.cpf,
            User.id != user.id,
        )
        .first()
    )

    if duplicate:
        raise HTTPException(
            status_code=409,
            detail=(
                "CPF já cadastrado "
                "em outra conta"
            ),
        )

    user.name = data.name
    user.cpf = data.cpf

    db.commit()
    db.refresh(user)

    return user


@router.patch("/me/profile-photo", response_model=UserResponse)
def update_profile_photo(
    data: ProfilePhotoUpdate,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    user.profile_photo = data.profile_photo
    user.profile_photo_opt_out = data.profile_photo is None
    db.commit()
    db.refresh(user)
    return user


@router.post("/me/profile-photo/google", response_model=UserResponse)
def use_google_profile_photo(
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    if not user.google_profile_photo:
        raise HTTPException(status_code=404, detail="A conta Google não possui foto disponível")
    user.profile_photo = user.google_profile_photo
    user.profile_photo_opt_out = False
    db.commit()
    db.refresh(user)
    return user
