import 'package:flutter/material.dart';

import '../../core/models.dart';
import '../../core/input_masks.dart';

class FinanceToolsScreen extends StatefulWidget {
  const FinanceToolsScreen({
    super.key,
    required this.snapshot,
    required this.isGuest,
    required this.onSaveTransaction,
    this.onAskAi,
    this.initialIndex = 0,
    this.initialCardId,
  });

  final FinancialSnapshot snapshot;
  final bool isGuest;
  final Future<void> Function(FinancialTransaction transaction) onSaveTransaction;
  final Future<String> Function(String question, Map<String, dynamic> summary)? onAskAi;
  final int initialIndex;
  final int? initialCardId;

  @override
  State<FinanceToolsScreen> createState() => _FinanceToolsScreenState();
}

class _FinanceToolsScreenState extends State<FinanceToolsScreen> {
  DateTime _invoiceMonth = DateTime(DateTime.now().year, DateTime.now().month);
  late List<FinancialTransaction> _invoiceTransactions;
  final Set<String> _payingInvoices = <String>{};
  int? _selectedCardId;

  @override
  void initState() {
    super.initState();
    _invoiceTransactions = List<FinancialTransaction>.from(widget.snapshot.transactions);
    final activeCards = widget.snapshot.cards.where((card) => card.active).toList();
    _selectedCardId = widget.initialCardId ?? (activeCards.isNotEmpty ? activeCards.first.id : null);
  }

  String money(num value) => 'R\$ ${value.toStringAsFixed(2).replaceAll('.', ',')}';

  DateTime? _date(String raw) {
    try {
      final value = raw.substring(0, 10);
      if (value.contains('/')) {
        final parts = value.split('/');
        return DateTime(int.parse(parts[2]), int.parse(parts[1]), int.parse(parts[0]));
      }
      return DateTime.parse(value);
    } catch (_) {
      return null;
    }
  }

  Iterable<FinancialTransaction> get cashTransactions =>
      widget.snapshot.transactions.where((t) => !t.isCard || t.source == 'card_payment');

  double get income => cashTransactions
      .where((t) => t.type == 'income')
      .fold<double>(0, (sum, t) => sum + t.amount.abs());

  double get expense => cashTransactions
      .where((t) => t.type != 'income')
      .fold<double>(0, (sum, t) => sum + t.amount.abs());

  double get balance {
    final balances = widget.snapshot.accounts
        .map((account) => account.currentBalance)
        .whereType<double>()
        .toList();
    if (balances.isNotEmpty) return balances.fold<double>(0, (a, b) => a + b);
    return income - expense;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Central de faturas')),
      body: _invoices(),
    );
  }

  Widget _metric(String title, String value) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title),
            const SizedBox(height: 5),
            Text(value, style: Theme.of(context).textTheme.titleMedium),
          ],
        ),
      ),
    );
  }

  Widget _forecast() {
    final now = DateTime.now();
    final future = cashTransactions.where((t) {
      final date = _date(t.date);
      return date != null && date.isAfter(now);
    }).toList()
      ..sort((a, b) => (_date(a.date) ?? now).compareTo(_date(b.date) ?? now));

    double projected = balance;
    final limit = now.add(const Duration(days: 90));
    for (final transaction in future) {
      final date = _date(transaction.date);
      if (date == null || !date.isBefore(limit)) continue;
      projected += transaction.type == 'income'
          ? transaction.amount.abs()
          : -transaction.amount.abs();
    }

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text('Previsão financeira', style: Theme.of(context).textTheme.headlineSmall),
        const Text('Projeção dos compromissos já cadastrados para os próximos 90 dias.'),
        const SizedBox(height: 12),
        Row(
          children: [
            Expanded(child: _metric('Saldo atual', money(balance))),
            const SizedBox(width: 8),
            Expanded(child: _metric('Projeção em 90 dias', money(projected))),
          ],
        ),
        const SizedBox(height: 12),
        Text('Próximos compromissos', style: Theme.of(context).textTheme.titleMedium),
        if (future.isEmpty)
          const Card(child: ListTile(title: Text('Nenhum compromisso futuro encontrado.'))),
        ...future.take(30).map(
          (t) => Card(
            child: ListTile(
              leading: Icon(t.type == 'income' ? Icons.arrow_downward : Icons.arrow_upward),
              title: Text(t.description),
              subtitle: Text(t.date),
              trailing: Text(money(t.type == 'income' ? t.amount.abs() : -t.amount.abs())),
            ),
          ),
        ),
      ],
    );
  }

  Widget _intelligence() {
    final cardSpend = widget.snapshot.transactions
        .where((t) => t.isCard && t.source != 'card_payment')
        .fold<double>(0, (sum, t) => sum + t.amount.abs());
    final byCategory = <int?, double>{};
    for (final transaction in cashTransactions.where((t) => t.type != 'income')) {
      byCategory[transaction.categoryId] =
          (byCategory[transaction.categoryId] ?? 0) + transaction.amount.abs();
    }
    MapEntry<int?, double>? top;
    for (final entry in byCategory.entries) {
      if (top == null || entry.value > top.value) top = entry;
    }
    String categoryName = 'Sem categoria';
    if (top?.key != null) {
      for (final category in widget.snapshot.categories) {
        if (category.id == top!.key) {
          categoryName = category.name;
          break;
        }
      }
    }

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text('Inteligência financeira', style: Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height: 8),
        Row(
          children: [
            Expanded(child: _metric('Saldo', money(balance))),
            const SizedBox(width: 8),
            Expanded(child: _metric('Compras no cartão', money(cardSpend))),
          ],
        ),
        if (top != null) _metric('Maior grupo de despesas', '$categoryName • ${money(top.value)}'),
        _metric(
          'Taxa de economia',
          income > 0 ? '${(((income - expense) / income) * 100).toStringAsFixed(1)}%' : 'Sem entradas suficientes',
        ),
        const SizedBox(height: 10),
        if (widget.isGuest)
          const Card(
            child: ListTile(
              leading: Icon(Icons.info_outline),
              title: Text('IA online indisponível no modo sem cadastro'),
              subtitle: Text('Os indicadores locais continuam disponíveis. Entre com uma conta para conversar com a IA.'),
            ),
          )
        else
          _AiBox(
            onAsk: widget.onAskAi,
            summary: {
              'saldo': balance,
              'entradas': income,
              'saidas': expense,
              'gastos_cartao': cardSpend,
              'contas': widget.snapshot.accounts.length,
              'cartoes': widget.snapshot.cards.length,
              'transacoes': widget.snapshot.transactions.length,
            },
          ),
      ],
    );
  }

  int _daysInMonth(int year, int month) => DateTime(year, month + 1, 0).day;

  DateTime _dayInMonth(DateTime month, int day) {
    final safe = day.clamp(1, _daysInMonth(month.year, month.month)).toInt();
    return DateTime(month.year, month.month, safe);
  }

  DateTime _invoiceClosing(CreditCardInfo card, DateTime month) {
    return _dayInMonth(month, card.closingDay ?? _daysInMonth(month.year, month.month));
  }

  DateTime _invoiceStart(CreditCardInfo card, DateTime month) {
    final previous = DateTime(month.year, month.month - 1);
    return _invoiceClosing(card, previous).add(const Duration(days: 1));
  }

  DateTime? _invoiceDue(CreditCardInfo card, DateTime closing) {
    final dueDay = card.dueDay;
    if (dueDay == null) return null;
    var month = DateTime(closing.year, closing.month);
    if (dueDay <= closing.day) month = DateTime(closing.year, closing.month + 1);
    return _dayInMonth(month, dueDay);
  }

  String _isoDay(DateTime value) =>
      '${value.year}-${value.month.toString().padLeft(2, '0')}-${value.day.toString().padLeft(2, '0')}';

  String _brDay(DateTime? value) {
    if (value == null) return 'não informado';
    return '${value.day.toString().padLeft(2, '0')}/${value.month.toString().padLeft(2, '0')}/${value.year}';
  }

  String _monthTitle(DateTime value) {
    const names = ['Janeiro','Fevereiro','Março','Abril','Maio','Junho','Julho','Agosto','Setembro','Outubro','Novembro','Dezembro'];
    return '${names[value.month - 1]} de ${value.year}';
  }

  String _invoiceKey(CreditCardInfo card, DateTime closing) =>
      'invoice_payment:${card.id}:${_isoDay(closing)}';

  List<FinancialTransaction> _allInvoiceTransactions() {
    // A Central mantém uma cópia local durante a navegação. Assim o pagamento
    // confirmado aparece imediatamente sem depender de sair e entrar na tela.
    // Também eliminamos duplicatas visuais caso o mesmo lançamento já tenha
    // chegado pelo snapshot do servidor.
    final result = <FinancialTransaction>[];
    final seen = <String>{};
    for (final t in _invoiceTransactions) {
      final key = t.externalTransactionId?.isNotEmpty == true
          ? 'external:${t.externalTransactionId}'
          : 'tx:${t.id}:${t.date}:${t.amount}:${t.source}:${t.cardId ?? 0}';
      if (seen.add(key)) result.add(t);
    }
    return result;
  }

  bool _isLegacyPaymentForInvoice(
    FinancialTransaction t,
    CreditCardInfo card,
    DateTime start,
    DateTime due,
  ) {
    if (t.source != 'card_payment' || t.cardId != null) return false;
    if (!t.description.toLowerCase().contains('final ${card.lastFour}'.toLowerCase())) return false;
    final d = _date(t.date);
    if (d == null) return false;
    final latest = due.add(const Duration(days: 15));
    return !d.isBefore(start) && !d.isAfter(latest);
  }

  Color _bankCardColor(String name) {
    final n = name.toLowerCase().replaceAll(' ', '');
    if (n.contains('nubank') || n == 'nu') return const Color(0xFF6F2DBD);
    if (n.contains('bancodobrasil') || n == 'bb' || n.contains('001')) return const Color(0xFFF4C400);
    if (n.contains('itau') || n.contains('341')) return const Color(0xFFEC7000);
    if (n.contains('bradesco') || n.contains('237')) return const Color(0xFFCC092F);
    if (n.contains('santander') || n.contains('033')) return const Color(0xFFEC0000);
    if (n.contains('inter') || n.contains('077')) return const Color(0xFFFF7A00);
    if (n.contains('c6') || n.contains('336')) return const Color(0xFF202124);
    if (n.contains('caixa') || n.contains('104')) return const Color(0xFF005CA9);
    if (n.contains('picpay')) return const Color(0xFF11C76F);
    if (n.contains('mercadopago')) return const Color(0xFF009EE3);
    return const Color(0xFF1769E0);
  }

  Color _bankCardTextColor(String name) {
    final n = name.toLowerCase().replaceAll(' ', '');
    return (n.contains('bancodobrasil') || n == 'bb' || n.contains('001'))
        ? const Color(0xFF12335B)
        : Colors.white;
  }

  ({DateTime closing, DateTime? due, List<FinancialTransaction> purchases, List<FinancialTransaction> payments, double total, double paid, double remaining, String status})
      _invoiceFor(CreditCardInfo card, DateTime month) {
    final closing = _invoiceClosing(card, month);
    final start = _invoiceStart(card, month);
    final due = _invoiceDue(card, closing);
    final all = _allInvoiceTransactions();
    final purchases = all.where((t) {
      if (t.cardId != card.id || t.source == 'card_payment') return false;
      final d = _date(t.date);
      return d != null && !d.isBefore(start) && !d.isAfter(closing);
    }).toList();
    final total = (-purchases.fold<double>(0, (sum, t) => sum + t.amount)).clamp(0, double.infinity).toDouble();
    final key = _invoiceKey(card, closing);
    final paymentLimit = due ?? closing.add(const Duration(days: 45));
    final payments = all.where((t) {
      if (t.source != 'card_payment') return false;
      if (t.externalTransactionId?.startsWith(key) == true) return true;
      if (t.cardId == card.id && t.purchaseDate?.startsWith(_isoDay(closing)) == true) return true;
      return _isLegacyPaymentForInvoice(t, card, start, paymentLimit);
    }).toList();
    final paid = payments.fold<double>(0, (sum, t) => sum + t.amount.abs());
    final remaining = (total - paid).clamp(0, double.infinity).toDouble();
    String status = 'Sem fatura';
    if (total > 0.005) {
      if (remaining <= 0.005) {
        status = 'Paga';
      } else if (due != null && due.isBefore(DateTime(DateTime.now().year, DateTime.now().month, DateTime.now().day))) {
        status = 'Vencida';
      } else if (due != null && due.difference(DateTime(DateTime.now().year, DateTime.now().month, DateTime.now().day)).inDays <= 5) {
        status = 'Próxima do vencimento';
      } else {
        status = 'Em aberto';
      }
    }
    return (closing: closing, due: due, purchases: purchases, payments: payments, total: total, paid: paid, remaining: remaining, status: status);
  }

  Color _statusColor(String status) => switch (status) {
        'Paga' => Colors.green,
        'Vencida' => Colors.red,
        'Próxima do vencimento' => Colors.orange,
        'Em aberto' => Colors.blue,
        _ => Colors.grey,
      };

  Widget _invoices() {
    final cards = widget.snapshot.cards.where((card) => card.active).toList();
    if (_selectedCardId == null && cards.isNotEmpty) _selectedCardId = cards.first.id;
    if (_selectedCardId != null && !cards.any((c) => c.id == _selectedCardId) && cards.isNotEmpty) {
      _selectedCardId = cards.first.id;
    }
    final selectedMatches = cards.where((c) => c.id == _selectedCardId).toList();
    final selected = selectedMatches.isEmpty ? null : selectedMatches.first;
    final invoice = selected == null ? null : _invoiceFor(selected, _invoiceMonth);

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text('Central de faturas', style: Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height: 4),
        const Text('Escolha um cartão e navegue pelos meses para consultar a fatura correspondente.'),
        const SizedBox(height: 14),
        if (cards.isEmpty)
          const Card(child: ListTile(title: Text('Nenhum cartão cadastrado.')))
        else
          SizedBox(
            height: 160,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              itemCount: cards.length,
              separatorBuilder: (_, __) => const SizedBox(width: 12),
              itemBuilder: (context, index) {
                final card = cards[index];
                final isSelected = card.id == _selectedCardId;
                final summary = _invoiceFor(card, _invoiceMonth);
                final bg = _bankCardColor(card.bankName);
                final fg = _bankCardTextColor(card.bankName);
                return InkWell(
                  borderRadius: BorderRadius.circular(20),
                  onTap: () => setState(() => _selectedCardId = card.id),
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 180),
                    width: 270,
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      color: bg,
                      borderRadius: BorderRadius.circular(20),
                      border: Border.all(color: isSelected ? Theme.of(context).colorScheme.primary : Colors.transparent, width: isSelected ? 3 : 1),
                      boxShadow: [BoxShadow(blurRadius: isSelected ? 14 : 8, offset: const Offset(0, 5), color: Colors.black.withValues(alpha: isSelected ? .22 : .12))],
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Row(
                          children: [
                            Expanded(child: Text(card.nickname?.trim().isNotEmpty == true ? card.nickname!.trim() : card.bankName, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: fg, fontWeight: FontWeight.w800, fontSize: 16))),
                            Icon(Icons.credit_card, color: fg),
                          ],
                        ),
                        Text('${card.brand}  •••• ${card.lastFour}', style: TextStyle(color: fg.withValues(alpha: .9))),
                        Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                          Text('Fatura do mês', style: TextStyle(color: fg.withValues(alpha: .78), fontSize: 12)),
                          Text(money(summary.remaining), style: TextStyle(color: fg, fontWeight: FontWeight.w800, fontSize: 22)),
                        ]),
                      ],
                    ),
                  ),
                );
              },
            ),
          ),
        const SizedBox(height: 14),
        Row(
          children: [
            IconButton(tooltip: 'Mês anterior', onPressed: () => setState(() => _invoiceMonth = DateTime(_invoiceMonth.year, _invoiceMonth.month - 1)), icon: const Icon(Icons.chevron_left)),
            Expanded(child: Text(_monthTitle(_invoiceMonth), textAlign: TextAlign.center, style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800))),
            IconButton(tooltip: 'Próximo mês', onPressed: () => setState(() => _invoiceMonth = DateTime(_invoiceMonth.year, _invoiceMonth.month + 1)), icon: const Icon(Icons.chevron_right)),
          ],
        ),
        const SizedBox(height: 8),
        if (selected != null && invoice != null)
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text(selected.nickname?.trim().isNotEmpty == true ? selected.nickname!.trim() : selected.bankName, style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800)),
                        Text('Fatura de ${_monthTitle(_invoiceMonth)}', style: Theme.of(context).textTheme.bodySmall),
                      ])),
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                        decoration: BoxDecoration(color: _statusColor(invoice.status).withValues(alpha: .13), borderRadius: BorderRadius.circular(999)),
                        child: Text(invoice.status, style: TextStyle(color: _statusColor(invoice.status), fontWeight: FontWeight.w800)),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  if (invoice.total <= 0.005) ...[
                    const Text('Sem fatura neste mês', style: TextStyle(fontWeight: FontWeight.w700)),
                    const SizedBox(height: 4),
                    Text('Não houve valor a pagar neste ciclo. Este mês não gera aviso nem notificação.', style: Theme.of(context).textTheme.bodySmall),
                  ] else ...[
                    Text('Total: ${money(invoice.total)}'),
                    Text('Pago: ${money(invoice.paid)} • Em aberto: ${money(invoice.remaining)}'),
                    Text('Fechamento: ${_brDay(invoice.closing)} • Vencimento: ${_brDay(invoice.due)}', style: Theme.of(context).textTheme.bodySmall),
                  ],
                  const Divider(height: 26),
                  const Text('Movimentações da fatura', style: TextStyle(fontWeight: FontWeight.w800)),
                  const SizedBox(height: 6),
                  if (invoice.purchases.isEmpty)
                    const Text('Nenhuma compra neste período.')
                  else
                    ...([...invoice.purchases]..sort((a, b) => b.date.compareTo(a.date))).map((t) => ListTile(
                          contentPadding: EdgeInsets.zero,
                          dense: true,
                          title: Text(t.description),
                          subtitle: Text(_brDay(_date(t.date))),
                          trailing: Text(money(t.amount.abs()), style: const TextStyle(fontWeight: FontWeight.w700)),
                        )),
                  if (invoice.payments.isNotEmpty) ...[
                    const Divider(),
                    const Text('Pagamentos', style: TextStyle(fontWeight: FontWeight.w800)),
                    ...([...invoice.payments]..sort((a, b) => b.date.compareTo(a.date))).map((t) => ListTile(
                          contentPadding: EdgeInsets.zero,
                          dense: true,
                          leading: const Icon(Icons.payments_outlined),
                          title: Text(money(t.amount.abs())),
                          subtitle: Text(_paymentDateTime(t.date)),
                        )),
                  ],
                  if (invoice.total > 0.005 && invoice.remaining > 0.005) ...[
                    const SizedBox(height: 8),
                    SizedBox(
                      width: double.infinity,
                      child: FilledButton.icon(
                        onPressed: _payingInvoices.contains(_invoiceKey(selected, invoice.closing)) ? null : () => _payInvoice(selected, invoice.closing, invoice.remaining, _invoiceKey(selected, invoice.closing)),
                        icon: const Icon(Icons.payments_outlined),
                        label: const Text('Pagar fatura'),
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ),
      ],
    );
  }

  Future<void> _payInvoice(
    CreditCardInfo card,
    DateTime closing,
    double suggestedTotal,
    String invoiceKey,
  ) async {
    if (_payingInvoices.contains(invoiceKey)) {
      return;
    }
    if (widget.snapshot.accounts.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Cadastre uma conta antes de pagar a fatura.')),
      );
      return;
    }

    int accountId = widget.snapshot.accounts.first.id;
    final amountController = TextEditingController(text: brlText(suggestedTotal));
    DateTime paymentDate = DateTime.now();

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (dialogContext, setDialogState) => AlertDialog(
          title: Text('Pagar fatura • final ${card.lastFour}'),
          content: SizedBox(
            width: 460,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Align(
                    alignment: Alignment.centerLeft,
                    child: Text('Fechamento: ${_brDay(closing)} • Vencimento: ${_brDay(_invoiceDue(card, closing))}'),
                  ),
                  const SizedBox(height: 8),
                  DropdownButtonFormField<int>(
                    initialValue: accountId,
                    decoration: const InputDecoration(labelText: 'Conta de débito'),
                    items: widget.snapshot.accounts
                        .map((a) => DropdownMenuItem(value: a.id, child: Text(a.institutionName)))
                        .toList(),
                    onChanged: (value) {
                      if (value != null) setDialogState(() => accountId = value);
                    },
                  ),
                  TextField(
                    controller: amountController,
                    keyboardType: TextInputType.number,
                    inputFormatters: const [BrlMoneyInputFormatter()],
                    decoration: const InputDecoration(labelText: 'Valor do pagamento'),
                  ),
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Data do pagamento'),
                    subtitle: Text(_brDay(paymentDate)),
                    trailing: const Icon(Icons.calendar_month),
                    onTap: () async {
                      final selected = await showDatePicker(
                        context: dialogContext,
                        initialDate: paymentDate,
                        firstDate: DateTime(2000),
                        lastDate: DateTime(2100),
                      );
                      if (selected != null) setDialogState(() => paymentDate = selected);
                    },
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Você pode pagar qualquer valor até o saldo restante. Cada pagamento gera uma saída na conta e fica registrado no histórico desta fatura.',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(dialogContext, false), child: const Text('Cancelar')),
            FilledButton(onPressed: () => Navigator.pop(dialogContext, true), child: const Text('Confirmar pagamento')),
          ],
        ),
      ),
    );

    if (confirmed != true) return;
    final amount = brlValue(amountController.text);
    if (amount <= 0 || amount - suggestedTotal > 0.005) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Informe um valor entre R\$ 0,01 e ${money(suggestedTotal)}.')),
        );
      }
      return;
    }

    setState(() => _payingInvoices.add(invoiceKey));
    final now = DateTime.now();
    final id = now.microsecondsSinceEpoch.remainder(2147483647);
    final paymentMoment = DateTime(paymentDate.year, paymentDate.month, paymentDate.day, now.hour, now.minute, now.second);
    final payment = FinancialTransaction(
      id: id,
      date: paymentMoment.toIso8601String(),
      description: 'Pagamento de fatura do cartão (final ${card.lastFour}), banco ${card.bankName}',
      amount: amount,
      type: 'expense',
      accountId: accountId,
      cardId: card.id,
      source: 'card_payment',
      purchaseDate: '${_isoDay(closing)}T00:00:00',
      externalTransactionId: '$invoiceKey:${now.microsecondsSinceEpoch}',
    );
    try {
      await widget.onSaveTransaction(payment);
      if (!mounted) return;
      setState(() {
        // Atualização otimista da própria Central: total pago, saldo restante,
        // status e histórico mudam no mesmo instante. Não adicionamos uma
        // segunda lista de pagamentos, evitando exibição duplicada.
        final alreadyPresent = _invoiceTransactions.any((t) =>
            t.externalTransactionId == payment.externalTransactionId ||
            (t.id == payment.id && t.source == payment.source));
        if (!alreadyPresent) {
          _invoiceTransactions.insert(0, payment);
        }
        _payingInvoices.remove(invoiceKey);
      });
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Pagamento de ${money(amount)} registrado na conta.')),
      );
    } catch (error) {
      if (!mounted) return;
      setState(() => _payingInvoices.remove(invoiceKey));
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Não foi possível registrar o pagamento: $error')),
      );
    }
  }


  String _paymentDateTime(String raw) {
    final value = DateTime.tryParse(raw);
    if (value == null) return raw;
    final date = '${value.day.toString().padLeft(2, '0')}/${value.month.toString().padLeft(2, '0')}/${value.year}';
    final time = '${value.hour.toString().padLeft(2, '0')}:${value.minute.toString().padLeft(2, '0')}';
    return '$date - $time';
  }


  String _recurrenceKey(String raw) {
    var value = raw.toLowerCase();
    const accents = 'áàâãäéèêëíìîïóòôõöúùûüç';
    const plain = 'aaaaaeeeeiiiiooooouuuuc';
    for (var i = 0; i < accents.length; i++) {
      value = value.replaceAll(accents[i], plain[i]);
    }
    value = value.replaceAll(RegExp(r'[^a-z0-9 ]'), ' ');
    value = value.replaceAll(RegExp(r'\b\d{1,4}\b'), ' ');
    return value.replaceAll(RegExp(r'\s+'), ' ').trim();
  }

  List<_RecurrencePattern> _detectRecurrences() {
    final groups = <String, List<FinancialTransaction>>{};
    for (final transaction in cashTransactions) {
      if (transaction.source == 'card_purchase') continue;
      final key = _recurrenceKey(transaction.description);
      if (key.length < 3) continue;
      groups.putIfAbsent(key, () => []).add(transaction);
    }
    final result = <_RecurrencePattern>[];
    for (final entry in groups.entries) {
      if (entry.value.length < 3) continue;
      final dated = entry.value
          .map((t) => MapEntry(_date(t.date), t))
          .where((e) => e.key != null)
          .map((e) => MapEntry(e.key!, e.value))
          .toList()
        ..sort((a, b) => a.key.compareTo(b.key));
      if (dated.length < 3) continue;
      final incomeRows = dated.where((e) => e.value.type == 'income').length;
      if (incomeRows != 0 && incomeRows != dated.length) continue;
      var monthlyGaps = 0;
      for (var i = 1; i < dated.length; i++) {
        final gap = dated[i].key.difference(dated[i - 1].key).inDays;
        if (gap >= 20 && gap <= 40) monthlyGaps++;
      }
      if (monthlyGaps < ((dated.length - 1) * .6)) continue;
      final amounts = dated.map((e) => e.value.amount.abs()).toList()..sort();
      final median = amounts[amounts.length ~/ 2];
      if (median <= 0) continue;
      final stable = amounts.where((a) => (a - median).abs() / median <= .15).length;
      if (stable < amounts.length * .7) continue;
      final last = dated.last;
      result.add(_RecurrencePattern(
        description: last.value.description,
        amount: median,
        isIncome: last.value.type == 'income',
        dayOfMonth: last.key.day,
        lastDate: last.key,
        occurrences: dated.length,
      ));
    }
    result.sort((a, b) => b.amount.compareTo(a.amount));
    return result;
  }

  DateTime _nextRecurrence(_RecurrencePattern pattern) {
    final nextMonth = pattern.lastDate.month == 12
        ? DateTime(pattern.lastDate.year + 1, 1, 1)
        : DateTime(pattern.lastDate.year, pattern.lastDate.month + 1, 1);
    final following = nextMonth.month == 12
        ? DateTime(nextMonth.year + 1, 1, 1)
        : DateTime(nextMonth.year, nextMonth.month + 1, 1);
    final lastDay = following.subtract(const Duration(days: 1)).day;
    return DateTime(nextMonth.year, nextMonth.month,
        pattern.dayOfMonth > lastDay ? lastDay : pattern.dayOfMonth);
  }

  Widget _recurrences() {
    final patterns = _detectRecurrences();
    final recurringIncome = patterns.where((p) => p.isIncome).fold<double>(0, (s, p) => s + p.amount);
    final recurringExpense = patterns.where((p) => !p.isIncome).fold<double>(0, (s, p) => s + p.amount);
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text('Recorrências detectadas', style: Theme.of(context).textTheme.headlineSmall),
        const Text('O FinanceApp procura lançamentos semelhantes que se repetem mensalmente. Compras de cartão não entram nesta detecção.'),
        const SizedBox(height: 12),
        Row(children: [
          Expanded(child: _metric('Entradas recorrentes/mês', money(recurringIncome))),
          const SizedBox(width: 8),
          Expanded(child: _metric('Despesas recorrentes/mês', money(recurringExpense))),
        ]),
        const SizedBox(height: 12),
        if (patterns.isEmpty)
          const Card(child: ListTile(
            leading: Icon(Icons.repeat),
            title: Text('Nenhum padrão mensal confirmado ainda'),
            subtitle: Text('São necessárias pelo menos 3 ocorrências semelhantes, com datas e valores consistentes.'),
          )),
        ...patterns.map((pattern) {
          final next = _nextRecurrence(pattern);
          final date = '${next.day.toString().padLeft(2, '0')}/${next.month.toString().padLeft(2, '0')}/${next.year}';
          return Card(child: ListTile(
            leading: Icon(pattern.isIncome ? Icons.south_west : Icons.north_east),
            title: Text(pattern.description),
            subtitle: Text('${pattern.occurrences} ocorrências • próxima estimativa: $date'),
            trailing: Text('${pattern.isIncome ? '+' : '-'} ${money(pattern.amount)}'),
          ));
        }),
        const SizedBox(height: 8),
        const Text('A detecção é uma estimativa baseada no histórico e não cria lançamentos automaticamente.'),
      ],
    );
  }

  Widget _settings() {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text('Configurações do aplicativo', style: Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height: 12),
        Card(
          child: Column(
            children: [
              const ListTile(
                leading: Icon(Icons.palette_outlined),
                title: Text('Aparência'),
                subtitle: Text('Tema claro, escuro ou do sistema está disponível no menu principal.'),
              ),
              const Divider(height: 1),
              ListTile(
                leading: const Icon(Icons.storage_outlined),
                title: const Text('Armazenamento local'),
                subtitle: Text(widget.isGuest
                    ? 'Modo sem cadastro: dados salvos neste navegador/dispositivo.'
                    : 'Os dados locais permitem abrir o aplicativo mesmo antes da próxima sincronização.'),
              ),
              const Divider(height: 1),
              ListTile(
                leading: const Icon(Icons.cloud_sync_outlined),
                title: const Text('Sincronização'),
                subtitle: Text(widget.isGuest
                    ? 'Crie/acesse uma conta para habilitar backup no servidor.'
                    : 'Com cadastro, o servidor é usado para backup e sincronização entre dispositivos.'),
              ),
            ],
          ),
        ),
        const Card(
          child: ListTile(
            leading: Icon(Icons.info_outline),
            title: Text('Migração multiplataforma'),
            subtitle: Text('Esta etapa também adiciona a detecção de recorrências mensais do Android à versão multiplataforma. Os próximos módulos serão portados sem remover as funções já existentes.'),
          ),
        ),
      ],
    );
  }
}

class _AiBox extends StatefulWidget {
  const _AiBox({required this.onAsk, required this.summary});
  final Future<String> Function(String, Map<String, dynamic>)? onAsk;
  final Map<String, dynamic> summary;

  @override
  State<_AiBox> createState() => _AiBoxState();
}

class _AiBoxState extends State<_AiBox> {
  final controller = TextEditingController();
  String? answer;
  bool busy = false;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Converse com a IA', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            TextField(
              controller: controller,
              minLines: 2,
              maxLines: 4,
              decoration: const InputDecoration(
                border: OutlineInputBorder(),
                hintText: 'Ex.: onde posso reduzir meus gastos?',
              ),
            ),
            const SizedBox(height: 8),
            FilledButton.icon(
              onPressed: busy || widget.onAsk == null
                  ? null
                  : () async {
                      if (controller.text.trim().length < 2) return;
                      setState(() => busy = true);
                      try {
                        answer = await widget.onAsk!(controller.text.trim(), widget.summary);
                      } catch (error) {
                        answer = error.toString().replaceFirst('Exception: ', '');
                      } finally {
                        if (mounted) setState(() => busy = false);
                      }
                    },
              icon: busy
                  ? const SizedBox.square(
                      dimension: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.auto_awesome),
              label: const Text('Perguntar à IA'),
            ),
            if (answer != null)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: SelectableText(answer!),
              ),
          ],
        ),
      ),
    );
  }
}


class _RecurrencePattern {
  const _RecurrencePattern({
    required this.description,
    required this.amount,
    required this.isIncome,
    required this.dayOfMonth,
    required this.lastDate,
    required this.occurrences,
  });
  final String description;
  final double amount;
  final bool isIncome;
  final int dayOfMonth;
  final DateTime lastDate;
  final int occurrences;
}
