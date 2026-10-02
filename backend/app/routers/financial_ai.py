from typing import Any

import httpx
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.core.config import settings
from app.core.security import get_current_user
from app.core.workspace import get_workspace_db as get_db
from app.models.user import User

router = APIRouter(prefix="/financial-ai", tags=["Financial AI"])


class FinancialAiRequest(BaseModel):
    question: str = Field(min_length=2, max_length=1000)
    summary: dict[str, Any]


class FinancialAiResponse(BaseModel):
    answer: str
    model: str


def _extract_text(payload: dict[str, Any]) -> str:
    parts: list[str] = []
    for item in payload.get("output", []):
        for content in item.get("content", []):
            if content.get("type") == "output_text" and content.get("text"):
                parts.append(content["text"])
    return "\n".join(parts).strip()


@router.post("/ask", response_model=FinancialAiResponse)
async def ask_financial_ai(
    data: FinancialAiRequest,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    del db, user
    if not settings.openai_api_key:
        raise HTTPException(503, "Assistente de IA ainda não foi configurado no servidor.")

    system = (
        "Você é um assistente financeiro pessoal em português do Brasil. "
        "Use SOMENTE o resumo financeiro fornecido. Não invente números. "
        "Regra contábil obrigatória: compra no cartão não reduz o saldo da conta; "
        "o pagamento da fatura é a saída de caixa. Compras no cartão podem ser "
        "comentadas separadamente. Explique os valores usados, diferencie fatos de "
        "estimativas e não prometa resultados. Responda de forma curta, prática e clara."
    )
    body = {
        "model": settings.openai_model,
        "instructions": system,
        "input": [
            {
                "role": "user",
                "content": [{
                    "type": "input_text",
                    "text": f"Pergunta: {data.question}\nResumo financeiro: {data.summary}",
                }],
            }
        ],
        "max_output_tokens": 700,
    }
    try:
        async with httpx.AsyncClient(timeout=45.0) as client:
            response = await client.post(
                "https://api.openai.com/v1/responses",
                headers={
                    "Authorization": f"Bearer {settings.openai_api_key}",
                    "Content-Type": "application/json",
                },
                json=body,
            )
        if response.status_code >= 400:
            raise HTTPException(502, "O provedor de IA não conseguiu responder agora.")
        answer = _extract_text(response.json())
        if not answer:
            raise HTTPException(502, "A IA retornou uma resposta vazia.")
        return FinancialAiResponse(answer=answer, model=settings.openai_model)
    except HTTPException:
        raise
    except (httpx.HTTPError, ValueError):
        raise HTTPException(502, "Não foi possível consultar a IA agora.")
