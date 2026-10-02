import 'dart:convert';
import 'dart:typed_data';
import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart';
import 'models.dart';

class ApiClient {
  ApiClient({String? baseUrl}) : baseUrl=(baseUrl??const String.fromEnvironment('API_BASE_URL',defaultValue:'http://localhost:8000')).replaceAll(RegExp(r'/+$'),'');
  final String baseUrl;
  String? _activeWorkspaceId;
  Uri _uri(String path)=>Uri.parse('$baseUrl/${path.replaceFirst(RegExp(r'^/+'),'')}');
  Map<String,String> _json()=>{'Content-Type':'application/json'};
  Map<String,String> _auth(String token,{String? workspaceId})=>{'Authorization':'Bearer $token',if(workspaceId!=null)'X-Workspace-Id':workspaceId};
  Future<AuthTokens> login(String email,String password) async {final r=await http.post(_uri('auth/login'),headers:_json(),body:jsonEncode({'email':email.trim(),'password':password}));_ok(r);return AuthTokens.fromJson(jsonDecode(r.body));}
  Future<AuthTokens> googleLogin(String idToken) async {final r=await http.post(_uri('auth/google'),headers:_json(),body:jsonEncode({'id_token':idToken}));_ok(r);return AuthTokens.fromJson(jsonDecode(r.body));}
  Future<String> register({required String name,required String cpf,required String email,required String birthDate,required String sex,required String password}) async {final r=await http.post(_uri('auth/register'),headers:_json(),body:jsonEncode({'name':name.trim(),'cpf':cpf.replaceAll(RegExp(r'\\D'),''),'email':email.trim(),'birth_date':birthDate,'sex':sex,'password':password,'legal_accepted':true,'terms_version':'1.0','privacy_version':'1.0'}));_ok(r);final b=jsonDecode(r.body);return b is Map&&b['message']!=null?b['message'].toString():'Cadastro concluído. Você já pode entrar.';}
  Future<String> forgotPassword(String email) async {final r=await http.post(_uri('auth/forgot-password'),headers:_json(),body:jsonEncode({'email':email.trim()}));_ok(r);final b=jsonDecode(r.body);return b['message']?.toString()??'Confira seu e-mail.';}
  Future<String> resetPassword(String email,String code,String password) async {final r=await http.post(_uri('auth/reset-password'),headers:_json(),body:jsonEncode({'email':email.trim(),'code':code.trim(),'password':password}));_ok(r);final b=jsonDecode(r.body);return b['message']?.toString()??'Senha alterada.';}
  Future<List<Workspace>> workspaces(String token) async {final uri=_uri('workspaces').replace(queryParameters:{'_refresh':DateTime.now().microsecondsSinceEpoch.toString()});final r=await http.get(uri,headers:{..._auth(token),'Cache-Control':'no-cache, no-store, must-revalidate','Pragma':'no-cache'});_ok(r);return (jsonDecode(r.body) as List).map((e)=>Workspace.fromJson(e)).toList();}
  Future<Workspace> createWorkspace(String token,{required String name,required String kind,required String clientId}) async {final r=await http.post(_uri('workspaces'),headers:{..._auth(token),..._json()},body:jsonEncode({'name':name.trim(),'kind':kind,'client_id':clientId}));_ok(r);return Workspace.fromJson(Map<String,dynamic>.from(jsonDecode(r.body)));}
  Future<Workspace> archiveWorkspace(String token,String id) async {final r=await http.post(_uri('workspaces/$id/archive'),headers:_auth(token));_ok(r);return Workspace.fromJson(Map<String,dynamic>.from(jsonDecode(r.body)));}
  Future<Workspace> restoreWorkspace(String token,String id) async {final r=await http.post(_uri('workspaces/$id/restore'),headers:_auth(token));_ok(r);return Workspace.fromJson(Map<String,dynamic>.from(jsonDecode(r.body)));}
  Future<void> requestWorkspaceBatchDelete(String token,List<String> ids) async {final r=await http.post(_uri('workspaces/batch-delete-code'),headers:{..._auth(token),..._json()},body:jsonEncode({'workspace_ids':ids}));_ok(r);}
  Future<void> confirmWorkspaceBatchDelete(String token,List<String> ids,String code) async {final uri=_uri('workspaces/batch-confirm-delete').replace(queryParameters:{'code':code});final r=await http.post(uri,headers:{..._auth(token),..._json()},body:jsonEncode({'workspace_ids':ids}));_ok(r);}
  Future<FinancialSnapshot> financialSnapshot(String token, String workspaceId) async {
    _activeWorkspaceId = workspaceId;
    final headers = _auth(token, workspaceId: workspaceId);

    // O snapshot principal e a fonte de verdade da tela. Ele e convertido
    // imediatamente, antes de consultar recursos auxiliares. Assim, um cartao,
    // orcamento ou meta com payload inesperado nunca descarta contas,
    // transacoes e categorias que chegaram corretamente do servidor.
    final requestId = DateTime.now().microsecondsSinceEpoch.toString();
    final freshHeaders = <String, String>{
      ...headers,
      'Cache-Control': 'no-cache, no-store, must-revalidate',
      'Pragma': 'no-cache',
    };
    final snapshotUri = _uri('sync/snapshot').replace(
      queryParameters: {'_refresh': requestId},
    );
    final coreResponse = await http.get(snapshotUri, headers: freshHeaders);
    _ok(coreResponse);
    final coreBody = Map<String, dynamic>.from(jsonDecode(coreResponse.body));
    var result = FinancialSnapshot.fromJson(coreBody);

    Future<List<dynamic>> optionalList(String path) async {
      try {
        final uri = _uri(path).replace(
          queryParameters: {'_refresh': requestId},
        );
        final response = await http.get(uri, headers: freshHeaders);
        if (response.statusCode < 200 || response.statusCode >= 300) {
          return const <dynamic>[];
        }
        final decoded = jsonDecode(response.body);
        return decoded is List ? decoded : const <dynamic>[];
      } catch (_) {
        return const <dynamic>[];
      }
    }

    final auxiliary = await Future.wait<List<dynamic>>([
      optionalList('cards'),
      optionalList('budgets'),
      optionalList('goals'),
    ]);

    final cards = <CreditCardInfo>[];
    for (final raw in auxiliary[0]) {
      try {
        cards.add(CreditCardInfo.fromJson(Map<String, dynamic>.from(raw as Map)));
      } catch (_) {
        // Um item invalido nao pode impedir a exibicao do snapshot principal.
      }
    }

    final budgets = <CategoryBudget>[];
    for (final raw in auxiliary[1]) {
      try {
        budgets.add(CategoryBudget.fromJson(Map<String, dynamic>.from(raw as Map)));
      } catch (_) {
        // Mantem os demais dados validos.
      }
    }

    final goals = <FinancialGoal>[];
    for (final raw in auxiliary[2]) {
      try {
        goals.add(FinancialGoal.fromJson(Map<String, dynamic>.from(raw as Map)));
      } catch (_) {
        // Mantem os demais dados validos.
      }
    }

    result = result.copyWith(
      cards: cards,
      budgets: budgets,
      goals: goals,
      syncedAt: DateTime.now().toIso8601String(),
    );
    return result;
  }


  Future<Map<String,dynamic>> forecastState(String token,String workspaceId) async {
    http.Response? last;
    for (final path in const ['sync/forecast-state','forecast-state']) {
      final uri=_uri(path).replace(queryParameters:{'_refresh':DateTime.now().microsecondsSinceEpoch.toString()});
      final r=await http.get(uri,headers:{..._auth(token,workspaceId:workspaceId),'Cache-Control':'no-cache, no-store, must-revalidate','Pragma':'no-cache'}).timeout(const Duration(seconds:20));
      last=r;
      if(r.statusCode==404) continue;
      _ok(r);
      return Map<String,dynamic>.from(jsonDecode(r.body) as Map);
    }
    throw Exception('Sincronização de cenários/planos indisponível: GET /sync/forecast-state e /forecast-state retornaram ${last?.statusCode ?? 'sem resposta'}. Reinicie/atualize o backend.');
  }

  Future<Map<String,dynamic>> saveForecastState(String token,String workspaceId,Map<String,dynamic> payload) async {
    http.Response? last;
    for (final path in const ['sync/forecast-state','forecast-state']) {
      final r=await http.put(_uri(path),headers:{..._auth(token,workspaceId:workspaceId),..._json()},body:jsonEncode({'payload':payload})).timeout(const Duration(seconds:20));
      last=r;
      if(r.statusCode==404) continue;
      _ok(r);
      return Map<String,dynamic>.from(jsonDecode(r.body) as Map);
    }
    throw Exception('Sincronização de cenários/planos indisponível: PUT /sync/forecast-state e /forecast-state retornaram ${last?.statusCode ?? 'sem resposta'}. Reinicie/atualize o backend.');
  }

  Future<FinancialTransaction> saveTransaction(String token,String workspaceId,FinancialTransaction value,{required bool exists}) async {
    final body={
      'account_id':value.accountId,
      'category_id':value.categoryId,
      'card_id':value.cardId,
      'date':value.date.contains('T')?value.date:'${value.date}T00:00:00',
      'description':value.description,
      'amount':value.amount.abs(),
      'transaction_type':value.type,
      'status':'posted',
      'source':value.source=='open_finance'?'manual':value.source,
      'purchase_date':value.purchaseDate,
      'external_transaction_id':value.externalTransactionId,
    };
    final headers={..._auth(token,workspaceId:workspaceId),..._json()};
    final r=exists
        ? await http.patch(_uri('transactions/${value.id}'),headers:headers,body:jsonEncode(body))
        : await http.post(_uri('transactions'),headers:headers,body:jsonEncode(body));
    _ok(r);
    final decoded=Map<String,dynamic>.from(jsonDecode(r.body));
    // FastAPI/Decimal pode serializar valores monetarios como String (ex.: "50.00").
    // O snapshot ja devolve double, mas a resposta imediata do POST/PATCH tambem
    // precisa ser normalizada para a importacao nao parar no primeiro registro.
    final rawAmount=decoded['amount'];
    if(rawAmount is String){decoded['amount']=double.tryParse(rawAmount.replaceAll(',','.'))??0.0;}
    return FinancialTransaction.fromJson(decoded);
  }

  Future<List<Map<String,dynamic>>> transactionAttachments(String token,String workspaceId,int transactionId) async {
    final r=await http.get(_uri('transactions/$transactionId/attachments'),headers:_auth(token,workspaceId:workspaceId));
    _ok(r);
    return (jsonDecode(r.body) as List).map((e)=>Map<String,dynamic>.from(e)).toList();
  }

  Future<Map<String,dynamic>> uploadTransactionAttachment(String token,String workspaceId,int transactionId,String name,List<int> bytes,String contentType) async {
    final request=http.MultipartRequest('POST',_uri('transactions/$transactionId/attachments'));
    request.headers.addAll(_auth(token,workspaceId:workspaceId));
    request.files.add(http.MultipartFile.fromBytes('file',bytes,filename:name,contentType:MediaType.parse(contentType)));
    final streamed=await request.send();
    final body=await streamed.stream.bytesToString();
    if(streamed.statusCode<200||streamed.statusCode>=300)throw Exception(body);
    return Map<String,dynamic>.from(jsonDecode(body));
  }

  Future<Uint8List> downloadTransactionAttachment(String token,String workspaceId,int transactionId,int attachmentId) async {
    final r=await http.get(_uri('transactions/$transactionId/attachments/$attachmentId/download'),headers:_auth(token,workspaceId:workspaceId));
    _ok(r); return r.bodyBytes;
  }

  Future<void> deleteTransactionAttachment(String token,String workspaceId,int transactionId,int attachmentId) async {
    final r=await http.delete(_uri('transactions/$transactionId/attachments/$attachmentId'),headers:_auth(token,workspaceId:workspaceId)); _ok(r);
  }

  Future<void> deleteTransaction(String token,String workspaceId,int id) async {
    final r=await http.delete(_uri('transactions/$id'),headers:_auth(token,workspaceId:workspaceId));
    _ok(r);
  }

  Future<FinancialAccount> saveAccount(String token,String workspaceId,FinancialAccount value,{required bool exists}) async {
    final body={'institution_name':value.institutionName,'account_name':value.accountName,'masked_account':value.maskedAccount};
    final headers={..._auth(token,workspaceId:workspaceId),..._json()};
    final r=exists
        ? await http.patch(_uri('accounts/${value.id}'),headers:headers,body:jsonEncode(body))
        : await http.post(_uri('accounts'),headers:headers,body:jsonEncode(body));
    _ok(r);
    return FinancialAccount.fromJson(Map<String,dynamic>.from(jsonDecode(r.body)));
  }

  Future<CreditCardInfo> saveCard(String token,String workspaceId,CreditCardInfo value,{required bool exists}) async {
    final body={'bank_name':value.bankName,'brand':value.brand,'last_four':value.lastFour,'nickname':value.nickname,'credit_limit':value.creditLimit,'closing_day':value.closingDay,'due_day':value.dueDay};
    final headers={..._auth(token,workspaceId:workspaceId),..._json()};
    final r=exists
        ? await http.patch(_uri('cards/${value.id}'),headers:headers,body:jsonEncode(body))
        : await http.post(_uri('cards'),headers:headers,body:jsonEncode(body));
    _ok(r);
    return CreditCardInfo.fromJson(Map<String,dynamic>.from(jsonDecode(r.body)));
  }

  Future<void> deleteCard(String token,String workspaceId,int id) async {
    final r=await http.delete(_uri('cards/$id'),headers:_auth(token,workspaceId:workspaceId));
    _ok(r);
  }

  Future<FinanceCategory> saveCategory(String token,String workspaceId,FinanceCategory value,{required bool exists}) async {
    final headers={..._auth(token,workspaceId:workspaceId),..._json()};
    final body={'name':value.name,'icon':value.icon};
    final r=exists
        ? await http.patch(_uri('categories/${value.id}'),headers:headers,body:jsonEncode(body))
        : await http.post(_uri('categories'),headers:headers,body:jsonEncode(body));
    _ok(r);
    return FinanceCategory.fromJson(Map<String,dynamic>.from(jsonDecode(r.body)));
  }

  Future<void> deleteCategory(String token,String workspaceId,int id) async {
    final r=await http.delete(_uri('categories/$id'),headers:_auth(token,workspaceId:workspaceId));
    _ok(r);
  }

  Future<void> saveBudget(String token,String workspaceId,int categoryId,double amount) async {
    final r=await http.put(_uri('budgets/$categoryId'),headers:{..._auth(token,workspaceId:workspaceId),..._json()},body:jsonEncode({'amount':amount}));
    _ok(r);
  }

  Future<void> deleteBudget(String token,String workspaceId,int categoryId) async {
    final r=await http.delete(_uri('budgets/$categoryId'),headers:_auth(token,workspaceId:workspaceId));
    _ok(r);
  }

  Future<FinancialGoal> saveGoal(String token,String workspaceId,FinancialGoal value,{required bool exists}) async {
    final body={'name':value.name,'target_amount':value.targetAmount,'current_amount':value.currentAmount,'target_date':value.targetDate};
    final headers={..._auth(token,workspaceId:workspaceId),..._json()};
    final r=exists
        ? await http.put(_uri('goals/${value.id}'),headers:headers,body:jsonEncode(body))
        : await http.post(_uri('goals'),headers:headers,body:jsonEncode(body));
    _ok(r);
    return FinancialGoal.fromJson(Map<String,dynamic>.from(jsonDecode(r.body)));
  }

  Future<void> deleteGoal(String token,String workspaceId,int id) async {
    final r=await http.delete(_uri('goals/$id'),headers:_auth(token,workspaceId:workspaceId));
    _ok(r);
  }

  Future<List<Map<String,dynamic>>> openFinanceConnections(String token,String workspaceId) async {
    final r=await http.get(_uri('openfinance/connections'),headers:_auth(token,workspaceId:workspaceId));
    _ok(r);
    return (jsonDecode(r.body) as List).map((e)=>Map<String,dynamic>.from(e as Map)).toList();
  }
  Future<Map<String,dynamic>> openFinanceMockConnect(String token,String workspaceId) async {
    final r=await http.post(_uri('openfinance/mock/connect'),headers:_auth(token,workspaceId:workspaceId));
    _ok(r);
    return Map<String,dynamic>.from(jsonDecode(r.body));
  }
  Future<Map<String,dynamic>> openFinanceWidgetToken(String token,String workspaceId) async {
    final r=await http.post(_uri('openfinance/belvo/widget-token'),headers:{..._auth(token,workspaceId:workspaceId),..._json()},body:'{}');
    _ok(r);
    return Map<String,dynamic>.from(jsonDecode(r.body));
  }
  Future<Map<String,dynamic>> openFinanceSync(String token,String workspaceId,int connectionId) async {
    final r=await http.post(_uri('openfinance/connections/$connectionId/sync'),headers:_auth(token,workspaceId:workspaceId));
    _ok(r);
    return Map<String,dynamic>.from(jsonDecode(r.body));
  }
  Future<void> openFinanceDisconnect(String token,String workspaceId,int connectionId) async {
    final r=await http.delete(_uri('openfinance/connections/$connectionId'),headers:_auth(token,workspaceId:workspaceId));
    _ok(r);
  }
  Future<List<Map<String,dynamic>>> openFinanceLogs(String token,String workspaceId,int connectionId) async {
    final r=await http.get(_uri('openfinance/connections/$connectionId/sync-logs'),headers:_auth(token,workspaceId:workspaceId));
    _ok(r);
    return (jsonDecode(r.body) as List).map((e)=>Map<String,dynamic>.from(e as Map)).toList();
  }


  Future<Map<String,dynamic>> currentUser(String token) async {final r=await http.get(_uri('users/me'),headers:_auth(token));_ok(r);return Map<String,dynamic>.from(jsonDecode(r.body));}
  Future<Map<String,dynamic>> updatePersonalProfile(String token,{required String name,required String cpf,String? birthDate,String? sex}) async {final r=await http.patch(_uri('users/me/personal'),headers:{..._auth(token),..._json()},body:jsonEncode({'name':name.trim(),'cpf':cpf.replaceAll(RegExp(r'\D'),''),'birth_date':birthDate,'sex':sex}));_ok(r);return Map<String,dynamic>.from(jsonDecode(r.body));}
  Future<Map<String,dynamic>> requestDataDeletionCode(String token) async {final r=await http.post(_uri('user-data/delete-code'),headers:_auth(token));_ok(r);return Map<String,dynamic>.from(jsonDecode(r.body));}
  Future<Map<String,dynamic>> confirmDataDeletion(String token,String code,Map<String,dynamic> options,{String? deletionToken}) async {
    final r=await http.post(_uri('user-data/confirm-delete-all'),headers:{..._auth(token,workspaceId:_activeWorkspaceId),..._json()},body:jsonEncode({'code':code,'deletion_token':deletionToken,'options':options}));
    _ok(r);
    final result=Map<String,dynamic>.from(jsonDecode(r.body));
    if(result['login_deleted']==true)return result;
    final workspaceId=_activeWorkspaceId;
    if(workspaceId==null)throw Exception('Workspace ativo não identificado para validar a exclusão.');
    final fresh=await financialSnapshot(token,workspaceId);
    final failures=<String>[];
    if(options['transactions']==true&&fresh.transactions.isNotEmpty)failures.add('transações');
    if(options['accounts']==true&&fresh.accounts.isNotEmpty)failures.add('contas');
    if(options['cards']==true&&fresh.cards.isNotEmpty)failures.add('cartões');
    if(options['categories']==true&&fresh.categories.isNotEmpty)failures.add('categorias');
    if(options['budgets']==true&&fresh.budgets.isNotEmpty)failures.add('orçamentos');
    if(options['goals']==true&&fresh.goals.isNotEmpty)failures.add('metas');
    if(failures.isNotEmpty)throw Exception('O servidor respondeu à exclusão, mas o snapshot ainda contém: ${failures.join(', ')}.');
    return result;
  }
  Future<String> askFinancialAi(String token,String workspaceId,String question,Map<String,dynamic> summary) async {final r=await http.post(_uri('financial-ai/ask'),headers:{..._auth(token,workspaceId:workspaceId),..._json()},body:jsonEncode({'question':question,'summary':summary}));_ok(r);final b=Map<String,dynamic>.from(jsonDecode(r.body));return (b['answer']??'').toString();}
  Future<bool> health() async {try{final r=await http.get(_uri('health')).timeout(const Duration(seconds:4));return r.statusCode>=200&&r.statusCode<300;}catch(_){return false;}}
  void _ok(http.Response r){if(r.statusCode>=200&&r.statusCode<300)return;var m='Não foi possível concluir a solicitação.';try{final b=jsonDecode(r.body);if(b is Map&&b['detail']!=null){final d=b['detail'];m=d is List?d.map((e)=>e is Map?e['msg']:e).join(' • '):d.toString();}}catch(_){}throw Exception(m);}
}
