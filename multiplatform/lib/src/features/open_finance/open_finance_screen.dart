import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';
import '../../core/api_client.dart';

class OpenFinanceScreen extends StatefulWidget {
  const OpenFinanceScreen({super.key, required this.api, required this.token, required this.workspaceId, required this.onRefreshFinance});
  final ApiClient api;
  final String token;
  final String workspaceId;
  final Future<void> Function() onRefreshFinance;

  @override
  State<OpenFinanceScreen> createState() => _OpenFinanceScreenState();
}

class _OpenFinanceScreenState extends State<OpenFinanceScreen> {
  bool loading = true;
  bool busy = false;
  String? error;
  List<Map<String, dynamic>> connections = const [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() { loading = true; error = null; });
    try {
      connections = await widget.api.openFinanceConnections(widget.token, widget.workspaceId);
    } catch (e) {
      error = e.toString().replaceFirst('Exception: ', '');
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> _run(Future<void> Function() action) async {
    if (busy) return;
    setState(() { busy = true; error = null; });
    try {
      await action();
    } catch (e) {
      error = e.toString().replaceFirst('Exception: ', '');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> _mockConnect() => _run(() async {
    await widget.api.openFinanceMockConnect(widget.token, widget.workspaceId);
    await _load();
    await widget.onRefreshFinance();
  });

  Future<void> _belvo() => _run(() async {
    final result = await widget.api.openFinanceWidgetToken(widget.token, widget.workspaceId);
    final raw = result['hosted_widget_url']?.toString();
    if (raw == null || raw.isEmpty) throw Exception('O backend não retornou a URL segura da Belvo.');
    final uri = Uri.parse(raw);
    if (!await launchUrl(uri, mode: LaunchMode.externalApplication)) {
      throw Exception('Não foi possível abrir o consentimento Open Finance.');
    }
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Conclua o consentimento no navegador e depois toque em Atualizar.')));
    }
  });

  Future<void> _sync(int id) => _run(() async {
    final result = await widget.api.openFinanceSync(widget.token, widget.workspaceId, id);
    final message = result['message']?.toString() ?? 'Sincronização solicitada.';
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
    await widget.onRefreshFinance();
    await _load();
  });

  Future<void> _disconnect(int id, String name) async {
    final ok = await showDialog<bool>(context: context, builder: (c) => AlertDialog(
      title: const Text('Desconectar banco?'),
      content: Text('A conexão com $name será removida. As transações já importadas não serão apagadas automaticamente.'),
      actions: [TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Cancelar')), FilledButton(onPressed: () => Navigator.pop(c, true), child: const Text('Desconectar'))],
    ));
    if (ok != true) return;
    await _run(() async { await widget.api.openFinanceDisconnect(widget.token, widget.workspaceId, id); await _load(); });
  }

  Future<void> _logs(int id, String name) async {
    await _run(() async {
      final logs = await widget.api.openFinanceLogs(widget.token, widget.workspaceId, id);
      if (!mounted) return;
      await showDialog<void>(context: context, builder: (c) => AlertDialog(
        title: Text('Sincronizações - $name'),
        content: SizedBox(width: 560, height: 420, child: logs.isEmpty
          ? const Center(child: Text('Nenhum histórico de sincronização.'))
          : ListView.separated(itemCount: logs.length, separatorBuilder: (_, __) => const Divider(), itemBuilder: (_, i) {
              final x = logs[i];
              return ListTile(
                leading: Icon(x['status'] == 'success' || x['status'] == 'completed' ? Icons.check_circle_outline : Icons.info_outline),
                title: Text('${x['status'] ?? 'sem status'} • ${x['imported_transactions'] ?? 0} importadas'),
                subtitle: Text('${x['message'] ?? ''}\n${x['finished_at'] ?? x['started_at'] ?? ''}'),
              );
            })),
        actions: [TextButton(onPressed: () => Navigator.pop(c), child: const Text('Fechar'))],
      ));
    });
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Open Finance'), actions: [IconButton(tooltip: 'Atualizar', onPressed: busy ? null : _load, icon: const Icon(Icons.refresh))]),
    body: loading ? const Center(child: CircularProgressIndicator()) : ListView(padding: const EdgeInsets.all(16), children: [
      const Card(child: ListTile(leading: Icon(Icons.security), title: Text('Conexão somente para leitura'), subtitle: Text('O FinanceApp importa dados financeiros para organização e análise. Ele não realiza pagamentos pelo Open Finance.'))),
      const SizedBox(height: 8),
      Wrap(spacing: 8, runSpacing: 8, children: [
        FilledButton.icon(onPressed: busy ? null : _belvo, icon: const Icon(Icons.account_balance), label: const Text('Conectar banco')),
        OutlinedButton.icon(onPressed: busy ? null : _mockConnect, icon: const Icon(Icons.science_outlined), label: const Text('Banco de teste')),
      ]),
      if (error != null) ...[const SizedBox(height: 12), Card(child: ListTile(leading: const Icon(Icons.error_outline), title: const Text('Não foi possível concluir'), subtitle: Text(error!)))],
      const SizedBox(height: 18),
      Text('Bancos conectados', style: Theme.of(context).textTheme.titleLarge),
      const SizedBox(height: 8),
      if (connections.isEmpty) const Card(child: ListTile(leading: Icon(Icons.account_balance_outlined), title: Text('Nenhum banco conectado'), subtitle: Text('Use Conectar banco para iniciar o consentimento ou Banco de teste para validar o fluxo.'))),
      ...connections.map((c) {
        final id = (c['id'] as num).toInt();
        final name = (c['institution_name'] ?? c['institution_id'] ?? c['provider'] ?? 'Banco').toString();
        final status = (c['status'] ?? 'pending').toString();
        return Card(child: ListTile(
          leading: const CircleAvatar(child: Icon(Icons.account_balance)),
          title: Text(name),
          subtitle: Text('${c['provider'] ?? ''} • ${_status(status)}'),
          trailing: PopupMenuButton<String>(onSelected: (v) { if (v == 'sync') _sync(id); if (v == 'logs') _logs(id, name); if (v == 'delete') _disconnect(id, name); }, itemBuilder: (_) => const [
            PopupMenuItem(value: 'sync', child: ListTile(leading: Icon(Icons.sync), title: Text('Sincronizar agora'))),
            PopupMenuItem(value: 'logs', child: ListTile(leading: Icon(Icons.history), title: Text('Histórico'))),
            PopupMenuDivider(),
            PopupMenuItem(value: 'delete', child: ListTile(leading: Icon(Icons.link_off), title: Text('Desconectar'))),
          ]),
        ));
      }),
      const SizedBox(height: 12),
      const Text('Depois de uma sincronização, contas e movimentações importadas passam a aparecer junto aos demais dados do workspace. Compras de cartão continuam separadas do saldo consolidado conforme as regras do FinanceApp.'),
    ]),
  );

  String _status(String value) {
    switch (value) {
      case 'active': return 'Ativo';
      case 'pending': return 'Aguardando consentimento';
      case 'error': return 'Com erro';
      case 'paused': return 'Pausado';
      default: return value;
    }
  }
}
