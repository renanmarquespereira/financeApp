class AuthTokens {
  const AuthTokens({required this.accessToken, required this.refreshToken});
  final String accessToken;
  final String refreshToken;
  factory AuthTokens.fromJson(Map<String, dynamic> json) => AuthTokens(accessToken: json['access_token'] as String, refreshToken: json['refresh_token'] as String);
}

class Workspace {
  const Workspace({required this.id, required this.name, required this.kind, required this.isDefault, this.archivedAt});
  final String id; final String name; final String kind; final bool isDefault; final String? archivedAt;
  bool get active => archivedAt == null;
  factory Workspace.fromJson(Map<String, dynamic> json) => Workspace(id: json['id'] as String, name: json['name'] as String, kind: json['kind'] as String? ?? 'personal', isDefault: json['is_default'] as bool? ?? false, archivedAt: json['archived_at'] as String?);
}

class FinancialAccount {
  const FinancialAccount({required this.id, required this.institutionName, this.accountName, this.maskedAccount, this.connectionStatus='manual', this.currentBalance});
  final int id; final String institutionName; final String? accountName; final String? maskedAccount; final String connectionStatus; final double? currentBalance;
  factory FinancialAccount.fromJson(Map<String,dynamic> j)=>FinancialAccount(id:(j['id'] as num).toInt(),institutionName:(j['institution_name']??'Conta').toString(),accountName:j['account_name']?.toString(),maskedAccount:j['masked_account']?.toString(),connectionStatus:(j['connection_status']??'manual').toString(),currentBalance:(j['current_balance'] as num?)?.toDouble());
  Map<String,dynamic> toJson()=>{'id':id,'institution_name':institutionName,'account_name':accountName,'masked_account':maskedAccount,'connection_status':connectionStatus,'current_balance':currentBalance};
}

class FinancialTransaction {
  const FinancialTransaction({required this.id,required this.date,required this.description,required this.amount,required this.type,this.accountId,this.categoryId,this.cardId,this.source='manual',this.purchaseDate,this.externalTransactionId,this.importCategoryName});
  final int id; final String date; final String description; final double amount; final String type; final int? accountId; final int? categoryId; final int? cardId; final String source; final String? purchaseDate; final String? externalTransactionId; final String? importCategoryName;
  bool get isCard => cardId != null;
  factory FinancialTransaction.fromJson(Map<String,dynamic> j) {
    final rawType = (j['transaction_type'] ?? 'expense').toString().toLowerCase();
    final normalizedType = switch (rawType) {
      'credit' => 'income',
      'income' => 'income',
      'entrada' => 'income',
      'debit' => 'expense',
      'expense' => 'expense',
      'saida' => 'expense',
      _ => rawType,
    };
    return FinancialTransaction(
      id: (j['id'] as num).toInt(),
      date: j['date'].toString(),
      description: (j['description'] ?? '').toString(),
      amount: (j['amount'] as num).toDouble(),
      type: normalizedType,
      accountId: (j['account_id'] as num?)?.toInt(),
      categoryId: (j['category_id'] as num?)?.toInt(),
      cardId: (j['card_id'] as num?)?.toInt(),
      source: (j['source'] ?? 'manual').toString(),
      purchaseDate: j['purchase_date']?.toString(),
      externalTransactionId: j['external_transaction_id']?.toString(),
    );
  }
  Map<String,dynamic> toJson()=>{'id':id,'date':date,'description':description,'amount':amount,'transaction_type':type,'account_id':accountId,'category_id':categoryId,'card_id':cardId,'source':source,'purchase_date':purchaseDate,'external_transaction_id':externalTransactionId};
}

class CreditCardInfo {
  const CreditCardInfo({required this.id,required this.bankName,required this.brand,required this.lastFour,this.nickname,this.creditLimit,this.closingDay,this.dueDay,this.active=true});
  final int id; final String bankName; final String brand; final String lastFour; final String? nickname; final double? creditLimit; final int? closingDay; final int? dueDay; final bool active;
  factory CreditCardInfo.fromJson(Map<String,dynamic> j)=>CreditCardInfo(id:(j['id'] as num).toInt(),bankName:(j['bank_name']??'Banco').toString(),brand:(j['brand']??'').toString(),lastFour:(j['last_four']??'').toString(),nickname:j['nickname']?.toString(),creditLimit:(j['credit_limit'] as num?)?.toDouble(),closingDay:(j['closing_day'] as num?)?.toInt(),dueDay:(j['due_day'] as num?)?.toInt(),active:j['active'] as bool? ?? true);
  Map<String,dynamic> toJson()=>{'id':id,'bank_name':bankName,'brand':brand,'last_four':lastFour,'nickname':nickname,'credit_limit':creditLimit,'closing_day':closingDay,'due_day':dueDay,'active':active};
}


class FinanceCategory {
  const FinanceCategory({required this.id, required this.name, this.type='expense', this.icon});
  final int id; final String name; final String type; final String? icon;
  factory FinanceCategory.fromJson(Map<String,dynamic> j)=>FinanceCategory(id:(j['id'] as num).toInt(),name:(j['name']??'Categoria').toString(),type:(j['type']??'expense').toString(),icon:j['icon']?.toString());
  Map<String,dynamic> toJson()=>{'id':id,'name':name,'type':type,'icon':icon};
}

class CategoryBudget {
  const CategoryBudget({required this.id, required this.categoryId, required this.amount});
  final int id; final int categoryId; final double amount;
  factory CategoryBudget.fromJson(Map<String,dynamic> j){num n(dynamic v)=>v is num?v:num.tryParse(v?.toString()??'')??0;return CategoryBudget(id:n(j['id']??j['category_id']).toInt(),categoryId:n(j['category_id']).toInt(),amount:n(j['amount']).toDouble());}
  Map<String,dynamic> toJson()=>{'id':id,'category_id':categoryId,'amount':amount};
}

class FinancialGoal {
  const FinancialGoal({required this.id,required this.name,required this.targetAmount,required this.currentAmount,this.targetDate});
  final int id; final String name; final double targetAmount; final double currentAmount; final String? targetDate;

  factory FinancialGoal.fromJson(Map<String,dynamic> j){
    num number(dynamic value){
      if(value is num)return value;
      final raw=(value?.toString()??'').trim().replaceAll(',','.');
      return num.tryParse(raw)??0;
    }
    return FinancialGoal(
      id:number(j['id']).toInt(),
      name:(j['name']??'Meta').toString(),
      targetAmount:number(j['target_amount']).toDouble(),
      currentAmount:number(j['current_amount']).toDouble(),
      targetDate:j['target_date']?.toString(),
    );
  }
  Map<String,dynamic> toJson()=>{'id':id,'name':name,'target_amount':targetAmount,'current_amount':currentAmount,'target_date':targetDate};
}

class FinancialSnapshot {
  const FinancialSnapshot({required this.accounts,required this.transactions,required this.cards,this.categories=const [],this.budgets=const [],this.goals=const [],this.syncedAt});
  final List<FinancialAccount> accounts; final List<FinancialTransaction> transactions; final List<CreditCardInfo> cards; final List<FinanceCategory> categories; final List<CategoryBudget> budgets; final List<FinancialGoal> goals; final String? syncedAt;
  factory FinancialSnapshot.empty()=>const FinancialSnapshot(accounts:[],transactions:[],cards:[],categories:[],budgets:[],goals:[]);
  factory FinancialSnapshot.fromJson(Map<String,dynamic> j)=>FinancialSnapshot(accounts:(j['accounts'] as List? ?? []).map((e)=>FinancialAccount.fromJson(Map<String,dynamic>.from(e))).toList(),transactions:(j['transactions'] as List? ?? []).map((e)=>FinancialTransaction.fromJson(Map<String,dynamic>.from(e))).toList(),cards:(j['cards'] as List? ?? []).map((e)=>CreditCardInfo.fromJson(Map<String,dynamic>.from(e))).toList(),categories:(j['categories'] as List? ?? []).map((e)=>FinanceCategory.fromJson(Map<String,dynamic>.from(e))).toList(),budgets:(j['budgets'] as List? ?? []).map((e)=>CategoryBudget.fromJson(Map<String,dynamic>.from(e))).toList(),goals:(j['goals'] as List? ?? []).map((e)=>FinancialGoal.fromJson(Map<String,dynamic>.from(e))).toList(),syncedAt:j['synced_at']?.toString());
  FinancialSnapshot copyWith({List<FinancialAccount>? accounts,List<FinancialTransaction>? transactions,List<CreditCardInfo>? cards,List<FinanceCategory>? categories,List<CategoryBudget>? budgets,List<FinancialGoal>? goals,String? syncedAt})=>FinancialSnapshot(accounts:accounts??this.accounts,transactions:transactions??this.transactions,cards:cards??this.cards,categories:categories??this.categories,budgets:budgets??this.budgets,goals:goals??this.goals,syncedAt:syncedAt??this.syncedAt);
  Map<String,dynamic> toJson()=>{'accounts':accounts.map((e)=>e.toJson()).toList(),'transactions':transactions.map((e)=>e.toJson()).toList(),'cards':cards.map((e)=>e.toJson()).toList(),'categories':categories.map((e)=>e.toJson()).toList(),'budgets':budgets.map((e)=>e.toJson()).toList(),'goals':goals.map((e)=>e.toJson()).toList(),'synced_at':syncedAt};
}
