from datetime import datetime
from pydantic import BaseModel, Field


class ConnectRequest(BaseModel):
    provider: str = "mock"
    institution_id: str | None = None
    institution_name: str | None = None


class WidgetTokenRequest(BaseModel):
    cpf: str | None = Field(default=None, description="CPF somente se sua configuração Belvo exigir pré-identificação")
    name: str | None = None


class RegisterBelvoLinkRequest(BaseModel):
    link_id: str
    institution_id: str | None = None
    institution_name: str | None = None


class ConnectionResponse(BaseModel):
    workspace_id: str
    id: int
    provider: str
    institution_id: str | None
    institution_name: str | None
    status: str
    authorization_url: str | None
    expires_at: datetime | None

    class Config:
        from_attributes = True
