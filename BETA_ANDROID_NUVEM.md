# FinanceApp - Beta Android na nuvem

## 1. Backend no Render
O arquivo `render.yaml` da raiz prepara o servico `financeapp-api` usando `backend/Dockerfile`.
No Render, configure pelo menos:

- `DATABASE_URL`: string de conexao PostgreSQL do Neon (preferencialmente com SSL conforme fornecida pelo Neon).
- `GOOGLE_CLIENT_ID`: se o login Google for usado.
- `OPENAI_API_KEY`: se os recursos de IA forem usados.
- `PUBLIC_API_URL`: URL HTTPS publica do proprio backend depois do primeiro deploy.
- SMTP: necessario para os fluxos que enviam codigo por e-mail.

`JWT_SECRET` e gerado pelo Blueprint quando o servico e criado.

Depois do deploy, teste no navegador:

`https://SEU-SERVICO.onrender.com/health`

A resposta esperada contem `"status":"ok"`.

## 2. Gerar APK Android apontando para a nuvem
O Android agora recebe a API por propriedade de build ou variavel de ambiente. Nao existe mais dependencia de um IP fixo da rede local.

Na raiz do projeto:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\\GERAR_APK_BETA.ps1" -ApiBaseUrl "https://SEU-SERVICO.onrender.com/"
```

O script gera um APK de teste em:

`android\\app\\build\\outputs\\apk\\debug\\app-debug.apk`

Esse APK pode ser instalado diretamente em um Android com instalacao de apps de fontes externas autorizada.

## 3. Desenvolvimento local
Sem `API_BASE_URL`, o Android usa `http://10.0.2.2:8000/`, apropriado para o emulador Android acessando o backend da maquina host.

Para aparelho fisico em desenvolvimento, passe explicitamente o IP local:

```powershell
gradle assembleDebug "-PAPI_BASE_URL=http://192.168.0.10:8000/"
```

## 4. Observacao sobre publicacao
Este fluxo e para beta fora da Play Store. Antes de distribuicao publica, gere um APK/AAB release assinado com uma chave privada permanente e remova permissao de trafego HTTP claro da configuracao de producao.
