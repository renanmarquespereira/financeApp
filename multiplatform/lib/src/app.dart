import 'dart:async';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter/foundation.dart';
import 'core/forecast_store.dart';
import 'core/finance_theme.dart';
import 'dart:math';
import 'package:flutter/material.dart';
import 'package:google_sign_in/google_sign_in.dart';
import 'core/api_client.dart';
import 'core/local_finance_store.dart';
import 'core/google_drive_backup.dart';
import 'core/notification_service.dart';
import 'core/models.dart';
import 'core/session_store.dart';
import 'features/auth/login_screen.dart';
import 'features/home/home_screen.dart';
import 'features/onboarding/onboarding_screen.dart';

bool _isPayableLinkedTransaction(FinancialTransaction t)=>t.externalTransactionId?.startsWith('payable:')==true;

class FinanceApp extends StatefulWidget{const FinanceApp({super.key});@override State<FinanceApp> createState()=>_FinanceAppState();}
class _FinanceAppState extends State<FinanceApp> with WidgetsBindingObserver{
  Timer? _forecastTimer;
  bool get _mobileLocalOnly => !kIsWeb && defaultTargetPlatform == TargetPlatform.iOS;
  bool _forecastRunning=false;
  @override void dispose(){_forecastTimer?.cancel();WidgetsBinding.instance.removeObserver(this);super.dispose();}
  @override void didChangeAppLifecycleState(AppLifecycleState state){
    if(state==AppLifecycleState.resumed){
      if(_mobileLocalOnly){GoogleDriveBackupService.runScheduledIfDue();}
      else{_syncSavedCollections();}
    }
  }
  Future<void> _syncSavedCollections() async {
    if(_mobileLocalOnly)return;
    if(_forecastRunning||guest||tokens==null||workspace==null)return;
    if(WidgetsBinding.instance.lifecycleState==AppLifecycleState.paused)return;
    _forecastRunning=true;
    final token=tokens!.accessToken;
    final candidates=List<Workspace>.of(workspaces);
    final current=workspace!.id;
    try {
      final prefs=await SharedPreferences.getInstance();
      for(final w in candidates.where((w)=>w.active)) {
        final cache=ForecastStore(w.id);
        if(w.id!=current&&!prefs.containsKey(cache.key))continue;
        if(tokens?.accessToken!=token||guest)break;
        try{await cache.sync(api,token);}catch(_){/* As telas mostram falhas e mantêm dados locais. */}
      }
    }finally{_forecastRunning=false;}
  }
  Future<void> _syncNotifications() async {
    try { await FinanceNotificationService.sync(snapshot); } catch (_) {}
  }
  final api=ApiClient(),store=SessionStore(),local=LocalFinanceStore(); final Map<int,int> _serverIds={}; AuthTokens? tokens; List<Workspace> workspaces=[]; Workspace? workspace; FinancialSnapshot snapshot=FinancialSnapshot.empty(); bool loading=true,busy=false,serverOk=false,syncing=false,guest=false,onboardingDone=false,showOnboarding=false; String? error; ThemeMode themeMode=ThemeMode.system;
  static const guestWorkspace=Workspace(id:'guest-local',name:'Meu espaço local',kind:'personal',isDefault:true);
  @override
  void initState(){
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _forecastTimer=Timer.periodic(const Duration(seconds:30),(_)=>_syncSavedCollections());
    _bootstrap();
  }

  Future<void> _bootstrap() async{final prefs=await SharedPreferences.getInstance();onboardingDone=prefs.getBool('financeapp_onboarding_v1_done')??false;final mode=await store.themeMode();themeMode=mode=='dark'?ThemeMode.dark:mode=='light'?ThemeMode.light:ThemeMode.system;guest=await store.guestMode();tokens=await store.readTokens();serverOk=await api.health();if(guest){workspace=guestWorkspace;workspaces=[guestWorkspace];await _loadLocal();}else if(tokens!=null){try{if(_mobileLocalOnly){await _loadMobileLocalWorkspace();await _loadLocal();await GoogleDriveBackupService.runScheduledIfDue();}else{await _loadWorkspaces();await _loadLocal();if(serverOk)await _sync();}}catch(_){if(workspace==null){await store.clear();tokens=null;}}}if(mounted)setState(()=>loading=false);}
  Future<void> _login(String email,String password) async=>_authenticate(()=>api.login(email,password));
  Future<void> _loadMobileLocalWorkspace() async {
    final prefs=await SharedPreferences.getInstance();
    final savedId=(await store.workspaceId())??"mobile-local";
    if(!prefs.containsKey("financeapp_local_workspace_name_$savedId")){
      await prefs.setString("financeapp_local_workspace_name_$savedId","Meu espaço");
    }
    final ids=<String>{savedId};
    for(final key in prefs.getKeys()){
      const prefix="financeapp_local_workspace_name_";
      if(key.startsWith(prefix))ids.add(key.substring(prefix.length));
    }
    final rows=ids.map((id)=>Workspace(
      id:id,
      name:prefs.getString("financeapp_local_workspace_name_$id")??"Workspace",
      kind:"personal",
      isDefault:id==savedId&&ids.length==1,
    )).toList();
    workspaces=rows;
    workspace=rows.where((w)=>w.id==savedId).firstOrNull??rows.first;
    await store.saveWorkspace(workspace!.id);
  }
  Future<void> _authenticate(Future<AuthTokens> Function() action) async{setState((){busy=true;error=null;});try{tokens=await action();await store.saveTokens(tokens!);guest=false;serverOk=true;if(_mobileLocalOnly){await _loadMobileLocalWorkspace();await _loadLocal();await GoogleDriveBackupService.runScheduledIfDue();}else{await _loadWorkspaces();await _loadLocal();await _sync();}}catch(e){error=e.toString().replaceFirst('Exception: ','');}finally{if(mounted)setState(()=>busy=false);}}
  Future<void> _google() async{const clientId=String.fromEnvironment('GOOGLE_CLIENT_ID',defaultValue:'152655487934-rps2j6acpa2btg0cg7itlqrt5vjunrp6.apps.googleusercontent.com');try{final g=GoogleSignIn(clientId:clientId.isEmpty?null:clientId,serverClientId:clientId.isEmpty?null:clientId,scopes:const['email','profile']);final account=await g.signIn();if(account==null)return;final auth=await account.authentication;final id=auth.idToken;if(id==null)throw Exception('Google não retornou um ID token. Configure GOOGLE_CLIENT_ID para Web/iOS.');await _authenticate(()=>api.googleLogin(id));}catch(e){if(mounted)setState(()=>error='Não foi possível entrar com Google: ${e.toString().replaceFirst('Exception: ','')}');}}
  Future<String> _register({required String name,required String cpf,required String email,required String birthDate,required String sex,required String password})=>api.register(name:name,cpf:cpf,email:email,birthDate:birthDate,sex:sex,password:password);
  Future<String> _forgot(String email)=>api.forgotPassword(email); Future<String> _reset(String email,String code,String password)=>api.resetPassword(email,code,password);
  Future<void> _guest() async{await store.enterGuest();guest=true;tokens=null;workspace=guestWorkspace;workspaces=[guestWorkspace];await _loadLocal();if(mounted)setState((){});}
  Future<void> _loadWorkspaces() async {
    workspaces = await api.workspaces(tokens!.accessToken);
    final active = workspaces.where((w) => w.active).toList();
    if (active.isEmpty) throw Exception('Nenhum Workspace ativo.');

    final savedId = await store.workspaceId();

    // O workspace salvo e a fonte de verdade. Depois que a migracao encontrou
    // o workspace UUID com os dados do Android, nao voltamos a sondar default-1
    // em toda inicializacao. Isso evita alternancia de snapshots na interface.
    final savedWorkspace = savedId == null
        ? null
        : active.where((w) => w.id == savedId).firstOrNull;

    if (savedWorkspace != null) {
      workspace = savedWorkspace;
      return;
    }

    // Compatibilidade apenas para a primeira execucao apos a migracao: se ainda
    // nao existe workspace salvo, procura uma unica vez aquele que contem dados.
    Workspace? bestWorkspace;
    FinancialSnapshot? bestSnapshot;
    var bestScore = -1;

    for (final candidate in active) {
      try {
        final candidateSnapshot = await api.financialSnapshot(
          tokens!.accessToken,
          candidate.id,
        );
        final score = candidateSnapshot.accounts.length +
            candidateSnapshot.transactions.length +
            candidateSnapshot.categories.length +
            candidateSnapshot.cards.length +
            candidateSnapshot.budgets.length +
            candidateSnapshot.goals.length;
        if (score > bestScore) {
          bestScore = score;
          bestWorkspace = candidate;
          bestSnapshot = candidateSnapshot;
        }
      } catch (_) {
        // Continua procurando entre os workspaces ativos.
      }
    }

    bestWorkspace ??=
        active.where((w) => w.isDefault).firstOrNull ?? active.first;
    workspace = bestWorkspace;
    await store.saveWorkspace(workspace!.id);

    if (bestSnapshot != null) {
      snapshot = bestSnapshot;
      await local.save(workspace!.id, bestSnapshot);
    }
  }
  Future<void> _loadLocal() async {
    if (workspace == null) return;
    // No Web autenticado, o servidor e sempre a fonte de verdade. Nunca recarrega
    // um snapshot financeiro antigo do SharedPreferences/LocalStorage do navegador.
    // Guest continua local; iOS/desktop ainda podem usar cache quando realmente offline.
    if (!_mobileLocalOnly && !guest && tokens != null && (kIsWeb || serverOk)) {
      snapshot = FinancialSnapshot.empty();
    } else {
      snapshot = await local.read(workspace!.id);
    }
    if (mounted) setState(() {});
    await _syncNotifications();
  }
  Future<void> _sync() async {
    if (_mobileLocalOnly) return;
    if (guest || tokens == null || workspace == null || syncing) return;

    final requestedWorkspaceId = workspace!.id;
    final forecastToken = tokens!.accessToken;
    if (mounted) setState(() => syncing = true);

    try {
      try { await ForecastStore(requestedWorkspaceId).sync(api,forecastToken); } catch (_) { /* Previsão/IA exibem a falha; preservar sync dos demais dados. */ }
      // Sincronizacao manual: recarrega tambem os workspaces do servidor.
      // Assim mudancas feitas no Android aparecem sem depender de F5.
      final freshWorkspaces = await api.workspaces(tokens!.accessToken);
      final currentRemote = freshWorkspaces.where(
        (w) => w.id == requestedWorkspaceId && w.active,
      ).firstOrNull;
      if (currentRemote == null) {
        final fallback = freshWorkspaces.where((w) => w.active).firstOrNull;
        if (fallback == null) throw Exception('Nenhum workspace ativo disponível.');
        workspaces = freshWorkspaces;
        workspace = fallback;
        await store.saveWorkspace(fallback.id);
        final freshFallback = await api.financialSnapshot(tokens!.accessToken, fallback.id);
        snapshot = freshFallback;
        await local.save(fallback.id, freshFallback);
        serverOk = true;
        if (mounted) setState(() => syncing = false); else syncing = false;
        return;
      }
      workspaces = freshWorkspaces;
      workspace = currentRemote;

      // Contas a pagar: a versão local vence em uma reconexão. O identificador
      // payable:<id> torna POST/PATCH reconciliável entre Android/Web/iOS.
      final localPayables=snapshot.transactions.where(_isPayableLinkedTransaction).toList();
      late FinancialSnapshot fresh;
      if(localPayables.isEmpty){
        // Caminho comum: um unico snapshot basta. Antes eram dois GETs iguais.
        fresh=await api.financialSnapshot(tokens!.accessToken,requestedWorkspaceId);
      }else{
        final preFresh=await api.financialSnapshot(tokens!.accessToken,requestedWorkspaceId);
        for(final localPayable in localPayables){
          final ext=localPayable.externalTransactionId;
          final remote=preFresh.transactions.where((t)=>t.externalTransactionId==ext).firstOrNull;
          try{
            final candidate=FinancialTransaction(id:remote?.id??localPayable.id,date:localPayable.date,description:localPayable.description,amount:localPayable.amount,type:localPayable.type,accountId:_serverId(localPayable.accountId),categoryId:_serverId(localPayable.categoryId),cardId:_serverId(localPayable.cardId),source:localPayable.source,purchaseDate:localPayable.purchaseDate,externalTransactionId:ext);
            final saved=await api.saveTransaction(tokens!.accessToken,requestedWorkspaceId,candidate,exists:remote!=null);
            _serverIds[localPayable.id]=saved.id;
          }catch(_){}
        }
        final remoteAfterPayables=await api.financialSnapshot(tokens!.accessToken,requestedWorkspaceId);
        final remotePayableIds=remoteAfterPayables.transactions.map((t)=>t.externalTransactionId).whereType<String>().toSet();
        final unsyncedLocalPayables=localPayables.where((t)=>t.externalTransactionId!=null&&!remotePayableIds.contains(t.externalTransactionId)).toList();
        fresh=unsyncedLocalPayables.isEmpty?remoteAfterPayables:remoteAfterPayables.copyWith(transactions:[...unsyncedLocalPayables,...remoteAfterPayables.transactions]);
      }

      // Se o usuario trocou de workspace enquanto a requisicao estava em voo,
      // nao deixa a resposta antiga substituir a tela atual.
      if (workspace?.id != requestedWorkspaceId) {
        if (mounted) {
          setState(() => syncing = false);
        } else {
          syncing = false;
        }
        return;
      }

      // Publica o snapshot na arvore Flutter imediatamente. O cache local e
      // persistido depois; SharedPreferences nunca deve bloquear a atualizacao
      // visual dos dados que acabaram de chegar do servidor.
      if (mounted) {
        setState(() {
          snapshot = fresh;
          serverOk = true;
          syncing = false;
        });
      } else {
        snapshot = fresh;
        serverOk = true;
        syncing = false;
      }

      await local.save(requestedWorkspaceId, fresh);
      await _syncNotifications();
    } catch (_) {
      serverOk = false;

      // Web autenticado nao pode ressuscitar transacoes excluidas a partir do
      // cache do navegador quando uma requisicao falha. Mantem a tela atual e
      // aguarda a proxima sincronizacao com o servidor.
      if (!kIsWeb &&
          snapshot.accounts.isEmpty &&
          snapshot.transactions.isEmpty &&
          snapshot.categories.isEmpty &&
          snapshot.cards.isEmpty) {
        final cached = await local.read(requestedWorkspaceId);
        if (workspace?.id == requestedWorkspaceId) {
          snapshot = cached;
        }
      }

      if (mounted) {
        setState(() => syncing = false);
      } else {
        syncing = false;
      }
    }
  }
  bool get _canSync => !_mobileLocalOnly && !guest && tokens != null && workspace != null;
  int? _serverId(int? id) => id == null ? null : (_serverIds[id] ?? id);

  Future<void> _saveSnapshot(FinancialSnapshot value) async {
    final previous=snapshot;
    snapshot=value;
    if(workspace!=null)await local.save(workspace!.id,value);
    if(mounted)setState((){});
    await _syncNotifications();
    if(!_canSync)return;
    try{
      for(final budget in value.budgets){
        final categoryId=_serverId(budget.categoryId)!;
        final old=previous.budgets.where((b)=>_serverId(b.categoryId)==categoryId).firstOrNull;
        if(old==null||old.amount!=budget.amount){await api.saveBudget(tokens!.accessToken,workspace!.id,categoryId,budget.amount);}
      }
      for(final old in previous.budgets){
        final categoryId=_serverId(old.categoryId)!;
        if(!value.budgets.any((b)=>_serverId(b.categoryId)==categoryId)){await api.deleteBudget(tokens!.accessToken,workspace!.id,categoryId);}
      }
      for(final goal in value.goals){
        final exists=previous.goals.any((g)=>g.id==goal.id);
        final saved=await api.saveGoal(tokens!.accessToken,workspace!.id,goal,exists:exists);
        if(!exists)_serverIds[goal.id]=saved.id;
      }
      for(final old in previous.goals){
        if(!value.goals.any((g)=>g.id==old.id)){await api.deleteGoal(tokens!.accessToken,workspace!.id,_serverId(old.id)!);}
      }
      await _sync();
    }catch(_){serverOk=false;if(mounted)setState((){});}
  }

  Future<void> _upsertTransaction(FinancialTransaction value) async {
    final exists=snapshot.transactions.any((e)=>e.id==value.id);
    if(_canSync){
      try{
        final normalized=FinancialTransaction(id:_serverId(value.id)!,date:value.date,description:value.description,amount:value.amount,type:value.type,accountId:_serverId(value.accountId),categoryId:_serverId(value.categoryId),cardId:_serverId(value.cardId),source:value.source,purchaseDate:value.purchaseDate,externalTransactionId:value.externalTransactionId);
        final saved=await api.saveTransaction(tokens!.accessToken,workspace!.id,normalized,exists:exists);
        if(!exists)_serverIds[value.id]=saved.id;
        await _sync();return;
      }catch(_){serverOk=false;}
    }
    final list=[...snapshot.transactions];final i=list.indexWhere((e)=>e.id==value.id);if(i>=0){list[i]=value;}else{list.insert(0,value);}await _saveLocalOnly(snapshot.copyWith(transactions:list));
  }

  String _importCategoryKey(String value) => value
      .trim()
      .toLowerCase()
      .replaceAll(RegExp(r'[áàãâä]'), 'a')
      .replaceAll(RegExp(r'[éèêë]'), 'e')
      .replaceAll(RegExp(r'[íìîï]'), 'i')
      .replaceAll(RegExp(r'[óòõôö]'), 'o')
      .replaceAll(RegExp(r'[úùûü]'), 'u')
      .replaceAll('ç', 'c')
      .replaceAll(RegExp(r'\s+'), ' ');

  Future<void> _importTransactions(List<FinancialTransaction> values) async {
    if (values.isEmpty) return;

    // IMPORTANTE: categorias sao sempre resolvidas dentro do workspace atual.
    // Uma categoria com o mesmo nome em outro workspace nao deve ser reutilizada.
    final categoriesByName = <String, FinanceCategory>{
      for (final category in snapshot.categories)
        _importCategoryKey(category.name): category,
    };

    if (_canSync) {
      var savedCount = 0;
      try {
        for (final value in values) {
          int? categoryId = _serverId(value.categoryId);
          final categoryName = value.importCategoryName?.trim();
          if (categoryName != null && categoryName.isNotEmpty) {
            final key = _importCategoryKey(categoryName);
            var category = categoriesByName[key];
            if (category == null) {
              // O endpoint recebe o workspace atual no cabecalho. Portanto a
              // verificacao/criacao fica isolada no workspace selecionado.
              category = await api.saveCategory(
                tokens!.accessToken,
                workspace!.id,
                FinanceCategory(id: -DateTime.now().microsecondsSinceEpoch, name: categoryName),
                exists: false,
              );
              categoriesByName[key] = category;
            }
            categoryId = category.id;
          }

          final normalized = FinancialTransaction(
            id: _serverId(value.id) ?? value.id,
            date: value.date,
            description: value.description,
            amount: value.amount,
            type: value.type,
            accountId: _serverId(value.accountId),
            categoryId: categoryId,
            cardId: _serverId(value.cardId),
            source: value.source,
          );
          final saved = await api.saveTransaction(
            tokens!.accessToken,
            workspace!.id,
            normalized,
            exists: false,
          );
          _serverIds[value.id] = saved.id;
          savedCount++;
        }
        await _sync();
        return;
      } catch (e) {
        serverOk = false;
        if (savedCount > 0) {
          await _sync();
          throw Exception('A importacao foi interrompida apos $savedCount registro(s). Sincronize e tente novamente apenas com os restantes.');
        }
        rethrow;
      }
    }

    // Modo local/visitante: cria a categoria somente no snapshot do workspace
    // que esta aberto e vincula os lancamentos a ela.
    final localCategories = [...snapshot.categories];
    var nextCategoryId = -DateTime.now().microsecondsSinceEpoch;
    final imported = <FinancialTransaction>[];
    for (final value in values) {
      var categoryId = value.categoryId;
      final categoryName = value.importCategoryName?.trim();
      if (categoryName != null && categoryName.isNotEmpty) {
        final key = _importCategoryKey(categoryName);
        var category = categoriesByName[key];
        if (category == null) {
          category = FinanceCategory(id: nextCategoryId--, name: categoryName);
          categoriesByName[key] = category;
          localCategories.add(category);
        }
        categoryId = category.id;
      }
      imported.add(FinancialTransaction(
        id: value.id,
        date: value.date,
        description: value.description,
        amount: value.amount,
        type: value.type,
        accountId: value.accountId,
        categoryId: categoryId,
        cardId: value.cardId,
        source: value.source,
      ));
    }
    await _saveLocalOnly(snapshot.copyWith(
      categories: localCategories,
      transactions: [...imported, ...snapshot.transactions],
    ));
  }

  Future<void> _deleteTransaction(int id) async {
    if(_canSync){try{await api.deleteTransaction(tokens!.accessToken,workspace!.id,_serverId(id)!);await _sync();return;}catch(_){serverOk=false;}}
    await _saveLocalOnly(snapshot.copyWith(transactions:snapshot.transactions.where((e)=>e.id!=id).toList()));
  }

  Future<void> _upsertAccount(FinancialAccount value) async {
    final exists=snapshot.accounts.any((e)=>e.id==value.id);
    if(_canSync){try{final saved=await api.saveAccount(tokens!.accessToken,workspace!.id,value,exists:exists);if(!exists)_serverIds[value.id]=saved.id;await _sync();return;}catch(_){serverOk=false;}}
    final list=[...snapshot.accounts];final i=list.indexWhere((e)=>e.id==value.id);if(i>=0){list[i]=value;}else{list.add(value);}await _saveLocalOnly(snapshot.copyWith(accounts:list));
  }

  Future<void> _deleteAccount(int id) async {
    // A API exige o fluxo próprio de código por e-mail para excluir uma conta.
    // Não removemos apenas localmente quando autenticado, pois isso faria Web e servidor divergirem.
    if(!guest&&tokens!=null&&!_mobileLocalOnly)throw Exception('Para excluir uma conta bancária autenticada, use Gerenciar dados e confirme o código enviado por e-mail.');
    await _saveLocalOnly(snapshot.copyWith(accounts:snapshot.accounts.where((e)=>e.id!=id).toList()));
  }

  Future<void> _upsertCard(CreditCardInfo value) async {
    final exists=snapshot.cards.any((e)=>e.id==value.id);
    if(_canSync){try{final saved=await api.saveCard(tokens!.accessToken,workspace!.id,value,exists:exists);if(!exists)_serverIds[value.id]=saved.id;await _sync();return;}catch(_){serverOk=false;}}
    final list=[...snapshot.cards];final i=list.indexWhere((e)=>e.id==value.id);if(i>=0){list[i]=value;}else{list.add(value);}await _saveLocalOnly(snapshot.copyWith(cards:list));
  }

  Future<void> _deleteCard(int id) async {
    if(_canSync){try{await api.deleteCard(tokens!.accessToken,workspace!.id,_serverId(id)!);await _sync();return;}catch(_){serverOk=false;}}
    await _saveLocalOnly(snapshot.copyWith(cards:snapshot.cards.where((e)=>e.id!=id).toList()));
  }

  Future<void> _upsertCategory(FinanceCategory value) async {
    final exists=snapshot.categories.any((e)=>e.id==value.id);
    if(_canSync){try{final saved=await api.saveCategory(tokens!.accessToken,workspace!.id,value,exists:exists);if(!exists)_serverIds[value.id]=saved.id;await _sync();return;}catch(_){serverOk=false;}}
    final list=[...snapshot.categories];final i=list.indexWhere((e)=>e.id==value.id);if(i>=0){list[i]=value;}else{list.add(value);}await _saveLocalOnly(snapshot.copyWith(categories:list));
  }

  Future<void> _deleteCategory(int id) async {
    if(_canSync){try{await api.deleteCategory(tokens!.accessToken,workspace!.id,_serverId(id)!);await _sync();return;}catch(_){serverOk=false;}}
    await _saveLocalOnly(snapshot.copyWith(categories:snapshot.categories.where((e)=>e.id!=id).toList(),transactions:snapshot.transactions.map((e)=>e.categoryId==id?FinancialTransaction(id:e.id,date:e.date,description:e.description,amount:e.amount,type:e.type,accountId:e.accountId,categoryId:null,cardId:e.cardId,source:e.source):e).toList()));
  }

  Future<void> _saveLocalOnly(FinancialSnapshot value) async {
    snapshot=value;
    if(workspace!=null)await local.save(workspace!.id,value);
    if(mounted)setState((){});
    await _syncNotifications();
  }

  Future<String> _askAi(String question,Map<String,dynamic> summary) async {if(tokens==null||workspace==null)throw Exception('Entre com uma conta para usar a IA.');return api.askFinancialAi(tokens!.accessToken,workspace!.id,question,summary);}
  Future<void> _select(Workspace value) async {
    if(_mobileLocalOnly){
      await store.saveWorkspace(value.id);workspace=value;snapshot=await local.read(value.id);if(mounted)setState((){});return;
    }
    await store.saveWorkspace(value.id);
    setState(() {
      workspace = value;
      // Evita exibir dados do workspace anterior enquanto o servidor responde.
      snapshot = FinancialSnapshot.empty();
    });
    await _sync();
  }
  String _newWorkspaceUuid() {
    final random = Random.secure();
    final bytes = List<int>.generate(16, (_) => random.nextInt(256));
    bytes[6] = (bytes[6] & 0x0f) | 0x40; // UUID v4
    bytes[8] = (bytes[8] & 0x3f) | 0x80; // RFC 4122 variant
    final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
    return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
  }

  Future<void> _createWorkspace(String name, String kind) async {
    if (_mobileLocalOnly) {
      final id=_newWorkspaceUuid();
      final created=Workspace(id:id,name:name.trim(),kind:kind,isDefault:false);
      final prefs=await SharedPreferences.getInstance();
      await prefs.setString("financeapp_local_workspace_name_$id",created.name);
      workspaces=[...workspaces,created];workspace=created;await store.saveWorkspace(id);
      snapshot=FinancialSnapshot.empty();await local.save(id,snapshot);if(mounted)setState((){});return;
    }
    if (tokens == null) throw Exception('Entre com sua conta para criar um workspace.');
    final clientId = _newWorkspaceUuid();
    final created = await api.createWorkspace(tokens!.accessToken,name:name,kind:kind,clientId:clientId);
    workspaces = await api.workspaces(tokens!.accessToken);
    final selected = workspaces.where((w)=>w.id==created.id).firstOrNull ?? created;
    await store.saveWorkspace(selected.id);
    if (mounted) {
      setState(() {
        workspace = selected;
        snapshot = FinancialSnapshot.empty();
        serverOk = true;
      });
    } else {
      workspace = selected;
      snapshot = FinancialSnapshot.empty();
    }
    await _sync();
  }
  Future<void> _reloadWorkspaces() async {
    if(_mobileLocalOnly){if(mounted)setState((){});return;}
    if(tokens==null)return;
    final rows=await api.workspaces(tokens!.accessToken);
    final current=workspace;
    final stillActive=current!=null&&rows.any((w)=>w.id==current.id&&w.active);
    workspaces=rows;
    if(!stillActive){
      workspace=rows.firstWhere((w)=>w.active&&w.isDefault,orElse:()=>rows.firstWhere((w)=>w.active));
      await store.saveWorkspace(workspace!.id);
      snapshot=FinancialSnapshot.empty();
      await _sync();
    }
    if(mounted)setState((){});
  }
  void _theme(ThemeMode value){store.saveThemeMode(value.name);setState(()=>themeMode=value);}
  Future<void> _logout() async{await store.clear();setState((){tokens=null;guest=false;workspace=null;workspaces=[];snapshot=FinancialSnapshot.empty();});}
  Future<void> _finishOnboarding() async{final prefs=await SharedPreferences.getInstance();await prefs.setBool('financeapp_onboarding_v1_done',true);if(mounted)setState((){onboardingDone=true;showOnboarding=false;});}
  void _showOnboarding(){if(mounted)setState(()=>showOnboarding=true);}

  @override Widget build(BuildContext context)=>MaterialApp(debugShowCheckedModeBanner:false,title:'FinanceApp',themeMode:themeMode,theme:financeTheme(Brightness.light),darkTheme:financeTheme(Brightness.dark),home:loading?const Scaffold(body:Center(child:CircularProgressIndicator())):(!onboardingDone||showOnboarding)?OnboardingScreen(onFinish:_finishOnboarding):(tokens==null&&!guest)?LoginScreen(onLogin:_login,onRegister:_register,onForgot:_forgot,onReset:_reset,onGoogle:_google,onGuest:_guest,busy:busy,error:error):HomeScreen(workspace:workspace!,workspaces:workspaces,onWorkspace:_select,onCreateWorkspace:_createWorkspace,onReloadWorkspaces:_reloadWorkspaces,onLogout:_logout,onTheme:_theme,themeMode:themeMode,serverOk:guest?false:serverOk,snapshot:snapshot,syncing:syncing,onSync:_sync,isGuest:guest,onSaveTransaction:_upsertTransaction,onImportTransactions:_importTransactions,onDeleteTransaction:_deleteTransaction,onSaveAccount:_upsertAccount,onDeleteAccount:_deleteAccount,onSaveCard:_upsertCard,onDeleteCard:_deleteCard,onSaveCategory:_upsertCategory,onDeleteCategory:_deleteCategory,onAskAi:guest?null:_askAi,onSaveSnapshot:_saveSnapshot,api:api,accessToken:tokens?.accessToken,onRefreshFinance:_sync,onShowOnboarding:_showOnboarding));
}
extension FirstOrNull<T> on Iterable<T>{T? get firstOrNull=>isEmpty?null:first;}
