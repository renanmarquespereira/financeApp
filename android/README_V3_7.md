# Finance App Android V3.7

- restauração automática do snapshot do servidor após todo login, inclusive logout/login na mesma execução;
- o usuário não precisa tocar em Sincronizar para recuperar os dados do PostgreSQL;
- transações organizadas por mês em um pager horizontal;
- mês e ano aparecem no topo;
- deslize para a esquerda/direita para navegar entre os meses;
- resumo de saldo, entradas, saídas e quantidade é calculado por mês;
- filtros por origem, tipo, banco e período continuam disponíveis;
- o filtro de mês foi removido porque a própria navegação mensal passa a cumprir essa função.

O PostgreSQL/FastAPI continua sendo a fonte principal de backup. O Room é cache/restauração local.
