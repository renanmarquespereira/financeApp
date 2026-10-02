# FinanceApp Apple

O projeto Flutter `multiplatform` e compartilhado entre Web, iPhone/iPad e macOS. Assim, o dashboard, busca global, metas, orcamentos, privacidade de saldo e os refinamentos visuais feitos no Web seguem para Apple pelo mesmo codigo.

## Preparar no Mac

Execute:

```bash
chmod +x apple/PREPARAR_APPLE.command
./apple/PREPARAR_APPLE.command
```

O script cria/atualiza `ios/` e `macos/`, configura permissao de camera para a leitura de boleto/QR e executa `flutter pub get`.

A assinatura final para iPhone/iPad precisa ser configurada no Xcode em Signing & Capabilities.
