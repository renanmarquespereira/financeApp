import json
from datetime import datetime, timezone
from typing import Any
from fastapi import APIRouter, Depends, Response
from pydantic import BaseModel, Field, field_validator
from sqlalchemy.orm import Session
from sqlalchemy import select
from app.services.forecast_merge import merge_forecast_payload
from app.core.security import get_current_user
from app.core.workspace import get_workspace_db as get_db, scope_id
from app.models.forecast_state import ForecastState
from app.models.user import User

router = APIRouter(prefix="/forecast-state", tags=["Forecast state"])

class ForecastStatePayload(BaseModel):
    payload: dict[str, Any] = Field(default_factory=dict)

    @field_validator("payload")
    @classmethod
    def validate_collections(cls, value):
        for key in ("scenarios", "aiPlans", "debts", "deletedScenarioIds", "deletedAiPlanIds", "deletedDebtIds"):
            if key in value and not isinstance(value[key], list):
                raise ValueError(f"{key} deve ser uma lista")
        for key in ("scenarios", "aiPlans", "debts"):
            for item in value.get(key, []):
                if not isinstance(item, dict) or item.get("id") is None:
                    raise ValueError(f"Registro inválido em {key}")
        return value

@router.get("")
def get_forecast_state(response: Response, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0, private"
    response.headers["Pragma"] = "no-cache"
    row = db.query(ForecastState).filter(ForecastState.user_id == user.id, ForecastState.workspace_id == scope_id(db)).first()
    if row is None:
        return {"sync_version": 2, "payload": {}, "updated_at": None}
    try:
        payload = json.loads(row.payload_json or "{}")
        if not isinstance(payload, dict):
            payload = {}
    except Exception:
        payload = {}
    return {"sync_version": 2, "payload": payload, "updated_at": row.updated_at.isoformat() if row.updated_at else None}

@router.put("")
def put_forecast_state(data: ForecastStatePayload, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    ws = scope_id(db)
    # Serialize even the first insert (a missing forecast row cannot be locked).
    db.execute(select(User.id).where(User.id == user.id).with_for_update()).scalar_one()
    row = db.query(ForecastState).filter(ForecastState.user_id == user.id, ForecastState.workspace_id == ws).first()
    if row is None:
        row = ForecastState(user_id=user.id, workspace_id=ws, payload_json="{}")
        db.add(row)
        current = {}
    else:
        try:
            current = json.loads(row.payload_json or "{}")
            if not isinstance(current, dict):
                current = {}
        except Exception:
            current = {}

    merged = merge_forecast_payload(current, data.payload)

    row.payload_json = json.dumps(merged, ensure_ascii=False, separators=(",", ":"))
    row.updated_at = datetime.now(timezone.utc)
    db.commit()
    db.refresh(row)
    return {"sync_version": 2, "payload": merged, "updated_at": row.updated_at.isoformat() if row.updated_at else None}
