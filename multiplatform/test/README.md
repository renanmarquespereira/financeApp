# Validação da Fase 3

1. Faça login online e selecione um Workspace.
2. Confirme que Início, Transações e Contas/Cartões carregam os dados do backend.
3. Feche o app, interrompa o backend/rede e abra novamente: o último snapshot deve permanecer visível.
4. Compras com card_id aparecem como Cartão e não entram no saldo consolidado da conta.
5. Troque de Workspace e confirme que cada um mantém cache separado.
6. Reative a rede e use Sincronizar ou pull-to-refresh para atualizar o cache.
