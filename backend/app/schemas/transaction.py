from datetime import datetime
from decimal import Decimal
from pydantic import BaseModel, ConfigDict, field_validator
class TransactionCreate(BaseModel):
    account_id:int|None=None
    category_id:int|None=None
    card_id:int|None=None
    installment_group:str|None=None
    installment_number:int|None=None
    installment_total:int|None=None
    purchase_date:datetime|None=None
    external_transaction_id:str|None=None
    date:datetime
    description:str
    amount:Decimal
    transaction_type:str="unknown"
    status:str="posted"
    source:str="manual"
    @field_validator("source")
    @classmethod
    def validate_source(cls, value:str):
        if value not in {"manual", "card_purchase", "card_payment"}:
            raise ValueError("Origem de transação inválida")
        return value
class TransactionResponse(TransactionCreate):
    workspace_id: str
    id:int
    source:str="manual"
    synced_at:datetime|None=None
    model_config=ConfigDict(from_attributes=True)


class TransactionUpdate(BaseModel):
    account_id:int|None=None
    category_id:int|None=None
    card_id:int|None=None
    installment_group:str|None=None
    installment_number:int|None=None
    installment_total:int|None=None
    purchase_date:datetime|None=None
    date:datetime|None=None
    description:str|None=None
    amount:Decimal|None=None
    transaction_type:str|None=None
    status:str|None=None
