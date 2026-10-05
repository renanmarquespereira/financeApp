import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter/foundation.dart';
import 'api_client.dart';

String forecastId(dynamic value) => value is num ? value.toInt().toString() : value.toString().replaceFirst(RegExp(r'\.0$'), '');

/// Each record is a saved snapshot. Deletion wins; only new IDs are uploaded.
/// Local mutations are durable before I/O. A late reply is merged with fresh disk state.
class ForecastStore {
  ForecastStore(this.workspaceId);
  final String workspaceId;
  String get key => 'financeapp_forecast_v3_$workspaceId';
  static final Map<String, Future<void>> _writes = {};
  static final Map<String, Future<void>> _syncs = {};
  static const collections = {'scenarios': 'deletedScenarioIds', 'aiPlans': 'deletedAiPlanIds', 'debts': 'deletedDebtIds'};

  Future<void> _atomic(Future<void> Function() action) async {
    final previous = _writes[key] ?? Future<void>.value();
    final next = previous.catchError((Object _) {}).then((_) => action());
    _writes[key] = next;
    try { await next; } finally { if (identical(_writes[key], next)) _writes.remove(key); }
  }

  Future<Map<String, dynamic>> read() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(key);
    if (raw != null) return Map<String,dynamic>.from(jsonDecode(raw) as Map);
    // Import all workspace-scoped snapshots, including pending legacy deletions.
    // Missing pending-upsert flags must never discard a locally saved snapshot.
    final suffix=workspaceId=='guest-local'?'local':workspaceId;
    List<dynamic> legacy(String prefix) {
      final text=prefs.getString('$prefix$workspaceId')??prefs.getString('$prefix$suffix');
      if(text==null)return [];
      return List<dynamic>.from(jsonDecode(text) as List);
    }
    final previous={
      'scenarios':legacy('financeapp_saved_forecast_scenarios_v2_'),
      'deletedScenarioIds':[
        ...legacy('financeapp_forecast_deleted_scenarios_v1_'),
        ...legacy('financeapp_pending_forecast_deletes_v1_')],
      'aiPlans':legacy('financeapp_saved_ai_plans_v2_'),
      'deletedAiPlanIds':[
        ...legacy('financeapp_deleted_ai_plans_v1_'),
        ...legacy('financeapp_pending_ai_plan_deletes_v1_')],
      'debts': const <dynamic>[],
      'deletedDebtIds': const <dynamic>[],
    };
    return merge(previous,{});
  }

  static Map<String,dynamic> merge(Map<String,dynamic> local, Map<String,dynamic> remote) {
    final result = <String,dynamic>{};
    for (final entry in collections.entries) {
      final deleted = <String>{for(final p in [local,remote]) for(final id in (p[entry.value] as List? ?? [])) forecastId(id)};
      final items = <String,dynamic>{};
      for (final p in [local,remote]) {
        for (final item in (p[entry.key] as List? ?? [])) {
          if (item is Map && item['id'] != null && !deleted.contains(forecastId(item['id']))) items[forecastId(item['id'])] = item;
        }
      }
      result[entry.key] = items.values.toList();
      result[entry.value] = deleted.toList();
    }
    return result;
  }

  Future<void> update(String collection, List<Map<String,dynamic>> items, Iterable<int> deleted) => _atomic(() async {
    final local = await read();
    final delta = {collection:items, collections[collection]!:deleted.toList()};
    final next = merge(local,delta);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(key,jsonEncode(next));
  });

  Future<void> sync(ApiClient api, String token) async {
    if(!kIsWeb && defaultTargetPlatform==TargetPlatform.iOS) return;
    final previous = _syncs[key] ?? Future<void>.value();
    final next = previous.catchError((Object _) {}).then((_) async {
      final response = await api.forecastState(token,workspaceId);
      if (response['payload'] is! Map) throw Exception('Resposta de cenários inválida.');
      if (response['sync_version'] != 2) throw Exception('Atualize o backend para sincronizar cenários e planos com segurança.');
      var remote = Map<String,dynamic>.from(response['payload'] as Map);
      final local = await read();
      final delta = <String,dynamic>{};
      for (final entry in collections.entries) {
        final known = {for(final e in (remote[entry.key] as List? ?? [])) forecastId(e['id'])};
        final removed = {for(final id in (remote[entry.value] as List? ?? [])) forecastId(id)};
        final added = (local[entry.key] as List? ?? []).where((e)=>!known.contains(forecastId(e['id']))&&!removed.contains(forecastId(e['id']))).toList();
        final deleted = (local[entry.value] as List? ?? []).where((id)=>!removed.contains(forecastId(id))).toList();
        if(added.isNotEmpty) delta[entry.key]=added;
        if(deleted.isNotEmpty) delta[entry.value]=deleted;
      }
      if(delta.isNotEmpty) {
        final saved = await api.saveForecastState(token,workspaceId,delta);
        if(saved['payload'] is! Map) throw Exception('O servidor não confirmou o salvamento.');
        remote=Map<String,dynamic>.from(saved['payload'] as Map);
      }
      await _atomic(() async {
        final fresh = await read();
        await (await SharedPreferences.getInstance()).setString(key,jsonEncode(merge(fresh,remote)));
      });
    });
    _syncs[key] = next;
    try { await next; } finally { if(identical(_syncs[key],next)) _syncs.remove(key); }
  }
}
