import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';
import 'package:file_picker/file_picker.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:url_launcher/url_launcher.dart';
import '../../core/input_masks.dart';
import '../../core/models.dart';
import '../../core/app_customization.dart';
import '../../core/api_client.dart';
import '../../core/category_bank_visuals.dart';
import '../../core/browser_download_stub.dart'
    if (dart.library.html) '../../core/browser_download_web.dart';
import '../settings/app_settings_screen.dart';
import '../settings/data_management_screen.dart';
import '../tools/finance_tools_screen.dart';
import '../planning/planning_screen.dart';
import '../summary/summary_screen.dart';
import 'insights_views.dart';
import 'transactions_view.dart';
import 'expense_scan.dart';
import 'debt_center.dart';

const _payablePrefix = '__PAYABLE_V1__|';

class _PayableMeta {
  const _PayableMeta(this.reminderDays, this.amount, this.description);
  final int reminderDays;
  final double amount;
  final String description;
}

String _encodePayable(String description, double amount, int reminderDays) {
  final cents = (amount.abs() * 100).round();
  final encoded = base64Encode(utf8.encode(description.trim()));
  return '$_payablePrefix${reminderDays.clamp(1, 30)}|$cents|$encoded';
}

_PayableMeta? _decodePayable(String value) {
  if (!value.startsWith(_payablePrefix)) return null;
  final parts = value.substring(_payablePrefix.length).split('|');
  if (parts.length < 3) return null;
  final reminder = int.tryParse(parts[0]);
  final cents = int.tryParse(parts[1]);
  if (reminder == null || cents == null) return null;
  try {
    final desc = utf8.decode(base64Decode(parts.sublist(2).join('|'))).trim();
    if (desc.isEmpty) return null;
    return _PayableMeta(reminder.clamp(1, 30), cents / 100.0, desc);
  } catch (_) {
    return null;
  }
}

bool _isPendingPayable(FinancialTransaction t) =>
    _decodePayable(t.description) != null;

bool _isNonCashPlanned(FinancialTransaction t) => _isPendingPayable(t) || isPlannedDebtTransaction(t);

class HomeScreen extends StatefulWidget {
  const HomeScreen({
    super.key,
    required this.workspace,
    required this.workspaces,
    required this.onWorkspace,
    required this.onCreateWorkspace,
    required this.onReloadWorkspaces,
    required this.onLogout,
    required this.onTheme,
    required this.themeMode,
    required this.serverOk,
    required this.snapshot,
    required this.syncing,
    required this.onSync,
    required this.isGuest,
    required this.onSaveTransaction,
    required this.onImportTransactions,
    required this.onDeleteTransaction,
    required this.onSaveAccount,
    required this.onDeleteAccount,
    required this.onSaveCard,
    required this.onDeleteCard,
    required this.onSaveCategory,
    required this.onDeleteCategory,
    this.onAskAi,
    required this.onSaveSnapshot,
    required this.api,
    this.accessToken,
    required this.onRefreshFinance,
    required this.onShowOnboarding,
  });
  final Workspace workspace;
  final List<Workspace> workspaces;
  final ValueChanged<Workspace> onWorkspace;
  final Future<void> Function(String, String) onCreateWorkspace;
  final Future<void> Function() onReloadWorkspaces;
  final VoidCallback onLogout;
  final ValueChanged<ThemeMode> onTheme;
  final ThemeMode themeMode;
  final bool serverOk, isGuest;
  final FinancialSnapshot snapshot;
  final bool syncing;
  final Future<void> Function() onSync;
  final Future<void> Function(FinancialTransaction) onSaveTransaction;
  final Future<void> Function(List<FinancialTransaction>) onImportTransactions;
  final Future<void> Function(int) onDeleteTransaction;
  final Future<void> Function(FinancialAccount) onSaveAccount;
  final Future<void> Function(int) onDeleteAccount;
  final Future<void> Function(CreditCardInfo) onSaveCard;
  final Future<void> Function(int) onDeleteCard;
  final Future<void> Function(FinanceCategory) onSaveCategory;
  final Future<void> Function(int) onDeleteCategory;
  final Future<String> Function(String, Map<String, dynamic>)? onAskAi;
  final Future<void> Function(FinancialSnapshot) onSaveSnapshot;
  final ApiClient api;
  final String? accessToken;
  final Future<void> Function() onRefreshFinance;
  final VoidCallback onShowOnboarding;
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  AppCustomization customization = const AppCustomization();
  final customizationStore = AppCustomizationStore();
  String? _userName;
  String? _profileImageB64;
  late List<FinanceCategory> _categoryCache;
  bool _showFinancialValues = false;
  final TextEditingController _globalSearchController = TextEditingController();
  String get _profileKey => 'financeapp_profile_photo_${widget.workspace.id}';
  @override
  void initState() {
    super.initState();
    _categoryCache = [...widget.snapshot.categories];
    _loadCustomization();
    _loadProfileHeader();
  }

  @override
  void dispose() {
    _globalSearchController.dispose();
    super.dispose();
  }

  @override
  void didUpdateWidget(covariant HomeScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.snapshot.categories != widget.snapshot.categories) {
      _categoryCache = [...widget.snapshot.categories];
    }
    if (oldWidget.workspace.id != widget.workspace.id) {
      index = 0;
      _categoryCache = [...widget.snapshot.categories];
      _loadCustomization();
      _loadProfileHeader();
    }
  }

  Future<void> _loadProfileHeader() async {
    final prefs = await SharedPreferences.getInstance();
    String? name;
    if (!widget.isGuest && widget.accessToken != null) {
      try {
        final u = await widget.api.currentUser(widget.accessToken!);
        name = (u['name'] ?? '').toString().trim();
      } catch (_) {}
    }
    if (mounted)
      setState(() {
        _userName = (name?.isNotEmpty == true) ? name : null;
        _profileImageB64 = prefs.getString(_profileKey);
      });
  }

  ImageProvider? get _profileImage {
    try {
      if (_profileImageB64?.isNotEmpty == true)
        return MemoryImage(base64Decode(_profileImageB64!));
    } catch (_) {}
    return null;
  }

  Future<void> _pickProfilePhoto() async {
    final picked = await ImagePicker().pickImage(
      source: ImageSource.gallery,
      imageQuality: 82,
      maxWidth: 800,
      maxHeight: 800,
    );
    if (picked == null) return;
    final bytes = await picked.readAsBytes();
    final encoded = base64Encode(bytes);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_profileKey, encoded);
    if (mounted) setState(() => _profileImageB64 = encoded);
  }

  Future<void> _removeProfilePhoto() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_profileKey);
    if (mounted) setState(() => _profileImageB64 = null);
  }

  Future<void> _loadCustomization() async {
    final v = await customizationStore.read(widget.workspace.id);
    if (mounted) setState(() => customization = v);
  }

  Future<void> _saveCustomization(AppCustomization v) async {
    await customizationStore.save(widget.workspace.id, v);
    if (mounted)
      setState(() {
        customization = v;
        if (!v.showDashboardTab && index == 0) index = 1;
      });
  }

  int index = 0;
  String money(num v) => 'R\$ ${v.toStringAsFixed(2).replaceAll('.', ',')}';
  int _id() => DateTime.now().microsecondsSinceEpoch.remainder(2147483647);
  @override
  Widget build(BuildContext context) {
    final nav =
        <({String key, NavigationDestination destination, Widget page})>[];
    if (customization.showDashboardTab)
      nav.add((
        key: 'home',
        destination: const NavigationDestination(
          icon: Icon(Icons.home_outlined),
          selectedIcon: Icon(Icons.home),
          label: 'Início',
        ),
        page: _dashboard(),
      ));
    if (customization.showTransactionsTab)
      nav.add((
        key: 'transactions',
        destination: const NavigationDestination(
          icon: Icon(Icons.receipt_long_outlined),
          selectedIcon: Icon(Icons.receipt_long),
          label: 'Transações',
        ),
        page: _transactions(),
      ));
    if (customization.showForecastTab)
      nav.add((
        key: 'forecast',
        destination: const NavigationDestination(
          icon: Icon(Icons.auto_graph_outlined),
          selectedIcon: Icon(Icons.auto_graph),
          label: 'Previsão',
        ),
        page: ForecastView(
          key: ValueKey('forecast_${widget.workspace.id}'),
          snapshot: widget.snapshot.copyWith(
            transactions: widget.snapshot.transactions
                .where((t) => !_isNonCashPlanned(t))
                .toList(),
          ),
          api: widget.api,
          accessToken: widget.accessToken,
          workspaceId: widget.workspace.id,
          refreshing: widget.syncing,
          isGuest: widget.isGuest,
          onSaveTransaction: widget.onSaveTransaction,
        ),
      ));
    if (customization.showIntelligenceTab)
      nav.add((
        key: 'intelligence',
        destination: const NavigationDestination(
          icon: Icon(Icons.psychology_alt_outlined),
          selectedIcon: Icon(Icons.psychology_alt),
          label: 'Inteligência',
        ),
        page: IntelligenceView(
          key: ValueKey('intelligence_${widget.workspace.id}'),
          api: widget.api,
          accessToken: widget.accessToken,
          workspaceId: widget.workspace.id,
          refreshing: widget.syncing,
          snapshot: widget.snapshot.copyWith(
            transactions: widget.snapshot.transactions
                .where((t) => !_isNonCashPlanned(t))
                .toList(),
          ),
          isGuest: widget.isGuest,
          onAskAi: widget.onAskAi,
        ),
      ));
    if (customization.showAccountsTab)
      nav.add((
        key: 'accounts',
        destination: const NavigationDestination(
          icon: Icon(Icons.account_balance_wallet_outlined),
          selectedIcon: Icon(Icons.account_balance_wallet),
          label: 'Bancos',
        ),
        page: _accountsCards(),
      ));
    if (nav.isEmpty)
      nav.add((
        key: 'home',
        destination: const NavigationDestination(
          icon: Icon(Icons.home),
          label: 'Início',
        ),
        page: _dashboard(),
      ));
    if (index >= nav.length) index = 0;
    final current = nav[index].key;
    final wide = MediaQuery.sizeOf(context).width >= 1000;
    if (wide) {
      return Scaffold(
        backgroundColor: Theme.of(context).scaffoldBackgroundColor,
        body: Row(
          children: [
            Container(
              width: 232,
              color: const Color(0xFF071A2E),
              padding: const EdgeInsets.fromLTRB(18, 24, 18, 18),
              child: SafeArea(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Row(
                      children: [
                        Container(
                          width: 42,
                          height: 42,
                          padding: const EdgeInsets.all(5),
                          decoration: BoxDecoration(
                            color: Colors.white,
                            borderRadius: BorderRadius.circular(12),
                          ),
                          child: Image.asset(
                            'assets/financeapp_logo.png',
                            fit: BoxFit.contain,
                          ),
                        ),
                        const SizedBox(width: 10),
                        const Text(
                          'FinanceApp',
                          style: TextStyle(
                            color: Colors.white,
                            fontSize: 19,
                            fontWeight: FontWeight.w800,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 28),
                    for (var i = 0; i < nav.length; i++)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 6),
                        child: _desktopNavButton(i, nav[i].destination),
                      ),
                    const Spacer(),
                    _desktopExtraButton(
                      Icons.settings_outlined,
                      'Configurações',
                      _openSettings,
                    ),
                  ],
                ),
              ),
            ),
            Expanded(
              child: Column(
                children: [
                  Container(
                    height: 82,
                    color: Theme.of(context).colorScheme.surface,
                    padding: EdgeInsets.symmetric(
                      horizontal: MediaQuery.sizeOf(context).width < 1200
                          ? 18
                          : 32,
                    ),
                    child: Row(
                      children: [
                        Expanded(
                          child: Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                'Olá, ${widget.isGuest ? 'visitante' : (_userName ?? 'Renan')} 👋',
                                style: Theme.of(context).textTheme.headlineSmall
                                    ?.copyWith(fontWeight: FontWeight.w800),
                              ),
                              const SizedBox(height: 3),
                              Row(
                                children: [
                                  Text(
                                    'Aqui está o seu resumo financeiro',
                                    style: Theme.of(context).textTheme.bodySmall
                                        ?.copyWith(
                                          color: Theme.of(
                                            context,
                                          ).colorScheme.onSurfaceVariant,
                                        ),
                                  ),
                                  if (!widget.isGuest) ...[
                                    const SizedBox(width: 10),
                                    _workspaceHeaderBadge(),
                                  ],
                                ],
                              ),
                            ],
                          ),
                        ),
                        ConstrainedBox(
                          constraints: BoxConstraints(
                            maxWidth: MediaQuery.sizeOf(context).width < 1200
                                ? 300
                                : 430,
                          ),
                          child: SizedBox(
                            height: 42,
                            child: TextField(
                              controller: _globalSearchController,
                              textInputAction: TextInputAction.search,
                              onSubmitted: _runGlobalSearch,
                              decoration: InputDecoration(
                                hintText: 'Buscar no FinanceApp...',
                                hintStyle: const TextStyle(
                                  color: Color(0xFF7B8494),
                                  fontSize: 13,
                                ),
                                prefixIcon: const Icon(
                                  Icons.search,
                                  size: 20,
                                  color: Color(0xFF374151),
                                ),
                                suffixIcon: IconButton(
                                  tooltip: 'Buscar',
                                  onPressed: () => _runGlobalSearch(
                                    _globalSearchController.text,
                                  ),
                                  icon: const Icon(
                                    Icons.arrow_forward_rounded,
                                    size: 19,
                                  ),
                                ),
                                filled: true,
                                fillColor: const Color(0xFFF4F6FA),
                                contentPadding: const EdgeInsets.symmetric(
                                  vertical: 0,
                                ),
                                border: OutlineInputBorder(
                                  borderRadius: BorderRadius.circular(22),
                                  borderSide: BorderSide.none,
                                ),
                                enabledBorder: OutlineInputBorder(
                                  borderRadius: BorderRadius.circular(22),
                                  borderSide: BorderSide.none,
                                ),
                                focusedBorder: OutlineInputBorder(
                                  borderRadius: BorderRadius.circular(22),
                                  borderSide: const BorderSide(
                                    color: Color(0xFF2B72F5),
                                    width: 1.2,
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ),
                        const SizedBox(width: 14),
                        if (!widget.isGuest)
                          IconButton(
                            tooltip: 'Sincronizar',
                            onPressed: widget.syncing ? null : widget.onSync,
                            icon: widget.syncing
                                ? const SizedBox.square(
                                    dimension: 18,
                                    child: CircularProgressIndicator(
                                      strokeWidth: 2,
                                    ),
                                  )
                                : const Icon(Icons.cloud_done_outlined),
                          ),
                        _profileMenu(),
                      ],
                    ),
                  ),
                  Expanded(child: nav[index].page),
                ],
              ),
            ),
          ],
        ),
        floatingActionButton: current == 'transactions'
            ? FloatingActionButton.extended(
                onPressed: _expenseAddMenu,
                icon: const Icon(Icons.add),
                label: const Text('Lançamento'),
              )
            : null,
      );
    }
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Theme.of(context).scaffoldBackgroundColor,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Olá, ${widget.isGuest ? 'visitante' : (_userName ?? 'Renan')} 👋',
              style: const TextStyle(fontWeight: FontWeight.w800),
            ),
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  'Aqui está o seu resumo financeiro',
                  style: Theme.of(context).textTheme.labelMedium?.copyWith(
                    color: const Color(0xFF7A8496),
                  ),
                ),
                if (!widget.isGuest) ...[
                  const SizedBox(width: 8),
                  Flexible(child: _workspaceHeaderBadge(compact: true)),
                ],
              ],
            ),
          ],
        ),
        actions: [
          if (!widget.isGuest)
            IconButton(
              tooltip: 'Sincronizar',
              onPressed: widget.syncing ? null : widget.onSync,
              icon: widget.syncing
                  ? const SizedBox.square(
                      dimension: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.notifications_none_rounded),
            ),
          _profileMenu(),
        ],
      ),
      body: nav[index].page,
      floatingActionButton: current == 'transactions'
          ? FloatingActionButton.extended(
              onPressed: _expenseAddMenu,
              icon: const Icon(Icons.add),
              label: const Text('Lançamento'),
            )
          : null,
      bottomNavigationBar: NavigationBar(
        height: 72,
        indicatorColor: const Color(0xFFEAF2FF),
        selectedIndex: index,
        onDestinationSelected: (v) => setState(() => index = v),
        destinations: nav.map((e) => e.destination).toList(),
      ),
    );
  }

  Widget _profileMenu() => PopupMenuButton<String>(
    tooltip: 'Conta e configurações',
    onSelected: (v) {
      if (v == 'personal') _openPersonal();
      if (v == 'settings') _openSettings();
      if (v == 'logout') widget.onLogout();
    },
    itemBuilder: (_) => [
      PopupMenuItem(
        value: 'personal',
        child: ListTile(
          leading: CircleAvatar(
            backgroundImage: _profileImage,
            child: _profileImage == null
                ? Icon(widget.isGuest ? Icons.person_outline : Icons.person)
                : null,
          ),
          title: Text(
            widget.isGuest ? 'Modo sem cadastro' : (_userName ?? 'Usuário'),
          ),
          subtitle: Text(widget.isGuest ? 'Dados locais' : 'Editar dados'),
        ),
      ),
      const PopupMenuDivider(),
      const PopupMenuItem(
        value: 'settings',
        child: ListTile(
          leading: Icon(Icons.settings_outlined),
          title: Text('Configurações do App'),
        ),
      ),
      const PopupMenuItem(
        value: 'logout',
        child: ListTile(leading: Icon(Icons.logout), title: Text('Sair')),
      ),
    ],
    child: Padding(
      padding: const EdgeInsets.symmetric(horizontal: 10),
      child: CircleAvatar(
        radius: 18,
        backgroundImage: _profileImage,
        backgroundColor: const Color(0xFFE8EEFA),
        child: _profileImage == null
            ? Icon(
                widget.isGuest ? Icons.person_outline : Icons.person,
                size: 20,
                color: const Color(0xFF263B5E),
              )
            : null,
      ),
    ),
  );
  Widget _desktopNavButton(int i, NavigationDestination d) {
    final selected = i == index;
    final candidate = selected ? d.selectedIcon : d.icon;
    final iconData = candidate is Icon
        ? (candidate.icon ?? Icons.circle_outlined)
        : Icons.circle_outlined;
    return InkWell(
      borderRadius: BorderRadius.circular(10),
      onTap: () => setState(() => index = i),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
        decoration: BoxDecoration(
          color: selected ? const Color(0xFF123B6D) : Colors.transparent,
          borderRadius: BorderRadius.circular(10),
        ),
        child: Row(
          children: [
            Icon(
              iconData,
              color: selected
                  ? const Color(0xFF65A7FF)
                  : const Color(0xFFB7C3D4),
              size: 21,
            ),
            const SizedBox(width: 12),
            Text(
              d.label,
              style: TextStyle(
                color: selected ? Colors.white : const Color(0xFFCDD6E2),
                fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _desktopExtraButton(IconData icon, String label, VoidCallback onTap) =>
      InkWell(
        borderRadius: BorderRadius.circular(10),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
          child: Row(
            children: [
              Icon(icon, color: const Color(0xFFB7C3D4), size: 21),
              const SizedBox(width: 12),
              Text(label, style: const TextStyle(color: Color(0xFFCDD6E2))),
            ],
          ),
        ),
      );
  String _normGlobal(String value) => value
      .toLowerCase()
      .replaceAll(RegExp(r'[áàãâä]'), 'a')
      .replaceAll(RegExp(r'[éèêë]'), 'e')
      .replaceAll(RegExp(r'[íìîï]'), 'i')
      .replaceAll(RegExp(r'[óòõôö]'), 'o')
      .replaceAll(RegExp(r'[úùûü]'), 'u')
      .replaceAll('ç', 'c')
      .trim();
  String _transactionGlobalText(FinancialTransaction t) {
    DateTime? d;
    try {
      d = DateTime.parse(t.date);
    } catch (_) {}
    final months = const [
      'janeiro',
      'fevereiro',
      'marco',
      'abril',
      'maio',
      'junho',
      'julho',
      'agosto',
      'setembro',
      'outubro',
      'novembro',
      'dezembro',
    ];
    final monthTokens = d == null
        ? ''
        : '${months[d.month - 1]} ${d.month.toString().padLeft(2, '0')} ${d.month.toString().padLeft(2, '0')}/${d.year} ${d.year}';
    final account =
        widget.snapshot.accounts
            .where((a) => a.id == t.accountId)
            .map(
              (a) =>
                  '${a.institutionName} ${a.accountName ?? ''} ${a.maskedAccount ?? ''}',
            )
            .firstOrNull ??
        '';
    final card =
        widget.snapshot.cards
            .where((c) => c.id == t.cardId)
            .map(
              (c) =>
                  '${c.bankName} ${c.brand} ${c.lastFour} ${c.nickname ?? ''}',
            )
            .firstOrNull ??
        '';
    final category =
        widget.snapshot.categories
            .where((c) => c.id == t.categoryId)
            .map((c) => c.name)
            .firstOrNull ??
        '';
    return _normGlobal(
      '${t.description} ${t.date} $monthTokens $account $card $category ${money(t.amount.abs())} ${t.type} ${t.source}',
    );
  }

  Widget _globalSearchTransactionCard(
    BuildContext dialogContext,
    FinancialTransaction t,
  ) {
    final income = t.type == 'income';
    final valueColor = income
        ? const Color(0xFF16865B)
        : const Color(0xFFD94A55);
    DateTime? d;
    try {
      d = DateTime.parse(t.date);
    } catch (_) {}
    final dateLabel = d == null
        ? (t.date.length >= 10 ? t.date.substring(0, 10) : t.date)
        : '${d.day.toString().padLeft(2, '0')}/${d.month.toString().padLeft(2, '0')}/${d.year}';
    final hasTime = t.date.contains('T') || t.date.contains(' ');
    final timeLabel = d != null && hasTime
        ? '${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}'
        : '--:--';
    final debtCreditor = isPlannedDebtTransaction(t)
        ? debtCreditorFromTransactionDescription(t.description)
        : null;
    final account =
        debtCreditor ??
        widget.snapshot.accounts
            .where((a) => a.id == t.accountId)
            .map((a) => a.institutionName)
            .firstOrNull ??
        (t.isCard
            ? widget.snapshot.cards
                  .where((c) => c.id == t.cardId)
                  .map((c) => c.bankName)
                  .firstOrNull
            : null) ??
        'Sem banco';
    final category =
        widget.snapshot.categories
            .where((c) => c.id == t.categoryId)
            .map((c) => c.name)
            .firstOrNull ??
        'Sem categoria';
    final typeLabel = income ? 'Entrada' : 'Saída';
    final sourceLabel = t.source.toLowerCase().contains('open')
        ? 'Open Finance'
        : 'Manual';
    return Card(
      margin: const EdgeInsets.fromLTRB(8, 4, 8, 6),
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: const BorderSide(color: Color(0xFFE6EAF0)),
      ),
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: () {
          Navigator.pop(dialogContext);
          _transactionDialog(t);
        },
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 38,
                height: 38,
                decoration: BoxDecoration(
                  color: valueColor.withOpacity(.10),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(
                  t.isCard
                      ? Icons.credit_card_rounded
                      : (income
                            ? Icons.arrow_downward_rounded
                            : Icons.arrow_upward_rounded),
                  color: valueColor,
                  size: 20,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      t.description,
                      style: const TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w700,
                        color: Color(0xFF1F2937),
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      '$account • $category',
                      style: const TextStyle(
                        fontSize: 12.5,
                        color: Color(0xFF5F6B7A),
                      ),
                    ),
                    const SizedBox(height: 3),
                    Text(
                      '$dateLabel • $timeLabel • $typeLabel • $sourceLabel',
                      style: const TextStyle(
                        fontSize: 12,
                        color: Color(0xFF7B8494),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 12),
              Text(
                '${income ? '' : '-'}${money(t.amount.abs())}',
                style: TextStyle(
                  fontSize: 15,
                  fontWeight: FontWeight.w800,
                  color: valueColor,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _runGlobalSearch(String raw) async {
    final q = _normGlobal(raw);
    if (q.isEmpty) return;
    final tx = widget.snapshot.transactions
        .where((t) => !_isNonCashPlanned(t))
        .where((t) => _transactionGlobalText(t).contains(q))
        .take(20)
        .toList();
    final accounts = widget.snapshot.accounts
        .where(
          (a) => _normGlobal(
            '${a.institutionName} ${a.accountName ?? ''} ${a.maskedAccount ?? ''}',
          ).contains(q),
        )
        .take(8)
        .toList();
    final cards = widget.snapshot.cards
        .where(
          (c) => _normGlobal(
            '${c.bankName} ${c.brand} ${c.lastFour} ${c.nickname ?? ''}',
          ).contains(q),
        )
        .take(8)
        .toList();
    final categories = widget.snapshot.categories
        .where((c) => _normGlobal(c.name).contains(q))
        .take(8)
        .toList();
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text('Resultados para “${raw.trim()}”'),
        content: SizedBox(
          width: 680,
          height: 460,
          child:
              (tx.isEmpty &&
                  accounts.isEmpty &&
                  cards.isEmpty &&
                  categories.isEmpty)
              ? const Center(
                  child: Text('Nenhum resultado encontrado no FinanceApp.'),
                )
              : ListView(
                  children: [
                    if (tx.isNotEmpty) ...[
                      const ListTile(
                        leading: Icon(Icons.receipt_long_outlined),
                        title: Text(
                          'Transações',
                          style: TextStyle(fontWeight: FontWeight.w800),
                        ),
                      ),
                      ...tx.map(
                        (t) => _globalSearchTransactionCard(dialogContext, t),
                      ),
                    ],
                    if (accounts.isNotEmpty) ...[
                      const Divider(),
                      const ListTile(
                        leading: Icon(Icons.account_balance_outlined),
                        title: Text(
                          'Contas',
                          style: TextStyle(fontWeight: FontWeight.w800),
                        ),
                      ),
                      ...accounts.map(
                        (a) => ListTile(
                          title: Text(a.institutionName),
                          subtitle: Text(
                            a.accountName ?? a.maskedAccount ?? 'Conta',
                          ),
                          onTap: () {
                            Navigator.pop(dialogContext);
                            _goTo('accounts');
                          },
                        ),
                      ),
                    ],
                    if (cards.isNotEmpty) ...[
                      const Divider(),
                      const ListTile(
                        leading: Icon(Icons.credit_card_outlined),
                        title: Text(
                          'Cartões',
                          style: TextStyle(fontWeight: FontWeight.w800),
                        ),
                      ),
                      ...cards.map(
                        (c) => ListTile(
                          title: Text(
                            c.nickname?.isNotEmpty == true
                                ? c.nickname!
                                : c.bankName,
                          ),
                          subtitle: Text('${c.brand} • final ${c.lastFour}'),
                          onTap: () {
                            Navigator.pop(dialogContext);
                            _goTo('accounts');
                          },
                        ),
                      ),
                    ],
                    if (categories.isNotEmpty) ...[
                      const Divider(),
                      const ListTile(
                        leading: Icon(Icons.category_outlined),
                        title: Text(
                          'Categorias',
                          style: TextStyle(fontWeight: FontWeight.w800),
                        ),
                      ),
                      ...categories.map(
                        (c) => ListTile(
                          title: Text(c.name),
                          onTap: () {
                            Navigator.pop(dialogContext);
                            _categoriesDialog();
                          },
                        ),
                      ),
                    ],
                  ],
                ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('Fechar'),
          ),
        ],
      ),
    );
  }

  void _goTo(String key) {
    final keys = <String>[];
    if (customization.showDashboardTab) keys.add('home');
    if (customization.showTransactionsTab) keys.add('transactions');
    if (customization.showForecastTab) keys.add('forecast');
    if (customization.showIntelligenceTab) keys.add('intelligence');
    if (customization.showAccountsTab) keys.add('accounts');
    final i = keys.indexOf(key);
    if (i >= 0) setState(() => index = i);
  }

  Future<void> _openPersonal() async {
    if (widget.isGuest) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            'No modo sem cadastro não há perfil de conta para editar.',
          ),
        ),
      );
      return;
    }
    if (widget.accessToken == null) return;
    try {
      final user = await widget.api.currentUser(widget.accessToken!);
      if (!mounted) return;
      final name = TextEditingController(text: (user['name'] ?? '').toString());
      final cpf = TextEditingController(text: cpfText(user['cpf']?.toString()));
      final birth = TextEditingController(
        text: brDateText(user['birth_date']?.toString()),
      );
      String sex = (user['sex'] ?? 'prefer_not_to_say').toString();
      final ok = await showDialog<bool>(
        context: context,
        builder: (c) => StatefulBuilder(
          builder: (c, set) => AlertDialog(
            title: const Text('Editar dados'),
            content: SizedBox(
              width: 440,
              child: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Center(
                      child: Column(
                        children: [
                          CircleAvatar(
                            radius: 38,
                            backgroundImage: _profileImage,
                            child: _profileImage == null
                                ? const Icon(Icons.person, size: 36)
                                : null,
                          ),
                          const SizedBox(height: 6),
                          Wrap(
                            alignment: WrapAlignment.center,
                            children: [
                              TextButton.icon(
                                onPressed: () async {
                                  await _pickProfilePhoto();
                                  set(() {});
                                },
                                icon: const Icon(Icons.photo_camera_outlined),
                                label: Text(
                                  _profileImage == null
                                      ? 'Adicionar foto'
                                      : 'Alterar foto',
                                ),
                              ),
                              if (_profileImage != null)
                                TextButton.icon(
                                  onPressed: () async {
                                    await _removeProfilePhoto();
                                    set(() {});
                                  },
                                  icon: const Icon(Icons.delete_outline),
                                  label: const Text('Excluir foto'),
                                ),
                            ],
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 18),
                    TextField(
                      controller: name,
                      decoration: const InputDecoration(
                        labelText: 'Nome completo',
                      ),
                    ),
                    const SizedBox(height: 18),
                    TextField(
                      controller: cpf,
                      keyboardType: TextInputType.number,
                      inputFormatters: [
                        FilteringTextInputFormatter.digitsOnly,
                        const CpfInputFormatter(),
                      ],
                      decoration: const InputDecoration(labelText: 'CPF'),
                    ),
                    const SizedBox(height: 18),
                    TextField(
                      controller: TextEditingController(
                        text: (user['email'] ?? '').toString(),
                      ),
                      enabled: false,
                      decoration: const InputDecoration(labelText: 'E-mail'),
                    ),
                    const SizedBox(height: 18),
                    TextField(
                      controller: birth,
                      keyboardType: TextInputType.number,
                      inputFormatters: const [BrDateInputFormatter()],
                      decoration: InputDecoration(
                        labelText: 'Data de nascimento',
                        hintText: 'dd/MM/aaaa',
                        suffixIcon: IconButton(
                          icon: const Icon(Icons.calendar_month),
                          onPressed: () async {
                            final iso = isoDateFromBr(birth.text);
                            final current = iso == null
                                ? null
                                : DateTime.tryParse(iso);
                            final d = await showDatePicker(
                              context: c,
                              initialDate: current ?? DateTime(1990),
                              firstDate: DateTime(1900),
                              lastDate: DateTime.now(),
                            );
                            if (d != null)
                              set(
                                () => birth.text =
                                    '${d.day.toString().padLeft(2, '0')}/${d.month.toString().padLeft(2, '0')}/${d.year}',
                              );
                          },
                        ),
                      ),
                    ),
                    const SizedBox(height: 18),
                    DropdownButtonFormField<String>(
                      initialValue:
                          [
                            'male',
                            'female',
                            'other',
                            'prefer_not_to_say',
                          ].contains(sex)
                          ? sex
                          : 'prefer_not_to_say',
                      decoration: const InputDecoration(labelText: 'Sexo'),
                      items: const [
                        DropdownMenuItem(
                          value: 'male',
                          child: Text('Masculino'),
                        ),
                        DropdownMenuItem(
                          value: 'female',
                          child: Text('Feminino'),
                        ),
                        DropdownMenuItem(value: 'other', child: Text('Outro')),
                        DropdownMenuItem(
                          value: 'prefer_not_to_say',
                          child: Text('Prefiro não informar'),
                        ),
                      ],
                      onChanged: (v) =>
                          set(() => sex = v ?? 'prefer_not_to_say'),
                    ),
                  ],
                ),
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(c, false),
                child: const Text('Cancelar'),
              ),
              FilledButton(
                onPressed: () => Navigator.pop(c, true),
                child: const Text('Salvar'),
              ),
            ],
          ),
        ),
      );
      if (ok == true) {
        await widget.api.updatePersonalProfile(
          widget.accessToken!,
          name: name.text,
          cpf: cpf.text,
          birthDate: isoDateFromBr(birth.text),
          sex: sex,
        );
        await _loadProfileHeader();
        if (mounted)
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Dados pessoais atualizados.')),
          );
      }
    } catch (e) {
      if (mounted)
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(e.toString().replaceFirst('Exception: ', ''))),
        );
    }
  }

  void _openSettings() {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => AppSettingsScreen(
          value: customization,
          themeMode: widget.themeMode,
          onTheme: widget.onTheme,
          onSave: _saveCustomization,
          onCategories: () {
            Navigator.pop(context);
            _categoriesDialog();
          },
          onOnboarding: () {
            Navigator.pop(context);
            widget.onShowOnboarding();
          },
          onDataManagement: () {
            Navigator.pop(context);
            Navigator.of(context).push(
              MaterialPageRoute(
                builder: (_) => DataManagementScreen(
                  snapshot: widget.snapshot,
                  onSave: widget.onSaveSnapshot,
                  isGuest: widget.isGuest,
                  api: widget.api,
                  accessToken: widget.accessToken,
                  onAccountDeleted: widget.onLogout,
                  onRefresh: widget.onRefreshFinance,
                  onImportTransactions: widget.onImportTransactions,
                ),
              ),
            );
          },
        ),
      ),
    );
  }

  Future<void> _transactionAttachmentsDialog(FinancialTransaction tx) async {
    if (widget.isGuest || widget.accessToken == null) {
      if (mounted)
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'Comprovantes sincronizados exigem uma conta conectada.',
            ),
          ),
        );
      return;
    }
    List<Map<String, dynamic>> items = [];
    bool loading = true;
    String? error;
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, setDialog) {
          Future<void> reload() async {
            try {
              items = await widget.api.transactionAttachments(
                widget.accessToken!,
                widget.workspace.id,
                tx.id,
              );
              error = null;
            } catch (e) {
              error = e.toString().replaceFirst('Exception: ', '');
            } finally {
              loading = false;
              if (dialogContext.mounted) setDialog(() {});
            }
          }

          Future<void> add() async {
            final picked = await FilePicker.platform.pickFiles(
              type: FileType.custom,
              allowedExtensions: ['jpg', 'jpeg', 'png', 'pdf'],
              withData: true,
            );
            if (picked == null) return;
            final f = picked.files.single;
            if (f.bytes == null) return;
            final ext = (f.extension ?? '').toLowerCase();
            final ct = ext == 'pdf'
                ? 'application/pdf'
                : ext == 'png'
                ? 'image/png'
                : 'image/jpeg';
            setDialog(() => loading = true);
            try {
              await widget.api.uploadTransactionAttachment(
                widget.accessToken!,
                widget.workspace.id,
                tx.id,
                f.name,
                f.bytes!,
                ct,
              );
              await reload();
            } catch (e) {
              error = e.toString();
              loading = false;
              if (dialogContext.mounted) setDialog(() {});
            }
          }

          Future<void> view(Map<String, dynamic> a) async {
            try {
              final bytes = await widget.api.downloadTransactionAttachment(
                widget.accessToken!,
                widget.workspace.id,
                tx.id,
                a['id'] as int,
              );
              final name = a['name']?.toString() ?? 'comprovante';
              final contentType =
                  a['content_type']?.toString() ?? 'application/octet-stream';
              final opened = await viewBytesInBrowser(bytes, name, contentType);
              if (!opened && mounted)
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(
                    content: Text(
                      'O navegador bloqueou a visualização. Permita pop-ups para este site.',
                    ),
                  ),
                );
            } catch (e) {
              if (mounted)
                ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(
                    content: Text(e.toString().replaceFirst('Exception: ', '')),
                  ),
                );
            }
          }

          Future<void> save(Map<String, dynamic> a) async {
            try {
              final bytes = await widget.api.downloadTransactionAttachment(
                widget.accessToken!,
                widget.workspace.id,
                tx.id,
                a['id'] as int,
              );
              final name = a['name']?.toString() ?? 'comprovante';
              final contentType =
                  a['content_type']?.toString() ?? 'application/octet-stream';
              final savedInBrowser = await saveBytesInBrowser(
                bytes,
                name,
                contentType,
              );
              if (!savedInBrowser) {
                await FilePicker.platform.saveFile(
                  dialogTitle: 'Salvar comprovante',
                  fileName: name,
                  bytes: bytes,
                );
              }
            } catch (e) {
              if (mounted)
                ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(
                    content: Text(e.toString().replaceFirst('Exception: ', '')),
                  ),
                );
            }
          }

          Future<void> share(Map<String, dynamic> a) async {
            try {
              final bytes = await widget.api.downloadTransactionAttachment(
                widget.accessToken!,
                widget.workspace.id,
                tx.id,
                a['id'] as int,
              );
              final name = a['name']?.toString() ?? 'comprovante';
              final contentType =
                  a['content_type']?.toString() ?? 'application/octet-stream';
              final shared = await shareBytesInBrowser(
                bytes,
                name,
                contentType,
              );
              if (!shared && mounted)
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(
                    content: Text(
                      'Compartilhamento de arquivos não é suportado por este navegador. Você ainda pode usar Baixar.',
                    ),
                  ),
                );
            } catch (e) {
              if (mounted)
                ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(
                    content: Text(e.toString().replaceFirst('Exception: ', '')),
                  ),
                );
            }
          }

          Future<void> remove(Map<String, dynamic> a) async {
            setDialog(() => loading = true);
            try {
              await widget.api.deleteTransactionAttachment(
                widget.accessToken!,
                widget.workspace.id,
                tx.id,
                a['id'] as int,
              );
              await reload();
            } catch (e) {
              error = e.toString();
              loading = false;
              if (dialogContext.mounted) setDialog(() {});
            }
          }

          if (loading && items.isEmpty && error == null)
            Future.microtask(reload);
          return AlertDialog(
            title: Text('Comprovantes • ${tx.description}'),
            content: SizedBox(
              width: 520,
              child: loading && items.isEmpty
                  ? const Center(
                      child: Padding(
                        padding: EdgeInsets.all(24),
                        child: CircularProgressIndicator(),
                      ),
                    )
                  : Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        if (error != null)
                          Padding(
                            padding: const EdgeInsets.only(bottom: 8),
                            child: Text(
                              error!,
                              style: TextStyle(
                                color: Theme.of(context).colorScheme.error,
                              ),
                            ),
                          ),
                        if (items.isEmpty)
                          const Padding(
                            padding: EdgeInsets.all(16),
                            child: Text('Nenhum comprovante anexado.'),
                          )
                        else
                          Flexible(
                            child: ListView(
                              shrinkWrap: true,
                              children: items
                                  .map(
                                    (a) => ListTile(
                                      leading: Icon(
                                        (a['content_type'] ?? '').toString() ==
                                                'application/pdf'
                                            ? Icons.picture_as_pdf
                                            : Icons.image_outlined,
                                      ),
                                      title: Text(
                                        a['name']?.toString() ?? 'Comprovante',
                                      ),
                                      subtitle: Text(
                                        '${((a['size_bytes'] as num?) ?? 0) / 1024 < 1024 ? (((a['size_bytes'] as num?) ?? 0) / 1024).toStringAsFixed(0) + ' KB' : (((a['size_bytes'] as num?) ?? 0) / 1048576).toStringAsFixed(1) + ' MB'}',
                                      ),
                                      trailing: Wrap(
                                        children: [
                                          IconButton(
                                            tooltip: 'Visualizar',
                                            onPressed: () => view(a),
                                            icon: const Icon(
                                              Icons.visibility_outlined,
                                            ),
                                          ),
                                          IconButton(
                                            tooltip: 'Baixar',
                                            onPressed: () => save(a),
                                            icon: const Icon(Icons.download),
                                          ),
                                          IconButton(
                                            tooltip: 'Compartilhar',
                                            onPressed: () => share(a),
                                            icon: const Icon(
                                              Icons.share_outlined,
                                            ),
                                          ),
                                          IconButton(
                                            tooltip: 'Excluir',
                                            onPressed: () => remove(a),
                                            icon: const Icon(
                                              Icons.delete_outline,
                                            ),
                                          ),
                                        ],
                                      ),
                                    ),
                                  )
                                  .toList(),
                            ),
                          ),
                      ],
                    ),
            ),
            actions: [
              TextButton.icon(
                onPressed: loading ? null : add,
                icon: const Icon(Icons.attach_file),
                label: const Text('Adicionar comprovante'),
              ),
              TextButton(
                onPressed: () => Navigator.pop(dialogContext),
                child: const Text('Fechar'),
              ),
            ],
          );
        },
      ),
    );
  }

  Future<void> _createWorkspaceDialog() async {
    final name = TextEditingController();
    var kind = 'personal';
    var saving = false;
    String? error;
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, setDialog) => AlertDialog(
          title: const Text('Criar workspace'),
          content: SizedBox(
            width: 420,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextField(
                  controller: name,
                  enabled: !saving,
                  autofocus: true,
                  decoration: const InputDecoration(
                    labelText: 'Nome',
                    hintText: 'Ex.: Empresa, Família',
                  ),
                ),
                const SizedBox(height: 12),
                DropdownButtonFormField<String>(
                  initialValue: kind,
                  decoration: const InputDecoration(labelText: 'Tipo'),
                  items: const [
                    DropdownMenuItem(value: 'personal', child: Text('Pessoal')),
                    DropdownMenuItem(value: 'business', child: Text('Empresa')),
                    DropdownMenuItem(value: 'family', child: Text('Família')),
                    DropdownMenuItem(value: 'other', child: Text('Outro')),
                  ],
                  onChanged: saving
                      ? null
                      : (v) => setDialog(() => kind = v ?? 'personal'),
                ),
                if (error != null) ...[
                  const SizedBox(height: 8),
                  Text(
                    error!,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                ],
                if (saving) ...[
                  const SizedBox(height: 12),
                  const LinearProgressIndicator(),
                ],
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: saving ? null : () => Navigator.pop(dialogContext),
              child: const Text('Cancelar'),
            ),
            FilledButton(
              onPressed: saving
                  ? null
                  : () async {
                      final clean = name.text.trim();
                      if (clean.isEmpty) {
                        setDialog(() => error = 'Informe o nome do workspace.');
                        return;
                      }
                      setDialog(() {
                        saving = true;
                        error = null;
                      });
                      try {
                        await widget.onCreateWorkspace(clean, kind);
                        if (dialogContext.mounted) Navigator.pop(dialogContext);
                      } catch (e) {
                        if (dialogContext.mounted)
                          setDialog(() {
                            saving = false;
                            error = e.toString().replaceFirst(
                              'Exception: ',
                              '',
                            );
                          });
                      }
                    },
              child: const Text('Criar e abrir'),
            ),
          ],
        ),
      ),
    );
  }

  Future<String?> _workspaceDeleteCode() async {
    final controllers = List.generate(4, (_) => TextEditingController());
    final nodes = List.generate(4, (_) => FocusNode());
    final result = await showDialog<String>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('Código de confirmação'),
        content: SizedBox(
          width: 420,
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: List.generate(
              4,
              (i) => SizedBox(
                width: 48,
                child: TextField(
                  controller: controllers[i],
                  focusNode: nodes[i],
                  autofocus: i == 0,
                  textAlign: TextAlign.center,
                  keyboardType: TextInputType.number,
                  inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                  decoration: const InputDecoration(
                    counterText: '',
                    border: OutlineInputBorder(),
                  ),
                  onChanged: (v) {
                    final d = v.replaceAll(RegExp(r'\D'), '');
                    if (d.length > 1) {
                      final limit = d.length > 4 ? 4 : d.length;
                      final pasted = d.substring(0, limit);
                      for (var j = 0; j < 4; j++) {
                        controllers[j].text = j < pasted.length
                            ? pasted[j]
                            : '';
                      }
                      nodes[pasted.isEmpty
                              ? 0
                              : (pasted.length > 4 ? 3 : pasted.length - 1)]
                          .requestFocus();
                    } else if (d.isNotEmpty) {
                      controllers[i].text = d[0];
                      if (i < 3) nodes[i + 1].requestFocus();
                    } else if (i > 0) {
                      nodes[i - 1].requestFocus();
                    }
                  },
                ),
              ),
            ),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(c),
            child: const Text('Cancelar'),
          ),
          FilledButton(
            onPressed: () =>
                Navigator.pop(c, controllers.map((e) => e.text).join()),
            child: const Text('Confirmar exclusão'),
          ),
        ],
      ),
    );
    for (final x in controllers) {
      x.dispose();
    }
    for (final x in nodes) {
      x.dispose();
    }
    return result?.length == 4 ? result : null;
  }

  Future<void> _workspaceManagerDialog() async {
    if (widget.accessToken == null) return;
    var archived = false;
    final selected = <String>{};
    var busy = false;
    String? error;
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, setDialog) {
          final visible = widget.workspaces
              .where((w) => w.active != archived)
              .toList();
          return AlertDialog(
            title: const Text('Gerenciar workspaces'),
            content: SizedBox(
              width: 620,
              height: 460,
              child: Column(
                children: [
                  Row(
                    children: [
                      FilterChip(
                        selected: !archived,
                        onSelected: busy
                            ? null
                            : (_) => setDialog(() {
                                archived = false;
                                selected.clear();
                              }),
                        label: const Text('Ativos'),
                      ),
                      const SizedBox(width: 8),
                      FilterChip(
                        selected: archived,
                        onSelected: busy
                            ? null
                            : (_) => setDialog(() {
                                archived = true;
                                selected.clear();
                              }),
                        label: const Text('Arquivados'),
                      ),
                      const Spacer(),
                      IconButton(
                        tooltip: 'Criar workspace',
                        onPressed: busy
                            ? null
                            : () async {
                                Navigator.pop(dialogContext);
                                await _createWorkspaceDialog();
                              },
                        icon: const Icon(Icons.add),
                      ),
                    ],
                  ),
                  if (error != null)
                    Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: Text(
                        error!,
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.error,
                        ),
                      ),
                    ),
                  if (busy) const LinearProgressIndicator(),
                  Expanded(
                    child: visible.isEmpty
                        ? Center(
                            child: Text(
                              archived
                                  ? 'Nenhum workspace arquivado.'
                                  : 'Nenhum workspace ativo.',
                            ),
                          )
                        : ListView.builder(
                            itemCount: visible.length,
                            itemBuilder: (_, i) {
                              final w = visible[i];
                              return Card(
                                margin: const EdgeInsets.only(bottom: 14),
                                elevation: 1.5,
                                shadowColor: Colors.black.withOpacity(.10),
                                surfaceTintColor: Colors.transparent,
                                child: Padding(
                                  padding: const EdgeInsets.symmetric(vertical: 4),
                                  child: ListTile(
                                  leading: archived
                                      ? Checkbox(
                                          value: selected.contains(w.id),
                                          onChanged: busy
                                              ? null
                                              : (v) => setDialog(
                                                  () => v == true
                                                      ? selected.add(w.id)
                                                      : selected.remove(w.id),
                                                ),
                                        )
                                      : null,
                                  title: Text(w.name),
                                  subtitle: Text(w.kind),
                                  trailing: archived
                                      ? Wrap(
                                          children: [
                                            IconButton(
                                              tooltip: 'Restaurar',
                                              onPressed: busy
                                                  ? null
                                                  : () async {
                                                      setDialog(
                                                        () => busy = true,
                                                      );
                                                      try {
                                                        await widget.api
                                                            .restoreWorkspace(
                                                              widget
                                                                  .accessToken!,
                                                              w.id,
                                                            );
                                                        await widget
                                                            .onReloadWorkspaces();
                                                        if (dialogContext
                                                            .mounted)
                                                          setDialog(
                                                            () => busy = false,
                                                          );
                                                      } catch (e) {
                                                        if (dialogContext
                                                            .mounted)
                                                          setDialog(() {
                                                            busy = false;
                                                            error = e
                                                                .toString()
                                                                .replaceFirst(
                                                                  'Exception: ',
                                                                  '',
                                                                );
                                                          });
                                                      }
                                                    },
                                              icon: const Icon(
                                                Icons.unarchive_outlined,
                                              ),
                                            ),
                                          ],
                                        )
                                      : Wrap(
                                          spacing: 4,
                                          children: [
                                            if (w.id == widget.workspace.id)
                                              const Padding(
                                                padding: EdgeInsets.symmetric(
                                                  horizontal: 8,
                                                  vertical: 12,
                                                ),
                                                child: Text(
                                                  'Atual',
                                                  style: TextStyle(
                                                    fontWeight: FontWeight.w800,
                                                    color: Color(0xFF246BFD),
                                                  ),
                                                ),
                                              )
                                            else
                                              TextButton(
                                                onPressed: busy
                                                    ? null
                                                    : () {
                                                        widget.onWorkspace(w);
                                                        Navigator.pop(
                                                          dialogContext,
                                                        );
                                                      },
                                                child: const Text('Selecionar'),
                                              ),
                                            IconButton(
                                              tooltip: 'Arquivar',
                                              onPressed: busy
                                                  ? null
                                                  : () async {
                                                      setDialog(
                                                        () => busy = true,
                                                      );
                                                      try {
                                                        await widget.api
                                                            .archiveWorkspace(
                                                              widget
                                                                  .accessToken!,
                                                              w.id,
                                                            );
                                                        await widget
                                                            .onReloadWorkspaces();
                                                        if (dialogContext
                                                            .mounted)
                                                          setDialog(
                                                            () => busy = false,
                                                          );
                                                      } catch (e) {
                                                        if (dialogContext
                                                            .mounted)
                                                          setDialog(() {
                                                            busy = false;
                                                            error = e
                                                                .toString()
                                                                .replaceFirst(
                                                                  'Exception: ',
                                                                  '',
                                                                );
                                                          });
                                                      }
                                                    },
                                              icon: const Icon(
                                                Icons.archive_outlined,
                                              ),
                                            ),
                                          ],
                                        ),
                                  ),
                                ),
                              );
                            },
                          ),
                  ),
                  if (archived && selected.isNotEmpty)
                    SizedBox(
                      width: double.infinity,
                      child: FilledButton.icon(
                        style: FilledButton.styleFrom(
                          backgroundColor: Theme.of(context).colorScheme.error,
                        ),
                        onPressed: busy
                            ? null
                            : () async {
                                setDialog(() => busy = true);
                                try {
                                  final ids = selected.toList();
                                  await widget.api.requestWorkspaceBatchDelete(
                                    widget.accessToken!,
                                    ids,
                                  );
                                  if (!mounted) return;
                                  final code = await _workspaceDeleteCode();
                                  if (code == null) {
                                    if (dialogContext.mounted)
                                      setDialog(() => busy = false);
                                    return;
                                  }
                                  await widget.api.confirmWorkspaceBatchDelete(
                                    widget.accessToken!,
                                    ids,
                                    code,
                                  );
                                  await widget.onReloadWorkspaces();
                                  selected.clear();
                                  if (dialogContext.mounted)
                                    setDialog(() => busy = false);
                                } catch (e) {
                                  if (dialogContext.mounted)
                                    setDialog(() {
                                      busy = false;
                                      error = e.toString().replaceFirst(
                                        'Exception: ',
                                        '',
                                      );
                                    });
                                }
                              },
                        icon: const Icon(Icons.delete_forever),
                        label: Text(
                          'Excluir selecionados (${selected.length})',
                        ),
                      ),
                    ),
                ],
              ),
            ),
            actions: [
              TextButton(
                onPressed: busy ? null : () => Navigator.pop(dialogContext),
                child: const Text('Fechar'),
              ),
            ],
          );
        },
      ),
    );
  }

  Widget _workspaceHeaderBadge({bool compact = false}) => InkWell(
    onTap: _workspaceManagerDialog,
    borderRadius: BorderRadius.circular(99),
    child: Container(
      constraints: BoxConstraints(maxWidth: compact ? 138 : 190),
      padding: EdgeInsets.symmetric(
        horizontal: compact ? 8 : 10,
        vertical: compact ? 4 : 5,
      ),
      decoration: BoxDecoration(
        color: const Color(0xFFEAF2FF),
        borderRadius: BorderRadius.circular(99),
        border: Border.all(color: const Color(0xFFD8E6FF)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            Icons.workspaces_rounded,
            size: compact ? 13 : 15,
            color: const Color(0xFF246BFD),
          ),
          const SizedBox(width: 5),
          Flexible(
            child: Text(
              widget.workspace.name,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: compact ? 10.5 : 11.5,
                fontWeight: FontWeight.w800,
                color: const Color(0xFF234C88),
              ),
            ),
          ),
          const SizedBox(width: 3),
          Icon(
            Icons.expand_more_rounded,
            size: compact ? 13 : 15,
            color: const Color(0xFF246BFD),
          ),
        ],
      ),
    ),
  );

  Widget _workspace() => widget.isGuest
      ? const SizedBox.shrink()
      : Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: DropdownButtonFormField<String>(
                initialValue: widget.workspace.id,
                decoration: const InputDecoration(
                  labelText: 'Workspace',
                  border: OutlineInputBorder(),
                ),
                items: widget.workspaces
                    .where((w) => w.active)
                    .map(
                      (w) => DropdownMenuItem(value: w.id, child: Text(w.name)),
                    )
                    .toList(),
                onChanged: (id) {
                  if (id != null)
                    widget.onWorkspace(
                      widget.workspaces.firstWhere((w) => w.id == id),
                    );
                },
              ),
            ),
            const SizedBox(width: 8),
            IconButton.filledTonal(
              tooltip: 'Gerenciar workspaces',
              onPressed: _workspaceManagerDialog,
              icon: const Icon(Icons.workspaces_outline),
            ),
          ],
        );
  Widget _dashboard() {
    final now = DateTime.now();
    DateTime? parseDate(String raw) {
      try {
        return DateTime.parse(raw.substring(0, 10));
      } catch (_) {
        return null;
      }
    }

    final accountTx = widget.snapshot.transactions.where(
      (t) => !_isNonCashPlanned(t) && (!t.isCard || t.source == 'card_payment'),
    );
    // O saldo consolidado deve usar o valor assinado vindo do snapshot do servidor.
    // Assim Web e Android usam a mesma fonte de verdade mesmo se algum registro antigo
    // tiver o campo de tipo divergente do sinal do valor.
    final income = accountTx
        .where((t) => t.amount > 0)
        .fold<double>(0, (a, t) => a + t.amount);
    final expense = accountTx
        .where((t) => t.amount < 0)
        .fold<double>(0, (a, t) => a + t.amount.abs());
    final consolidatedBalance = accountTx.fold<double>(
      0,
      (a, t) => a + t.amount,
    );
    final recent =
        widget.snapshot.transactions
            .where((t) => !_isNonCashPlanned(t))
            .toList()
          ..sort(
            (a, b) => (parseDate(b.date) ?? DateTime(1900)).compareTo(
              parseDate(a.date) ?? DateTime(1900),
            ),
          );
    final monthExpenses = widget.snapshot.transactions.where((t) {
      final d = parseDate(t.date);
      return !_isNonCashPlanned(t) &&
          d != null &&
          d.year == now.year &&
          d.month == now.month &&
          t.type != 'income' &&
          (!t.isCard || t.source == 'card_payment');
    }).toList();
    FinancialTransaction? biggest;
    for (final t in monthExpenses) {
      if (biggest == null || t.amount.abs() > biggest.amount.abs()) biggest = t;
    }
    double spentFor(int categoryId) => monthExpenses
        .where((t) => t.categoryId == categoryId)
        .fold(0, (a, t) => a + t.amount.abs());
    final cards = widget.snapshot.cards.where((c) => c.active).toList();
    DateTime invoiceDueDate(int day) {
      var year = now.year, month = now.month;
      int daysInMonth(int y, int m) => DateTime(y, m + 1, 0).day;
      var due = DateTime(
        year,
        month,
        day.clamp(1, daysInMonth(year, month)).toInt(),
      );
      final today = DateTime(now.year, now.month, now.day);
      if (due.isBefore(today)) {
        month++;
        if (month == 13) {
          month = 1;
          year++;
        }
        due = DateTime(
          year,
          month,
          day.clamp(1, daysInMonth(year, month)).toInt(),
        );
      }
      return due;
    }

    final invoiceAlerts = <(CreditCardInfo, int, DateTime)>[];
    for (final c in cards) {
      if (c.dueDay == null) continue;
      DateTime closeFor(DateTime month) {
        final last = DateTime(month.year, month.month + 1, 0).day;
        return DateTime(month.year, month.month, (c.closingDay ?? last).clamp(1, last).toInt());
      }
      DateTime endFor(DateTime date) {
        final close = closeFor(DateTime(date.year, date.month));
        if (!date.isAfter(close)) return close;
        return closeFor(DateTime(date.year, date.month + 1));
      }
      final grouped = <String, List<FinancialTransaction>>{};
      for (final t in widget.snapshot.transactions.where((t) => t.cardId == c.id && t.source != 'card_payment')) {
        final d = parseDate(t.date);
        if (d == null) continue;
        final end = endFor(d);
        final key = '${end.year}-${end.month.toString().padLeft(2, '0')}-${end.day.toString().padLeft(2, '0')}';
        grouped.putIfAbsent(key, () => <FinancialTransaction>[]).add(t);
      }
      for (final entry in grouped.entries) {
        final end = DateTime.parse(entry.key);
        final total = (-entry.value.fold<double>(0, (sum, t) => sum + t.amount)).clamp(0, double.infinity).toDouble();
        if (total <= 0.005) continue;
        final paid = widget.snapshot.transactions
            .where((t) => t.cardId == c.id && t.source == 'card_payment' && t.purchaseDate?.startsWith(entry.key) == true)
            .fold<double>(0, (sum, t) => sum + t.amount.abs());
        if (total - paid <= 0.005) continue;
        var dueMonth = DateTime(end.year, end.month);
        if (c.dueDay! <= end.day) dueMonth = DateTime(end.year, end.month + 1);
        final last = DateTime(dueMonth.year, dueMonth.month + 1, 0).day;
        final due = DateTime(dueMonth.year, dueMonth.month, c.dueDay!.clamp(1, last).toInt());
        final days = due.difference(DateTime(now.year, now.month, now.day)).inDays;
        if (days >= 0 && days <= 5) invoiceAlerts.add((c, days, due));
      }
    }
    invoiceAlerts.sort((a, b) => a.$2.compareTo(b.$2));
    final gap = customization.compactDashboard ? 10.0 : 20.0;
    return ListView(
      padding: EdgeInsets.fromLTRB(gap, gap, gap, 32),
      children: [
        if (!widget.isGuest)
          Text(
            '${const ['Janeiro', 'Fevereiro', 'Março', 'Abril', 'Maio', 'Junho', 'Julho', 'Agosto', 'Setembro', 'Outubro', 'Novembro', 'Dezembro'][now.month - 1]} de ${now.year}',
            style: const TextStyle(
              fontSize: 20,
              fontWeight: FontWeight.w800,
              color: Color(0xFF263B5E),
            ),
          ),
        if (widget.isGuest)
          const Card(
            child: ListTile(
              leading: Icon(Icons.phone_android),
              title: Text('Modo sem cadastro'),
              subtitle: Text(
                'Seus dados ficam salvos localmente neste dispositivo/navegador.',
              ),
            ),
          ),
        const SizedBox(height: 8),
        LayoutBuilder(
          builder: (context, constraints) {
            final wide = constraints.maxWidth >= 760;
            final hidden = 'R\$ ••••••';
            final balance = customization.showCurrentBalance
                ? _metric(
                    'Saldo consolidado',
                    _showFinancialValues ? money(consolidatedBalance) : hidden,
                  )
                : null;
            final incomeCard = customization.showIncome
                ? _metric(
                    'Entradas',
                    _showFinancialValues ? money(income) : hidden,
                  )
                : null;
            final expenseCard = customization.showExpenses
                ? _metric(
                    'Saídas',
                    _showFinancialValues ? money(expense) : hidden,
                  )
                : null;
            final items = wide
                ? [incomeCard, balance, expenseCard]
                : [balance, incomeCard, expenseCard];
            final visible = items.whereType<Widget>().toList();
            if (visible.isEmpty) return const SizedBox.shrink();
            if (wide)
              return Row(
                children: [
                  for (var i = 0; i < visible.length; i++) ...[
                    if (i > 0) const SizedBox(width: 18),
                    Expanded(child: visible[i]),
                  ],
                ],
              );
            if (visible.length == 3)
              return Column(
                children: [
                  SizedBox(width: double.infinity, child: visible[0]),
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      Expanded(child: visible[1]),
                      const SizedBox(width: 12),
                      Expanded(child: visible[2]),
                    ],
                  ),
                ],
              );
            return Column(
              children: [
                for (var i = 0; i < visible.length; i++) ...[
                  if (i > 0) const SizedBox(height: 12),
                  SizedBox(width: double.infinity, child: visible[i]),
                ],
              ],
            );
          },
        ),
        Builder(
          builder: (context) {
            final today = DateTime(now.year, now.month, now.day);
            final due =
                widget.snapshot.transactions
                    .map((t) {
                      final m = _decodePayable(t.description);
                      final d = parseDate(t.date);
                      if (m == null || d == null) return null;
                      final days = DateTime(
                        d.year,
                        d.month,
                        d.day,
                      ).difference(today).inDays;
                      return days <= m.reminderDays ? (t, m, days, d) : null;
                    })
                    .whereType<
                      (FinancialTransaction, _PayableMeta, int, DateTime)
                    >()
                    .toList()
                  ..sort((a, b) => a.$3.compareTo(b.$3));
            if (due.isEmpty) return const SizedBox.shrink();
            final first = due.first;
            final overdue = first.$3 < 0;
            final tone = overdue
                ? const Color(0xFFC62828)
                : const Color(0xFFE58A00);
            return Padding(
              padding: const EdgeInsets.only(top: 26),
              child: InkWell(
                onTap: _payablesDialog,
                borderRadius: BorderRadius.circular(16),
                child: Container(
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(
                    color: tone.withOpacity(.09),
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(color: tone.withOpacity(.22)),
                  ),
                  child: Row(
                    children: [
                      Icon(Icons.notifications_active_outlined, color: tone),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              overdue
                                  ? 'Conta vencida'
                                  : 'Conta próxima do vencimento',
                              style: TextStyle(
                                fontWeight: FontWeight.w800,
                                color: tone,
                              ),
                            ),
                            Text(
                              overdue
                                  ? '${first.$2.description} venceu há ${-first.$3} dia(s) • ${money(first.$2.amount)}'
                                  : '${first.$2.description} vence em ${first.$3} dia(s) • ${money(first.$2.amount)}',
                              style: const TextStyle(fontSize: 12),
                            ),
                            if (due.length > 1)
                              Text(
                                '+ ${due.length - 1} outra(s) conta(s)',
                                style: const TextStyle(
                                  fontSize: 11,
                                  color: Color(0xFF697386),
                                ),
                              ),
                          ],
                        ),
                      ),
                      const Icon(Icons.chevron_right_rounded),
                    ],
                  ),
                ),
              ),
            );
          },
        ),
        if (invoiceAlerts.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 26),
            child: InkWell(
              onTap: () => Navigator.of(context).push(
                MaterialPageRoute(
                  builder: (_) => FinanceToolsScreen(
                    snapshot: widget.snapshot,
                    isGuest: widget.isGuest,
                    onSaveTransaction: widget.onSaveTransaction,
                    onAskAi: widget.onAskAi,
                    initialIndex: 2,
                    initialCardId: invoiceAlerts.first.$1.id,
                  ),
                ),
              ),
              borderRadius: BorderRadius.circular(16),
              child: Container(
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  color: const Color(0xFF1479F8).withOpacity(.08),
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(
                    color: const Color(0xFF1479F8).withOpacity(.20),
                  ),
                ),
                child: Row(
                  children: [
                    const Icon(
                      Icons.credit_card_rounded,
                      color: Color(0xFF1479F8),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text(
                            'Fatura próxima do vencimento',
                            style: TextStyle(
                              fontWeight: FontWeight.w800,
                              color: Color(0xFF1479F8),
                            ),
                          ),
                          Text(
                            invoiceAlerts.first.$2 == 0
                                ? '${invoiceAlerts.first.$1.nickname?.isNotEmpty == true ? invoiceAlerts.first.$1.nickname! : invoiceAlerts.first.$1.bankName} vence hoje'
                                : '${invoiceAlerts.first.$1.nickname?.isNotEmpty == true ? invoiceAlerts.first.$1.nickname! : invoiceAlerts.first.$1.bankName} vence em ${invoiceAlerts.first.$2} dia(s)',
                            style: const TextStyle(fontSize: 12),
                          ),
                          if (invoiceAlerts.length > 1)
                            Text(
                              '+ ${invoiceAlerts.length - 1} outra(s) fatura(s)',
                              style: const TextStyle(
                                fontSize: 11,
                                color: Color(0xFF697386),
                              ),
                            ),
                        ],
                      ),
                    ),
                    const Icon(Icons.chevron_right_rounded),
                  ],
                ),
              ),
            ),
          ),
        if (customization.showQuickActions) ...[
          const SizedBox(height: 18),
          _sectionHeading('Ações rápidas'),
          const SizedBox(height: 10),
          LayoutBuilder(
            builder: (context, constraints) {
              final actions = <Widget>[
                _quickAction(
                  Icons.add_rounded,
                  'Nova transação',
                  'Manual ou leitura de código',
                  const Color(0xFF1479F8),
                  _expenseAddMenu,
                ),
                _quickAction(
                  Icons.receipt_long_outlined,
                  'Transações',
                  'Ver movimentações',
                  const Color(0xFFFF7A2F),
                  () => _goTo('transactions'),
                ),
                _quickAction(
                  Icons.account_balance_wallet_outlined,
                  'Bancos e cartões',
                  'Bancos e cartões',
                  const Color(0xFF00A67A),
                  () => _goTo('accounts'),
                ),
                _quickAction(
                  Icons.auto_graph_rounded,
                  'Planejamento',
                  'Planeje seu futuro',
                  const Color(0xFF7A4AF4),
                  () => Navigator.of(context).push(
                    MaterialPageRoute(
                      builder: (_) => PlanningScreen(
                        snapshot: widget.snapshot,
                        onSaveSnapshot: widget.onSaveSnapshot,
                        workspaceId: widget.workspace.id,
                        onSaveTransaction: widget.onSaveTransaction,
                        api: widget.api,
                        accessToken: widget.accessToken,
                      ),
                    ),
                  ),
                ),
                _quickAction(
                  Icons.bar_chart_rounded,
                  'Resumo',
                  'Análise completa',
                  const Color(0xFF4F62D8),
                  () => Navigator.of(context).push(
                    MaterialPageRoute(
                      builder: (_) => SummaryScreen(
                        snapshot: widget.snapshot.copyWith(
                          transactions: widget.snapshot.transactions
                              .where((t) => !_isNonCashPlanned(t))
                              .toList(),
                        ),
                      ),
                    ),
                  ),
                ),
                _quickAction(
                  Icons.event_note_rounded,
                  'Central financeira',
                  'Faturas e contas a pagar',
                  const Color(0xFF0B6E75),
                  _financeCentersMenu,
                ),
              ];
              if (constraints.maxWidth >= 900) {
                return Row(
                  children: [
                    for (var i = 0; i < actions.length; i++) ...[
                      if (i > 0) const SizedBox(width: 12),
                      Expanded(child: actions[i]),
                    ],
                  ],
                );
              }
              final columns = constraints.maxWidth >= 620 ? 3 : 3;
              final itemWidth =
                  (constraints.maxWidth - (12 * (columns - 1))) / columns;
              return Wrap(
                alignment: WrapAlignment.start,
                spacing: 12,
                runSpacing: 12,
                children: actions
                    .map((w) => SizedBox(width: itemWidth, child: w))
                    .toList(),
              );
            },
          ),
        ],
        if (customization.showRecentTransactions) ...[
          const SizedBox(height: 18),
          Row(
            children: [
              Expanded(child: _sectionHeading('Transações recentes')),
              TextButton(
                onPressed: () => _goTo('transactions'),
                child: const Text('Ver todas'),
              ),
            ],
          ),
          if (recent.isEmpty)
            const Card(child: ListTile(title: Text('Nenhuma transação ainda.')))
          else
            ...recent.take(5).map((t) {
              final d = parseDate(t.date);
              final dateLabel = d == null
                  ? t.date
                  : '${d.day.toString().padLeft(2, '0')}/${d.month.toString().padLeft(2, '0')}/${d.year}';
              final timeLabel = t.isCard && d != null
                  ? ' - ${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}'
                  : '';
              final debtCreditor = isPlannedDebtTransaction(t)
                  ? debtCreditorFromTransactionDescription(t.description)
                  : null;
              final account =
                  debtCreditor ??
                  widget.snapshot.accounts
                      .where((a) => a.id == t.accountId)
                      .map((a) => a.institutionName)
                      .firstOrNull ??
                  (t.isCard
                      ? widget.snapshot.cards
                            .where((c) => c.id == t.cardId)
                            .map((c) => c.bankName)
                            .firstOrNull
                      : null) ??
                  'Sem banco';
              final categoryObj = widget.snapshot.categories.where((c) => c.id == t.categoryId).firstOrNull;
              final category = categoryObj?.name ?? 'Sem categoria';
              final typeLabel = t.type == 'income' ? 'Entrada' : 'Saída';
              final sourceLabel = t.source.toLowerCase().contains('open')
                  ? 'Open Finance'
                  : 'Manual';
              return Card(
                margin: const EdgeInsets.only(bottom: 10),
                elevation: 2.5,
                shadowColor: Colors.black.withOpacity(.16),
                surfaceTintColor: Colors.transparent,
                color: Theme.of(context).colorScheme.surface,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(16),
                  side: BorderSide(
                    color: Theme.of(context).colorScheme.outlineVariant.withOpacity(.55),
                  ),
                ),
                child: ListTile(
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                  leading: Icon(
                    t.isCard
                        ? Icons.credit_card
                        : t.type == 'income'
                        ? Icons.arrow_downward
                        : Icons.arrow_upward,
                  ),
                  title: Text(t.description),
                  subtitle: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const SizedBox(height: 4),
                      Wrap(
                        crossAxisAlignment: WrapCrossAlignment.center,
                        spacing: 8,
                        runSpacing: 4,
                        children: [
                          BankBadge(account),
                          Row(mainAxisSize: MainAxisSize.min, children: [
                            Icon(categoryIconData(categoryObj?.icon, category), size: 15, color: Theme.of(context).colorScheme.onSurfaceVariant),
                            const SizedBox(width: 4),
                            Text(category, style: Theme.of(context).textTheme.bodySmall),
                          ]),
                        ],
                      ),
                      const SizedBox(height: 4),
                      Text('$dateLabel$timeLabel - $typeLabel - $sourceLabel'),
                    ],
                  ),
                  isThreeLine: true,
                  trailing: Text(
                    '${t.type == 'income' ? '' : '-'}${money(t.amount.abs())}',
                    style: TextStyle(
                      color: t.type == 'income' ? Colors.green : Colors.red,
                      fontWeight: FontWeight.w800,
                      fontSize: 19,
                    ),
                  ),
                ),
              );
            }),
        ],
        if (customization.showMonthlyBudgets) const SizedBox(height: 12),
        if (customization.showMonthlyBudgets)
          Row(
            children: [
              Expanded(child: _sectionHeading('Orçamentos mensais')),
              TextButton(
                onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute(
                    builder: (_) => PlanningScreen(
                      snapshot: widget.snapshot,
                      onSaveSnapshot: widget.onSaveSnapshot,
                      workspaceId: widget.workspace.id,
                      onSaveTransaction: widget.onSaveTransaction,
                      api: widget.api,
                      accessToken: widget.accessToken,
                    ),
                  ),
                ),
                child: const Text('Planejamento'),
              ),
            ],
          ),
        if (customization.showMonthlyBudgets && widget.snapshot.budgets.isEmpty)
          const Card(
            child: ListTile(
              leading: Icon(Icons.pie_chart_outline),
              title: Text('Nenhum orçamento mensal definido.'),
            ),
          )
        else if (customization.showMonthlyBudgets)
          ...widget.snapshot.budgets.take(4).map((b) {
            final cat =
                (widget.snapshot.categories
                    .where((c) => c.id == b.categoryId)
                    .isEmpty
                ? null
                : widget.snapshot.categories
                      .where((c) => c.id == b.categoryId)
                      .first);
            final spent = spentFor(b.categoryId);
            return Card(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      cat?.name ?? 'Categoria',
                      style: Theme.of(context).textTheme.titleSmall,
                    ),
                    Text(
                      '${money(spent)} de ${money(b.amount)}',
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 6),
                    LinearProgressIndicator(
                      value: b.amount <= 0 ? 0 : (spent / b.amount).clamp(0, 1),
                    ),
                  ],
                ),
              ),
            );
          }),
        const SizedBox(height: 12),
        Row(
          children: [
            Expanded(child: _sectionHeading('Metas financeiras')),
            TextButton(
              onPressed: () => Navigator.of(context).push(
                MaterialPageRoute(
                  builder: (_) => PlanningScreen(
                    snapshot: widget.snapshot,
                    onSaveSnapshot: widget.onSaveSnapshot,
                    workspaceId: widget.workspace.id,
                    onSaveTransaction: widget.onSaveTransaction,
                    api: widget.api,
                    accessToken: widget.accessToken,
                  ),
                ),
              ),
              child: const Text('Ver metas'),
            ),
          ],
        ),
        if (widget.snapshot.goals.isEmpty)
          const Card(
            child: ListTile(
              leading: Icon(Icons.flag_outlined),
              title: Text('Nenhuma meta financeira cadastrada.'),
            ),
          )
        else
          ...widget.snapshot.goals.take(4).map((g) {
            final progress = g.targetAmount <= 0
                ? 0.0
                : (g.currentAmount / g.targetAmount).clamp(0.0, 1.0);
            String? targetLabel;
            if (g.targetDate != null && g.targetDate!.trim().isNotEmpty) {
              final d = DateTime.tryParse(g.targetDate!);
              targetLabel = d == null
                  ? g.targetDate
                  : '${d.day.toString().padLeft(2, '0')}/${d.month.toString().padLeft(2, '0')}/${d.year}';
            }
            return Card(
              child: Padding(
                padding: const EdgeInsets.all(14),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Container(
                          padding: const EdgeInsets.all(8),
                          decoration: BoxDecoration(
                            color: Theme.of(context).colorScheme.primaryContainer,
                            borderRadius: BorderRadius.circular(10),
                          ),
                          child: const Icon(
                            Icons.flag_outlined,
                            color: Color(0xFF2563EB),
                            size: 20,
                          ),
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Text(
                            g.name,
                            style: Theme.of(context).textTheme.titleSmall
                                ?.copyWith(fontWeight: FontWeight.w800),
                          ),
                        ),
                        Text(
                          '${(progress * 100).toStringAsFixed(0)}%',
                          style: const TextStyle(
                            fontWeight: FontWeight.w800,
                            color: Color(0xFF2563EB),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 10),
                    Text(
                      '${money(g.currentAmount)} de ${money(g.targetAmount)}',
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                        color: Color(0xFF1F2937),
                      ),
                    ),
                    if (targetLabel != null) ...[
                      const SizedBox(height: 2),
                      Text(
                        'Meta até $targetLabel',
                        style: const TextStyle(
                          fontSize: 12,
                          color: Color(0xFF778196),
                        ),
                      ),
                    ],
                    const SizedBox(height: 8),
                    ClipRRect(
                      borderRadius: BorderRadius.circular(999),
                      child: LinearProgressIndicator(
                        value: progress,
                        minHeight: 8,
                        backgroundColor: const Color(0xFFE8EEF7),
                        valueColor: const AlwaysStoppedAnimation(
                          Color(0xFF2563EB),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            );
          }),
        if (customization.showBiggestExpense) const SizedBox(height: 12),
        if (customization.showBiggestExpense)
          _sectionHeading('Maior gasto do mês'),
        if (customization.showBiggestExpense)
          Card(
            child: biggest == null
                ? const ListTile(
                    leading: Icon(Icons.trending_up),
                    title: Text('Nenhum gasto neste mês'),
                  )
                : (() {
                    final t = biggest!;
                    final d = parseDate(t.date);
                    final dateLabel = d == null
                        ? (t.date.length > 10
                              ? t.date.substring(0, 10)
                              : t.date)
                        : '${d.day.toString().padLeft(2, '0')}/${d.month.toString().padLeft(2, '0')}/${d.year}';
                    final timeLabel = t.isCard && d != null
                        ? ' - ${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}'
                        : '';
                    final debtCreditor = isPlannedDebtTransaction(t)
                        ? debtCreditorFromTransactionDescription(t.description)
                        : null;
                    final account =
                        debtCreditor ??
                        widget.snapshot.accounts
                            .where((a) => a.id == t.accountId)
                            .map((a) => a.institutionName)
                            .firstOrNull ??
                        (t.isCard
                            ? widget.snapshot.cards
                                  .where((c) => c.id == t.cardId)
                                  .map((c) => c.bankName)
                                  .firstOrNull
                            : null) ??
                        'Sem banco';
                    final category =
                        widget.snapshot.categories
                            .where((c) => c.id == t.categoryId)
                            .map((c) => c.name)
                            .firstOrNull ??
                        'Sem categoria';
                    final sourceLabel = t.source.toLowerCase().contains('open')
                        ? 'Open Finance'
                        : 'Manual';
                    return ListTile(
                      leading: Icon(
                        t.isCard ? Icons.credit_card : Icons.trending_up,
                      ),
                      title: Text(t.description),
                      subtitle: Text(
                        '$account - $category\n$dateLabel$timeLabel - Saída - $sourceLabel',
                      ),
                      isThreeLine: true,
                      trailing: Text(
                        '-${money(t.amount.abs())}',
                        style: const TextStyle(
                          color: Colors.red,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    );
                  })(),
          ),
        const SizedBox(height: 24),
      ],
    );
  }

  Widget _sectionHeading(String title) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      Container(
        width: 4,
        height: 22,
        decoration: BoxDecoration(
          color: const Color(0xFF2B72F5),
          borderRadius: BorderRadius.circular(99),
        ),
      ),
      const SizedBox(width: 9),
      Flexible(
        child: Text(
          title,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(
            fontSize: 20,
            fontWeight: FontWeight.w800,
            color: Color(0xFF1D3557),
            letterSpacing: -0.2,
            height: 1.1,
          ),
        ),
      ),
    ],
  );

  Widget _metric(String title, String value) {
    final income = title == 'Entradas';
    final expense = title == 'Saídas';
    final valueColor = income
        ? const Color(0xFF079B68)
        : expense
        ? const Color(0xFFE34B58)
        : Colors.white;
    final bg = income
        ? const Color(0xFFEAFBF4)
        : expense
        ? const Color(0xFFFFF0F2)
        : null;
    if (title == 'Saldo consolidado')
      return Container(
        constraints: const BoxConstraints(minHeight: 126),
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(
          gradient: const LinearGradient(
            begin: Alignment.centerLeft,
            end: Alignment.centerRight,
            colors: [Color(0xFF1378F5), Color(0xFF7657F6)],
          ),
          borderRadius: BorderRadius.circular(20),
          boxShadow: [
            BoxShadow(
              color: const Color(0xFF5268EE).withOpacity(.18),
              blurRadius: 22,
              offset: const Offset(0, 9),
            ),
          ],
        ),
        child: Stack(
          children: [
            Positioned(
              right: -2,
              bottom: -5,
              child: Icon(
                Icons.show_chart_rounded,
                size: 82,
                color: Colors.white.withOpacity(.20),
              ),
            ),
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Row(
                  children: [
                    const Text(
                      'Saldo consolidado',
                      style: TextStyle(
                        color: Colors.white,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(width: 4),
                    IconButton(
                      tooltip: _showFinancialValues
                          ? 'Ocultar valores'
                          : 'Mostrar valores',
                      padding: EdgeInsets.zero,
                      constraints: const BoxConstraints.tightFor(
                        width: 32,
                        height: 32,
                      ),
                      onPressed: () => setState(
                        () => _showFinancialValues = !_showFinancialValues,
                      ),
                      icon: Icon(
                        _showFinancialValues
                            ? Icons.visibility_outlined
                            : Icons.visibility_off_outlined,
                        color: Colors.white70,
                        size: 18,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 9),
                Text(
                  value,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 32,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                const SizedBox(height: 5),
                const Text(
                  'Seu saldo considerando as movimentações registradas',
                  style: TextStyle(color: Color(0xFFDCE8FF), fontSize: 11.5),
                ),
              ],
            ),
          ],
        ),
      );
    return Container(
      constraints: const BoxConstraints(minHeight: 126),
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(
          color: (income ? const Color(0xFFCFF4E5) : const Color(0xFFFFDDE2)),
        ),
      ),
      child: Stack(
        children: [
          Positioned(
            right: 4,
            bottom: 0,
            child: Icon(
              Icons.show_chart_rounded,
              size: 52,
              color: valueColor.withOpacity(.13),
            ),
          ),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Row(
                children: [
                  Container(
                    padding: const EdgeInsets.all(7),
                    decoration: BoxDecoration(
                      color: valueColor.withOpacity(.12),
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Icon(
                      income
                          ? Icons.account_balance_wallet_outlined
                          : Icons.arrow_outward_rounded,
                      color: valueColor,
                      size: 20,
                    ),
                  ),
                  const SizedBox(width: 9),
                  Text(
                    title,
                    style: const TextStyle(fontWeight: FontWeight.w700),
                  ),
                ],
              ),
              const SizedBox(height: 10),
              Text(
                value,
                style: const TextStyle(
                  color: Color(0xFF111827),
                  fontSize: 27,
                  fontWeight: FontWeight.w800,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Future<void> _financeCentersMenu() async {
    final choice = await showDialog<String>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('Central financeira'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Card(
              margin: EdgeInsets.zero,
              child: ListTile(
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
                leading: const Icon(Icons.credit_card_rounded),
                title: const Text('Central de Faturas'),
                subtitle: const Text('Consulte e pague suas faturas de cartão'),
                onTap: () => Navigator.pop(c, 'invoices'),
              ),
            ),
            const SizedBox(height: 10),
            Card(
              margin: EdgeInsets.zero,
              child: ListTile(
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
                leading: const Icon(Icons.event_note_rounded),
                title: const Text('Contas a pagar'),
                subtitle: const Text(
                  'Pendentes, próximas do vencimento e vencidas',
                ),
                onTap: () => Navigator.pop(c, 'payables'),
              ),
            ),
            const SizedBox(height: 10),
            Card(
              margin: EdgeInsets.zero,
              child: ListTile(
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
                leading: const Icon(Icons.credit_score_rounded),
                title: const Text('Minhas dívidas'),
                subtitle: const Text('Parcelas, saldo devedor e histórico de pagamentos'),
                onTap: () => Navigator.pop(c, 'debts'),
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(c),
            child: const Text('Fechar'),
          ),
        ],
      ),
    );
    if (!mounted || choice == null) return;
    if (choice == 'payables') {
      await _payablesDialog();
      return;
    }
    if (choice == 'debts') {
      await showDebtCenter(
        context,
        workspaceId: widget.workspace.id,
        api: widget.api,
        accessToken: widget.accessToken,
        accounts: widget.snapshot.accounts,
        transactions: widget.snapshot.transactions,
        onSaveTransaction: widget.onSaveTransaction,
      );
      return;
    }
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => FinanceToolsScreen(
          snapshot: widget.snapshot,
          isGuest: widget.isGuest,
          onSaveTransaction: widget.onSaveTransaction,
          onAskAi: widget.onAskAi,
          initialIndex: 2,
        ),
      ),
    );
  }

  Widget _quickAction(
    IconData icon,
    String title,
    String subtitle,
    Color accent,
    VoidCallback onTap,
  ) {
    final desktop = MediaQuery.sizeOf(context).width >= 900;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(16),
      child: Container(
        height: desktop ? 74 : 102,
        padding: EdgeInsets.symmetric(
          horizontal: desktop ? 14 : 8,
          vertical: desktop ? 0 : 10,
        ),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: const Color(0xFFE9EDF4)),
          boxShadow: [
            BoxShadow(
              color: const Color(0xFF0F172A).withOpacity(.035),
              blurRadius: 12,
              offset: const Offset(0, 4),
            ),
          ],
        ),
        child: desktop
            ? Row(
                children: [
                  Container(
                    width: 42,
                    height: 42,
                    decoration: BoxDecoration(
                      color: accent.withOpacity(.12),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Icon(icon, color: accent),
                  ),
                  const SizedBox(width: 11),
                  Expanded(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            fontWeight: FontWeight.w700,
                            fontSize: 12.5,
                          ),
                        ),
                        Text(
                          subtitle,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            fontSize: 10.5,
                            color: Color(0xFF7B8494),
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              )
            : Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Container(
                    width: 42,
                    height: 42,
                    decoration: BoxDecoration(
                      color: accent.withOpacity(.12),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Icon(icon, color: accent),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    title,
                    textAlign: TextAlign.center,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontWeight: FontWeight.w700,
                      fontSize: 11.5,
                    ),
                  ),
                ],
              ),
      ),
    );
  }

  Widget _transactions() => TransactionsView(
    // A aba Transações também exibe compromissos financeiros ainda não pagos.
    // Forecast/Inteligência continuam ignorando esses lançamentos para não
    // afetar saldo, saídas, orçamento ou gráficos antes do pagamento.
    snapshot: widget.snapshot,
    onEdit: _transactionDialog,
    onDelete: widget.onDeleteTransaction,
    onAddCard: () async => _cardDialog(),
    onAttachments: _transactionAttachmentsDialog,
  );

  String _bankKey(String name) {
    final n = _normGlobal(name);
    if (n.contains('nubank') || n.contains('nu pagamentos')) return 'nubank';
    if (n.contains('banco do brasil') || n.trim() == 'bb') return 'bb';
    if (n.contains('itau')) return 'itau';
    if (n.contains('santander')) return 'santander';
    if (n.contains('bradesco')) return 'bradesco';
    if (n.contains('caixa')) return 'caixa';
    if (n.contains('inter')) return 'inter';
    if (n.contains('c6')) return 'c6';
    if (n.contains('btg')) return 'btg';
    if (n.contains('picpay')) return 'picpay';
    if (n.contains('mercado pago')) return 'mercadopago';
    if (n.contains('sicredi')) return 'sicredi';
    if (n.contains('sicoob')) return 'sicoob';
    return n.replaceAll(RegExp(r'[^a-z0-9]'), '');
  }

  Color _bankColor(String name) {
    final n = _bankKey(name);
    if (n.contains('nubank')) return const Color(0xFF820AD1);
    if (n.contains('banco do brasil') || n.trim() == 'bb')
      return const Color(0xFFF9D616);
    if (n.contains('itau') || n.contains('itaú'))
      return const Color(0xFFFF7A00);
    if (n.contains('santander')) return const Color(0xFFEC0000);
    if (n.contains('bradesco')) return const Color(0xFFCC092F);
    if (n.contains('caixa')) return const Color(0xFF0067B1);
    if (n.contains('inter')) return const Color(0xFFFF6B00);
    if (n.contains('c6')) return const Color(0xFF111111);
    if (n.contains('btg')) return const Color(0xFF143C70);
    if (n.contains('picpay')) return const Color(0xFF21C25E);
    if (n.contains('mercado pago')) return const Color(0xFF19A8E0);
    if (n.contains('sicredi')) return const Color(0xFF48A23F);
    if (n.contains('sicoob')) return const Color(0xFF126B5A);
    return const Color(0xFF246BFD);
  }

  Color _bankForeground(String name) {
    final n = _bankKey(name);
    if (n.contains('banco do brasil') || n.trim() == 'bb')
      return const Color(0xFF123B70);
    return Colors.white;
  }

  String _bankMark(String name) {
    final n = _bankKey(name);
    if (n.contains('nubank')) return 'nu';
    if (n.contains('banco do brasil') || n.trim() == 'bb') return 'BB';
    if (n.contains('itau') || n.contains('itaú')) return 'itaú';
    if (n.contains('santander')) return 'S';
    if (n.contains('bradesco')) return 'B';
    if (n.contains('caixa')) return 'X';
    if (n.contains('inter')) return 'inter';
    if (n.contains('c6')) return 'C6';
    if (n.contains('btg')) return 'BTG';
    if (n.contains('picpay')) return 'P';
    if (n.contains('mercado pago')) return 'MP';
    final parts = name
        .trim()
        .split(RegExp(r'\s+'))
        .where((e) => e.isNotEmpty)
        .take(2)
        .toList();
    return parts.isEmpty ? 'B' : parts.map((e) => e[0].toUpperCase()).join();
  }

  Widget _bankBadge(String name, {double size = 46}) {
    final bg = _bankColor(name);
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(size * .28),
      ),
      alignment: Alignment.center,
      child: Text(
        _bankMark(name),
        maxLines: 1,
        style: TextStyle(
          color: _bankForeground(name),
          fontSize: size * .25,
          fontWeight: FontWeight.w900,
          letterSpacing: -.2,
        ),
      ),
    );
  }

  List<Color> _cardGradient(String name) {
    final base = _bankColor(name);
    final n = _bankKey(name);
    if (n.contains('nubank'))
      return const [Color(0xFF6A0DAD), Color(0xFF9A30E5)];
    if (n.contains('banco do brasil') || n.trim() == 'bb')
      return const [Color(0xFFFFD600), Color(0xFFF2C400)];
    if (n.contains('itau') || n.contains('itaú'))
      return const [Color(0xFFFF8A00), Color(0xFFFF5F00)];
    if (n.contains('santander'))
      return const [Color(0xFFEC0000), Color(0xFFB80000)];
    if (n.contains('caixa'))
      return const [Color(0xFF005CA9), Color(0xFF00A5DF)];
    if (n.contains('inter'))
      return const [Color(0xFFFF7A00), Color(0xFFFFA000)];
    if (n.contains('c6')) return const [Color(0xFF101010), Color(0xFF353535)];
    return [base, Color.lerp(base, const Color(0xFF0F2F5F), .32)!];
  }

  Widget _accountsCards() {
    final accounts = widget.snapshot.accounts;
    final cards = widget.snapshot.cards.where((c) => c.active).toList();
    final wide = MediaQuery.sizeOf(context).width >= 760;
    final cardWidth = wide ? 340.0 : MediaQuery.sizeOf(context).width - 32;
    // Cartoes usam um formato um pouco mais comprido/retangular em telas largas.
    // Em telas estreitas continuam ocupando a largura disponivel.
    final creditCardWidth = wide ? 390.0 : cardWidth;
    Widget sectionTitle(
      String title,
      IconData icon,
      VoidCallback onAdd,
      String tooltip,
    ) => Row(
      children: [
        Container(
          width: 42,
          height: 42,
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.primaryContainer,
            borderRadius: BorderRadius.circular(13),
          ),
          child: Icon(icon, color: Theme.of(context).colorScheme.primary),
        ),
        const SizedBox(width: 11),
        Flexible(
          fit: FlexFit.loose,
          child: Text(
            title,
            style: TextStyle(
              fontSize: 22,
              fontWeight: FontWeight.w800,
              color: Theme.of(context).colorScheme.onSurface,
              letterSpacing: -.3,
            ),
          ),
        ),
        const SizedBox(width: 8),
        IconButton.filled(
          tooltip: tooltip,
          onPressed: onAdd,
          style: IconButton.styleFrom(
            backgroundColor: const Color(0xFF246BFD),
            foregroundColor: Colors.white,
          ),
          icon: const Icon(Icons.add_rounded),
        ),
        const Spacer(),
      ],
    );
    Widget accountCard(FinancialAccount a) {
      final details = [
        a.accountName,
        a.maskedAccount,
      ].whereType<String>().where((e) => e.trim().isNotEmpty).join(' • ');
      final movementCount = widget.snapshot.transactions
          .where((t) => t.accountId == a.id && !t.isCard)
          .length;
      final calculated = widget.snapshot.transactions
          .where((t) => t.accountId == a.id && !t.isCard)
          .fold<double>(0, (sum, t) => sum + t.amount);
      final balance = a.currentBalance ?? calculated;
      final connected = a.connectionStatus.toLowerCase() == 'connected';
      return InkWell(
        onTap: () => _accountDialog(a),
        borderRadius: BorderRadius.circular(18),
        child: Container(
          padding: const EdgeInsets.all(18),
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.surface,
            borderRadius: BorderRadius.circular(18),
            border: Border.all(color: Theme.of(context).colorScheme.outline),
            boxShadow: [
              BoxShadow(
                color: const Color(0xFF0F172A).withOpacity(.05),
                blurRadius: 18,
                offset: const Offset(0, 6),
              ),
            ],
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  _bankBadge(a.institutionName),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          a.institutionName,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w800,
                            color: Theme.of(context).colorScheme.onSurface,
                          ),
                        ),
                        if (details.isNotEmpty)
                          Text(
                            details,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 12,
                              color: Theme.of(context).colorScheme.onSurfaceVariant,
                            ),
                          ),
                      ],
                    ),
                  ),
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 9,
                      vertical: 5,
                    ),
                    decoration: BoxDecoration(
                      color: connected
                          ? const Color(0xFFEAFBF4)
                          : Theme.of(context).colorScheme.surfaceContainerHighest,
                      borderRadius: BorderRadius.circular(99),
                    ),
                    child: Text(
                      connected ? 'Conectado' : 'Manual',
                      style: TextStyle(
                        fontSize: 10.5,
                        fontWeight: FontWeight.w700,
                        color: connected
                            ? const Color(0xFF087A55)
                            : Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ),
                  PopupMenuButton<String>(
                    onSelected: (v) {
                      if (v == 'edit') _accountDialog(a);
                      if (v == 'delete') _deleteBankAccount(a);
                    },
                    itemBuilder: (_) => const [
                      PopupMenuItem(value: 'edit', child: Text('Editar')),
                      PopupMenuItem(value: 'delete', child: Text('Excluir')),
                    ],
                  ),
                ],
              ),
              const SizedBox(height: 18),
              Text(
                'Saldo',
                style: TextStyle(
                  fontSize: 11.5,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                  fontWeight: FontWeight.w600,
                ),
              ),
              const SizedBox(height: 3),
              Text(
                money(balance),
                style: TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.w800,
                  color: Theme.of(context).colorScheme.onSurface,
                ),
              ),
              const SizedBox(height: 10),
              Row(
                children: [
                  Icon(
                    Icons.swap_horiz_rounded,
                    size: 17,
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                  const SizedBox(width: 5),
                  Text(
                    '$movementCount movimenta${movementCount == 1 ? 'ção' : 'ções'}',
                    style: TextStyle(
                      fontSize: 11.5,
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      );
    }

    Widget creditCard(CreditCardInfo c) {
      final purchases = widget.snapshot.transactions.where(
        (t) => t.cardId == c.id && t.source != 'card_payment',
      );
      final invoice = purchases.fold<double>(
        0,
        (sum, t) => sum + t.amount.abs(),
      );
      final title = c.nickname?.trim().isNotEmpty == true
          ? c.nickname!
          : c.bankName;
      final fg = _bankForeground(c.bankName);
      final secondary = fg.withOpacity(.82);
      return InkWell(
        onTap: () => _cardDialog(c),
        borderRadius: BorderRadius.circular(16),
        child: Container(
          padding: const EdgeInsets.all(18),
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: _cardGradient(c.bankName),
            ),
            borderRadius: BorderRadius.circular(16),
            boxShadow: [
              BoxShadow(
                color: _bankColor(c.bankName).withOpacity(.18),
                blurRadius: 18,
                offset: const Offset(0, 7),
              ),
            ],
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Container(
                    width: 45,
                    height: 45,
                    decoration: BoxDecoration(
                      color: Colors.white.withOpacity(.92),
                      borderRadius: BorderRadius.circular(13),
                    ),
                    alignment: Alignment.center,
                    child: Text(
                      _bankMark(c.bankName),
                      style: TextStyle(
                        color: _bankColor(c.bankName),
                        fontWeight: FontWeight.w900,
                        fontSize: 12,
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: fg,
                            fontSize: 16,
                            fontWeight: FontWeight.w900,
                          ),
                        ),
                        Text(
                          '${c.bankName} • ${c.brand}',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: secondary,
                            fontSize: 11.5,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ],
                    ),
                  ),
                  PopupMenuButton<String>(
                    iconColor: fg,
                    onSelected: (v) async {
                      if (v == 'invoice') {
                        await Navigator.of(context).push(
                          MaterialPageRoute(
                            builder: (_) => FinanceToolsScreen(
                              snapshot: widget.snapshot,
                              isGuest: widget.isGuest,
                              onSaveTransaction: widget.onSaveTransaction,
                              onAskAi: widget.onAskAi,
                              initialIndex: 2,
                              initialCardId: c.id,
                            ),
                          ),
                        );
                      }
                      if (v == 'edit') _cardDialog(c);
                      if (v == 'delete') await _confirmDeleteCard(c);
                    },
                    itemBuilder: (_) => const [
                      PopupMenuItem(
                        value: 'invoice',
                        child: ListTile(
                          leading: Icon(Icons.receipt_long_outlined),
                          title: Text('Central de Faturas'),
                        ),
                      ),
                      PopupMenuItem(
                        value: 'edit',
                        child: ListTile(
                          leading: Icon(Icons.edit_outlined),
                          title: Text('Editar'),
                        ),
                      ),
                      PopupMenuDivider(),
                      PopupMenuItem(
                        value: 'delete',
                        child: ListTile(
                          leading: Icon(
                            Icons.delete_outline,
                            color: Colors.red,
                          ),
                          title: Text(
                            'Excluir cartão',
                            style: TextStyle(color: Colors.red),
                          ),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
              const SizedBox(height: 18),
              Row(
                children: [
                  Container(
                    width: 27,
                    height: 19,
                    decoration: BoxDecoration(
                      color: const Color(0xFFFFD88A).withOpacity(.95),
                      borderRadius: BorderRadius.circular(4),
                    ),
                  ),
                  const Spacer(),
                  Text(
                    c.brand.toUpperCase(),
                    style: TextStyle(
                      color: secondary,
                      fontSize: 10,
                      fontWeight: FontWeight.w800,
                      letterSpacing: .7,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 15),
              Text(
                '••••  ${c.lastFour}',
                style: TextStyle(
                  color: fg,
                  fontSize: 18,
                  fontWeight: FontWeight.w800,
                  letterSpacing: 1.4,
                ),
              ),
              const SizedBox(height: 16),
              Row(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Fatura atual',
                          style: TextStyle(color: secondary, fontSize: 11),
                        ),
                        const SizedBox(height: 3),
                        Text(
                          money(invoice),
                          style: TextStyle(
                            color: fg,
                            fontSize: 22,
                            fontWeight: FontWeight.w900,
                          ),
                        ),
                      ],
                    ),
                  ),
                  if (c.dueDay != null)
                    Text(
                      'Vence dia ${c.dueDay}',
                      style: TextStyle(
                        color: secondary,
                        fontSize: 11.5,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                ],
              ),
              if (c.creditLimit != null) ...[
                const SizedBox(height: 12),
                ClipRRect(
                  borderRadius: BorderRadius.circular(99),
                  child: LinearProgressIndicator(
                    value: c.creditLimit! <= 0
                        ? 0.0
                        : (invoice / c.creditLimit!).clamp(0.0, 1.0).toDouble(),
                    minHeight: 5,
                    backgroundColor: fg.withOpacity(.20),
                    valueColor: AlwaysStoppedAnimation<Color>(
                      fg.withOpacity(.90),
                    ),
                  ),
                ),
                const SizedBox(height: 5),
                Text(
                  'Limite ${money(c.creditLimit!)}',
                  style: TextStyle(
                    color: secondary,
                    fontSize: 10.5,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ],
            ],
          ),
        ),
      );
    }

    return ListView(
      padding: EdgeInsets.fromLTRB(wide ? 28 : 16, 22, wide ? 28 : 16, 100),
      children: [
        sectionTitle(
          'Bancos',
          Icons.account_balance_rounded,
          _accountAddMenu,
          'Adicionar conta',
        ),
        const SizedBox(height: 14),
        if (accounts.isEmpty)
          Container(
            padding: const EdgeInsets.symmetric(vertical: 36, horizontal: 16),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.surface,
              borderRadius: BorderRadius.circular(18),
              border: Border.all(color: Theme.of(context).colorScheme.outline),
            ),
            child: Column(
              children: [
                Icon(
                  Icons.account_balance_outlined,
                  size: 34,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
                const SizedBox(height: 10),
                Text(
                  'Nenhuma conta cadastrada',
                  style: TextStyle(
                    fontWeight: FontWeight.w700,
                    color: Theme.of(context).colorScheme.onSurface,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  'Adicione uma conta manual ou vincule pelo Open Finance.',
                  textAlign: TextAlign.center,
                  style: TextStyle(fontSize: 12, color: Theme.of(context).colorScheme.onSurfaceVariant),
                ),
              ],
            ),
          )
        else
          Wrap(
            spacing: 14,
            runSpacing: 14,
            children: accounts
                .map((a) => SizedBox(width: cardWidth, child: accountCard(a)))
                .toList(),
          ),
        const SizedBox(height: 30),
        sectionTitle(
          'Cartões',
          Icons.credit_card_rounded,
          () => _cardDialog(),
          'Adicionar cartão',
        ),
        const SizedBox(height: 14),
        if (cards.isEmpty)
          Container(
            padding: const EdgeInsets.symmetric(vertical: 36, horizontal: 16),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.surface,
              borderRadius: BorderRadius.circular(18),
              border: Border.all(color: Theme.of(context).colorScheme.outline),
            ),
            child: Column(
              children: [
                Icon(
                  Icons.credit_card_off_rounded,
                  size: 34,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
                const SizedBox(height: 10),
                Text(
                  'Nenhum cartão cadastrado',
                  style: TextStyle(
                    fontWeight: FontWeight.w700,
                    color: Theme.of(context).colorScheme.onSurface,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  'Cadastre um cartão para acompanhar compras e faturas.',
                  textAlign: TextAlign.center,
                  style: TextStyle(fontSize: 12, color: Theme.of(context).colorScheme.onSurfaceVariant),
                ),
              ],
            ),
          )
        else
          Wrap(
            spacing: 14,
            runSpacing: 14,
            children: cards
                .map(
                  (c) => SizedBox(
                    width: creditCardWidth,
                    child: ConstrainedBox(
                      constraints: BoxConstraints(
                        minHeight: creditCardWidth / 1.75,
                      ),
                      child: creditCard(c),
                    ),
                  ),
                )
                .toList(),
          ),
      ],
    );
  }

  Future<String?> _sixDigitDeleteCode(String email) async {
    final controllers = List.generate(4, (_) => TextEditingController());
    final nodes = List.generate(4, (_) => FocusNode());
    final result = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Código de confirmação'),
        content: SizedBox(
          width: 420,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text('Enviamos um código de 4 dígitos para $email.'),
              const SizedBox(height: 16),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: List.generate(
                  4,
                  (i) => SizedBox(
                    width: 48,
                    child: TextField(
                      controller: controllers[i],
                      focusNode: nodes[i],
                      autofocus: i == 0,
                      textAlign: TextAlign.center,
                      keyboardType: TextInputType.number,
                      inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                      decoration: const InputDecoration(
                        counterText: '',
                        border: OutlineInputBorder(),
                      ),
                      onChanged: (value) {
                        final d = value.replaceAll(RegExp(r'\D'), '');
                        if (d.length > 1) {
                          final limit = d.length > 4 ? 4 : d.length;
                          final pasted = d.substring(0, limit);
                          for (var j = 0; j < 4; j++) {
                            controllers[j].text = j < pasted.length
                                ? pasted[j]
                                : '';
                          }
                          nodes[pasted.isEmpty
                                  ? 0
                                  : (pasted.length > 4 ? 3 : pasted.length - 1)]
                              .requestFocus();
                        } else if (d.isNotEmpty) {
                          controllers[i].text = d[0];
                          if (i < 3) nodes[i + 1].requestFocus();
                        } else if (i > 0) {
                          nodes[i - 1].requestFocus();
                        }
                      },
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('Cancelar'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(
              dialogContext,
              controllers.map((e) => e.text).join(),
            ),
            child: const Text('Confirmar'),
          ),
        ],
      ),
    );
    for (final c in controllers) {
      c.dispose();
    }
    for (final n in nodes) {
      n.dispose();
    }
    return result?.length == 4 ? result : null;
  }

  Future<void> _deleteBankAccount(FinancialAccount account) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('Excluir conta bancária?'),
        content: Text(
          'A conta ${account.institutionName} será removida. As transações já vinculadas a ela serão mantidas.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(c, false),
            child: const Text('Cancelar'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () => Navigator.pop(c, true),
            child: const Text('Excluir conta'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    if (widget.isGuest) {
      await widget.onDeleteAccount(account.id);
      return;
    }
    if (widget.accessToken == null) return;
    try {
      final sent = await widget.api.requestDataDeletionCode(
        widget.accessToken!,
      );
      if (!mounted) return;
      final code = await _sixDigitDeleteCode(
        (sent['email'] ?? 'seu e-mail').toString(),
      );
      if (code == null) return;
      final options = <String, dynamic>{
        'transactions': false,
        'categories': false,
        'accounts': false,
        'cards': false,
        'budgets': false,
        'goals': false,
        'open_finance': false,
        'workspace_ids': <String>[],
        'account_ids': <int>[account.id],
        'delete_account': false,
      };
      await widget.api.confirmDataDeletion(
        widget.accessToken!,
        code,
        options,
        deletionToken: sent['deletion_token']?.toString(),
      );
      await widget.onRefreshFinance();
      if (mounted)
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Conta bancária excluída.')),
        );
    } catch (e) {
      if (mounted)
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(e.toString().replaceFirst('Exception: ', ''))),
        );
    }
  }

  Future<void> _confirmDeleteCard(CreditCardInfo card) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Excluir cartão?'),
        content: const Text(
          'Tem certeza de que deseja excluir este cartão? As transações já registradas com este cartão serão mantidas.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('Cancelar'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            style: TextButton.styleFrom(foregroundColor: Colors.red),
            child: const Text('Excluir cartão'),
          ),
        ],
      ),
    );
    if (confirmed == true) await widget.onDeleteCard(card.id);
  }

  Future<void> _accountAddMenu() async {
    final v = await showModalBottomSheet<String>(
      context: context,
      builder: (c) => SafeArea(
        child: Wrap(
          children: [
            ListTile(
              leading: const Icon(Icons.edit_outlined),
              title: const Text('Adicionar manual'),
              onTap: () => Navigator.pop(c, 'manual'),
            ),
            if (!widget.isGuest)
              ListTile(
                leading: const Icon(Icons.account_balance_outlined),
                title: const Text('Vincular Open Finance'),
                onTap: () => Navigator.pop(c, 'openfinance'),
              ),
          ],
        ),
      ),
    );
    if (v == 'manual') _accountDialog();
    if (v == 'openfinance') _openBelvo();
  }

  Future<void> _openBelvo() async {
    if (widget.isGuest || widget.accessToken == null) return;
    try {
      final result = await widget.api.openFinanceWidgetToken(
        widget.accessToken!,
        widget.workspace.id,
      );
      final raw = result['hosted_widget_url']?.toString();
      if (raw == null || raw.isEmpty)
        throw Exception('O backend não retornou a URL segura da Belvo.');
      final ok = await launchUrl(
        Uri.parse(raw),
        mode: LaunchMode.externalApplication,
      );
      if (!ok)
        throw Exception('Não foi possível abrir o consentimento Open Finance.');
    } catch (e) {
      if (mounted)
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(e.toString().replaceFirst('Exception: ', ''))),
        );
    }
  }

  Future<int?> _createCategory() async {
    final name = TextEditingController();
    final limit = TextEditingController();
    String? selectedIcon;
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => StatefulBuilder(
        builder: (c, setDialogState) => AlertDialog(
          title: const Text('Nova categoria'),
          content: SizedBox(
            width: 420,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                TextField(
                  controller: name,
                  autofocus: true,
                  onChanged: (_) => setDialogState(() {}),
                  decoration: const InputDecoration(labelText: 'Nome da categoria *'),
                ),
                const SizedBox(height: 12),
                const Text('Ícone da categoria', style: TextStyle(fontWeight: FontWeight.w700)),
                const SizedBox(height: 8),
                CategoryIconPicker(
                  selected: selectedIcon,
                  categoryName: name.text,
                  onSelected: (value) => setDialogState(() => selectedIcon = value),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: limit,
                  keyboardType: TextInputType.number,
                  inputFormatters: const [BrlMoneyInputFormatter()],
                  decoration: const InputDecoration(labelText: 'Limite mensal (opcional)'),
                ),
                const SizedBox(height: 8),
                const Text('A categoria poderá ser usada em entradas ou saídas.'),
              ],
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Cancelar')),
            FilledButton(onPressed: name.text.trim().isEmpty ? null : () => Navigator.pop(c, true), child: const Text('Adicionar')),
          ],
        ),
      ),
    );
    if (ok != true || name.text.trim().isEmpty) return null;
    final id = _id();
    final icon = selectedIcon ?? inferredCategoryIconId(name.text);
    final created = FinanceCategory(id: id, name: name.text.trim(), icon: icon);
    await widget.onSaveCategory(created);
    if (mounted) setState(() => _categoryCache = [..._categoryCache.where((e) => e.id != id), created]);
    final value = brlValue(limit.text);
    if (value > 0) {
      final budgets = [...widget.snapshot.budgets.where((b) => b.categoryId != id), CategoryBudget(id: _id(), categoryId: id, amount: value)];
      await widget.onSaveSnapshot(widget.snapshot.copyWith(categories: [...widget.snapshot.categories.where((e) => e.id != id), created], budgets: budgets));
    }
    return id;
  }

  Future<void> _categoriesDialog() async {
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Categorias'),
        content: SizedBox(
          width: 460,
          height: 420,
          child: Column(
            children: [
              Expanded(
                child: _categoryCache.isEmpty
                    ? const Center(child: Text('Nenhuma categoria cadastrada.'))
                    : ListView(
                        children: _categoryCache.map((cat) {
                          final budget = widget.snapshot.budgets.where(
                            (b) => b.categoryId == cat.id,
                          );
                          return ListTile(
                            leading: Icon(categoryIconData(cat.icon, cat.name)),
                            title: Text(cat.name),
                            subtitle: Text(
                              budget.isEmpty
                                  ? 'Sem limite mensal'
                                  : 'Limite mensal: ${money(budget.first.amount)}',
                            ),
                            trailing: IconButton(
                              icon: const Icon(Icons.delete_outline),
                              onPressed: () async {
                                await widget.onDeleteCategory(cat.id);
                                if (mounted)
                                  setState(
                                    () => _categoryCache = _categoryCache
                                        .where((e) => e.id != cat.id)
                                        .toList(),
                                  );
                                if (mounted) Navigator.pop(dialogContext);
                                await _categoriesDialog();
                              },
                            ),
                          );
                        }).toList(),
                      ),
              ),
              FilledButton.icon(
                onPressed: () async {
                  Navigator.pop(dialogContext);
                  await _createCategory();
                  if (mounted) await _categoriesDialog();
                },
                icon: const Icon(Icons.add),
                label: const Text('Adicionar categoria'),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('Fechar'),
          ),
        ],
      ),
    );
  }

  Future<void> _addFinanceMenu() async {
    final v = await showModalBottomSheet<String>(
      context: context,
      builder: (c) => SafeArea(
        child: Wrap(
          children: [
            ListTile(
              leading: const Icon(Icons.account_balance),
              title: const Text('Adicionar conta'),
              onTap: () => Navigator.pop(c, 'account'),
            ),
            ListTile(
              leading: const Icon(Icons.credit_card),
              title: const Text('Adicionar cartão'),
              onTap: () => Navigator.pop(c, 'card'),
            ),
          ],
        ),
      ),
    );
    if (v == 'account') _accountDialog();
    if (v == 'card') _cardDialog();
  }

  Future<void> _expenseAddMenu() async {
    final choice = await showModalBottomSheet<String>(
      context: context,
      builder: (c) => SafeArea(
        child: Wrap(
          children: [
            const ListTile(
              title: Text('Adicionar despesa'),
              subtitle: Text('Escolha a forma de lançamento'),
            ),
            ListTile(
              leading: const Icon(Icons.edit_outlined),
              title: const Text('Adicionar manualmente'),
              onTap: () => Navigator.pop(c, 'manual'),
            ),
            ListTile(
              leading: const Icon(Icons.qr_code_scanner_rounded),
              title: const Text('Ler boleto / QR Code'),
              subtitle: const Text(
                'Preenche os dados disponíveis e pede sua confirmação',
              ),
              onTap: () => Navigator.pop(c, 'scan'),
            ),
            ListTile(
              leading: const Icon(Icons.event_note_outlined),
              title: const Text('Conta com vencimento'),
              subtitle: const Text('Só entra nas despesas quando for paga'),
              onTap: () => Navigator.pop(c, 'payable'),
            ),
            ListTile(
              leading: const Icon(Icons.credit_score_outlined),
              title: const Text('Adicionar dívida'),
              subtitle: const Text('Cadastre parcelas e acompanhe os pagamentos'),
              onTap: () => Navigator.pop(c, 'debt'),
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
    if (!mounted || choice == null) return;
    if (choice == 'manual') {
      await _transactionDialog();
      return;
    }
    if (choice == 'payable') {
      await _newPayableDialog();
      return;
    }
    if (choice == 'debt') {
      await showDebtCenter(
        context,
        workspaceId: widget.workspace.id,
        api: widget.api,
        accessToken: widget.accessToken,
        accounts: widget.snapshot.accounts,
        transactions: widget.snapshot.transactions,
        onSaveTransaction: widget.onSaveTransaction,
        startNew: true,
      );
      return;
    }
    if (choice == 'payables') {
      await _payablesDialog();
      return;
    }
    if (choice == 'scan') {
      final draft = await Navigator.of(context).push<ScannedExpenseDraft>(
        MaterialPageRoute(builder: (_) => const ExpenseScannerPage()),
      );
      if (draft != null && mounted) {
        if (draft.dueDate != null) {
          final asPayable = await showDialog<bool>(
            context: context,
            builder: (c) => AlertDialog(
              title: const Text('Como deseja salvar?'),
              content: const Text(
                'Este documento possui vencimento. Você pode deixá-lo em Contas a pagar e só lançar a despesa quando pagar.',
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(c, false),
                  child: const Text('Lançar agora'),
                ),
                FilledButton(
                  onPressed: () => Navigator.pop(c, true),
                  child: const Text('Conta a pagar'),
                ),
              ],
            ),
          );
          if (asPayable == true) {
            await _newPayableDialog(draft);
          } else {
            await _transactionDialog(null, draft);
          }
        } else {
          await _transactionDialog(null, draft);
        }
      }
    }
  }

  Future<void> _newPayableDialog([ScannedExpenseDraft? scanned]) async {
    final desc = TextEditingController(text: scanned?.description ?? '');
    final amount = TextEditingController(
      text: scanned?.amount == null ? '' : brlText(scanned!.amount!),
    );
    DateTime due =
        scanned?.dueDate ?? DateTime.now().add(const Duration(days: 7));
    int reminder = 5;
    int? accountId;
    int? categoryId;
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => StatefulBuilder(
        builder: (c, set) => AlertDialog(
          title: const Text('Nova conta a pagar'),
          content: SizedBox(
            width: 460,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  TextField(
                    controller: desc,
                    decoration: const InputDecoration(labelText: 'Descrição *'),
                  ),
                  const SizedBox(height: 14),
                  TextField(
                    controller: amount,
                    keyboardType: TextInputType.number,
                    inputFormatters: const [BrlMoneyInputFormatter()],
                    decoration: const InputDecoration(labelText: 'Valor *'),
                  ),
                  const SizedBox(height: 14),
                  TextField(
                    readOnly: true,
                    controller: TextEditingController(
                      text: '${due.day.toString().padLeft(2, '0')}/${due.month.toString().padLeft(2, '0')}/${due.year}',
                    ),
                    decoration: InputDecoration(
                      labelText: 'Vencimento',
                      suffixIcon: IconButton(
                        icon: const Icon(Icons.calendar_month),
                        onPressed: () async {
                          final d = await showDatePicker(
                            context: c,
                            initialDate: due,
                            firstDate: DateTime(1900),
                            lastDate: DateTime(2200),
                          );
                          if (d != null) set(() => due = d);
                        },
                      ),
                    ),
                    onTap: () async {
                      final d = await showDatePicker(
                        context: c,
                        initialDate: due,
                        firstDate: DateTime(1900),
                        lastDate: DateTime(2200),
                      );
                      if (d != null) set(() => due = d);
                    },
                  ),
                  const SizedBox(height: 14),
                  DropdownButtonFormField<int>(
                    initialValue: accountId,
                    items: widget.snapshot.accounts
                        .map(
                          (e) => DropdownMenuItem(
                            value: e.id,
                            child: Text(e.institutionName),
                          ),
                        )
                        .toList(),
                    onChanged: (v) => set(() => accountId = v),
                    decoration: const InputDecoration(
                      labelText: 'Banco/conta (opcional)',
                    ),
                  ),
                  const SizedBox(height: 14),
                  DropdownButtonFormField<int>(
                    initialValue: categoryId,
                    items: _categoryCache
                        .map(
                          (e) => DropdownMenuItem(
                            value: e.id,
                            child: Text(e.name),
                          ),
                        )
                        .toList(),
                    onChanged: (v) => set(() => categoryId = v),
                    decoration: const InputDecoration(
                      labelText: 'Categoria (opcional)',
                    ),
                  ),
                  const SizedBox(height: 10),
                  Align(
                    alignment: Alignment.centerLeft,
                    child: Text(
                      'Avisar antes do vencimento',
                      style: Theme.of(c).textTheme.titleSmall,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Wrap(
                    spacing: 8,
                    children: [
                      for (final d in const [5, 10, 15])
                        ChoiceChip(
                          label: Text('$d dias'),
                          selected: reminder == d,
                          onSelected: (_) => set(() => reminder = d),
                        ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  const Text(
                    'A conta fica pendente e não entra no Saldo, Saídas, orçamentos ou gráficos até você marcar como paga.',
                    style: TextStyle(fontSize: 12, color: Color(0xFF697386)),
                  ),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(c, false),
              child: const Text('Cancelar'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(c, true),
              child: const Text('Salvar'),
            ),
          ],
        ),
      ),
    );
    if (ok != true || desc.text.trim().isEmpty) return;
    final value = brlValue(amount.text);
    if (value <= 0) return;
    final clientKey = 'payable:${DateTime.now().microsecondsSinceEpoch}';
    await widget.onSaveTransaction(
      FinancialTransaction(
        id: _id(),
        date:
            '${due.year}-${due.month.toString().padLeft(2, '0')}-${due.day.toString().padLeft(2, '0')}',
        description: _encodePayable(desc.text.trim(), value, reminder),
        amount: 0,
        type: 'expense',
        accountId: accountId,
        categoryId: categoryId,
        source: 'manual',
        externalTransactionId: clientKey,
      ),
    );
    if (mounted) await _payablesDialog();
  }

  Future<void> _payablesDialog() async {
    final pending = widget.snapshot.transactions.where(_isPendingPayable).toList()
      ..sort((a, b) => a.date.compareTo(b.date));
    final total = pending.fold<double>(0, (sum, t) => sum + (_decodePayable(t.description)?.amount ?? 0));
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    int overdue = 0;
    int upcoming = 0;
    for (final t in pending) {
      final due = DateTime.tryParse(t.date);
      if (due == null) continue;
      final days = DateTime(due.year, due.month, due.day).difference(today).inDays;
      if (days < 0) overdue++;
      if (days >= 0 && days <= 7) upcoming++;
    }

    await showDialog<void>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('Contas a pagar'),
        content: SizedBox(
          width: 620,
          height: 560,
          child: Column(
            children: [
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  onPressed: () {
                    Navigator.pop(c);
                    Future.microtask(_newPayableDialog);
                  },
                  icon: const Icon(Icons.add),
                  label: const Text('Adicionar conta'),
                ),
              ),
              const SizedBox(height: 12),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Theme.of(c).colorScheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Resumo', style: Theme.of(c).textTheme.labelMedium),
                    Text(money(total), style: Theme.of(c).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800)),
                    Text('${pending.length} pendente(s) • $upcoming nos próximos 7 dias • $overdue vencida(s)', style: Theme.of(c).textTheme.bodySmall),
                  ],
                ),
              ),
              const SizedBox(height: 12),
              Expanded(
                child: pending.isEmpty
                    ? const Center(child: Text('Nenhuma conta pendente. Use “Adicionar conta” para cadastrar.'))
                    : ListView.separated(
                        itemCount: pending.length,
                        separatorBuilder: (_, __) => const SizedBox(height: 10),
                        itemBuilder: (_, i) {
                          final t = pending[i];
                          final m = _decodePayable(t.description)!;
                          final due = DateTime.tryParse(t.date);
                          final days = due == null ? null : DateTime(due.year, due.month, due.day).difference(today).inDays;
                          final status = days == null
                              ? 'Pendente'
                              : days < 0
                                  ? 'Vencida'
                                  : days == 0
                                      ? 'Vence hoje'
                                      : days <= 7
                                          ? 'Próxima do vencimento'
                                          : 'Em aberto';
                          final statusColor = days != null && days < 0
                              ? Colors.red
                              : days != null && days <= 7
                                  ? Colors.orange
                                  : Theme.of(c).colorScheme.primary;
                          final bank = widget.snapshot.accounts.where((a) => a.id == t.accountId).map((a) => a.institutionName).firstOrNull;
                          final category = _categoryCache.where((x) => x.id == t.categoryId).map((x) => x.name).firstOrNull;
                          return Card(
                            child: Padding(
                              padding: const EdgeInsets.all(14),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Row(
                                    children: [
                                      Expanded(
                                        child: Column(
                                          crossAxisAlignment: CrossAxisAlignment.start,
                                          children: [
                                            Text(m.description, style: const TextStyle(fontWeight: FontWeight.w800)),
                                            if (bank != null || category != null)
                                              Text([if (bank != null) bank, if (category != null) category].join(' • '), style: Theme.of(c).textTheme.bodySmall),
                                          ],
                                        ),
                                      ),
                                      Text(money(m.amount), style: const TextStyle(fontWeight: FontWeight.w800)),
                                    ],
                                  ),
                                  const SizedBox(height: 8),
                                  Container(
                                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                                    decoration: BoxDecoration(color: statusColor.withOpacity(0.12), borderRadius: BorderRadius.circular(999)),
                                    child: Text(status, style: TextStyle(color: statusColor, fontWeight: FontWeight.w700, fontSize: 12)),
                                  ),
                                  if (due != null) ...[
                                    const SizedBox(height: 6),
                                    Text('${due.day.toString().padLeft(2, '0')}/${due.month.toString().padLeft(2, '0')}/${due.year} • aviso ${m.reminderDays} dias antes', style: Theme.of(c).textTheme.bodySmall),
                                  ],
                                  Row(
                                    mainAxisAlignment: MainAxisAlignment.end,
                                    children: [
                                      TextButton.icon(
                                        onPressed: () async {
                                          await widget.onDeleteTransaction(t.id);
                                          if (c.mounted) Navigator.pop(c);
                                          if (mounted) Future.microtask(_payablesDialog);
                                        },
                                        icon: const Icon(Icons.delete_outline),
                                        label: const Text('Excluir'),
                                      ),
                                      FilledButton.tonalIcon(
                                        onPressed: () {
                                          Navigator.pop(c);
                                          Future.microtask(() => _payPayable(t, m));
                                        },
                                        icon: const Icon(Icons.payments_outlined),
                                        label: const Text('Pagar'),
                                      ),
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
        actions: [TextButton(onPressed: () => Navigator.pop(c), child: const Text('Fechar'))],
      ),
    );
  }

  Future<void> _payPayable(FinancialTransaction old, _PayableMeta meta) async {
    int? accountId = old.accountId;
    int? categoryId = old.categoryId;
    DateTime date = DateTime.now();
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => StatefulBuilder(
        builder: (c, set) => AlertDialog(
          title: const Text('Confirmar pagamento'),
          content: SizedBox(
            width: 430,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text('${meta.description} • ${money(meta.amount)}'),
                DropdownButtonFormField<int>(
                  initialValue:
                      widget.snapshot.accounts.any((a) => a.id == accountId)
                      ? accountId
                      : null,
                  items: widget.snapshot.accounts
                      .map(
                        (e) => DropdownMenuItem(
                          value: e.id,
                          child: Text(e.institutionName),
                        ),
                      )
                      .toList(),
                  onChanged: (v) => set(() => accountId = v),
                  decoration: const InputDecoration(labelText: 'Banco/conta'),
                ),
                DropdownButtonFormField<int>(
                  initialValue: _categoryCache.any((a) => a.id == categoryId)
                      ? categoryId
                      : null,
                  items: _categoryCache
                      .map(
                        (e) =>
                            DropdownMenuItem(value: e.id, child: Text(e.name)),
                      )
                      .toList(),
                  onChanged: (v) => set(() => categoryId = v),
                  decoration: const InputDecoration(labelText: 'Categoria'),
                ),
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Data do pagamento'),
                  subtitle: Text(
                    '${date.day.toString().padLeft(2, '0')}/${date.month.toString().padLeft(2, '0')}/${date.year}',
                  ),
                  trailing: const Icon(Icons.calendar_month),
                  onTap: () async {
                    final d = await showDatePicker(
                      context: c,
                      initialDate: date,
                      firstDate: DateTime(2000),
                      lastDate: DateTime(2100),
                    );
                    if (d != null) set(() => date = d);
                  },
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(c, false),
              child: const Text('Cancelar'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(c, true),
              child: const Text('Marcar como paga'),
            ),
          ],
        ),
      ),
    );
    if (ok != true) return;
    await widget.onSaveTransaction(
      FinancialTransaction(
        id: old.id,
        date:
            '${date.year}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')}',
        description: 'Conta paga • ${meta.description}',
        amount: meta.amount,
        type: 'expense',
        accountId: accountId,
        categoryId: categoryId,
        source: 'manual',
        externalTransactionId: old.externalTransactionId,
      ),
    );
  }

  Future<void> _transactionDialog([
    FinancialTransaction? old,
    ScannedExpenseDraft? scanned,
  ]) async {
    if (old != null &&
        (isPlannedDebtTransaction(old) ||
            old.description.startsWith('Pagamento de dívida •'))) {
      await showDebtCenter(
        context,
        workspaceId: widget.workspace.id,
        api: widget.api,
        accessToken: widget.accessToken,
        accounts: widget.snapshot.accounts,
        transactions: widget.snapshot.transactions,
        onSaveTransaction: widget.onSaveTransaction,
      );
      return;
    }
    final desc = TextEditingController(
      text: old?.description ?? scanned?.description ?? '',
    );
    final amount = TextEditingController(
      text: old != null
          ? brlText(old.amount.abs())
          : (scanned?.amount == null ? '' : brlText(scanned!.amount!)),
    );
    String type = old?.type ?? 'expense';
    bool card = old?.isCard ?? false;
    bool recurring = false;
    int recurringMonths = 12;
    int installmentCount = 1;
    bool customInstallments = false;
    final customInstallmentController = TextEditingController();
    int? accountId = old?.accountId;
    int? cardId = old?.cardId;
    int? categoryId = old?.categoryId;
    DateTime date =
        DateTime.tryParse(old?.date ?? '') ??
        scanned?.dueDate ??
        DateTime.now();
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => StatefulBuilder(
        builder: (c, set) => AlertDialog(
          title: Text(old == null ? 'Novo lançamento' : 'Editar lançamento'),
          content: SizedBox(
            width: 460,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  TextField(
                    controller: desc,
                    decoration: const InputDecoration(labelText: 'Descrição *'),
                  ),
                  const SizedBox(height: 18),
                  TextField(
                    controller: amount,
                    keyboardType: TextInputType.number,
                    inputFormatters: const [BrlMoneyInputFormatter()],
                    decoration: const InputDecoration(labelText: 'Valor *'),
                  ),
                  const SizedBox(height: 18),
                  if (scanned != null)
                    Container(
                      width: double.infinity,
                      margin: const EdgeInsets.only(top: 8, bottom: 4),
                      padding: const EdgeInsets.all(10),
                      decoration: BoxDecoration(
                        color: Theme.of(c).colorScheme.surfaceContainerHighest,
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: Text(
                        scanned.kind == 'pix'
                            ? 'Dados preenchidos a partir do QR Code Pix. Confira antes de salvar.'
                            : 'Dados preenchidos a partir do boleto/código. Confira antes de salvar.',
                        style: Theme.of(c).textTheme.bodySmall,
                      ),
                    ),
                  if (!card) const SizedBox(height: 18),
                  if (!card)
                    DropdownButtonFormField<String>(
                      initialValue: type,
                      items: const [
                        DropdownMenuItem(
                          value: 'expense',
                          child: Text('Saída'),
                        ),
                        DropdownMenuItem(
                          value: 'income',
                          child: Text('Entrada'),
                        ),
                      ],
                      onChanged: (v) => set(() => type = v!),
                      decoration: const InputDecoration(labelText: 'Tipo'),
                    ),
                  const SizedBox(height: 18),
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Data'),
                    subtitle: Text(
                      '${date.day.toString().padLeft(2, '0')}/${date.month.toString().padLeft(2, '0')}/${date.year}',
                    ),
                    trailing: const Icon(Icons.calendar_month),
                    onTap: () async {
                      final d = await showDatePicker(
                        context: c,
                        initialDate: date,
                        firstDate: DateTime(2000),
                        lastDate: DateTime(2100),
                      );
                      if (d != null) set(() => date = d);
                    },
                  ),
                  const SizedBox(height: 16),
                  if (old == null && type == 'expense') ...[
                    SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      value: recurring,
                      onChanged: (v) => set(() { recurring = v; if (v && card) { installmentCount = 1; customInstallments = false; customInstallmentController.clear(); } }),
                      title: Text(card ? 'Despesa recorrente no cartão' : 'Despesa recorrente'),
                      subtitle: Text(card ? 'Cria uma nova compra mensal neste cartão' : 'Repete este lançamento mensalmente'),
                    ),
                    if (recurring) ...[
                      Text('Repetir por $recurringMonths meses', style: const TextStyle(fontWeight: FontWeight.w700)),
                      Slider(
                        value: recurringMonths.toDouble(),
                        min: 2,
                        max: 60,
                        divisions: 58,
                        label: '$recurringMonths',
                        onChanged: (v) => set(() => recurringMonths = v.round()),
                      ),
                    ],
                    const SizedBox(height: 8),
                  ],
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    value: card,
                    onChanged: (v) => set(() {
                      card = v;
                      if (v) {
                        type = 'expense';
                        cardId = null;
                        if (recurring) {
                          installmentCount = 1;
                          customInstallments = false;
                          customInstallmentController.clear();
                        }
                      } else {
                        cardId = null;
                        installmentCount = 1;
                        customInstallments = false;
                      }
                    }),
                    title: const Text('Compra no cartão'),
                  ),
                  const SizedBox(height: 18),
                  DropdownButtonFormField<int>(
                    initialValue:
                        widget.snapshot.accounts.any((e) => e.id == accountId)
                        ? accountId
                        : null,
                    items: widget.snapshot.accounts
                        .map(
                          (e) => DropdownMenuItem(
                            value: e.id,
                            child: Text(e.institutionName),
                          ),
                        )
                        .toList(),
                    onChanged: (v) => set(() {
                      accountId = v;
                      if (cardId != null) {
                        final selected = widget.snapshot.cards
                            .where((e) => e.id == cardId)
                            .firstOrNull;
                        final bank = widget.snapshot.accounts
                            .where((a) => a.id == v)
                            .map((a) => a.institutionName)
                            .firstOrNull;
                        if (selected != null &&
                            (bank == null ||
                                _bankKey(selected.bankName) != _bankKey(bank)))
                          cardId = null;
                      }
                    }),
                    decoration: InputDecoration(
                      labelText: card ? 'Banco do cartão *' : 'Banco/conta',
                    ),
                  ),
                  if (card) const SizedBox(height: 18),
                  if (card)
                    Builder(
                      builder: (context) {
                        final bank = widget.snapshot.accounts
                            .where((a) => a.id == accountId)
                            .map((a) => a.institutionName)
                            .firstOrNull;
                        final available = bank == null
                            ? <CreditCardInfo>[]
                            : widget.snapshot.cards
                                  .where(
                                    (e) =>
                                        e.active &&
                                        _bankKey(e.bankName) == _bankKey(bank),
                                  )
                                  .toList();
                        return DropdownButtonFormField<int>(
                          initialValue: available.any((e) => e.id == cardId)
                              ? cardId
                              : null,
                          items: available
                              .map(
                                (e) => DropdownMenuItem(
                                  value: e.id,
                                  child: Text(
                                    '${e.nickname?.isNotEmpty == true ? e.nickname : e.bankName} • final ${e.lastFour}',
                                  ),
                                ),
                              )
                              .toList(),
                          onChanged: bank == null
                              ? null
                              : (v) => set(() => cardId = v),
                          decoration: InputDecoration(
                            labelText: bank == null
                                ? 'Selecione o banco primeiro'
                                : 'Cartão *',
                          ),
                        );
                      },
                    ),
                  if (card && !recurring) ...[
                    const SizedBox(height: 18),
                    Align(
                      alignment: Alignment.centerLeft,
                      child: Text(
                        installmentCount == 1 ? 'Parcelas: à vista / 1x' : 'Parcelas: ${installmentCount}x',
                        style: const TextStyle(fontWeight: FontWeight.w700),
                      ),
                    ),
                    Slider(
                      value: installmentCount.clamp(1, 48).toDouble(),
                      min: 1,
                      max: 48,
                      divisions: 47,
                      label: '${installmentCount.clamp(1, 48)}x',
                      onChanged: (v) => set(() {
                        customInstallments = false;
                        installmentCount = v.round().clamp(1, 48).toInt();
                      }),
                    ),
                    CheckboxListTile(
                      contentPadding: EdgeInsets.zero,
                      value: customInstallments,
                      onChanged: (v) => set(() {
                        customInstallments = v ?? false;
                        if (customInstallments && installmentCount <= 48) installmentCount = 49;
                        if (!customInstallments) installmentCount = installmentCount.clamp(1, 48).toInt();
                      }),
                      title: const Text('Mais de 48 parcelas'),
                      controlAffinity: ListTileControlAffinity.leading,
                    ),
                    if (customInstallments)
                      TextField(
                        controller: customInstallmentController,
                        keyboardType: TextInputType.number,
                        inputFormatters: [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(3)],
                        onChanged: (v) => set(() => installmentCount = (int.tryParse(v) ?? 49).clamp(49, 360).toInt()),
                        decoration: const InputDecoration(labelText: 'Quantidade de parcelas', hintText: 'Ex.: 60'),
                      ),
                  ],
                  const SizedBox(height: 18),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      Expanded(
                        child: DropdownButtonFormField<int>(
                          initialValue:
                              _categoryCache.any((e) => e.id == categoryId)
                              ? categoryId
                              : null,
                          items: _categoryCache
                              .map(
                                (e) => DropdownMenuItem(
                                  value: e.id,
                                  child: Text(e.name),
                                ),
                              )
                              .toList(),
                          onChanged: (v) => set(() => categoryId = v),
                          decoration: const InputDecoration(
                            labelText: 'Categoria',
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      IconButton.filledTonal(
                        tooltip: 'Nova categoria',
                        onPressed: () async {
                          final id = await _createCategory();
                          if (id != null) set(() => categoryId = id);
                        },
                        icon: const Icon(Icons.add),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(c, false),
              child: const Text('Cancelar'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(c, true),
              child: const Text('Salvar'),
            ),
          ],
        ),
      ),
    );
    if (ok != true || desc.text.trim().isEmpty) return;
    final value = brlValue(amount.text);
    if (value <= 0) return;
    if (card && (accountId == null || cardId == null)) {
      if (mounted)
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Selecione o banco e um cartão desse banco.'),
          ),
        );
      return;
    }
    final baseDate = DateTime(date.year, date.month, date.day);
    if (old == null && recurring && card && type == 'expense') {
      final group = 'card-recurring:${DateTime.now().microsecondsSinceEpoch}';
      for (var i = 0; i < recurringMonths; i++) {
        final d = DateTime(baseDate.year, baseDate.month + i, baseDate.day);
        await widget.onSaveTransaction(
          FinancialTransaction(
            id: _id() + i,
            date: '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}',
            description: desc.text.trim(),
            amount: value,
            type: 'expense',
            categoryId: categoryId,
            cardId: cardId,
            source: 'card_purchase',
            purchaseDate: '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}',
            externalTransactionId: '$group:${i + 1}',
          ),
        );
      }
    } else if (old == null && card && installmentCount > 1) {
      final totalCents = (value * 100).round();
      final baseCents = totalCents ~/ installmentCount;
      final remainder = totalCents % installmentCount;
      final group = 'card-installment:${DateTime.now().microsecondsSinceEpoch}';
      for (var i = 0; i < installmentCount; i++) {
        final cents = baseCents + (i < remainder ? 1 : 0);
        final d = DateTime(baseDate.year, baseDate.month + i, baseDate.day);
        await widget.onSaveTransaction(
          FinancialTransaction(
            id: _id() + i,
            date: '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}',
            description: '${desc.text.trim()} • Parcela ${i + 1}/$installmentCount',
            amount: cents / 100.0,
            type: 'expense',
            categoryId: categoryId,
            cardId: cardId,
            source: 'card_purchase',
            purchaseDate: '${baseDate.year}-${baseDate.month.toString().padLeft(2, '0')}-${baseDate.day.toString().padLeft(2, '0')}',
            externalTransactionId: '$group:${i + 1}',
          ),
        );
      }
    } else if (old == null && recurring && !card && type == 'expense') {
      final group = 'recurring:${DateTime.now().microsecondsSinceEpoch}';
      for (var i = 0; i < recurringMonths; i++) {
        final d = DateTime(baseDate.year, baseDate.month + i, baseDate.day);
        await widget.onSaveTransaction(
          FinancialTransaction(
            id: _id() + i,
            date: '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}',
            description: desc.text.trim(),
            amount: value,
            type: 'expense',
            accountId: accountId,
            categoryId: categoryId,
            source: 'manual',
            externalTransactionId: '$group:${i + 1}',
          ),
        );
      }
    } else {
      await widget.onSaveTransaction(
        FinancialTransaction(
          id: old?.id ?? _id(),
          date: '${date.year}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')}',
          description: desc.text.trim(),
          amount: value,
          type: card ? 'expense' : type,
          accountId: card ? null : accountId,
          categoryId: categoryId,
          cardId: card ? cardId : null,
          source: card ? 'card_purchase' : 'manual',
          purchaseDate: card ? '${baseDate.year}-${baseDate.month.toString().padLeft(2, '0')}-${baseDate.day.toString().padLeft(2, '0')}' : null,
          externalTransactionId: old?.externalTransactionId,
        ),
      );
    }
  }

  Future<void> _accountDialog([FinancialAccount? old]) async {
    final bank = TextEditingController(text: old?.institutionName ?? '');
    final name = TextEditingController(text: old?.accountName ?? '');
    final number = TextEditingController(text: old?.maskedAccount ?? '');
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: Text(old == null ? 'Adicionar conta' : 'Editar conta'),
        content: SizedBox(
          width: 420,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: bank,
                decoration: const InputDecoration(labelText: 'Nome do banco *'),
              ),
              const SizedBox(height: 18),
              TextField(
                controller: name,
                decoration: const InputDecoration(labelText: 'Nome da conta'),
              ),
              const SizedBox(height: 18),
              TextField(
                controller: number,
                decoration: const InputDecoration(
                  labelText: 'Número/agência (opcional)',
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(c, false),
            child: const Text('Cancelar'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(c, true),
            child: const Text('Salvar'),
          ),
        ],
      ),
    );
    if (ok == true && bank.text.trim().isNotEmpty)
      await widget.onSaveAccount(
        FinancialAccount(
          id: old?.id ?? _id(),
          institutionName: bank.text.trim(),
          accountName: name.text.trim(),
          maskedAccount: number.text.trim(),
          currentBalance: old?.currentBalance,
        ),
      );
  }

  Future<void> _cardDialog([CreditCardInfo? old]) async {
    final accounts = widget.snapshot.accounts;
    int? accountId;

    if (old != null) {
      for (final account in accounts) {
        if (account.institutionName == old.bankName) {
          accountId = account.id;
          break;
        }
      }
    } else if (accounts.isNotEmpty) {
      accountId = accounts.first.id;
    }

    final brand = TextEditingController(text: old?.brand ?? '');
    final last = TextEditingController(text: old?.lastFour ?? '');
    final nick = TextEditingController(text: old?.nickname ?? '');

    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => StatefulBuilder(
        builder: (c, set) => AlertDialog(
          title: Text(old == null ? 'Adicionar cartão' : 'Editar cartão'),
          content: SizedBox(
            width: 420,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                DropdownButtonFormField<int>(
                  initialValue: accounts.any((e) => e.id == accountId)
                      ? accountId
                      : null,
                  items: accounts
                      .map(
                        (e) => DropdownMenuItem<int>(
                          value: e.id,
                          child: Text(e.institutionName),
                        ),
                      )
                      .toList(),
                  onChanged: (v) => set(() => accountId = v),
                  decoration: const InputDecoration(
                    labelText: 'Banco emissor *',
                  ),
                ),
                const SizedBox(height: 14),
                DropdownButtonFormField<String>(
                  initialValue:
                      [
                        'Visa',
                        'Mastercard',
                        'Elo',
                        'American Express',
                        'Hipercard',
                      ].contains(brand.text)
                      ? brand.text
                      : null,
                  items:
                      [
                            'Visa',
                            'Mastercard',
                            'Elo',
                            'American Express',
                            'Hipercard',
                          ]
                          .map(
                            (e) => DropdownMenuItem(value: e, child: Text(e)),
                          )
                          .toList(),
                  onChanged: (v) => brand.text = v ?? '',
                  decoration: const InputDecoration(labelText: 'Bandeira *'),
                ),
                const SizedBox(height: 14),
                TextField(
                  controller: last,
                  maxLength: 4,
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(
                    labelText: '4 últimos dígitos *',
                  ),
                ),
                const SizedBox(height: 14),
                TextField(
                  controller: nick,
                  decoration: const InputDecoration(labelText: 'Apelido'),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(c, false),
              child: const Text('Cancelar'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(c, true),
              child: const Text('Salvar'),
            ),
          ],
        ),
      ),
    );

    if (ok == true &&
        accountId != null &&
        brand.text.isNotEmpty &&
        last.text.trim().length == 4) {
      final selectedAccount = accounts.firstWhere((e) => e.id == accountId);
      await widget.onSaveCard(
        CreditCardInfo(
          id: old?.id ?? _id(),
          bankName: selectedAccount.institutionName,
          brand: brand.text,
          lastFour: last.text.trim(),
          nickname: nick.text.trim(),
          creditLimit: old?.creditLimit,
          closingDay: old?.closingDay,
          dueDay: old?.dueDay,
        ),
      );
    }

    brand.dispose();
    last.dispose();
    nick.dispose();
  }
}
