import 'package:shared_preferences/shared_preferences.dart';
import 'models.dart';
class SessionStore {
  static const _access='access_token',_refresh='refresh_token',_workspace='workspace_id',_theme='theme_mode',_guest='guest_mode';
  Future<AuthTokens?> readTokens() async {final p=await SharedPreferences.getInstance();final a=p.getString(_access),r=p.getString(_refresh);if(a==null||r==null)return null;return AuthTokens(accessToken:a,refreshToken:r);}
  Future<void> saveTokens(AuthTokens v) async {final p=await SharedPreferences.getInstance();await p.setString(_access,v.accessToken);await p.setString(_refresh,v.refreshToken);await p.setBool(_guest,false);}
  Future<bool> guestMode() async=>(await SharedPreferences.getInstance()).getBool(_guest)??false;
  Future<void> enterGuest() async {final p=await SharedPreferences.getInstance();await p.remove(_access);await p.remove(_refresh);await p.setBool(_guest,true);}
  Future<void> clear() async {final p=await SharedPreferences.getInstance();await p.remove(_access);await p.remove(_refresh);await p.remove(_workspace);await p.remove(_guest);}
  Future<String?> workspaceId() async=>(await SharedPreferences.getInstance()).getString(_workspace);
  Future<void> saveWorkspace(String id) async=>(await SharedPreferences.getInstance()).setString(_workspace,id);
  Future<String> themeMode() async=>(await SharedPreferences.getInstance()).getString(_theme)??'system';
  Future<void> saveThemeMode(String mode) async=>(await SharedPreferences.getInstance()).setString(_theme,mode);
}
