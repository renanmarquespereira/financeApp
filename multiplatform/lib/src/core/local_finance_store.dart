import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';
import 'models.dart';

class LocalFinanceStore {
  static const _guestKey = 'financeapp_guest_snapshot_v2';
  String _key(String workspaceId) => workspaceId == 'guest-local' ? _guestKey : 'finance_snapshot_v2_$workspaceId';
  String _legacyKey(String workspaceId) => 'finance_snapshot_$workspaceId';

  Future<FinancialSnapshot> read(String workspaceId) async {
    final prefs = await SharedPreferences.getInstance();
    var raw = prefs.getString(_key(workspaceId));
    raw ??= prefs.getString(_legacyKey(workspaceId));
    if (raw == null || raw.isEmpty) return FinancialSnapshot.empty();
    try {
      final value = FinancialSnapshot.fromJson(Map<String, dynamic>.from(jsonDecode(raw) as Map));
      await save(workspaceId, value); // migra automaticamente a chave antiga
      return value;
    } catch (_) {
      return FinancialSnapshot.empty();
    }
  }

  Future<void> save(String workspaceId, FinancialSnapshot snapshot) async {
    final prefs = await SharedPreferences.getInstance();
    final ok = await prefs.setString(_key(workspaceId), jsonEncode(snapshot.toJson()));
    if (!ok) throw StateError('Não foi possível persistir os dados locais.');
  }
}
