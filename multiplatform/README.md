# FinanceApp Multiplataforma - Fase 3

Frente Flutter para Android, iOS e Web, convivendo com o Android Kotlin atual.

## Entregue nesta fase
- cache financeiro local por Workspace usando armazenamento compatível com Android/iOS/Web;
- sincronização de snapshot com o backend real;
- modo de leitura offline com o último cache disponível;
- dashboard sem somar compras de cartão ao consolidado da conta;
- abas Transações e Contas/Cartões permanentemente disponíveis;
- listagem inicial de transações, contas e cartões;
- troca de Workspace recarrega cache e tenta sincronizar;
- pull-to-refresh e botão de sincronização.

## Executar
    flutter create --platforms=android,ios,web .
    flutter pub get
    flutter run -d chrome --dart-define=API_BASE_URL=http://SEU_IP:8000

## Próxima etapa
Edição/criação offline com fila de operações e resolução de sincronização, mantendo as regras financeiras do app Kotlin.
