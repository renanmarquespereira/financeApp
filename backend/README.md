# Finance App API V5

Backend FastAPI + PostgreSQL + Redis/Celery com lançamentos manuais e integração Open Finance via Belvo.

## 1. Configurar Sandbox Belvo

Edite `.env` e preencha:

```env
BELVO_SECRET_ID=...
BELVO_SECRET_PASSWORD=...
```

As chaves ficam somente no backend. O aplicativo recebe apenas um token efêmero do Hosted Widget.

## 2. Subir a aplicação

```powershell
docker compose down -v
docker compose build --no-cache
docker compose up
```

Abra `http://localhost:8000/docs`.

## 3. Fluxo Open Finance

1. Faça login e autorize no Swagger.
2. `POST /openfinance/belvo/widget-token` gera um token efêmero.
3. Abra o `hosted_widget_url` retornado (em produção isso será aberto no app Android).
4. Ao concluir o Widget, o frontend recebe o `link_id` da Belvo.
5. Envie o `link_id` em `POST /openfinance/belvo/register-link`.
6. A conexão fica ativa e uma sincronização é enfileirada no Celery.
7. `POST /openfinance/connections/{id}/sync` permite sincronizar manualmente.
8. `GET /transactions` mostra transações manuais e importadas.

## 4. Webhook

Configure no dashboard Belvo uma URL pública HTTPS apontando para:

`POST /openfinance/webhook/belvo`

Para desenvolvimento local, localhost não é alcançável pela Belvo; use um túnel HTTPS somente se quiser testar webhooks.
Se `BELVO_WEBHOOK_TOKEN` for preenchido, configure o mesmo token Bearer no webhook da Belvo.

## Observação

Após solicitar histórico com `fetch_resources`, a Belvo recomenda aguardar o webhook de atualização histórica antes de consultar os dados; por isso a V5 aceita tanto sincronização disparada pelo webhook quanto rechecagens do worker.
