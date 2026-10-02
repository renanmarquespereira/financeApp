# Finance App V8 - Open Finance Mock

A V8 permite desenvolver o app inteiro sem depender de um provedor externo.

## Subir do zero

Como a V8 adiciona `mock_sequence`, no primeiro teste use:

```powershell
docker compose down -v
docker compose up --build
```

Abra http://localhost:8000/docs, faça login e use **Authorize**.

## Teste do fluxo automático

1. `POST /openfinance/mock/connect` - cria o Mock Bank e agenda a importação inicial.
2. Aguarde alguns segundos.
3. `GET /accounts` e `GET /transactions` - devem aparecer contas/transações com `source=open_finance`.
4. `GET /openfinance/connections` - anote o `id`.
5. `POST /openfinance/mock/connections/{id}/generate-transaction` - simula uma nova transação.
6. Aguarde alguns segundos e consulte `GET /transactions` novamente.
7. Repita o passo 5 para simular monitoramento contínuo.
8. `GET /openfinance/connections/{id}/sync-logs` mostra o histórico do worker.

O Celery Beat verifica conexões ativas a cada 60 segundos e, no modo mock, simula uma nova movimentação automaticamente a cada 120 segundos. O provider Belvo foi mantido, mas as credenciais foram removidas do `.env`.
