from pydantic import BaseModel, ConfigDict, field_validator


class CreditCardCreate(BaseModel):
    bank_name: str
    brand: str
    last_four: str
    nickname: str | None = None
    credit_limit: float | None = None
    closing_day: int | None = None
    due_day: int | None = None

    @field_validator("last_four")
    @classmethod
    def validate_last_four(cls, value: str) -> str:
        digits = "".join(ch for ch in value if ch.isdigit())
        if len(digits) != 4:
            raise ValueError("Informe os 4 últimos dígitos do cartão")
        return digits

    @field_validator("closing_day", "due_day")
    @classmethod
    def validate_day(cls, value: int | None) -> int | None:
        if value is not None and not 1 <= value <= 31:
            raise ValueError("O dia deve estar entre 1 e 31")
        return value

    @field_validator("credit_limit")
    @classmethod
    def validate_limit(cls, value: float | None) -> float | None:
        if value is not None and value < 0:
            raise ValueError("O limite não pode ser negativo")
        return value


class CreditCardResponse(CreditCardCreate):
    workspace_id: str
    id: int
    active: bool = True
    model_config = ConfigDict(from_attributes=True)


class CreditCardUpdate(CreditCardCreate):
    pass
