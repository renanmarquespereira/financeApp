from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.core.security import get_current_user
from app.core.workspace import get_workspace_db as get_db, scope_id
from app.models.goal import FinancialGoal
from app.models.goal_contribution import GoalContribution
from app.models.user import User
from app.schemas.goal import (
    GoalContributionCreate,
    GoalContributionResponse,
    GoalResponse,
    GoalUpsert,
)

router = APIRouter(
    prefix="/goals",
    tags=["goals"],
)


@router.get("", response_model=list[GoalResponse])
def list_goals(
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    return (
        db.query(FinancialGoal)
        .filter(FinancialGoal.user_id == user.id)
        .order_by(FinancialGoal.id.desc())
        .all()
    )


@router.post("", response_model=GoalResponse)
def create_goal(
    data: GoalUpsert,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    obj = FinancialGoal(
        user_id=user.id,
        name=data.name,
        target_amount=data.target_amount,
        current_amount=data.current_amount,
        target_date=data.target_date,
    )
    db.add(obj)
    db.commit()
    db.refresh(obj)
    return obj


@router.put("/{goal_id}", response_model=GoalResponse)
def update_goal(
    goal_id: int,
    data: GoalUpsert,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    obj = (
        db.query(FinancialGoal)
        .filter(
            FinancialGoal.id == goal_id,
            FinancialGoal.user_id == user.id,
        )
        .first()
    )
    if not obj:
        raise HTTPException(404, "Meta não encontrada")

    obj.name = data.name
    obj.target_amount = data.target_amount
    obj.current_amount = data.current_amount
    obj.target_date = data.target_date

    db.commit()
    db.refresh(obj)
    return obj



@router.get(
    "/{goal_id}/contributions",
    response_model=list[GoalContributionResponse],
)
def list_goal_contributions(
    goal_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    goal = (
        db.query(FinancialGoal)
        .filter(
            FinancialGoal.id == goal_id,
            FinancialGoal.user_id == user.id,
        )
        .first()
    )
    if not goal:
        raise HTTPException(404, "Meta não encontrada")

    return (
        db.query(GoalContribution)
        .filter(
            GoalContribution.goal_id == goal_id,
            GoalContribution.user_id == user.id,
        )
        .order_by(GoalContribution.created_at.desc())
        .all()
    )


@router.post(
    "/{goal_id}/contributions",
    response_model=GoalContributionResponse,
)
def add_goal_contribution(
    goal_id: int,
    data: GoalContributionCreate,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    goal = (
        db.query(FinancialGoal)
        .filter(
            FinancialGoal.id == goal_id,
            FinancialGoal.user_id == user.id,
        )
        .first()
    )
    if not goal:
        raise HTTPException(404, "Meta não encontrada")

    existing = (
        db.query(GoalContribution)
        .filter(
            GoalContribution.user_id == user.id,
            GoalContribution.client_key == data.client_key,
        )
        .first()
    )
    if existing:
        return existing

    contribution = GoalContribution(
        user_id=user.id,
        goal_id=goal_id,
        amount=data.amount,
        client_key=data.client_key,
    )

    goal.current_amount = goal.current_amount + data.amount

    db.add(contribution)
    db.commit()
    db.refresh(contribution)

    return contribution


@router.delete(
    "/{goal_id}/contributions/{contribution_id}"
)
def delete_goal_contribution(
    goal_id: int,
    contribution_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    goal = (
        db.query(FinancialGoal)
        .filter(
            FinancialGoal.id == goal_id,
            FinancialGoal.user_id == user.id,
        )
        .first()
    )
    if not goal:
        raise HTTPException(
            404,
            "Meta não encontrada",
        )

    contribution = (
        db.query(GoalContribution)
        .filter(
            GoalContribution.id == contribution_id,
            GoalContribution.goal_id == goal_id,
            GoalContribution.user_id == user.id,
        )
        .first()
    )
    if not contribution:
        raise HTTPException(
            404,
            "Aporte não encontrado",
        )

    goal.current_amount = max(
        goal.current_amount - contribution.amount,
        0,
    )

    db.delete(contribution)
    db.commit()

    return {
        "status": "deleted",
        "goal_id": goal_id,
        "contribution_id": contribution_id,
        "current_amount": goal.current_amount,
    }


@router.delete("/{goal_id}")
def delete_goal(
    goal_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    obj = (
        db.query(FinancialGoal)
        .filter(
            FinancialGoal.id == goal_id,
            FinancialGoal.user_id == user.id,
        )
        .first()
    )
    if not obj:
        raise HTTPException(404, "Meta não encontrada")

    db.delete(obj)
    db.commit()
    return {"status": "deleted", "goal_id": goal_id}
