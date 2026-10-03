import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../core/api_client.dart';
import '../../core/forecast_store.dart';
import '../../core/input_masks.dart';
import '../../core/models.dart';

const plannedDebtPrefix = 'Prevista • Dívida • ';
bool isPlannedDebtTransaction(FinancialTransaction t) => t.description.toLowerCase().startsWith(plannedDebtPrefix.toLowerCase());

String _canonicalDebtName(String raw) {
  final value = raw.trim();
  final lower = value.toLowerCase();
  final indexes = <int>[];
  for (final marker in const [' • credor:', ' • parcela']) {
    final i = lower.indexOf(marker);
    if (i >= 0) indexes.add(i);
  }
  final cut = indexes.isEmpty ? value.length : indexes.reduce((a, b) => a < b ? a : b);
  return value.substring(0, cut).trim();
}

String? _debtNameFromDescription(String description) {
  String body;
  if (description.toLowerCase().startsWith(plannedDebtPrefix.toLowerCase())) {
    body = description.substring(plannedDebtPrefix.length);
  } else if (description.toLowerCase().startsWith('pagamento de dívida •')) {
    final i = description.indexOf('•');
    body = i >= 0 ? description.substring(i + 1) : description;
  } else {
    return null;
  }
  final name = _canonicalDebtName(body);
  return name.isEmpty ? null : name;
}

String? debtCreditorFromTransactionDescription(String description) {
  final m = RegExp(r'•\s*Credor:\s*(.*?)\s*(?:•\s*Parcela|$)', caseSensitive: false).firstMatch(description);
  final value = m?.group(1)?.trim() ?? '';
  return value.isEmpty ? null : value;
}

int? _debtInstallmentNumber(FinancialTransaction t) =>
    int.tryParse(RegExp(r'Parcela\s+(\d+)/', caseSensitive: false).firstMatch(t.description)?.group(1) ?? '');

int? _debtInstallmentTotal(FinancialTransaction t) =>
    int.tryParse(RegExp(r'Parcela\s+\d+/(\d+)', caseSensitive: false).firstMatch(t.description)?.group(1) ?? '');

int _stableDebtId(String name, DateTime firstDue) {
  final raw = '${name.toLowerCase()}|${_iso(firstDue)}';
  var hash = 17;
  for (final unit in raw.codeUnits) {
    hash = ((hash * 31) + unit) & 0x7fffffff;
  }
  if (hash == 0) hash = 1;
  return -hash;
}

class _DebtPayment {
  const _DebtPayment({required this.amount, required this.date, this.accountId, this.installments = 1});
  final double amount;
  final String date;
  final int? accountId;
  final int installments;
  Map<String, dynamic> toJson() => {'amount': amount, 'date': date, 'accountId': accountId, 'installments': installments};
  factory _DebtPayment.fromJson(Map<String, dynamic> j) => _DebtPayment(
        amount: (j['amount'] as num?)?.toDouble() ?? 0,
        date: (j['date'] ?? '').toString(),
        accountId: (j['accountId'] as num?)?.toInt(),
        installments: ((j['installments'] as num?)?.toInt() ?? 1).clamp(1, 360),
      );
}

class _Debt {
  const _Debt({
    required this.id,
    required this.name,
    required this.creditor,
    required this.installmentAmount,
    required this.installmentCount,
    required this.firstDueDate,
    required this.originalAmount,
    required this.payments,
  });
  final int id;
  final String name, creditor, firstDueDate;
  final double installmentAmount, originalAmount;
  final int installmentCount;
  final List<_DebtPayment> payments;
  double get paid => payments.fold(0.0, (a, b) => a + b.amount);
  double get remaining => (originalAmount - paid).clamp(0.0, double.infinity).toDouble();
  int get paidInstallments => payments.fold<int>(0, (a, b) => a + b.installments).clamp(0, installmentCount).toInt();
  DateTime get due {
    final d = DateTime.tryParse(firstDueDate) ?? DateTime.now();
    return _monthDate(d, paidInstallments);
  }
  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'creditor': creditor,
        'installmentAmount': installmentAmount,
        'installmentCount': installmentCount,
        'firstDueDate': firstDueDate,
        'dueDate': firstDueDate,
        'nextDueDate': _iso(due),
        'originalAmount': originalAmount,
        'payments': payments.map((e) => e.toJson()).toList(),
      };
  factory _Debt.fromJson(Map<String, dynamic> j) {
    final installment = (j['installmentAmount'] as num?)?.toDouble() ?? 0;
    final original = (j['originalAmount'] as num?)?.toDouble() ?? 0;
    final count = ((j['installmentCount'] as num?)?.toInt() ?? 0) > 0
        ? (j['installmentCount'] as num).toInt()
        : (installment > 0 ? (original / installment).ceil().clamp(1, 360).toInt() : 1);
    final due = (j['firstDueDate'] ?? j['dueDate'] ?? j['nextDueDate'] ?? DateTime.now().toIso8601String()).toString();
    return _Debt(
      id: (j['id'] as num).toInt(),
      name: (j['name'] ?? 'Dívida').toString(),
      creditor: (j['creditor'] ?? '').toString(),
      installmentAmount: installment > 0 ? installment : original,
      installmentCount: count,
      firstDueDate: due.substring(0, due.length >= 10 ? 10 : due.length),
      originalAmount: original > 0 ? original : installment * count,
      payments: (j['payments'] as List? ?? []).map((e) => _DebtPayment.fromJson(Map<String, dynamic>.from(e))).toList(),
    );
  }
}

List<_Debt> _normalizeDebtRecords(List<_Debt> rows) {
  final groups = <String, List<_Debt>>{};
  for (final debt in rows) {
    final cleanName = _canonicalDebtName(debt.name);
    final creditor = debt.creditor.trim().isNotEmpty
        ? debt.creditor.trim()
        : (debtCreditorFromTransactionDescription(debt.name) ?? '');
    final normalized = _Debt(
      id: debt.id,
      name: cleanName.isEmpty ? debt.name.trim() : cleanName,
      creditor: creditor,
      installmentAmount: debt.installmentAmount,
      installmentCount: debt.installmentCount,
      firstDueDate: debt.firstDueDate,
      originalAmount: debt.originalAmount,
      payments: debt.payments,
    );
    final key = '${normalized.name.toLowerCase()}|${normalized.creditor.toLowerCase()}';
    groups.putIfAbsent(key, () => <_Debt>[]).add(normalized);
  }

  return groups.values.map((group) {
    final preferred = group.where((d) => d.id > 0).firstOrNull ?? group.first;
    final paymentMap = <String, _DebtPayment>{};
    for (final d in group) {
      for (final p in d.payments) {
        paymentMap['${p.date}|${p.amount}|${p.accountId}|${p.installments}'] = p;
      }
    }
    final dueDates = group.map((d) => DateTime.tryParse(d.firstDueDate)).whereType<DateTime>().toList()..sort();
    final firstDue = dueDates.isEmpty ? preferred.firstDueDate : _iso(dueDates.first);
    final creditor = group.where((d) => d.creditor.trim().isNotEmpty).map((d) => d.creditor.trim()).firstOrNull ?? '';
    final installments = group.map((d) => d.installmentCount).reduce((a, b) => a > b ? a : b);
    final originals = group.map((d) => d.originalAmount).toList();
    final original = originals.reduce((a, b) => a > b ? a : b);
    final installment = group.map((d) => d.installmentAmount).where((v) => v > 0).firstOrNull ?? preferred.installmentAmount;
    return _Debt(
      id: preferred.id,
      name: group.first.name,
      creditor: creditor,
      installmentAmount: installment,
      installmentCount: installments,
      firstDueDate: firstDue,
      originalAmount: original,
      payments: paymentMap.values.toList(),
    );
  }).toList();
}

List<_Debt> _recoverDebtRecordsFromTransactions(
  List<FinancialTransaction> transactions,
  List<_Debt> existing,
) {
  final existingNames = existing.map((d) => d.name.trim().toLowerCase()).toSet();
  final groups = <String, List<FinancialTransaction>>{};
  for (final tx in transactions) {
    final isPayment = tx.description.toLowerCase().startsWith('pagamento de dívida •');
    if (!isPlannedDebtTransaction(tx) && !isPayment) continue;
    final name = _debtNameFromDescription(tx.description);
    if (name == null) continue;
    groups.putIfAbsent(name, () => <FinancialTransaction>[]).add(tx);
  }

  final result = <_Debt>[];
  for (final entry in groups.entries) {
    final name = entry.key;
    if (existingNames.contains(name.trim().toLowerCase())) continue;
    final rows = entry.value;
    final totals = rows.map(_debtInstallmentTotal).whereType<int>().toList();
    final numbers = rows.map(_debtInstallmentNumber).whereType<int>().toList();
    final allCounts = [...totals, ...numbers];
    final count = (allCounts.isEmpty ? rows.length.clamp(1, 360) : allCounts.reduce((a, b) => a > b ? a : b).clamp(1, 360)).toInt();
    final planned = rows.where(isPlannedDebtTransaction).toList();
    final refs = planned.isEmpty ? rows : planned;
    if (refs.isEmpty) continue;
    final installmentAmount = refs.map((t) => t.amount.abs()).where((v) => v > 0).fold<double>(0, (a, b) => a > b ? a : b);
    if (installmentAmount <= 0) continue;
    final dueCandidates = <DateTime>[];
    for (final tx in rows) {
      final date = DateTime.tryParse(tx.date);
      if (date == null) continue;
      final number = _debtInstallmentNumber(tx) ?? 1;
      dueCandidates.add(_monthDate(date, -(number - 1)));
    }
    dueCandidates.sort();
    final firstDue = dueCandidates.isEmpty ? _monthDate(DateTime.now(), 1) : dueCandidates.first;
    final creditor = rows.map((t) => debtCreditorFromTransactionDescription(t.description)).whereType<String>().firstOrNull ?? '';
    final payments = rows
        .where((t) => t.description.toLowerCase().startsWith('pagamento de dívida •'))
        .map((t) => _DebtPayment(amount: t.amount.abs(), date: _iso(DateTime.tryParse(t.date) ?? DateTime.now()), accountId: t.accountId, installments: 1))
        .toList();
    result.add(_Debt(
      id: _stableDebtId(name, firstDue),
      name: name,
      creditor: creditor,
      installmentAmount: installmentAmount,
      installmentCount: count,
      firstDueDate: _iso(firstDue),
      originalAmount: installmentAmount * count,
      payments: payments,
    ));
  }
  return result;
}

class DebtCenterController {
  final String workspaceId;
  final ApiClient api;
  final String? accessToken;
  final List<FinancialAccount> accounts;
  final List<FinancialTransaction> transactions;
  final Future<void> Function(FinancialTransaction) onSaveTransaction;
  final Map<String, FinancialTransaction> localPlanned = {};
  final ForecastStore _store;
  List<_Debt> debts = [];
  Set<int> deleted = {};

  factory DebtCenterController.create({
    required String workspaceId,
    required ApiClient api,
    String? accessToken,
    required List<FinancialAccount> accounts,
    required List<FinancialTransaction> transactions,
    required Future<void> Function(FinancialTransaction) onSaveTransaction,
  }) {
    return DebtCenterController._(
      workspaceId: workspaceId,
      api: api,
      accessToken: accessToken,
      accounts: accounts,
      transactions: transactions,
      onSaveTransaction: onSaveTransaction,
      store: ForecastStore(workspaceId),
    );
  }

  DebtCenterController._({
    required this.workspaceId,
    required this.api,
    this.accessToken,
    required this.accounts,
    required this.transactions,
    required this.onSaveTransaction,
    required ForecastStore store,
  }) : _store = store;

  Future<void> load() async {
    try {
      if (accessToken != null) await _store.sync(api, accessToken!);
    } catch (_) {}
    final p = await _store.read();
    final rawDebts = (p['debts'] as List? ?? []).map((e) => _Debt.fromJson(Map<String, dynamic>.from(e))).toList();
    deleted = (p['deletedDebtIds'] as List? ?? []).map((e) => int.tryParse(forecastId(e))).whereType<int>().toSet();
    final filtered = rawDebts.where((d) => !deleted.contains(d.id)).toList();
    final base = _normalizeDebtRecords(filtered);
    final keptIds = base.map((d) => d.id).toSet();
    final collapsedIds = filtered.map((d) => d.id).where((id) => !keptIds.contains(id)).toSet();
    if (collapsedIds.isNotEmpty) deleted.addAll(collapsedIds);
    final recovered = _recoverDebtRecordsFromTransactions(transactions, base);
    debts = _normalizeDebtRecords([...base, ...recovered]);
    final repairedExisting = collapsedIds.isNotEmpty || filtered.any((d) => _canonicalDebtName(d.name) != d.name.trim());
    if (recovered.isNotEmpty || repairedExisting) {
      await save();
    }
  }

  Future<void> save() async {
    await _store.update('debts', debts.map((e) => e.toJson()).toList(), deleted);
    try {
      if (accessToken != null) await _store.sync(api, accessToken!);
    } catch (_) {}
  }

  FinancialTransaction? plannedFor(int debtId, int installment) {
    final ext = 'debt-plan:$debtId:$installment';
    final direct = transactions.where((t) => t.externalTransactionId == ext).firstOrNull ?? localPlanned[ext];
    if (direct != null) return direct;
    final debt = debts.where((d) => d.id == debtId).firstOrNull;
    if (debt == null) return null;
    final candidates = <FinancialTransaction>[...transactions, ...localPlanned.values];
    return candidates.where((t) {
      if (!isPlannedDebtTransaction(t)) return false;
      final name = _debtNameFromDescription(t.description);
      return name != null &&
          name.toLowerCase() == debt.name.trim().toLowerCase() &&
          _debtInstallmentNumber(t) == installment;
    }).firstOrNull;
  }

  Future<void> deletePlanned(FinancialTransaction tx) async {
    if (tx.externalTransactionId != null) localPlanned.remove(tx.externalTransactionId);
    if (accessToken != null && transactions.any((t) => t.id == tx.id)) {
      try {
        await api.deleteTransaction(accessToken!, workspaceId, tx.id);
      } catch (_) {}
    }
  }
}

Future<void> showDebtCenter(
  BuildContext context, {
  required String workspaceId,
  required ApiClient api,
  String? accessToken,
  required List<FinancialAccount> accounts,
  required List<FinancialTransaction> transactions,
  required Future<void> Function(FinancialTransaction) onSaveTransaction,
  bool startNew = false,
}) async {
  final ctl = DebtCenterController.create(
    workspaceId: workspaceId,
    api: api,
    accessToken: accessToken,
    accounts: accounts,
    transactions: transactions,
    onSaveTransaction: onSaveTransaction,
  );
  await ctl.load();
  if (startNew) await _editDebt(context, ctl, null);
  if (!context.mounted) return;

  await showDialog(
    context: context,
    builder: (ctx) => StatefulBuilder(
      builder: (ctx, setD) => AlertDialog(
        title: const Text('Minhas dívidas'),
        content: SizedBox(
          width: 620,
          height: 560,
          child: Column(
            children: [
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  onPressed: () async {
                    await _editDebt(ctx, ctl, null);
                    setD(() {});
                  },
                  icon: const Icon(Icons.add),
                  label: const Text('Adicionar dívida'),
                ),
              ),
              const SizedBox(height: 12),
              Expanded(
                child: ctl.debts.isEmpty
                    ? const Center(child: Text('Nenhuma dívida cadastrada.'))
                    : ListView.separated(
                        itemCount: ctl.debts.length,
                        separatorBuilder: (_, __) => const SizedBox(height: 10),
                        itemBuilder: (_, i) {
                          final d = ctl.debts[i];
                          final progress = d.originalAmount <= 0 ? 0.0 : (d.paid / d.originalAmount).clamp(0.0, 1.0);
                          final overdue = d.remaining > 0 && d.due.isBefore(DateTime.now());
                          return Card(
                            child: Padding(
                              padding: const EdgeInsets.all(14),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Row(children: [
                                    Expanded(
                                      child: Column(
                                        crossAxisAlignment: CrossAxisAlignment.start,
                                        children: [
                                          Text(d.name, style: Theme.of(ctx).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)),
                                          if (d.creditor.isNotEmpty) Text('Credor: ${d.creditor}'),
                                        ],
                                      ),
                                    ),
                                    Text(
                                      d.remaining <= 0 ? 'Quitada' : overdue ? 'Atrasada' : 'Em dia',
                                      style: TextStyle(fontWeight: FontWeight.w700, color: d.remaining <= 0 ? Colors.green : overdue ? Colors.red : null),
                                    ),
                                  ]),
                                  const SizedBox(height: 8),
                                  LinearProgressIndicator(value: progress),
                                  const SizedBox(height: 8),
                                  Text('${d.paidInstallments}/${d.installmentCount} parcelas • ${brlText(d.installmentAmount)} cada'),
                                  Text('Pago: ${brlText(d.paid)} • Restante: ${brlText(d.remaining)}', style: const TextStyle(fontWeight: FontWeight.w700)),
                                  if (d.remaining > 0) Text('Próxima: ${_brDate(d.due.toIso8601String())}'),
                                  if (d.payments.isNotEmpty)
                                    ExpansionTile(
                                      tilePadding: EdgeInsets.zero,
                                      title: Text('Histórico (${d.payments.length})'),
                                      children: d.payments.reversed.map((p) {
                                        final bank = accounts.where((a) => a.id == p.accountId).map((a) => a.institutionName).firstOrNull ?? 'Banco não informado';
                                        return ListTile(
                                          dense: true,
                                          title: Text(brlText(p.amount)),
                                          subtitle: Text('${d.creditor.isEmpty ? '' : 'Credor: ${d.creditor} • '}${_brDate(p.date)} • ${p.installments} parcela(s) • $bank'),
                                        );
                                      }).toList(),
                                    ),
                                  Column(
                                    crossAxisAlignment: CrossAxisAlignment.stretch,
                                    children: [
                                      Row(
                                        mainAxisAlignment: MainAxisAlignment.end,
                                        children: [
                                          TextButton.icon(
                                            onPressed: () async {
                                              await _editDebt(ctx, ctl, d);
                                              setD(() {});
                                            },
                                            icon: const Icon(Icons.edit),
                                            label: const Text('Editar'),
                                          ),
                                          TextButton.icon(
                                            onPressed: () async {
                                              ctl.deleted.add(d.id);
                                              ctl.debts.removeWhere((x) => x.id == d.id);
                                              for (var n = d.paidInstallments + 1; n <= d.installmentCount; n++) {
                                                final tx = ctl.plannedFor(d.id, n);
                                                if (tx != null) await ctl.deletePlanned(tx);
                                              }
                                              await ctl.save();
                                              setD(() {});
                                            },
                                            icon: const Icon(Icons.delete_outline),
                                            label: const Text('Excluir'),
                                          ),
                                        ],
                                      ),
                                      if (d.remaining > 0) ...[
                                        const SizedBox(height: 6),
                                        SizedBox(
                                          width: double.infinity,
                                          child: FilledButton.tonalIcon(
                                            onPressed: () async {
                                              await _payDebt(ctx, ctl, d);
                                              setD(() {});
                                            },
                                            icon: const Icon(Icons.payments_outlined),
                                            label: const Text('Registrar pagamento'),
                                          ),
                                        ),
                                      ],
                                    ],
                                  ),
                                ],
                              ),
                            ),
                          );
                        },
                      ),
              ),
            ],
          ),
        ),
        actions: [TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Fechar'))],
      ),
    ),
  );
}

Future<void> _editDebt(BuildContext context, DebtCenterController ctl, _Debt? existing) async {
  final name = TextEditingController(text: existing?.name ?? '');
  final creditor = TextEditingController(text: existing?.creditor ?? '');
  final totalCtrl = TextEditingController(text: existing == null ? '' : brlText(existing.originalAmount));
  final installmentCtrl = TextEditingController(text: existing == null ? '' : brlText(existing.installmentAmount));
  final countCtrl = TextEditingController(text: '${existing?.installmentCount ?? 12}');
  final paid = existing?.paid ?? 0.0;
  final paidInstallments = existing?.paidInstallments ?? 0;
  var fromTotal = existing != null;
  var nextDue = existing?.due ?? DateTime.now().add(const Duration(days: 30));

  final ok = await showDialog<bool>(
    context: context,
    builder: (c) => StatefulBuilder(
      builder: (c, setD) {
        final requested = int.tryParse(countCtrl.text) ?? 0;
        final minCount = existing == null ? 1 : paidInstallments + (existing.remaining > 0.009 ? 1 : 0);
        final count = requested.clamp(0, 360);
        final remainingCount = (count - paidInstallments).clamp(0, 360);
        final typedTotal = brlValue(totalCtrl.text);
        final typedInstallment = brlValue(installmentCtrl.text);
        final total = fromTotal ? typedTotal : paid + typedInstallment * remainingCount;
        final installment = fromTotal
            ? (remainingCount > 0 ? (total - paid).clamp(0.0, double.infinity) / remainingCount : 0.0)
            : typedInstallment;
        final valid = name.text.trim().isNotEmpty &&
            count >= minCount &&
            total + 0.009 >= paid &&
            ((remainingCount == 0 && (total - paid).abs() < 0.01) || (remainingCount > 0 && installment > 0));

        return AlertDialog(
          title: Text(existing == null ? 'Adicionar dívida' : 'Editar dívida'),
          content: SizedBox(
            width: 500,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  TextField(controller: name, decoration: const InputDecoration(labelText: 'Nome da dívida *')),
                  const SizedBox(height: 14),
                  TextField(controller: creditor, decoration: const InputDecoration(labelText: 'Credor')),
                  const SizedBox(height: 14),
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    value: fromTotal,
                    onChanged: (v) => setD(() => fromTotal = v),
                    title: const Text('Informar valor total'),
                    subtitle: Text(fromTotal ? 'O valor das parcelas é recalculado automaticamente' : 'O total é recalculado pelo valor e quantidade de parcelas'),
                  ),
                  if (fromTotal)
                    TextField(
                      controller: totalCtrl,
                      onChanged: (_) => setD(() {}),
                      keyboardType: TextInputType.number,
                      inputFormatters: const [BrlMoneyInputFormatter()],
                      decoration: const InputDecoration(labelText: 'Valor total da dívida *'),
                    )
                  else
                    TextField(
                      controller: installmentCtrl,
                      onChanged: (_) => setD(() {}),
                      keyboardType: TextInputType.number,
                      inputFormatters: const [BrlMoneyInputFormatter()],
                      decoration: const InputDecoration(labelText: 'Valor da parcela *'),
                    ),
                  const SizedBox(height: 14),
                  TextField(
                    controller: countCtrl,
                    onChanged: (_) => setD(() {}),
                    keyboardType: TextInputType.number,
                    inputFormatters: [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(3)],
                    decoration: const InputDecoration(labelText: 'Quantidade de parcelas *'),
                  ),
                  const SizedBox(height: 14),
                  Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(color: Theme.of(c).colorScheme.surfaceContainerHighest, borderRadius: BorderRadius.circular(10)),
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      const Text('Resumo do acordo'),
                      Text('Total: ${brlText(total)}', style: Theme.of(c).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700)),
                      if (remainingCount > 0) Text('Parcelas restantes: $remainingCount × ${brlText(installment)}'),
                      if (paid > 0) Text('Já pago: ${brlText(paid)}'),
                    ]),
                  ),
                  const SizedBox(height: 14),
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(existing == null ? 'Primeiro vencimento' : 'Próximo vencimento'),
                    subtitle: Text(_brDate(nextDue.toIso8601String())),
                    trailing: const Icon(Icons.calendar_month),
                    onTap: () async {
                      final d = await showDatePicker(context: c, initialDate: nextDue, firstDate: DateTime(1900), lastDate: DateTime(2200));
                      if (d != null) setD(() => nextDue = d);
                    },
                  ),
                  if (existing != null && existing.payments.isNotEmpty)
                    const Align(alignment: Alignment.centerLeft, child: Text('Os pagamentos já registrados serão preservados.')),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Cancelar')),
            FilledButton(onPressed: valid ? () => Navigator.pop(c, true) : null, child: const Text('Salvar')),
          ],
        );
      },
    ),
  );
  if (ok != true) return;

  final count = (int.tryParse(countCtrl.text) ?? 1).clamp(1, 360).toInt();
  final remainingCount = (count - paidInstallments).clamp(0, 360).toInt();
  final typedTotal = brlValue(totalCtrl.text);
  final typedInstallment = brlValue(installmentCtrl.text);
  final total = fromTotal ? typedTotal : paid + typedInstallment * remainingCount;
  final installment = fromTotal
      ? (remainingCount > 0 ? (total - paid).clamp(0.0, double.infinity) / remainingCount : 0.0)
      : typedInstallment;
  final firstDue = existing == null ? nextDue : _monthDate(nextDue, -paidInstallments);

  final debt = _Debt(
    id: existing?.id ?? DateTime.now().microsecondsSinceEpoch.remainder(2147483647),
    name: name.text.trim(),
    creditor: creditor.text.trim(),
    installmentAmount: installment,
    installmentCount: count,
    firstDueDate: _iso(firstDue),
    originalAmount: total,
    payments: existing?.payments ?? const [],
  );

  if (existing == null) {
    ctl.debts = [debt, ...ctl.debts];
  } else {
    final idx = ctl.debts.indexWhere((d) => d.id == existing.id);
    if (idx >= 0) ctl.debts[idx] = debt;
  }
  await ctl.save();
  await _reconcileDebtPlans(ctl, debt, oldCount: existing?.installmentCount ?? 0);
}

Future<void> _reconcileDebtPlans(DebtCenterController ctl, _Debt debt, {required int oldCount}) async {
  final creditor = debt.creditor.isEmpty ? '' : ' • Credor: ${debt.creditor}';
  final start = debt.paidInstallments + 1;
  final maxCount = oldCount > debt.installmentCount ? oldCount : debt.installmentCount;
  for (var n = start; n <= maxCount; n++) {
    final existing = ctl.plannedFor(debt.id, n);
    if (n > debt.installmentCount) {
      if (existing != null) await ctl.deletePlanned(existing);
      continue;
    }
    final date = _monthDate(DateTime.parse(debt.firstDueDate), n - 1);
    final ext = 'debt-plan:${debt.id}:$n';
    final tx = FinancialTransaction(
      id: existing?.id ?? (DateTime.now().microsecondsSinceEpoch.remainder(2147480000) + n),
      date: _iso(date),
      description: '$plannedDebtPrefix${debt.name}$creditor • Parcela $n/${debt.installmentCount}',
      amount: debt.installmentAmount,
      type: 'expense',
      accountId: existing?.accountId,
      categoryId: existing?.categoryId,
      source: 'manual',
      externalTransactionId: ext,
    );
    ctl.localPlanned[ext] = tx;
    await ctl.onSaveTransaction(tx);
  }
}

Future<void> _payDebt(BuildContext context, DebtCenterController ctl, _Debt d) async {
  int accountIdValue = -1;
  DateTime date = DateTime.now();
  int installmentsToPay = 1;
  final remainingInstallments = (d.installmentCount - d.paidInstallments).clamp(1, d.installmentCount).toInt();
  final ok = await showDialog<bool>(
    context: context,
    builder: (c) => StatefulBuilder(
      builder: (c, setD) {
        final first = d.paidInstallments + 1;
        final last = (first + installmentsToPay - 1).clamp(first, d.installmentCount).toInt();
        final count = (last - first + 1).clamp(1, remainingInstallments).toInt();
        final total = (d.installmentAmount * count).clamp(0, d.remaining).toDouble();
        return AlertDialog(
          title: Text('Registrar pagamento • ${d.name}'),
          content: SizedBox(
            width: 460,
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              if (d.creditor.isNotEmpty) Align(alignment: Alignment.centerLeft, child: Text('Credor: ${d.creditor}', style: const TextStyle(fontWeight: FontWeight.w600))),
              if (remainingInstallments > 1) ...[
                Align(alignment: Alignment.centerLeft, child: Text('Quantidade de parcelas: $installmentsToPay', style: const TextStyle(fontWeight: FontWeight.w700))),
                Slider(
                  value: installmentsToPay.toDouble(),
                  min: 1,
                  max: remainingInstallments.toDouble(),
                  divisions: remainingInstallments > 1 ? remainingInstallments - 1 : null,
                  onChanged: (v) => setD(() => installmentsToPay = v.round().clamp(1, remainingInstallments)),
                ),
              ],
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(color: Theme.of(c).colorScheme.surfaceContainerHighest, borderRadius: BorderRadius.circular(10)),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(first == last ? 'Pagando parcela $first/${d.installmentCount}' : 'Pagando parcelas $first a $last de ${d.installmentCount}', style: const TextStyle(fontWeight: FontWeight.w700)),
                  Text('Total do pagamento: ${brlText(total)}'),
                ]),
              ),
              const SizedBox(height: 14),
              DropdownButtonFormField<int>(
                initialValue: accountIdValue < 0 ? null : accountIdValue,
                items: ctl.accounts.map((a) => DropdownMenuItem(value: a.id, child: Text(a.institutionName))).toList(),
                onChanged: (v) => setD(() => accountIdValue = v ?? -1),
                decoration: const InputDecoration(labelText: 'Banco/conta do pagamento *'),
              ),
              const SizedBox(height: 14),
              ListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Data do pagamento'),
                subtitle: Text(_brDate(date.toIso8601String())),
                trailing: const Icon(Icons.calendar_month),
                onTap: () async {
                  final x = await showDatePicker(context: c, initialDate: date, firstDate: DateTime(1900), lastDate: DateTime(2200));
                  if (x != null) setD(() => date = x);
                },
              ),
              const SizedBox(height: 8),
              const Text('As parcelas selecionadas deixam de ser previstas e passam a ser saídas reais no banco escolhido.'),
            ]),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Cancelar')),
            FilledButton(onPressed: accountIdValue < 0 ? null : () => Navigator.pop(c, true), child: const Text('Registrar')),
          ],
        );
      },
    ),
  );
  if (ok != true) return;

  final first = d.paidInstallments + 1;
  final last = (first + installmentsToPay - 1).clamp(first, d.installmentCount).toInt();
  final count = (last - first + 1).clamp(1, remainingInstallments).toInt();
  final value = (d.installmentAmount * count).clamp(0, d.remaining).toDouble();
  final p = _DebtPayment(amount: value, date: _iso(date), accountId: accountIdValue, installments: count);
  final idx = ctl.debts.indexWhere((x) => x.id == d.id);
  if (idx < 0) return;
  ctl.debts[idx] = _Debt(
    id: d.id,
    name: d.name,
    creditor: d.creditor,
    installmentAmount: d.installmentAmount,
    installmentCount: d.installmentCount,
    firstDueDate: d.firstDueDate,
    originalAmount: d.originalAmount,
    payments: [...d.payments, p],
  );
  await ctl.save();

  final creditor = d.creditor.isEmpty ? '' : ' • Credor: ${d.creditor}';
  for (var installment = first; installment <= last; installment++) {
    final ext = 'debt-plan:${d.id}:$installment';
    final planned = ctl.plannedFor(d.id, installment);
    final installmentValue = installment == last
        ? (value - d.installmentAmount * (count - 1)).clamp(0, d.installmentAmount).toDouble()
        : d.installmentAmount;
    final tx = FinancialTransaction(
      id: planned?.id ?? DateTime.now().microsecondsSinceEpoch.remainder(2147483647) + installment,
      date: _iso(date),
      description: 'Pagamento de dívida • ${d.name}$creditor • Parcela $installment/${d.installmentCount}',
      amount: installmentValue,
      type: 'expense',
      accountId: accountIdValue,
      categoryId: planned?.categoryId,
      source: 'manual',
      externalTransactionId: planned?.externalTransactionId ?? 'debt-payment:${d.id}:$installment:${DateTime.now().microsecondsSinceEpoch}',
    );
    await ctl.onSaveTransaction(tx);
    ctl.localPlanned.remove(ext);
  }
}

DateTime _monthDate(DateTime base, int delta) {
  final first = DateTime(base.year, base.month + delta, 1);
  final last = DateTime(first.year, first.month + 1, 0).day;
  return DateTime(first.year, first.month, base.day.clamp(1, last));
}

String _iso(DateTime d) => '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
String _brDate(String raw) {
  final d = DateTime.tryParse(raw);
  return d == null ? raw : '${d.day.toString().padLeft(2, '0')}/${d.month.toString().padLeft(2, '0')}/${d.year}';
}
extension _FirstOrNull<E> on Iterable<E> { E? get firstOrNull => isEmpty ? null : first; }
