# Finance App Android V3.6

- restauração automática de contas/transações após login em outro aparelho;
- Room reconstruído por `GET /sync/snapshot`;
- configurações mostram última sincronização;
- botão `Sincronizar agora`;
- Google Sign-In mostra o erro real quando falhar;
- biometria continua usando refresh token, sem salvar senha em texto.

Ainda é necessário configurar `GOOGLE_WEB_CLIENT_ID` no Android e `GOOGLE_CLIENT_ID` no backend com o mesmo Web Client ID.
