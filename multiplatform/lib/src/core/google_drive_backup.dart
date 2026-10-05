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

  Future<String> _backupJson(String workspaceId,FinancialSnapshot snapshot) async {
    final prefs=await SharedPreferences.getInstance();
    final forecastRaw=prefs.getString('financeapp_forecast_v3_$workspaceId');
    return jsonEncode({
      'format':'financeapp-mobile-backup-v2',
      'created_at':DateTime.now().toUtc().toIso8601String(),
      'workspace_id':workspaceId,
      'snapshot':snapshot.toJson(),
      'forecast_state':forecastRaw==null?null:jsonDecode(forecastRaw),
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

  Future<FinancialSnapshot> restore(String fileId,{GoogleSignInAccount? account}) async {
    account??=await connect();
    if(account==null)throw Exception('Conecte uma conta Google.');
    final h=await _headers(account);
    final r=await http.get(Uri.parse('https://www.googleapis.com/drive/v3/files/$fileId?alt=media'),headers:h);
    if(r.statusCode<200||r.statusCode>=300)throw Exception('Google Drive: ${r.body}');
    final body=Map<String,dynamic>.from(jsonDecode(r.body));
    if(body['format']!='financeapp-mobile-backup-v2')throw Exception('Backup incompatível com esta versão do FinanceApp.');
    final workspaceId=(await SessionStore().workspaceId())??body['workspace_id']?.toString();
    if(workspaceId==null)throw Exception('Workspace local não identificado.');
    final snapshot=FinancialSnapshot.fromJson(Map<String,dynamic>.from(body['snapshot'] as Map));
    await LocalFinanceStore().save(workspaceId,snapshot);
    if(body['forecast_state'] is Map){
      final p=await SharedPreferences.getInstance();
      await p.setString('financeapp_forecast_v3_$workspaceId',jsonEncode(body['forecast_state']));
    }
    return snapshot;
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
