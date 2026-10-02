# Finance App V9

Correção de diagnóstico/origem das transações Open Finance.

## O que mudou
- Transações importadas pelo provider são sempre gravadas com `source="open_finance"`.
- Se um registro antigo com `external_transaction_id` já existir com `source="manual"`,
  a sincronização corrige a origem para `open_finance`.
- Novo endpoint `GET /openfinance/mock/transactions` lista somente transações automáticas.
- O retorno interno da sincronização também identifica `source="open_finance"`.

## Teste limpo recomendado
Como a V8 pode ter deixado registros antigos de testes no volume do PostgreSQL:

    docker compose down -v
    docker compose up --build

Depois:
1. Cadastre/login e autorize no Swagger.
2. `POST /openfinance/mock/connect`
3. Aguarde alguns segundos.
4. `GET /openfinance/mock/transactions`
5. Todos os itens retornados devem ter `"source": "open_finance"`.
6. Crie uma transação manual em `POST /transactions`.
7. Em `GET /transactions`, a manual deve ter `"source": "manual"`.
