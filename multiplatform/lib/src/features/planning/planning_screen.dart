import 'dart:convert';
import 'package:flutter/material.dart';
import '../../core/api_client.dart';
import '../../core/forecast_store.dart';
import '../../core/input_masks.dart';
import '../../core/models.dart';
import '../../core/category_bank_visuals.dart';


class _DebtPayment {
  const _DebtPayment({required this.amount,required this.date,this.accountId});
  final double amount; final String date; final int? accountId;
  Map<String,dynamic> toJson()=>{'amount':amount,'date':date,'accountId':accountId};
  factory _DebtPayment.fromJson(Map<String,dynamic> j)=>_DebtPayment(amount:(j['amount'] as num?)?.toDouble()??0,date:(j['date']??'').toString(),accountId:(j['accountId'] as num?)?.toInt());
}
class _Debt {
  const _Debt({required this.id,required this.name,required this.creditor,required this.originalAmount,required this.dueDate,required this.installmentAmount,required this.payments});
  final int id; final String name,creditor,dueDate; final double originalAmount,installmentAmount; final List<_DebtPayment> payments;
  double get paid=>payments.fold(0.0,(a,b)=>a+b.amount);
  double get remaining=>(originalAmount-paid).clamp(0.0,originalAmount).toDouble();
  Map<String,dynamic> toJson()=>{'id':id,'name':name,'creditor':creditor,'originalAmount':originalAmount,'dueDate':dueDate,'installmentAmount':installmentAmount,'payments':payments.map((e)=>e.toJson()).toList()};
  factory _Debt.fromJson(Map<String,dynamic> j)=>_Debt(id:(j['id'] as num).toInt(),name:(j['name']??'Dívida').toString(),creditor:(j['creditor']??'').toString(),originalAmount:(j['originalAmount'] as num?)?.toDouble()??0,dueDate:(j['dueDate']??'').toString(),installmentAmount:(j['installmentAmount'] as num?)?.toDouble()??0,payments:(j['payments'] as List? ?? []).map((e)=>_DebtPayment.fromJson(Map<String,dynamic>.from(e))).toList());
}

class PlanningScreen extends StatefulWidget {
  const PlanningScreen({super.key, required this.snapshot, required this.onSaveSnapshot, required this.workspaceId, required this.onSaveTransaction, required this.api, this.accessToken});
  final FinancialSnapshot snapshot;
  final Future<void> Function(FinancialSnapshot) onSaveSnapshot;
  final String workspaceId;
  final Future<void> Function(FinancialTransaction) onSaveTransaction;
  final ApiClient api;
  final String? accessToken;
  @override State<PlanningScreen> createState()=>_PlanningScreenState();
}

class _PlanningScreenState extends State<PlanningScreen> {
  late FinancialSnapshot data;
  List<_Debt> debts=[];
  late final ForecastStore _debtStore=ForecastStore(widget.workspaceId);
  Set<int> deletedDebtIds={};
  @override void initState(){super.initState();data=widget.snapshot;_loadDebts();}
  Future<void> _loadDebts() async { try{if(widget.accessToken!=null)await _debtStore.sync(widget.api,widget.accessToken!);final payload=await _debtStore.read();final v=(payload['debts'] as List? ?? []).map((e)=>_Debt.fromJson(Map<String,dynamic>.from(e))).toList();deletedDebtIds=(payload['deletedDebtIds'] as List? ?? []).map((e)=>int.tryParse(forecastId(e))).whereType<int>().toSet();if(mounted)setState(()=>debts=v.where((d)=>!deletedDebtIds.contains(d.id)).toList());}catch(_){}}
  Future<void> _saveDebts() async { await _debtStore.update('debts',debts.map((e)=>e.toJson()).toList(),deletedDebtIds);try{if(widget.accessToken!=null)await _debtStore.sync(widget.api,widget.accessToken!);}catch(_){} if(mounted)setState((){}); }
  @override void didUpdateWidget(covariant PlanningScreen oldWidget){super.didUpdateWidget(oldWidget);if(oldWidget.snapshot!=widget.snapshot)data=widget.snapshot;}
  String money(num v)=>'R\$ ${v.toStringAsFixed(2).replaceAll('.', ',')}';
  int id()=>DateTime.now().microsecondsSinceEpoch.remainder(2147483647);
  double spent(int categoryId){final now=DateTime.now();return data.transactions.where((t){DateTime? d;try{d=DateTime.parse(t.date.substring(0,10));}catch(_){return false;}return t.categoryId==categoryId&&t.type!='income'&&d.year==now.year&&d.month==now.month;}).fold(0,(a,t)=>a+t.amount.abs());}
  Future<void> save(FinancialSnapshot next) async {setState(()=>data=next);await widget.onSaveSnapshot(next);}

  @override Widget build(BuildContext context)=>DefaultTabController(length:2,child:Scaffold(appBar:AppBar(title:const Text('Planejamento'),bottom:const TabBar(tabs:[Tab(icon:Icon(Icons.pie_chart_outline),text:'Categorias'),Tab(icon:Icon(Icons.flag_outlined),text:'Metas')])) ,body:TabBarView(children:[_budgets(),_goals()])));

  Widget _budgets(){final cats=data.categories.where((c)=>c.type!='income').toList();return ListView(padding:const EdgeInsets.all(16),children:[Wrap(spacing:12,runSpacing:10,crossAxisAlignment:WrapCrossAlignment.center,alignment:WrapAlignment.spaceBetween,children:[Text('Categorias',style:Theme.of(context).textTheme.headlineSmall),FilledButton.icon(onPressed:() async { await _categoryDialog(); },icon:const Icon(Icons.add),label:const Text('Adicionar categoria'))]),const SizedBox(height:6),const Text('Defina um limite por categoria e acompanhe quanto já foi consumido no mês.'),const SizedBox(height:16),if(cats.isEmpty)const Card(child:ListTile(title:Text('Nenhuma categoria de despesa cadastrada.'),subtitle:Text('Use “Adicionar categoria” para criar a primeira.'))),...cats.map((c){final b=data.budgets.where((e)=>e.categoryId==c.id).firstOrNull;final s=spent(c.id);final limit=b?.amount??0;final progress=limit<=0?0.0:(s/limit).clamp(0.0,1.0);return Card(child:ListTile(onTap:()=>_budgetDialog(c,b),leading:Icon(categoryIconData(c.icon,c.name)),title:Text(c.name),subtitle:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[Text(limit>0?'${money(s)} de ${money(limit)}':'Sem limite definido',style:TextStyle(fontSize:limit>0?16:14,fontWeight:limit>0?FontWeight.w700:FontWeight.w400)),const SizedBox(height:6),LinearProgressIndicator(value:progress)]),trailing:IconButton(icon:const Icon(Icons.edit),onPressed:()=>_budgetDialog(c,b))));})]);}

  Widget _goals()=>ListView(padding:const EdgeInsets.all(16),children:[Row(children:[Expanded(child:Text('Metas financeiras',style:Theme.of(context).textTheme.headlineSmall)),FilledButton.icon(onPressed:()=>_goalDialog(),icon:const Icon(Icons.add),label:const Text('Nova'))]),const SizedBox(height:8),if(data.goals.isEmpty)const Card(child:ListTile(title:Text('Nenhuma meta cadastrada.'),subtitle:Text('Crie uma meta para reserva, viagem, compra ou outro objetivo.'))),...data.goals.map((g){final p=g.targetAmount<=0?0.0:(g.currentAmount/g.targetAmount).clamp(0.0,1.0);return Card(child:Padding(padding:const EdgeInsets.all(12),child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[Row(children:[Expanded(child:Text(g.name,style:Theme.of(context).textTheme.titleMedium)),PopupMenuButton<String>(onSelected:(v){if(v=='edit')_goalDialog(g);if(v=='delete')save(data.copyWith(goals:data.goals.where((e)=>e.id!=g.id).toList()));},itemBuilder:(_)=>const[PopupMenuItem(value:'edit',child:Text('Editar')),PopupMenuItem(value:'delete',child:Text('Excluir'))])]),Text('${money(g.currentAmount)} de ${money(g.targetAmount)}${g.targetDate==null?'':' • até ${g.targetDate}'}'),const SizedBox(height:8),LinearProgressIndicator(value:p),const SizedBox(height:8),Align(alignment:Alignment.centerRight,child:TextButton.icon(onPressed:()=>_contributionDialog(g),icon:const Icon(Icons.savings_outlined),label:const Text('Adicionar valor')))])));})]);


  Widget _debts()=>ListView(padding:const EdgeInsets.all(16),children:[
    Row(children:[Expanded(child:Text('Minhas dívidas',style:Theme.of(context).textTheme.headlineSmall)),FilledButton.icon(onPressed:_debtDialog,icon:const Icon(Icons.add),label:const Text('Nova dívida'))]),
    const SizedBox(height:8),
    if(debts.isEmpty)const Card(child:ListTile(leading:Icon(Icons.credit_score_outlined),title:Text('Nenhuma dívida cadastrada.'),subtitle:Text('Cadastre uma dívida e acompanhe cada pagamento até a quitação.'))),
    ...debts.map((d){final progress=d.originalAmount<=0?0.0:(d.paid/d.originalAmount).clamp(0.0,1.0);final due=DateTime.tryParse(d.dueDate);final now=DateTime.now();final status=d.remaining<=0?'Quitada':(due!=null&&DateTime(due.year,due.month,due.day).isBefore(DateTime(now.year,now.month,now.day))?'Atrasada':'Em dia');return Card(child:Padding(padding:const EdgeInsets.all(14),child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[Row(children:[Expanded(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[Text(d.name,style:Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight:FontWeight.w700)),if(d.creditor.isNotEmpty)Text(d.creditor)])),PopupMenuButton<String>(onSelected:(v){if(v=='pay')_debtPaymentDialog(d);if(v=='delete'){setState((){debts.removeWhere((e)=>e.id==d.id);deletedDebtIds.add(d.id);});_saveDebts();}},itemBuilder:(_)=>const[PopupMenuItem(value:'pay',child:Text('Registrar pagamento')),PopupMenuItem(value:'delete',child:Text('Excluir'))])]),const SizedBox(height:8),LinearProgressIndicator(value:progress),const SizedBox(height:8),Text('Pago: ${money(d.paid)}  •  Restante: ${money(d.remaining)}',style:const TextStyle(fontWeight:FontWeight.w700)),Text('Próximo vencimento: ${_brDate(d.dueDate)}${d.installmentAmount>0?' • ${money(d.installmentAmount)}':''}'),Text(status,style:TextStyle(color:status=='Atrasada'?Colors.red:status=='Quitada'?Colors.green:null,fontWeight:FontWeight.w700)),if(d.payments.isNotEmpty)...[const SizedBox(height:8),ExpansionTile(tilePadding:EdgeInsets.zero,title:Text('Histórico (${d.payments.length})'),children:d.payments.reversed.map((p)=>ListTile(dense:true,title:Text(money(p.amount)),subtitle:Text(_brDate(p.date)))).toList())]])));}),
  ]);

  String _brDate(String raw){final d=DateTime.tryParse(raw);return d==null?raw:'${d.day.toString().padLeft(2,'0')}/${d.month.toString().padLeft(2,'0')}/${d.year}';}
  Future<void> _debtDialog() async {final name=TextEditingController();final creditor=TextEditingController();final total=TextEditingController();final installment=TextEditingController();DateTime due=DateTime.now().add(const Duration(days:30));final ok=await showDialog<bool>(context:context,builder:(c)=>StatefulBuilder(builder:(c,set)=>AlertDialog(title:const Text('Nova dívida'),content:SizedBox(width:460,child:SingleChildScrollView(child:Column(mainAxisSize:MainAxisSize.min,children:[TextField(controller:name,decoration:const InputDecoration(labelText:'Nome da dívida *')),const SizedBox(height:14),TextField(controller:creditor,decoration:const InputDecoration(labelText:'Credor')),const SizedBox(height:14),TextField(controller:total,keyboardType:TextInputType.number,inputFormatters:const[BrlMoneyInputFormatter()],decoration:const InputDecoration(labelText:'Valor original *')),const SizedBox(height:14),TextField(controller:installment,keyboardType:TextInputType.number,inputFormatters:const[BrlMoneyInputFormatter()],decoration:const InputDecoration(labelText:'Valor da próxima parcela (opcional)')),const SizedBox(height:14),TextField(readOnly:true,controller:TextEditingController(text:_brDate(due.toIso8601String())),decoration:InputDecoration(labelText:'Próximo vencimento',suffixIcon:IconButton(icon:const Icon(Icons.calendar_month),onPressed:()async{final d=await showDatePicker(context:c,initialDate:due,firstDate:DateTime(1900),lastDate:DateTime(2200));if(d!=null)set(()=>due=d);})),onTap:()async{final d=await showDatePicker(context:c,initialDate:due,firstDate:DateTime(1900),lastDate:DateTime(2200));if(d!=null)set(()=>due=d);})]))),actions:[TextButton(onPressed:()=>Navigator.pop(c,false),child:const Text('Cancelar')),FilledButton(onPressed:()=>Navigator.pop(c,true),child:const Text('Salvar'))])));if(ok!=true)return;final v=brlValue(total.text);if(name.text.trim().isEmpty||v<=0)return;setState(()=>debts.add(_Debt(id:id(),name:name.text.trim(),creditor:creditor.text.trim(),originalAmount:v,dueDate:'${due.year}-${due.month.toString().padLeft(2,'0')}-${due.day.toString().padLeft(2,'0')}',installmentAmount:brlValue(installment.text),payments:const[])));await _saveDebts();}

  Future<void> _debtPaymentDialog(_Debt d) async {final amount=TextEditingController(text:d.installmentAmount>0?brlText(d.installmentAmount):'');DateTime date=DateTime.now();int? accountId;bool createTx=true;final ok=await showDialog<bool>(context:context,builder:(c)=>StatefulBuilder(builder:(c,set)=>AlertDialog(title:Text('Registrar pagamento • ${d.name}'),content:SizedBox(width:460,child:Column(mainAxisSize:MainAxisSize.min,children:[TextField(controller:amount,keyboardType:TextInputType.number,inputFormatters:const[BrlMoneyInputFormatter()],decoration:const InputDecoration(labelText:'Valor pago *')),const SizedBox(height:14),DropdownButtonFormField<int>(initialValue:accountId,items:data.accounts.map((e)=>DropdownMenuItem(value:e.id,child:Text(e.institutionName))).toList(),onChanged:(v)=>set(()=>accountId=v),decoration:const InputDecoration(labelText:'Banco/conta (opcional)')),const SizedBox(height:14),TextField(readOnly:true,controller:TextEditingController(text:_brDate(date.toIso8601String())),decoration:InputDecoration(labelText:'Data do pagamento',suffixIcon:IconButton(icon:const Icon(Icons.calendar_month),onPressed:()async{final x=await showDatePicker(context:c,initialDate:date,firstDate:DateTime(1900),lastDate:DateTime(2200));if(x!=null)set(()=>date=x);}))),SwitchListTile(contentPadding:EdgeInsets.zero,value:createTx,onChanged:(v)=>set(()=>createTx=v),title:const Text('Gerar saída em Transações'))])),actions:[TextButton(onPressed:()=>Navigator.pop(c,false),child:const Text('Cancelar')),FilledButton(onPressed:()=>Navigator.pop(c,true),child:const Text('Registrar'))])));if(ok!=true)return;final v=brlValue(amount.text);if(v<=0||v>d.remaining+0.009)return;final payment=_DebtPayment(amount:v,date:'${date.year}-${date.month.toString().padLeft(2,'0')}-${date.day.toString().padLeft(2,'0')}',accountId:accountId);final i=debts.indexWhere((e)=>e.id==d.id);if(i<0)return;debts[i]=_Debt(id:d.id,name:d.name,creditor:d.creditor,originalAmount:d.originalAmount,dueDate:d.dueDate,installmentAmount:d.installmentAmount,payments:[...d.payments,payment]);await _saveDebts();if(createTx){await widget.onSaveTransaction(FinancialTransaction(id:id(),date:payment.date,description:'Pagamento de dívida • ${d.name}',amount:v,type:'expense',accountId:accountId,source:'manual',externalTransactionId:'debt:${d.id}:${DateTime.now().microsecondsSinceEpoch}'));}}


  Future<void> _categoryDialog() async {
    final name=TextEditingController();
    final limit=TextEditingController();
    String? selectedIcon;
    if(!mounted)return;
    final ok=await showDialog<bool>(context:context,useRootNavigator:false,builder:(ctx)=>StatefulBuilder(builder:(ctx,setD)=>AlertDialog(
      title:const Text('Adicionar categoria'),
      content:SizedBox(width:430,child:SingleChildScrollView(child:Column(mainAxisSize:MainAxisSize.min,crossAxisAlignment:CrossAxisAlignment.start,children:[
        TextField(controller:name,autofocus:true,onChanged:(_)=>setD((){}),decoration:const InputDecoration(labelText:'Nome da categoria *')),
        const SizedBox(height:18),
        const Text('Ícone da categoria',style:TextStyle(fontWeight:FontWeight.w700)),
        const SizedBox(height:12),
        CategoryIconPicker(selected:selectedIcon,categoryName:name.text,onSelected:(v)=>setD(()=>selectedIcon=v)),
        const SizedBox(height:18),
        TextField(controller:limit,keyboardType:TextInputType.number,inputFormatters:const[BrlMoneyInputFormatter()],decoration:const InputDecoration(labelText:'Limite mensal (opcional)')),
      ]))),
      actions:[TextButton(onPressed:()=>Navigator.pop(ctx,false),child:const Text('Cancelar')),FilledButton(onPressed:name.text.trim().isEmpty?null:()=>Navigator.pop(ctx,true),child:const Text('Adicionar'))]
    )));
    if(ok!=true||name.text.trim().isEmpty)return;
    final categoryId=id();
    final category=FinanceCategory(id:categoryId,name:name.text.trim(),type:'expense',icon:selectedIcon??inferredCategoryIconId(name.text));
    final categories=[...data.categories,category];
    final budgets=[...data.budgets];
    final value=brlValue(limit.text);
    if(value>0)budgets.add(CategoryBudget(id:id(),categoryId:categoryId,amount:value));
    await save(data.copyWith(categories:categories,budgets:budgets));
  }

  Future<void> _budgetDialog(FinanceCategory c,CategoryBudget? current) async {final ctrl=TextEditingController(text:current==null?'':brlText(current.amount));await showDialog(context:context,builder:(ctx)=>AlertDialog(title:Text('Orçamento • ${c.name}'),content:TextField(controller:ctrl,keyboardType:TextInputType.number,inputFormatters:const[BrlMoneyInputFormatter()],decoration:const InputDecoration(labelText:'Limite mensal')),actions:[TextButton(onPressed:()=>Navigator.pop(ctx),child:const Text('Cancelar')),FilledButton(onPressed:(){final value=brlValue(ctrl.text);if(value==null||value<0)return;final list=[...data.budgets]..removeWhere((e)=>e.categoryId==c.id);if(value>0)list.add(CategoryBudget(id:current?.id??id(),categoryId:c.id,amount:value));Navigator.pop(ctx);save(data.copyWith(budgets:list));},child:const Text('Salvar'))]));}

  Future<void> _goalDialog([FinancialGoal? current]) async {final name=TextEditingController(text:current?.name??'');final target=TextEditingController(text:current==null?'':brlText(current.targetAmount));final date=TextEditingController(text:current?.targetDate??'');await showDialog(context:context,builder:(ctx)=>AlertDialog(title:Text(current==null?'Nova meta':'Editar meta'),content:SizedBox(width:420,child:Column(mainAxisSize:MainAxisSize.min,children:[TextField(controller:name,decoration:const InputDecoration(labelText:'Nome da meta')),const SizedBox(height:14),TextField(controller:target,keyboardType:TextInputType.number,inputFormatters:const[BrlMoneyInputFormatter()],decoration:const InputDecoration(labelText:'Valor alvo')),const SizedBox(height:14),TextField(controller:date,decoration:const InputDecoration(labelText:'Data alvo (AAAA-MM-DD)'))])),actions:[TextButton(onPressed:()=>Navigator.pop(ctx),child:const Text('Cancelar')),FilledButton(onPressed:(){final value=brlValue(target.text);if(name.text.trim().isEmpty||value==null||value<=0)return;final g=FinancialGoal(id:current?.id??id(),name:name.text.trim(),targetAmount:value,currentAmount:current?.currentAmount??0,targetDate:date.text.trim().isEmpty?null:date.text.trim());final list=[...data.goals];final i=list.indexWhere((e)=>e.id==g.id);if(i<0)list.add(g);else list[i]=g;Navigator.pop(ctx);save(data.copyWith(goals:list));},child:const Text('Salvar'))]));}

  Future<void> _contributionDialog(FinancialGoal g) async {final ctrl=TextEditingController();await showDialog(context:context,builder:(ctx)=>AlertDialog(title:Text('Adicionar valor • ${g.name}'),content:TextField(controller:ctrl,keyboardType:TextInputType.number,inputFormatters:const[BrlMoneyInputFormatter()],decoration:const InputDecoration(labelText:'Valor')),actions:[TextButton(onPressed:()=>Navigator.pop(ctx),child:const Text('Cancelar')),FilledButton(onPressed:(){final value=brlValue(ctrl.text);if(value==null||value<=0)return;final next=FinancialGoal(id:g.id,name:g.name,targetAmount:g.targetAmount,currentAmount:g.currentAmount+value,targetDate:g.targetDate);final list=[...data.goals];list[list.indexWhere((e)=>e.id==g.id)]=next;Navigator.pop(ctx);save(data.copyWith(goals:list));},child:const Text('Adicionar'))]));}
}

extension FirstOrNull<E> on Iterable<E>{E? get firstOrNull=>isEmpty?null:first;}
