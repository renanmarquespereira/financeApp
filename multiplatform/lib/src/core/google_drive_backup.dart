import 'dart:convert';
import 'dart:typed_data';
import 'package:flutter/foundation.dart';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import 'local_finance_store.dart';
import 'models.dart';
import 'session_store.dart';

class DriveBackupFile {
  const DriveBackupFile({required this.id,required this.name,this.createdTime});
  final String id;
  final String name;
  final DateTime? createdTime;
}

class GoogleDriveBackupService {
  static const _scope='https://www.googleapis.com/auth/drive.file';
  static const _folderName='FinanceApp Backups';
  static const _prefsInterval='financeapp_backup_interval_days';
  static const _prefsLast='financeapp_backup_last_at';
  static const _prefsAccount='financeapp_backup_google_email';

  GoogleDriveBackupService({GoogleSignIn? signIn})
      : _signIn=signIn??GoogleSignIn(scopes:const['email',_scope]);

  final GoogleSignIn _signIn;

  Future<GoogleSignInAccount?> connect() async {
    var account=await _signIn.signInSilently();
    account??=await _signIn.signIn();
    if(account!=null){
      await _signIn.requestScopes(const[_scope]);
      final p=await SharedPreferences.getInstance();
      await p.setString(_prefsAccount,account.email);
    }
    return account;
  }

  Future<GoogleSignInAccount?> silentAccount()=>_signIn.signInSilently();

  Future<Map<String,String>> _headers(GoogleSignInAccount account) async {
    final h=await account.authHeaders;
    return {...h,'Accept':'application/json'};
  }

  Future<String> _ensureFolder(GoogleSignInAccount account) async {
    final h=await _headers(account);
    final q="mimeType='application/vnd.google-apps.folder' and name='$_folderName' and trashed=false";
    final list=await http.get(Uri.https('www.googleapis.com','/drive/v3/files',{
      'q':q,'spaces':'drive','fields':'files(id,name)','pageSize':'10',
    }),headers:h);
    if(list.statusCode<200||list.statusCode>=300)throw Exception('Google Drive: ${list.body}');
    final files=(jsonDecode(list.body)['files'] as List? ?? const[]);
    if(files.isNotEmpty)return files.first['id'].toString();
    final create=await http.post(
      Uri.parse('https://www.googleapis.com/drive/v3/files?fields=id,name'),
      headers:{...h,'Content-Type':'application/json'},
      body:jsonEncode({'name':_folderName,'mimeType':'application/vnd.google-apps.folder'}),
    );
    if(create.statusCode<200||create.statusCode>=300)throw Exception('Google Drive: ${create.body}');
    return jsonDecode(create.body)['id'].toString();
  }

  Future<List<Workspace>> _localWorkspaces(String currentWorkspaceId) async {
    final prefs=await SharedPreferences.getInstance();
    final ids=<String>{currentWorkspaceId};
    for(final key in prefs.getKeys()){
      const prefix='financeapp_local_workspace_name_';
      if(key.startsWith(prefix))ids.add(key.substring(prefix.length));
    }
    final result=<Workspace>[];
    for(final id in ids){
      final name=prefs.getString('financeapp_local_workspace_name_$id')??(id==currentWorkspaceId?'Meu espaço':'Workspace');
      result.add(Workspace(id:id,name:name,kind:'personal',isDefault:id==currentWorkspaceId&&ids.length==1));
    }
    return result;
  }

  Future<String> _backupJson(String workspaceId,FinancialSnapshot snapshot) async {
    final prefs=await SharedPreferences.getInstance();
    final all=await _localWorkspaces(workspaceId);
    final rows=<Map<String,dynamic>>[];
    for(final w in all){
      final snap=w.id==workspaceId?snapshot:await LocalFinanceStore().read(w.id);
      final forecastRaw=prefs.getString('financeapp_forecast_v3_${w.id}');
      rows.add({
        'workspace':{'id':w.id,'name':w.name,'kind':w.kind,'is_default':w.isDefault,'archived_at':w.archivedAt},
        'snapshot':snap.toJson(),
        'forecast_state':forecastRaw==null?null:jsonDecode(forecastRaw),
      });
    }
    return jsonEncode({
      'format':'financeapp-backup-v3',
      'created_at':DateTime.now().toUtc().toIso8601String(),
      'workspaces':rows,
    });
  }

  Future<DriveBackupFile> upload({required String workspaceId,required FinancialSnapshot snapshot,GoogleSignInAccount? account}) async {
    account??=await connect();
    if(account==null)throw Exception('Conecte uma conta Google para usar o backup.');
    final folder=await _ensureFolder(account);
    final headers=await _headers(account);
    final now=DateTime.now();
    String two(int v)=>v.toString().padLeft(2,'0');
    final name='FinanceApp_Backup_${now.year}-${two(now.month)}-${two(now.day)}_${two(now.hour)}-${two(now.minute)}.json';
    final metadata=await http.post(
      Uri.parse('https://www.googleapis.com/drive/v3/files?fields=id,name,createdTime'),
      headers:{...headers,'Content-Type':'application/json'},
      body:jsonEncode({'name':name,'parents':[folder],'mimeType':'application/json'}),
    );
    if(metadata.statusCode<200||metadata.statusCode>=300)throw Exception('Google Drive: ${metadata.body}');
    final m=Map<String,dynamic>.from(jsonDecode(metadata.body));
    final id=m['id'].toString();
    final content=await _backupJson(workspaceId,snapshot);
    final media=await http.patch(
      Uri.parse('https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media'),
      headers:{...headers,'Content-Type':'application/json; charset=utf-8'},
      body:utf8.encode(content),
    );
    if(media.statusCode<200||media.statusCode>=300)throw Exception('Google Drive: ${media.body}');
    final p=await SharedPreferences.getInstance();
    await p.setInt(_prefsLast,DateTime.now().millisecondsSinceEpoch);
    return DriveBackupFile(id:id,name:name,createdTime:DateTime.tryParse(m['createdTime']?.toString()??''));
  }

  Future<List<DriveBackupFile>> list({GoogleSignInAccount? account}) async {
    account??=await connect();
    if(account==null)return const[];
    final folder=await _ensureFolder(account);
    final h=await _headers(account);
    final q="'$folder' in parents and trashed=false";
    final r=await http.get(Uri.https('www.googleapis.com','/drive/v3/files',{
      'q':q,'orderBy':'createdTime desc','fields':'files(id,name,createdTime)','pageSize':'100',
    }),headers:h);
    if(r.statusCode<200||r.statusCode>=300)throw Exception('Google Drive: ${r.body}');
    return (jsonDecode(r.body)['files'] as List? ?? const[]).map((e){
      final m=Map<String,dynamic>.from(e as Map);
      return DriveBackupFile(id:m['id'].toString(),name:m['name'].toString(),createdTime:DateTime.tryParse(m['createdTime']?.toString()??''));
    }).toList();
  }

  FinancialSnapshot _mergeSnapshots(FinancialSnapshot current,FinancialSnapshot incoming){
    List<T> keepExisting<T,K>(List<T> existing,List<T> backup,K Function(T) key){
      final map=<K,T>{for(final e in existing)key(e):e};
      for(final e in backup){map.putIfAbsent(key(e),()=>e);}
      return map.values.toList();
    }
    return FinancialSnapshot(
      accounts:keepExisting(current.accounts,incoming.accounts,(e)=>e.id),
      transactions:keepExisting(current.transactions,incoming.transactions,(e)=>e.id),
      cards:keepExisting(current.cards,incoming.cards,(e)=>e.id),
      categories:keepExisting(current.categories,incoming.categories,(e)=>e.id),
      budgets:keepExisting(current.budgets,incoming.budgets,(e)=>e.categoryId),
      goals:keepExisting(current.goals,incoming.goals,(e)=>e.id),
      syncedAt:current.syncedAt??incoming.syncedAt,
    );
  }

  Future<FinancialSnapshot> restore(String fileId,{GoogleSignInAccount? account,String mode='replace'}) async {
    account??=await connect();
    if(account==null)throw Exception('Conecte uma conta Google.');
    final h=await _headers(account);
    final r=await http.get(Uri.parse('https://www.googleapis.com/drive/v3/files/$fileId?alt=media'),headers:h);
    if(r.statusCode<200||r.statusCode>=300)throw Exception('Google Drive: ${r.body}');
    final body=Map<String,dynamic>.from(jsonDecode(r.body));
    final format=body['format']?.toString();
    final currentId=(await SessionStore().workspaceId())??'mobile-local';
    final prefs=await SharedPreferences.getInstance();

    if(format=='financeapp-mobile-backup-v2'){
      final incoming=FinancialSnapshot.fromJson(Map<String,dynamic>.from(body['snapshot'] as Map));
      final current=await LocalFinanceStore().read(currentId);
      final result=mode=='merge'?_mergeSnapshots(current,incoming):incoming;
      await LocalFinanceStore().save(currentId,result);
      if(body['forecast_state'] is Map){await prefs.setString('financeapp_forecast_v3_$currentId',jsonEncode(body['forecast_state']));}
      return result;
    }

    if(format!='financeapp-backup-v3')throw Exception('Backup incompatível com esta versão do FinanceApp.');
    final workspaces=(body['workspaces'] as List? ?? const[]);
    if(workspaces.isEmpty)throw Exception('O backup não contém workspaces.');

    final backupIds=<String>{};
    for(final rowRaw in workspaces){
      final row=Map<String,dynamic>.from(rowRaw as Map);
      final wm=Map<String,dynamic>.from(row['workspace'] as Map);
      final id=wm['id'].toString();
      backupIds.add(id);
      final name=(wm['name']??'Workspace').toString();
      await prefs.setString('financeapp_local_workspace_name_$id',name);
      final incoming=FinancialSnapshot.fromJson(Map<String,dynamic>.from(row['snapshot'] as Map));
      final current=await LocalFinanceStore().read(id);
      final result=mode=='merge'?_mergeSnapshots(current,incoming):incoming;
      await LocalFinanceStore().save(id,result);
      if(row['forecast_state'] is Map){await prefs.setString('financeapp_forecast_v3_$id',jsonEncode(row['forecast_state']));}
    }

    if(mode=='replace'){
      final known=await _localWorkspaces(currentId);
      for(final w in known.where((e)=>!backupIds.contains(e.id))){
        await prefs.remove('finance_snapshot_v2_${w.id}');
        await prefs.remove('finance_snapshot_${w.id}');
        await prefs.remove('financeapp_local_workspace_name_${w.id}');
        await prefs.remove('financeapp_forecast_v3_${w.id}');
      }
    }

    var selectedId=currentId;
    if(!backupIds.contains(selectedId)){
      final first=Map<String,dynamic>.from(workspaces.first as Map);
      selectedId=Map<String,dynamic>.from(first['workspace'] as Map)['id'].toString();
      await SessionStore().saveWorkspace(selectedId);
    }
    return LocalFinanceStore().read(selectedId);
  }

  Future<void> delete(String fileId,{GoogleSignInAccount? account}) async {
    account??=await connect();
    if(account==null)throw Exception('Conecte uma conta Google.');
    final h=await _headers(account);
    final r=await http.delete(Uri.parse('https://www.googleapis.com/drive/v3/files/$fileId'),headers:h);
    if(r.statusCode!=204&&r.statusCode!=200)throw Exception('Google Drive: ${r.body}');
  }

  static Future<int> intervalDays() async=>(await SharedPreferences.getInstance()).getInt(_prefsInterval)??0;
  static Future<void> setIntervalDays(int days) async=>(await SharedPreferences.getInstance()).setInt(_prefsInterval,days);
  static Future<int> lastBackupAt() async=>(await SharedPreferences.getInstance()).getInt(_prefsLast)??0;
  static Future<String?> accountEmail() async=>(await SharedPreferences.getInstance()).getString(_prefsAccount);

  static Future<void> runScheduledIfDue() async {
    if(kIsWeb||defaultTargetPlatform!=TargetPlatform.iOS)return;
    final days=await intervalDays();
    if(days<=0)return;
    final last=await lastBackupAt();
    if(DateTime.now().millisecondsSinceEpoch-last<days*86400000)return;
    final workspaceId=await SessionStore().workspaceId();
    if(workspaceId==null)return;
    final account=await GoogleDriveBackupService().silentAccount();
    if(account==null)return;
    final snapshot=await LocalFinanceStore().read(workspaceId);
    await GoogleDriveBackupService().upload(workspaceId:workspaceId,snapshot:snapshot,account:account);
  }
}
