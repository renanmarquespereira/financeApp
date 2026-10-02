import 'dart:async';
import '../../core/forecast_store.dart';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../../core/models.dart';
import '../../core/api_client.dart';

String _money(num value) =>
    'R\$ ${value.toStringAsFixed(2).replaceAll('.', ',')}';
DateTime? _date(String raw) {
  try {
    return DateTime.parse(raw.substring(0, 10));
  } catch (_) {
    return null;
  }
}

Iterable<FinancialTransaction> _cash(FinancialSnapshot s) =>
    s.transactions.where((t) => !t.isCard || t.source == 'card_payment');
double _income(FinancialSnapshot s) => _cash(
  s,
).where((t) => t.type == 'income').fold(0, (a, t) => a + t.amount.abs());
double _expense(FinancialSnapshot s) => _cash(
  s,
).where((t) => t.type != 'income').fold(0, (a, t) => a + t.amount.abs());
double _balance(FinancialSnapshot s) {
  final values = s.accounts
      .map((a) => a.currentBalance)
      .whereType<double>()
      .toList();
  return values.isNotEmpty
      ? values.fold(0, (a, b) => a + b)
      : _income(s) - _expense(s);
}

String _brDate(DateTime d) =>
    '${d.day.toString().padLeft(2, '0')}/${d.month.toString().padLeft(2, '0')}/${d.year}';
String _monthName(int m) => const [
  'Janeiro',
  'Fevereiro',
  'Março',
  'Abril',
  'Maio',
  'Junho',
  'Julho',
  'Agosto',
  'Setembro',
  'Outubro',
  'Novembro',
  'Dezembro',
][m - 1];
DateTime _safeDay(int year, int month, int day) => DateTime(
  year,
  month,
  day.clamp(1, DateTime(year, month + 1, 0).day).toInt(),
);

class _ForecastMovement {
  const _ForecastMovement(this.date, this.label, this.amount, this.kind);
  final DateTime date;
  final String label, kind;
  final double amount;
}

class _ScenarioItem {
  const _ScenarioItem({
    required this.description,
    required this.amount,
    required this.firstDate,
    required this.isIncome,
    required this.installments,
    required this.recurring,
  });
  final String description;
  final double amount;
  final DateTime firstDate;
  final bool isIncome, recurring;
  final int installments;
  Map<String, dynamic> toJson() => {
    'description': description,
    'amount': amount,
    'firstDate': firstDate.toIso8601String(),
    'isIncome': isIncome,
    'installments': installments,
    'recurring': recurring,
  };
  factory _ScenarioItem.fromJson(Map<String, dynamic> j) => _ScenarioItem(
    description: (j['description'] ?? 'Item').toString(),
    amount: (j['amount'] as num? ?? 0).toDouble(),
    firstDate:
        DateTime.tryParse((j['firstDate'] ?? '').toString()) ??
        DateTime.now().add(const Duration(days: 1)),
    isIncome: j['isIncome'] == true,
    installments: (j['installments'] as num? ?? 1).toInt().clamp(1, 36).toInt(),
    recurring: j['recurring'] == true,
  );
}

class _SavedScenario {
  const _SavedScenario({
    required this.id,
    required this.name,
    required this.horizon,
    required this.items,
    required this.createdAt,
  });
  final int id, horizon;
  final String name, createdAt;
  final List<_ScenarioItem> items;
  Map<String, dynamic> toJson() => {
    'id': id,
    'name': name,
    'horizon': horizon,
    'items': items.map((e) => e.toJson()).toList(),
    'createdAt': createdAt,
  };
  factory _SavedScenario.fromJson(Map<String, dynamic> j) => _SavedScenario(
    id: (j['id'] as num? ?? DateTime.now().millisecondsSinceEpoch).toInt(),
    name: (j['name'] ?? 'Cenário').toString(),
    horizon: (j['horizon'] as num? ?? 90).toInt(),
    items: (j['items'] as List? ?? [])
        .map((e) => _ScenarioItem.fromJson(Map<String, dynamic>.from(e)))
        .toList(),
    createdAt: (j['createdAt'] ?? '').toString(),
  );
}

class _ScenarioDialogResult {
  const _ScenarioDialogResult(this.item, this.addMore);
  final _ScenarioItem item;
  final bool addMore;
}

class _MonthProjection {
  const _MonthProjection(this.month, this.income, this.expense, this.balance);
  final DateTime month;
  final double income, expense, balance;
}

double _forecastBalance(FinancialSnapshot s) {
  final today = DateTime.now();
  return _cash(s)
      .where((t) {
        final d = _date(t.date);
        return d == null ||
            !DateTime(
              d.year,
              d.month,
              d.day,
            ).isAfter(DateTime(today.year, today.month, today.day));
      })
      .fold<double>(
        0,
        (sum, t) =>
            sum + (t.type == 'income' ? t.amount.abs() : -t.amount.abs()),
      );
}

class ForecastView extends StatefulWidget {
  const ForecastView({
    super.key,
    required this.snapshot,
    this.api,
    this.accessToken,
    this.workspaceId,
    this.isGuest = false,
    this.refreshing = false,
    this.onSaveTransaction,
  });
  final FinancialSnapshot snapshot;
  final ApiClient? api;
  final String? accessToken, workspaceId;
  final bool isGuest, refreshing;
  final Future<void> Function(FinancialTransaction)? onSaveTransaction;
  @override
  State<ForecastView> createState() => _ForecastViewState();
}

class _ForecastViewState extends State<ForecastView>
    with WidgetsBindingObserver {
  int horizon = 90;
  final List<_ScenarioItem> scenarios = [];
  List<_SavedScenario> savedScenarios = [];
  Set<int> deletedScenarioIds = {};
  ForecastStore get _savedStore =>
      ForecastStore(widget.workspaceId ?? 'guest-local');
  String? _syncError;
  bool _syncBusy = false;
  Timer? _syncTimer;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadForecastState();
    _syncTimer = Timer.periodic(Duration(seconds: 30), (_) => _syncScenarios());
  }

  @override
  void dispose() {
    _syncTimer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _syncScenarios();
  }

  @override
  void didUpdateWidget(covariant ForecastView old) {
    super.didUpdateWidget(old);
    if ((old.refreshing && !widget.refreshing) ||
        old.accessToken != widget.accessToken)
      _syncScenarios();
  }

  Future<void> _readScenarios() async {
    final payload = await _savedStore.read();
    final items = <_SavedScenario>[];
    for (final raw in (payload['scenarios'] as List? ?? [])) {
      try {
        items.add(
          _SavedScenario.fromJson(Map<String, dynamic>.from(raw as Map)),
        );
      } catch (_) {}
    }
    if (mounted)
      setState(() {
        deletedScenarioIds = {
          ...deletedScenarioIds,
          ...(payload['deletedScenarioIds'] as List? ?? [])
              .map((v) => int.tryParse(forecastId(v)))
              .whereType<int>(),
        };
        savedScenarios =
            {
                for (final v in savedScenarios) v.id: v,
                for (final v in items) v.id: v,
              }.values.where((v) => !deletedScenarioIds.contains(v.id)).toList()
              ..sort((a, b) => b.id.compareTo(a.id));
      });
  }

  Future<void> _loadForecastState() async {
    await _readScenarios();
    await _syncScenarios();
  }

  Future<void> _syncScenarios() async {
    if (_syncBusy ||
        widget.isGuest ||
        widget.api == null ||
        widget.accessToken == null ||
        widget.workspaceId == null)
      return;
    _syncBusy = true;
    try {
      await _savedStore.sync(widget.api!, widget.accessToken!);
      await _readScenarios();
      if (mounted) setState(() => _syncError = null);
    } catch (e) {
      if (mounted)
        setState(
          () => _syncError =
              'Dados locais preservados. ${e.toString().replaceFirst("Exception: ", "")}',
        );
    } finally {
      _syncBusy = false;
    }
  }

  Future<void> _persistForecastState({bool syncRemote = true}) async {
    await _savedStore.update(
      'scenarios',
      savedScenarios.map((e) => e.toJson()).toList(),
      deletedScenarioIds,
    );
    if (syncRemote) {
      await _savedStoreSyncAfterEdit();
    }
  }

  Future<void> _savedStoreSyncAfterEdit() async {
    if (widget.isGuest || widget.api == null || widget.accessToken == null)
      return;
    try {
      await _savedStore.sync(widget.api!, widget.accessToken!);
      await _readScenarios();
      if (mounted) setState(() => _syncError = null);
    } catch (e) {
      if (mounted)
        setState(() => _syncError = 'Salvo localmente. ${e.toString()}');
    }
  }

  List<_ForecastMovement> _baseMovements(int maxDays) {
    final s = widget.snapshot,
        now = DateTime.now(),
        limit = now.add(Duration(days: maxDays));
    final result = <_ForecastMovement>[];
    for (final t in _cash(s)) {
      final d = _date(t.date);
      if (d == null || !d.isAfter(now) || d.isAfter(limit)) continue;
      final signed = t.type == 'income' ? t.amount.abs() : -t.amount.abs();
      result.add(
        _ForecastMovement(
          d,
          t.description,
          signed,
          t.type == 'income' ? 'Entrada' : 'Saída',
        ),
      );
    }
    for (final t in s.transactions.where(
      (t) => t.isCard && t.source != 'card_payment',
    )) {
      final purchase = _date(t.purchaseDate ?? t.date);
      if (purchase == null) continue;
      CreditCardInfo? card;
      for (final c in s.cards) {
        if (c.id == t.cardId) {
          card = c;
          break;
        }
      }
      final dueDay = card?.dueDay;
      if (dueDay == null) continue;
      var due = _safeDay(purchase.year, purchase.month, dueDay);
      if (due.isBefore(purchase))
        due = _safeDay(purchase.year, purchase.month + 1, dueDay);
      if (!due.isAfter(now) || due.isAfter(limit)) continue;
      final name = card!.nickname?.isNotEmpty == true
          ? card.nickname!
          : '${card.bankName} •••• ${card.lastFour}';
      result.add(
        _ForecastMovement(
          due,
          'Fatura prevista • $name',
          -t.amount.abs(),
          'Cartão',
        ),
      );
    }
    result.sort((a, b) => a.date.compareTo(b.date));
    return result;
  }

  List<_ForecastMovement> _scenarioMovements(int maxDays) {
    final now = DateTime.now(),
        limit = now.add(Duration(days: maxDays)),
        out = <_ForecastMovement>[];
    for (final s in scenarios) {
      final count = s.installments.clamp(1, 36).toInt();
      final part = s.isIncome ? s.amount : s.amount / count;
      for (var i = 0; i < count; i++) {
        final d = DateTime(
          s.firstDate.year,
          s.firstDate.month + i,
          s.firstDate.day,
        );
        if (!d.isAfter(now) || d.isAfter(limit)) continue;
        out.add(
          _ForecastMovement(
            d,
            'Simulação • ${s.description}',
            s.isIncome ? part : -part,
            s.isIncome ? 'Entrada simulada' : 'Despesa simulada',
          ),
        );
      }
    }
    return out;
  }

  @override
  Widget build(BuildContext context) {
    final now = DateTime.now();
    final base = _baseMovements(horizon),
        sim = _scenarioMovements(horizon),
        movements = [...base, ...sim]..sort((a, b) => a.date.compareTo(b.date));
    final balance = _forecastBalance(widget.snapshot);
    final projected =
        balance + movements.fold<double>(0, (a, m) => a + m.amount);
    final realProjected =
        balance + base.fold<double>(0, (a, m) => a + m.amount);
    final income = movements
        .where((m) => m.amount > 0)
        .fold<double>(0, (a, m) => a + m.amount);
    final expense = movements
        .where((m) => m.amount < 0)
        .fold<double>(0, (a, m) => a + m.amount.abs());
    var running = balance, minBalance = balance;
    DateTime minDate = now;
    DateTime? firstNegative;
    for (final m in movements) {
      running += m.amount;
      if (running < minBalance) {
        minBalance = running;
        minDate = m.date;
      }
      if (running < 0 && firstNegative == null) firstNegative = m.date;
    }
    final risk = firstNegative != null
        ? 'Saldo negativo previsto'
        : projected < 0
        ? 'Atenção'
        : 'Tranquilo';
    final riskColor = firstNegative != null
        ? const Color(0xFFC73E47)
        : const Color(0xFF16845B);

    final months = <_MonthProjection>[];
    var month = DateTime(now.year, now.month), monthBalance = balance;
    final end = now.add(Duration(days: horizon));
    final last = DateTime(end.year, end.month);
    while (!month.isAfter(last)) {
      final mm = movements.where(
        (m) => m.date.year == month.year && m.date.month == month.month,
      );
      final inc = mm
          .where((m) => m.amount > 0)
          .fold<double>(0, (a, m) => a + m.amount);
      final exp = mm
          .where((m) => m.amount < 0)
          .fold<double>(0, (a, m) => a + m.amount.abs());
      monthBalance += inc - exp;
      months.add(_MonthProjection(month, inc, exp, monthBalance));
      month = DateTime(month.year, month.month + 1);
    }

    return Container(
      color: const Color(0xFFF6F8FC),
      child: ListView(
        padding: const EdgeInsets.fromLTRB(18, 18, 18, 28),
        children: [
          if (_syncError != null) _SyncNotice(_syncError!, _syncScenarios),
          Container(
            padding: const EdgeInsets.all(22),
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                colors: [
                  Color(0xFF0E4FA3),
                  Color(0xFF416AD9),
                  Color(0xFF6D5CE7),
                ],
              ),
              borderRadius: BorderRadius.circular(22),
            ),
            child: Row(
              children: [
                const Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Previsão financeira',
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 24,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                      SizedBox(height: 6),
                      Text(
                        'Uma visão simples do que já está previsto e dos cenários que você simular.',
                        style: TextStyle(
                          color: Color(0xFFE7EEFF),
                          fontSize: 14,
                          height: 1.35,
                        ),
                      ),
                    ],
                  ),
                ),
                Icon(
                  Icons.auto_graph_rounded,
                  color: Colors.white.withOpacity(.95),
                  size: 28,
                ),
              ],
            ),
          ),
          const SizedBox(height: 14),
          SegmentedButton<int>(
            segments: const [
              ButtonSegment(value: 30, label: Text('30 dias')),
              ButtonSegment(value: 60, label: Text('60 dias')),
              ButtonSegment(value: 90, label: Text('90 dias')),
            ],
            selected: {horizon},
            onSelectionChanged: (v) => setState(() => horizon = v.first),
            showSelectedIcon: true,
          ),
          const SizedBox(height: 14),
          const SizedBox(height: 14),
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(18),
              border: Border.all(color: const Color(0xFFE4E9F1)),
            ),
            child: Row(
              children: [
                Container(
                  width: 42,
                  height: 42,
                  decoration: BoxDecoration(
                    color: riskColor.withOpacity(.10),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(
                    firstNegative != null
                        ? Icons.warning_amber_rounded
                        : Icons.verified_outlined,
                    color: riskColor,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        'Risco da projeção',
                        style: TextStyle(
                          color: Color(0xFF667085),
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      const SizedBox(height: 3),
                      Text(
                        risk,
                        style: TextStyle(
                          fontSize: 17,
                          fontWeight: FontWeight.w800,
                          color: riskColor,
                        ),
                      ),
                      Text(
                        firstNegative != null
                            ? 'Pode ficar negativo em ${_brDate(firstNegative)}.'
                            : 'Nenhum saldo negativo foi identificado neste período.',
                        style: const TextStyle(
                          fontSize: 13,
                          color: Color(0xFF667085),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 18),
          const Text(
            'Resumo da previsão',
            style: TextStyle(
              fontSize: 19,
              fontWeight: FontWeight.w800,
              color: Color(0xFF182230),
            ),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: _ForecastMetric(
                  'Saldo atual',
                  _money(balance),
                  Icons.account_balance_wallet_outlined,
                  const Color(0xFF0E4FA3),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: _ForecastMetric(
                  scenarios.isEmpty ? 'Saldo previsto' : 'Saldo com cenário',
                  _money(projected),
                  Icons.auto_graph_rounded,
                  const Color(0xFF4F5BD5),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: _ForecastMetric(
                  'Entradas previstas',
                  _money(income),
                  Icons.south_west_rounded,
                  const Color(0xFF16845B),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: _ForecastMetric(
                  'Saídas previstas',
                  _money(expense),
                  Icons.north_east_rounded,
                  const Color(0xFFC73E47),
                ),
              ),
            ],
          ),
          if (scenarios.isNotEmpty) ...[
            const SizedBox(height: 8),
            Text(
              'Sem o cenário: ${_money(realProjected)} • impacto: ${_money(projected - realProjected)}',
              style: const TextStyle(fontSize: 12, color: Color(0xFF667085)),
            ),
          ],
          const SizedBox(height: 6),
          Text(
            'Menor saldo previsto: ${_money(minBalance)} em ${_brDate(minDate)}',
            style: const TextStyle(fontSize: 12, color: Color(0xFF667085)),
          ),
          const SizedBox(height: 14),
          SizedBox(
            width: 220,
            child: FilledButton.icon(
              onPressed: _addScenario,
              icon: const Icon(Icons.add_circle_outline),
              label: const Text('Adicionar'),
            ),
          ),
          const SizedBox(height: 16),
          const Text(
            'Cenários realizados',
            style: TextStyle(
              fontSize: 19,
              fontWeight: FontWeight.w800,
              color: Color(0xFF182230),
            ),
          ),
          const SizedBox(height: 10),
          if (savedScenarios.isEmpty)
            const Text(
              'Nenhum cenário salvo ainda.',
              style: TextStyle(color: Color(0xFF667085)),
            )
          else
            ...savedScenarios.map(
              (saved) => Padding(
                padding: const EdgeInsets.only(bottom: 9),
                child: _savedScenarioCard(saved),
              ),
            ),
          if (scenarios.isNotEmpty) ...[
            const SizedBox(height: 18),
            Row(
              children: [
                const Expanded(
                  child: Text(
                    'Cenário atual',
                    style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800),
                  ),
                ),
                TextButton.icon(
                  onPressed: _saveCurrentScenario,
                  icon: const Icon(Icons.bookmark_add_outlined),
                  label: const Text('Salvar'),
                ),
              ],
            ),
            ...scenarios.map(
              (s) => Padding(
                padding: const EdgeInsets.only(bottom: 9),
                child: Container(
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(14),
                    border: Border.all(color: const Color(0xFFE4E9F1)),
                  ),
                  child: Row(
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              s.description,
                              style: const TextStyle(
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                            const SizedBox(height: 3),
                            Text(
                              '${s.isIncome ? 'Entrada' : 'Despesa'} • ${_money(s.amount)}${s.installments > 1 ? ' • ${s.installments}x' : ' • à vista'}',
                              style: const TextStyle(
                                fontSize: 12,
                                color: Color(0xFF667085),
                              ),
                            ),
                            Text(
                              'Primeira em ${_brDate(s.firstDate)}',
                              style: const TextStyle(
                                fontSize: 12,
                                color: Color(0xFF667085),
                              ),
                            ),
                          ],
                        ),
                      ),
                      if (widget.onSaveTransaction != null)
                        TextButton.icon(
                          onPressed: () => _transformScenario(s),
                          icon: const Icon(Icons.check_circle_outline),
                          label: const Text('Tornar real'),
                        ),
                      IconButton(
                        tooltip: 'Remover',
                        onPressed: () => setState(() => scenarios.remove(s)),
                        icon: const Icon(Icons.delete_outline),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ],
          const SizedBox(height: 18),
          const Text(
            'Projeção por mês',
            style: TextStyle(
              fontSize: 19,
              fontWeight: FontWeight.w800,
              color: Color(0xFF182230),
            ),
          ),
          const SizedBox(height: 10),
          ...months.map(
            (m) => Padding(
              padding: const EdgeInsets.only(bottom: 9),
              child: Container(
                padding: const EdgeInsets.all(15),
                decoration: BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(color: const Color(0xFFE4E9F1)),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '${_monthName(m.month.month)} ${m.month.year}',
                      style: const TextStyle(
                        fontWeight: FontWeight.w800,
                        fontSize: 16,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text('Entradas: ${_money(m.income)}'),
                    Text('Saídas e faturas: ${_money(m.expense)}'),
                    const Divider(),
                    Text(
                      'Saldo ao fim do mês: ${_money(m.balance)}',
                      style: const TextStyle(fontWeight: FontWeight.w700),
                    ),
                  ],
                ),
              ),
            ),
          ),
          const SizedBox(height: 8),
          const Text(
            'Simulações só viram transações quando você escolher transformar um item em lançamento.',
            style: TextStyle(fontSize: 12, color: Color(0xFF667085)),
          ),
        ],
      ),
    );
  }

  Future<void> _transformScenario(_ScenarioItem s) async {
    final save = widget.onSaveTransaction;
    if (save == null) return;
    final count = s.installments.clamp(1, 36).toInt();
    final part = s.isIncome ? s.amount : s.amount / count;
    try {
      for (var i = 0; i < count; i++) {
        final d = DateTime(
          s.firstDate.year,
          s.firstDate.month + i,
          s.firstDate.day,
        );
        await save(
          FinancialTransaction(
            id:
                -DateTime.now().microsecondsSinceEpoch.remainder(2147483647) -
                i,
            date: d.toIso8601String(),
            description: count > 1
                ? '${s.description} ${i + 1}/$count'
                : s.description,
            amount: part,
            type: s.isIncome ? 'income' : 'expense',
            source: 'manual',
          ),
        );
      }
      if (mounted) {
        setState(() => scenarios.remove(s));
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Cenário transformado em lançamento real.'),
          ),
        );
      }
    } catch (e) {
      if (mounted)
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Não foi possível criar o lançamento: $e')),
        );
    }
  }

  Future<void> _addScenario() async {
    final desc = TextEditingController(), amount = TextEditingController();
    var isIncome = false,
        installments = 1,
        first = DateTime.now().add(const Duration(days: 1));
    final result = await showDialog<_ScenarioDialogResult>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, setLocal) => AlertDialog(
          title: const Text('Adicionar ao cenário'),
          content: SizedBox(
            width: 480,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  SegmentedButton<bool>(
                    segments: const [
                      ButtonSegment(value: false, label: Text('Despesa')),
                      ButtonSegment(value: true, label: Text('Entrada')),
                    ],
                    selected: {isIncome},
                    onSelectionChanged: (v) =>
                        setLocal(() => isIncome = v.first),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: desc,
                    decoration: const InputDecoration(
                      labelText: 'Descrição',
                      border: OutlineInputBorder(),
                    ),
                  ),
                  const SizedBox(height: 10),
                  TextField(
                    controller: amount,
                    keyboardType: const TextInputType.numberWithOptions(
                      decimal: true,
                    ),
                    decoration: const InputDecoration(
                      labelText: 'Valor (R\$)',
                      border: OutlineInputBorder(),
                    ),
                  ),
                  const SizedBox(height: 10),
                  if (!isIncome)
                    Row(
                      children: [
                        const Text('Parcelas'),
                        const SizedBox(width: 12),
                        IconButton(
                          onPressed: installments > 1
                              ? () => setLocal(() => installments--)
                              : null,
                          icon: const Icon(Icons.remove_circle_outline),
                        ),
                        Text(
                          '$installments',
                          style: const TextStyle(fontWeight: FontWeight.w700),
                        ),
                        IconButton(
                          onPressed: installments < 36
                              ? () => setLocal(() => installments++)
                              : null,
                          icon: const Icon(Icons.add_circle_outline),
                        ),
                      ],
                    ),
                  const SizedBox(height: 6),
                  OutlinedButton.icon(
                    onPressed: () async {
                      final picked = await showDatePicker(
                        context: context,
                        initialDate: first,
                        firstDate: DateTime.now(),
                        lastDate: DateTime.now().add(
                          const Duration(days: 3650),
                        ),
                      );
                      if (picked != null) setLocal(() => first = picked);
                    },
                    icon: const Icon(Icons.event_outlined),
                    label: Text('Primeira data: ${_brDate(first)}'),
                  ),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: const Text('Cancelar'),
            ),
            TextButton(
              onPressed: () {
                final item = _scenarioFromFields(
                  desc,
                  amount,
                  first,
                  isIncome,
                  installments,
                );
                if (item != null)
                  Navigator.pop(
                    dialogContext,
                    _ScenarioDialogResult(item, false),
                  );
              },
              child: const Text('Adicionar e sair'),
            ),
            FilledButton(
              onPressed: () {
                final item = _scenarioFromFields(
                  desc,
                  amount,
                  first,
                  isIncome,
                  installments,
                );
                if (item != null)
                  Navigator.pop(
                    dialogContext,
                    _ScenarioDialogResult(item, true),
                  );
              },
              child: const Text('Adicionar mais'),
            ),
          ],
        ),
      ),
    );
    if (result != null && mounted) {
      setState(() => scenarios.add(result.item));
      if (result.addMore) Future.microtask(_addScenario);
    }
  }

  _ScenarioItem? _scenarioFromFields(
    TextEditingController desc,
    TextEditingController amount,
    DateTime first,
    bool isIncome,
    int installments,
  ) {
    final raw = amount.text.replaceAll('.', '').replaceAll(',', '.');
    final value = double.tryParse(raw) ?? 0;
    if (desc.text.trim().isEmpty || value <= 0) return null;
    return _ScenarioItem(
      description: desc.text.trim(),
      amount: value,
      firstDate: first,
      isIncome: isIncome,
      installments: isIncome ? 1 : installments,
      recurring: false,
    );
  }

  Future<void> _saveCurrentScenario() async {
    if (scenarios.isEmpty) return;
    final name = TextEditingController();
    final result = await showDialog<String>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('Salvar cenário'),
        content: TextField(
          controller: name,
          autofocus: true,
          decoration: const InputDecoration(
            labelText: 'Nome do cenário',
            hintText: 'Ex.: Reforma da casa',
            border: OutlineInputBorder(),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(c),
            child: const Text('Cancelar'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(c, name.text.trim()),
            child: const Text('Salvar'),
          ),
        ],
      ),
    );
    if (result == null || result.isEmpty) return;
    final saved = _SavedScenario(
      id: DateTime.now().millisecondsSinceEpoch,
      name: result,
      horizon: horizon,
      items: List.of(scenarios),
      createdAt: _brDate(DateTime.now()),
    );
    setState(() {
      deletedScenarioIds.remove(saved.id);
      savedScenarios = [saved, ...savedScenarios];
    });
    await _persistForecastState();
  }

  Widget _savedScenarioCard(_SavedScenario saved) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
    decoration: BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(14),
      border: Border.all(color: const Color(0xFFE4E9F1)),
    ),
    child: Row(
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                saved.name,
                style: const TextStyle(
                  fontWeight: FontWeight.w800,
                  color: Color(0xFF182230),
                ),
              ),
              const SizedBox(height: 3),
              Text(
                '${saved.items.length} item(ns) • ${saved.horizon} dias • ${saved.createdAt}',
                style: const TextStyle(fontSize: 12, color: Color(0xFF667085)),
              ),
            ],
          ),
        ),
        PopupMenuButton<String>(
          tooltip: 'Opções do cenário',
          onSelected: (value) async {
            if (value == 'use') {
              setState(() {
                scenarios
                  ..clear()
                  ..addAll(saved.items);
                horizon = saved.horizon;
              });
            } else if (value == 'real') {
              await _transformSavedScenario(saved);
            } else if (value == 'delete') {
              await _confirmDeleteScenario(saved);
            }
          },
          itemBuilder: (_) => const [
            PopupMenuItem(value: 'use', child: Text('Usar')),
            PopupMenuItem(
              value: 'real',
              child: Text('Transformar em lançamento real'),
            ),
            PopupMenuItem(value: 'delete', child: Text('Excluir')),
          ],
        ),
      ],
    ),
  );

  Future<void> _transformSavedScenario(_SavedScenario saved) async {
    final save = widget.onSaveTransaction;
    if (save == null) return;
    final ok =
        await showDialog<bool>(
          context: context,
          builder: (c) => AlertDialog(
            title: const Text('Transformar cenário em lançamentos?'),
            content: Text(
              "Todos os ${saved.items.length} item(ns) de '${saved.name}' serão criados como lançamentos reais.",
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(c, false),
                child: const Text('Cancelar'),
              ),
              FilledButton(
                onPressed: () => Navigator.pop(c, true),
                child: const Text('Transformar'),
              ),
            ],
          ),
        ) ??
        false;
    if (!ok) return;
    try {
      for (final item in saved.items) {
        final count = item.installments.clamp(1, 36).toInt();
        final part = item.isIncome ? item.amount : item.amount / count;
        for (var i = 0; i < count; i++) {
          final d = DateTime(
            item.firstDate.year,
            item.firstDate.month + i,
            item.firstDate.day,
          );
          await save(
            FinancialTransaction(
              id:
                  -DateTime.now().microsecondsSinceEpoch.remainder(2147483647) -
                  i,
              date: d.toIso8601String(),
              description: count > 1
                  ? '${item.description} ${i + 1}/$count'
                  : item.description,
              amount: part,
              type: item.isIncome ? 'income' : 'expense',
              source: 'manual',
            ),
          );
        }
      }
      if (mounted)
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Cenário transformado em lançamentos reais.'),
          ),
        );
    } catch (e) {
      if (mounted)
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Não foi possível transformar o cenário: $e')),
        );
    }
  }

  Future<void> _confirmDeleteScenario(_SavedScenario saved) async {
    final ok =
        await showDialog<bool>(
          context: context,
          builder: (c) => AlertDialog(
            title: const Text('Excluir cenário?'),
            content: Text(
              "O cenário '${saved.name}' será excluído dos seus cenários sincronizados.",
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(c, false),
                child: const Text('Cancelar'),
              ),
              FilledButton(
                onPressed: () => Navigator.pop(c, true),
                child: const Text('Excluir'),
              ),
            ],
          ),
        ) ??
        false;
    if (!ok) return;
    setState(() {
      deletedScenarioIds.add(saved.id);
      savedScenarios.removeWhere((e) => e.id == saved.id);
    });
    await _persistForecastState();
  }
}

class _ForecastMetric extends StatelessWidget {
  const _ForecastMetric(this.title, this.value, this.icon, this.accent);
  final String title, value;
  final IconData icon;
  final Color accent;
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(16),
    decoration: BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(16),
      border: Border.all(color: const Color(0xFFE4E9F1)),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 36,
          height: 36,
          decoration: BoxDecoration(
            color: accent.withOpacity(.10),
            borderRadius: BorderRadius.circular(10),
          ),
          child: Icon(icon, color: accent, size: 20),
        ),
        const SizedBox(height: 10),
        Text(
          title,
          style: const TextStyle(
            color: Color(0xFF667085),
            fontSize: 12,
            fontWeight: FontWeight.w600,
          ),
        ),
        const SizedBox(height: 4),
        FittedBox(
          fit: BoxFit.scaleDown,
          alignment: Alignment.centerLeft,
          child: Text(
            value,
            style: TextStyle(
              fontSize: 18,
              fontWeight: FontWeight.w800,
              color: accent,
            ),
          ),
        ),
      ],
    ),
  );
}

class _ForecastCommitment extends StatelessWidget {
  const _ForecastCommitment(this.t);
  final _ForecastMovement t;
  @override
  Widget build(BuildContext context) {
    final income = t.amount > 0;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: const Color(0xFFE4E9F1)),
      ),
      child: Row(
        children: [
          Container(
            width: 40,
            height: 40,
            decoration: BoxDecoration(
              color:
                  (income ? const Color(0xFF16845B) : const Color(0xFFC73E47))
                      .withOpacity(.10),
              borderRadius: BorderRadius.circular(11),
            ),
            child: Icon(
              income ? Icons.south_west_rounded : Icons.north_east_rounded,
              color: income ? const Color(0xFF16845B) : const Color(0xFFC73E47),
              size: 20,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  t.label,
                  style: const TextStyle(
                    fontWeight: FontWeight.w700,
                    color: Color(0xFF182230),
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  '${_brDate(t.date)} • ${t.kind}',
                  style: const TextStyle(
                    color: Color(0xFF667085),
                    fontSize: 12,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          Text(
            _money(t.amount),
            style: TextStyle(
              fontWeight: FontWeight.w800,
              color: income ? const Color(0xFF16845B) : const Color(0xFFC73E47),
            ),
          ),
        ],
      ),
    );
  }
}

String _cleanAi(String raw) => raw
    .replaceAll('**', '')
    .replaceAll(RegExp(r'^#{1,3}\s+', multiLine: true), '')
    .trim();

class _SavedAiPlan {
  const _SavedAiPlan({
    required this.id,
    required this.title,
    required this.content,
    required this.createdAt,
    this.goal = "",
    this.target = 0,
    this.months = 12,
  });
  final int id, months;
  final double target;
  final String title, content, createdAt, goal;
  Map<String, dynamic> toJson() => {
    'id': id,
    'title': title,
    'content': content,
    'createdAt': createdAt,
    'goal': goal,
    'target': target,
    'months': months,
  };
  factory _SavedAiPlan.fromJson(Map<String, dynamic> j) => _SavedAiPlan(
    id: (j['id'] as num? ?? DateTime.now().millisecondsSinceEpoch).toInt(),
    title: (j['title'] ?? 'Plano financeiro').toString(),
    content: (j['content'] ?? '').toString(),
    createdAt: (j['createdAt'] ?? '').toString(),
    goal: (j['goal'] ?? '').toString(),
    target: (j['target'] as num? ?? 0).toDouble(),
    months: (j['months'] as num? ?? 12).toInt(),
  );
}

class IntelligenceView extends StatefulWidget {
  const IntelligenceView({
    super.key,
    required this.snapshot,
    required this.isGuest,
    this.onAskAi,
    this.api,
    this.accessToken,
    this.workspaceId,
    this.refreshing = false,
  });
  final ApiClient? api;
  final String? accessToken, workspaceId;
  final bool refreshing;
  final FinancialSnapshot snapshot;
  final bool isGuest;
  final Future<String> Function(String, Map<String, dynamic>)? onAskAi;
  @override
  State<IntelligenceView> createState() => _IntelligenceViewState();
}

class _IntelligenceViewState extends State<IntelligenceView>
    with WidgetsBindingObserver {
  final ctrl = TextEditingController(),
      goalCtrl = TextEditingController(),
      targetCtrl = TextEditingController(),
      monthsCtrl = TextEditingController(text: '12');
  bool busy = false;
  String? answer, plan;
  List<_SavedAiPlan> savedPlans = [];
  ForecastStore get _savedStore =>
      ForecastStore(widget.workspaceId ?? 'guest-local');
  Set<int> _deletedPlans = {};
  String? _syncError;
  bool _syncBusy = false;
  Timer? _syncTimer;
  bool _hasLegacyPlans = false;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadPlans();
    _syncTimer = Timer.periodic(Duration(seconds: 30), (_) => _syncPlans());
  }

  @override
  void dispose() {
    _syncTimer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    ctrl.dispose();
    goalCtrl.dispose();
    targetCtrl.dispose();
    monthsCtrl.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _syncPlans();
  }

  @override
  void didUpdateWidget(covariant IntelligenceView old) {
    super.didUpdateWidget(old);
    if ((old.refreshing && !widget.refreshing) ||
        old.accessToken != widget.accessToken)
      _syncPlans();
  }

  Future<void> _readPlans() async {
    final payload = await _savedStore.read();
    final items = <_SavedAiPlan>[];
    for (final raw in (payload['aiPlans'] as List? ?? [])) {
      try {
        items.add(_SavedAiPlan.fromJson(Map<String, dynamic>.from(raw as Map)));
      } catch (_) {}
    }
    if (mounted)
      setState(() {
        _deletedPlans = {
          ..._deletedPlans,
          ...(payload['deletedAiPlanIds'] as List? ?? [])
              .map((v) => int.tryParse(forecastId(v)))
              .whereType<int>(),
        };
        savedPlans =
            {
                for (final v in savedPlans) v.id: v,
                for (final v in items) v.id: v,
              }.values.where((v) => !_deletedPlans.contains(v.id)).toList()
              ..sort((a, b) => b.id.compareTo(a.id));
      });
  }

  Future<void> _loadPlans() async {
    await _readPlans();
    final prefs = await SharedPreferences.getInstance();
    if (mounted)
      setState(
        () => _hasLegacyPlans =
            prefs.getString('financeapp_saved_ai_plans_v1') != null &&
            prefs.getString('financeapp_legacy_ai_owner') == null,
      );
    await _syncPlans();
  }

  Future<void> _syncPlans() async {
    if (_syncBusy ||
        widget.isGuest ||
        widget.api == null ||
        widget.accessToken == null ||
        widget.workspaceId == null)
      return;
    _syncBusy = true;
    try {
      await _savedStore.sync(widget.api!, widget.accessToken!);
      await _readPlans();
      if (mounted) setState(() => _syncError = null);
    } catch (e) {
      if (mounted)
        setState(
          () => _syncError =
              'Dados locais preservados. ${e.toString().replaceFirst("Exception: ", "")}',
        );
    } finally {
      _syncBusy = false;
    }
  }

  Future<void> _persistPlans() async {
    await _savedStore.update(
      'aiPlans',
      savedPlans.map((e) => e.toJson()).toList(),
      _deletedPlans,
    );
    if (!widget.isGuest && widget.api != null && widget.accessToken != null) {
      try {
        await _savedStore.sync(widget.api!, widget.accessToken!);
        await _readPlans();
        if (mounted) setState(() => _syncError = null);
      } catch (e) {
        if (mounted)
          setState(() => _syncError = 'Salvo localmente. ${e.toString()}');
      }
    }
  }

  Future<void> _importLegacyPlans() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: Text('Importar planos antigos?'),
        content: Text(
          'Os planos antigos deste navegador/aparelho não tinham carteira identificada. Confirme que são seus e pertencem à carteira atual antes de enviá-los ao servidor.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(c, false),
            child: Text('Cancelar'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(c, true),
            child: Text('Importar nesta carteira'),
          ),
        ],
      ),
    );
    if (ok != true) return;
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString('financeapp_saved_ai_plans_v1');
    if (raw == null) return;
    final items = (jsonDecode(raw) as List)
        .map((e) => Map<String, dynamic>.from(e as Map))
        .toList();
    await _savedStore.update('aiPlans', items, []);
    await prefs.setString(
      'financeapp_legacy_ai_owner',
      widget.workspaceId ?? 'guest-local',
    );
    if (mounted) setState(() => _hasLegacyPlans = false);
    await _readPlans();
    await _syncPlans();
  }

  Future<void> _saveCurrentPlan() async {
    if (plan == null || plan!.trim().isEmpty) return;
    final title = goalCtrl.text.trim().isEmpty
        ? 'Plano financeiro'
        : goalCtrl.text.trim();
    setState(
      () => savedPlans = [
        _SavedAiPlan(
          id: DateTime.now().millisecondsSinceEpoch,
          title: title,
          content: plan!,
          createdAt: _brDate(DateTime.now()),
          goal: goalCtrl.text.trim(),
          target:
              double.tryParse(
                targetCtrl.text.replaceAll('.', '').replaceAll(',', '.'),
              ) ??
              0,
          months: int.tryParse(monthsCtrl.text) ?? 12,
        ),
        ...savedPlans,
      ],
    );
    await _persistPlans();
  }

  Future<void> _showSavedPlans() async {
    await showDialog<void>(
      context: context,
      builder: (c) => StatefulBuilder(
        builder: (context, setLocal) => AlertDialog(
          title: const Text('Meus planos da IA'),
          content: SizedBox(
            width: 560,
            height: savedPlans.isEmpty ? 100 : 430,
            child: savedPlans.isEmpty
                ? const Center(child: Text('Nenhum plano salvo ainda.'))
                : ListView.separated(
                    itemCount: savedPlans.length,
                    separatorBuilder: (_, __) => const Divider(height: 1),
                    itemBuilder: (_, i) {
                      final saved = savedPlans[i];
                      return ListTile(
                        title: Text(
                          saved.title,
                          style: const TextStyle(fontWeight: FontWeight.w700),
                        ),
                        subtitle: Text(saved.createdAt),
                        trailing: Wrap(
                          children: [
                            IconButton(
                              tooltip: 'Abrir',
                              onPressed: () {
                                setState(() => plan = saved.content);
                                Navigator.pop(c);
                              },
                              icon: const Icon(Icons.folder_open_outlined),
                            ),
                            IconButton(
                              tooltip: 'Excluir',
                              onPressed: () async {
                                final ok = await showDialog<bool>(
                                  context: context,
                                  builder: (d) => AlertDialog(
                                    title: Text('Excluir plano?'),
                                    content: Text(
                                      'Excluir "${saved.title}" dos aparelhos sincronizados?',
                                    ),
                                    actions: [
                                      TextButton(
                                        onPressed: () =>
                                            Navigator.pop(d, false),
                                        child: Text('Cancelar'),
                                      ),
                                      FilledButton(
                                        onPressed: () => Navigator.pop(d, true),
                                        child: Text('Excluir'),
                                      ),
                                    ],
                                  ),
                                );
                                if (ok != true || !mounted || !c.mounted)
                                  return;
                                setState(() {
                                  _deletedPlans.add(saved.id);
                                  savedPlans.removeWhere(
                                    (p) => p.id == saved.id,
                                  );
                                });
                                setLocal(() {});
                                await _persistPlans();
                              },
                              icon: const Icon(Icons.delete_outline),
                            ),
                          ],
                        ),
                      );
                    },
                  ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(c),
              child: const Text('Fechar'),
            ),
          ],
        ),
      ),
    );
  }

  Map<String, dynamic> _fullSummary() {
    final s = widget.snapshot, now = DateTime.now();
    String categoryName(int? id) {
      for (final c in s.categories) {
        if (c.id == id) return c.name;
      }
      return 'Sem categoria';
    }

    String accountName(int? id) {
      for (final a in s.accounts) {
        if (a.id == id) return a.institutionName;
      }
      return 'Sem conta';
    }

    String cardName(int? id) {
      for (final c in s.cards) {
        if (c.id == id)
          return c.nickname?.isNotEmpty == true ? c.nickname! : c.bankName;
      }
      return 'Sem cartão';
    }

    final categoryTotals = <String, double>{};
    for (final t in _cash(s).where((t) => t.type != 'income')) {
      final n = categoryName(t.categoryId);
      categoryTotals[n] = (categoryTotals[n] ?? 0) + t.amount.abs();
    }
    final future = s.transactions
        .where((t) {
          final d = _date(t.date);
          return d != null && d.isAfter(now);
        })
        .map(
          (t) => {
            'data': t.date,
            'descricao': t.description,
            'valor': t.amount,
            'tipo': t.type,
            'origem': t.source,
          },
        )
        .toList();
    return {
      'reference_date': now.toIso8601String(),
      'saldo_consolidado': _balance(s),
      'entradas': _income(s),
      'saidas': _expense(s),
      'contas': s.accounts
          .map(
            (a) => {
              'id': a.id,
              'instituicao': a.institutionName,
              'nome': a.accountName,
              'saldo': a.currentBalance,
              'status': a.connectionStatus,
            },
          )
          .toList(),
      'cartoes': s.cards
          .map(
            (c) => {
              'id': c.id,
              'banco': c.bankName,
              'apelido': c.nickname,
              'bandeira': c.brand,
              'final': c.lastFour,
              'limite': c.creditLimit,
              'fechamento': c.closingDay,
              'vencimento': c.dueDay,
              'ativo': c.active,
            },
          )
          .toList(),
      'categorias': s.categories
          .map((c) => {'id': c.id, 'nome': c.name, 'tipo': c.type})
          .toList(),
      'orcamentos': s.budgets
          .map(
            (b) => {
              'categoria': categoryName(b.categoryId),
              'limite': b.amount,
            },
          )
          .toList(),
      'metas': s.goals
          .map(
            (g) => {
              'nome': g.name,
              'meta': g.targetAmount,
              'atual': g.currentAmount,
              'data_alvo': g.targetDate,
            },
          )
          .toList(),
      'gastos_por_categoria': categoryTotals.entries
          .map((e) => {'categoria': e.key, 'valor': e.value})
          .toList(),
      'compromissos_futuros': future,
      'transacoes': s.transactions
          .map(
            (t) => {
              'data': t.date,
              'descricao': t.description,
              'valor': t.amount,
              'tipo': t.type,
              'banco': accountName(t.accountId),
              'categoria': categoryName(t.categoryId),
              'cartao': cardName(t.cardId),
              'origem': t.source,
            },
          )
          .toList(),
      'regra_cartao':
          'compras no cartão não reduzem caixa; pagamento da fatura é a saída de caixa',
    };
  }

  @override
  Widget build(BuildContext context) {
    final income = _income(widget.snapshot),
        expense = _expense(widget.snapshot),
        balance = _balance(widget.snapshot);
    final cardSpend = widget.snapshot.transactions
        .where((t) => t.isCard && t.source != 'card_payment')
        .fold<double>(0, (a, t) => a + t.amount.abs());
    final byCat = <int?, double>{};
    for (final t in _cash(widget.snapshot).where((t) => t.type != 'income')) {
      byCat[t.categoryId] = (byCat[t.categoryId] ?? 0) + t.amount.abs();
    }
    MapEntry<int?, double>? top;
    for (final e in byCat.entries) {
      if (top == null || e.value > top.value) top = e;
    }
    var cat = 'Sem categoria';
    if (top?.key != null) {
      for (final c in widget.snapshot.categories) {
        if (c.id == top!.key) {
          cat = c.name;
          break;
        }
      }
    }
    final rate = income > 0 ? (((income - expense) / income) * 100) : null;
    return Container(
      color: const Color(0xFFF6F8FC),
      child: ListView(
        padding: const EdgeInsets.fromLTRB(18, 18, 18, 28),
        children: [
          if (_syncError != null) _SyncNotice(_syncError!, _syncPlans),
          if (_hasLegacyPlans)
            TextButton.icon(
              onPressed: _importLegacyPlans,
              icon: Icon(Icons.file_download_outlined),
              label: Text('Importar planos antigos deste aparelho'),
            ),

          Container(
            padding: const EdgeInsets.all(22),
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                colors: [
                  Color(0xFF0E4FA3),
                  Color(0xFF416AD9),
                  Color(0xFF6D5CE7),
                ],
              ),
              borderRadius: BorderRadius.circular(22),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Inteligência financeira',
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 24,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                      SizedBox(height: 6),
                      Text(
                        'A IA usa o contexto financeiro completo do workspace para analisar, responder e montar planos.',
                        style: TextStyle(
                          color: Color(0xFFE7EEFF),
                          fontSize: 14,
                          height: 1.35,
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 12),
                Icon(
                  Icons.auto_awesome_rounded,
                  color: Colors.white.withOpacity(.96),
                  size: 30,
                ),
              ],
            ),
          ),
          const SizedBox(height: 14),
          Row(
            children: [
              Expanded(
                child: _IntelMetric(
                  'Saldo atual',
                  _money(balance),
                  Icons.account_balance_wallet_outlined,
                  const Color(0xFF0E4FA3),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: _IntelMetric(
                  'Compras no cartão',
                  _money(cardSpend),
                  Icons.credit_card_rounded,
                  const Color(0xFF6D5CE7),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: _IntelMetric(
                  'Entradas',
                  _money(income),
                  Icons.south_west_rounded,
                  const Color(0xFF16845B),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: _IntelMetric(
                  'Saídas',
                  _money(expense),
                  Icons.north_east_rounded,
                  const Color(0xFFC73E47),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          if (top != null)
            _IntelWideMetric(
              'Maior grupo de despesas',
              '$cat • ${_money(top.value)}',
              Icons.pie_chart_outline_rounded,
            ),
          const SizedBox(height: 10),
          _IntelWideMetric(
            'Taxa de economia',
            rate == null
                ? 'Sem entradas suficientes'
                : '${rate.toStringAsFixed(1)}%',
            Icons.savings_outlined,
          ),
          const SizedBox(height: 20),
          const Text(
            'Planejamento financeiro com IA',
            style: TextStyle(
              fontSize: 19,
              fontWeight: FontWeight.w800,
              color: Color(0xFF182230),
            ),
          ),
          const SizedBox(height: 10),
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                colors: [Colors.white, Color(0xFFF7FAFF), Color(0xFFFAF8FF)],
              ),
              borderRadius: BorderRadius.circular(18),
              border: Border.all(color: const Color(0xFFE4E9F1)),
            ),
            child: Column(
              children: [
                TextField(
                  controller: goalCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Objetivo',
                    hintText: 'Ex.: comprar um carro',
                    border: OutlineInputBorder(),
                  ),
                ),
                const SizedBox(height: 10),
                Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: targetCtrl,
                        keyboardType: const TextInputType.numberWithOptions(
                          decimal: true,
                        ),
                        decoration: const InputDecoration(
                          labelText: 'Valor da meta',
                          prefixText: 'R\$ ',
                          border: OutlineInputBorder(),
                        ),
                      ),
                    ),
                    const SizedBox(width: 10),
                    SizedBox(
                      width: 150,
                      child: TextField(
                        controller: monthsCtrl,
                        keyboardType: TextInputType.number,
                        decoration: const InputDecoration(
                          labelText: 'Prazo (meses)',
                          border: OutlineInputBorder(),
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                FilledButton.icon(
                  onPressed: busy || widget.isGuest
                      ? null
                      : () => _ask(planMode: true),
                  icon: busy
                      ? const SizedBox.square(
                          dimension: 16,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.auto_awesome),
                  label: const Text('Preparar plano com IA'),
                  style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(48),
                  ),
                ),
                if (plan != null) ...[
                  const SizedBox(height: 14),
                  Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      color: const Color(0xFFF7FAFF),
                      borderRadius: BorderRadius.circular(16),
                      border: Border.all(color: const Color(0xFFDCE6F7)),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Row(
                          children: [
                            Icon(
                              Icons.auto_awesome_rounded,
                              color: Color(0xFF4F5BD5),
                            ),
                            SizedBox(width: 8),
                            Text(
                              'Plano gerado',
                              style: TextStyle(
                                fontSize: 17,
                                fontWeight: FontWeight.w800,
                                color: Color(0xFF182230),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 12),
                        _AiFormattedText(plan!),
                        const SizedBox(height: 12),
                        Row(
                          children: [
                            Expanded(
                              child: FilledButton.icon(
                                onPressed: _saveCurrentPlan,
                                icon: const Icon(Icons.bookmark_add_outlined),
                                label: const Text('Salvar plano'),
                              ),
                            ),
                            const SizedBox(width: 8),
                            Expanded(
                              child: OutlinedButton.icon(
                                onPressed: _showSavedPlans,
                                icon: const Icon(Icons.folder_open_outlined),
                                label: Text(
                                  'Meus planos (${savedPlans.length})',
                                ),
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ] else ...[
                  SizedBox(height: 10),
                  OutlinedButton.icon(
                    onPressed: _showSavedPlans,
                    icon: const Icon(Icons.folder_open_outlined),
                    label: Text('Meus planos da IA (${savedPlans.length})'),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(height: 20),
          const Text(
            'Assistente financeiro',
            style: TextStyle(
              fontSize: 19,
              fontWeight: FontWeight.w800,
              color: Color(0xFF182230),
            ),
          ),
          const SizedBox(height: 10),
          if (widget.isGuest)
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(16),
                border: Border.all(color: const Color(0xFFE4E9F1)),
              ),
              child: const Row(
                children: [
                  Icon(Icons.info_outline_rounded, color: Color(0xFF0E4FA3)),
                  SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      'Entre com uma conta para conversar com a IA. Os indicadores locais continuam funcionando.',
                    ),
                  ),
                ],
              ),
            )
          else
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                gradient: const LinearGradient(
                  colors: [Colors.white, Color(0xFFF8FBFF)],
                ),
                borderRadius: BorderRadius.circular(18),
                border: Border.all(color: const Color(0xFFE4E9F1)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Row(
                    children: [
                      Icon(
                        Icons.chat_bubble_outline_rounded,
                        color: Color(0xFF0E4FA3),
                      ),
                      SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          'Converse com a IA',
                          style: TextStyle(
                            fontSize: 17,
                            fontWeight: FontWeight.w800,
                            color: Color(0xFF182230),
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 5),
                  const Text(
                    'A IA recebe contas, cartões, categorias, orçamentos, metas, transações e compromissos futuros do workspace.',
                    style: TextStyle(color: Color(0xFF667085), fontSize: 12),
                  ),
                  const SizedBox(height: 10),
                  TextField(
                    controller: ctrl,
                    minLines: 2,
                    maxLines: 4,
                    decoration: InputDecoration(
                      filled: true,
                      fillColor: const Color(0xFFF8FAFD),
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(14),
                        borderSide: const BorderSide(color: Color(0xFFE4E9F1)),
                      ),
                      enabledBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(14),
                        borderSide: const BorderSide(color: Color(0xFFE4E9F1)),
                      ),
                      hintText: 'Ex.: onde posso economizar este mês?',
                    ),
                  ),
                  const SizedBox(height: 10),
                  Align(
                    alignment: Alignment.centerRight,
                    child: FilledButton.icon(
                      onPressed: busy ? null : () => _ask(),
                      icon: busy
                          ? const SizedBox.square(
                              dimension: 16,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.auto_awesome),
                      label: Text(busy ? 'Consultando' : 'Perguntar à IA'),
                    ),
                  ),
                  if (answer != null) ...[
                    const Divider(height: 24),
                    _AiFormattedText(answer!),
                  ],
                ],
              ),
            ),
        ],
      ),
    );
  }

  Future<void> _ask({bool planMode = false}) async {
    if (widget.onAskAi == null) return;
    String question;
    if (planMode) {
      final raw = targetCtrl.text.replaceAll('.', '').replaceAll(',', '.');
      final target = double.tryParse(raw) ?? 0;
      final months = int.tryParse(monthsCtrl.text) ?? 0;
      if (goalCtrl.text.trim().isEmpty || target <= 0 || months <= 0) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Informe objetivo, valor da meta e prazo.'),
          ),
        );
        return;
      }
      question =
          "Monte um plano financeiro prático, em português do Brasil, para o objetivo '${goalCtrl.text.trim()}', meta de ${_money(target)}, em $months meses. Use todos os dados financeiros enviados no resumo, proponha etapas, esforço mensal, prioridades, riscos e ajustes concretos. Não crie transações automaticamente.";
    } else {
      if (ctrl.text.trim().isEmpty) return;
      question = ctrl.text.trim();
    }
    setState(() => busy = true);
    try {
      final response = await widget.onAskAi!(question, _fullSummary());
      if (mounted)
        setState(() {
          if (planMode) {
            plan = response;
          } else {
            answer = response;
          }
        });
    } catch (e) {
      if (mounted)
        setState(
          () => planMode
              ? plan = 'Não foi possível gerar o plano agora: $e'
              : answer = 'Não foi possível consultar a IA: $e',
        );
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }
}

class _AiFormattedText extends StatelessWidget {
  const _AiFormattedText(this.content);
  final String content;
  @override
  Widget build(BuildContext context) {
    final lines = content
        .split('\n')
        .map((e) => e.trim())
        .where((e) => e.isNotEmpty);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (final raw in lines)
          Padding(padding: const EdgeInsets.only(bottom: 7), child: _line(raw)),
      ],
    );
  }

  Widget _line(String raw) {
    final clean = _cleanAi(raw);
    if (raw.startsWith('###') || raw.startsWith('##') || raw.startsWith('# '))
      return Text(
        clean,
        style: const TextStyle(
          fontSize: 17,
          fontWeight: FontWeight.w800,
          color: Color(0xFF183B72),
        ),
      );
    if (raw.startsWith('**') && raw.endsWith('**') && clean.length < 90)
      return Text(
        clean,
        style: const TextStyle(
          fontWeight: FontWeight.w800,
          color: Color(0xFF25324A),
        ),
      );
    if (raw.startsWith('-') || raw.startsWith('*'))
      return Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            '•  ',
            style: TextStyle(
              color: Color(0xFF4F5BD5),
              fontWeight: FontWeight.w900,
            ),
          ),
          Expanded(
            child: Text(
              clean.replaceFirst(RegExp(r'^[•\-]\s*'), ''),
              style: const TextStyle(height: 1.45, color: Color(0xFF344054)),
            ),
          ),
        ],
      );
    return Text(
      clean,
      style: const TextStyle(height: 1.45, color: Color(0xFF344054)),
    );
  }
}

class _IntelMetric extends StatelessWidget {
  const _IntelMetric(this.title, this.value, this.icon, this.accent);
  final String title, value;
  final IconData icon;
  final Color accent;
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(15),
    decoration: BoxDecoration(
      gradient: LinearGradient(
        colors: [Colors.white, accent.withOpacity(.035)],
      ),
      borderRadius: BorderRadius.circular(16),
      border: Border.all(color: const Color(0xFFE4E9F1)),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 36,
          height: 36,
          decoration: BoxDecoration(
            color: accent.withOpacity(.10),
            borderRadius: BorderRadius.circular(10),
          ),
          child: Icon(icon, color: accent, size: 20),
        ),
        const SizedBox(height: 10),
        Text(
          title,
          style: const TextStyle(
            color: Color(0xFF667085),
            fontSize: 12,
            fontWeight: FontWeight.w600,
          ),
        ),
        const SizedBox(height: 4),
        FittedBox(
          fit: BoxFit.scaleDown,
          alignment: Alignment.centerLeft,
          child: Text(
            value,
            style: const TextStyle(
              fontSize: 18,
              fontWeight: FontWeight.w800,
              color: Color(0xFF182230),
            ),
          ),
        ),
      ],
    ),
  );
}

class _IntelWideMetric extends StatelessWidget {
  const _IntelWideMetric(this.title, this.value, this.icon);
  final String title, value;
  final IconData icon;
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 14),
    decoration: BoxDecoration(
      gradient: const LinearGradient(colors: [Colors.white, Color(0xFFF7FAFF)]),
      borderRadius: BorderRadius.circular(16),
      border: Border.all(color: const Color(0xFFE4E9F1)),
    ),
    child: Row(
      children: [
        Container(
          width: 38,
          height: 38,
          decoration: BoxDecoration(
            color: const Color(0xFF0E4FA3).withOpacity(.09),
            borderRadius: BorderRadius.circular(11),
          ),
          child: Icon(icon, color: const Color(0xFF0E4FA3), size: 21),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: const TextStyle(
                  color: Color(0xFF667085),
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                ),
              ),
              const SizedBox(height: 3),
              Text(
                value,
                style: const TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.w800,
                  color: Color(0xFF182230),
                ),
              ),
            ],
          ),
        ),
      ],
    ),
  );
}

class _SyncNotice extends StatelessWidget {
  const _SyncNotice(this.message, this.retry);
  final String message;
  final VoidCallback retry;
  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: EdgeInsets.all(12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(message),
          TextButton.icon(
            onPressed: retry,
            icon: Icon(Icons.sync),
            label: Text('Tentar sincronizar'),
          ),
        ],
      ),
    ),
  );
}
