# Finance App V10

## Correção
O Celery worker não registrava as tasks definidas em `app.workers.tasks`.

A V10:
- inclui explicitamente `app.workers.tasks` no Celery;
- mantém autodiscovery de tasks;
- preserva Beat, Redis, PostgreSQL e Mock Open Finance;
- mantém o endpoint `GET /openfinance/mock/transactions`.

## Teste recomendado
Como há mensagens antigas no Redis e dados de testes no PostgreSQL:

    docker compose down -v
    docker compose up --build

Depois confirme:

    docker compose ps

Todos devem estar `Up`:
- api
- postgres
- redis
- worker
- beat

Confira o worker:

    docker compose logs worker --tail=100

Na inicialização deve aparecer uma lista contendo:
- app.workers.tasks.sync_openfinance
- app.workers.tasks.enqueue_due_syncs
- app.workers.tasks.generate_mock_activity

Então:
1. Cadastre/login.
2. Authorize no Swagger.
3. POST /openfinance/mock/connect
4. POST /openfinance/connections/{id}/sync
5. GET /openfinance/mock/transactions

As transações automáticas devem ter `"source": "open_finance"`.
