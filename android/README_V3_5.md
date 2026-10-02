# Finance App Android V3.5

Novidades:
- contas exibem `Conectado`, `Desconectado` ou `Manual`;
- exclusão de conta com confirmação;
- exclusão da conta remove também as transações;
- botão mostrar/ocultar senha;
- Sign in with Google via Credential Manager;
- acesso posterior com biometria usando o refresh token da sessão anterior.

## Google Sign-In

No `app/build.gradle.kts`, troque:

`COLE_SEU_WEB_CLIENT_ID_AQUI`

pelo **Web Client ID** do Google Auth Platform.

No backend V10.2, coloque o mesmo valor no `.env`:

`GOOGLE_CLIENT_ID=...`

O Android envia o ID token ao backend e o backend valida o token antes de criar a sessão.

## Biometria

Depois de um login válido, o refresh token é preservado para login rápido.
O app nunca salva a senha em texto.
