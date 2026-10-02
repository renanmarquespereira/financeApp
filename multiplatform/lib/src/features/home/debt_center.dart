import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../core/api_client.dart';
import '../../core/forecast_store.dart';
import '../../core/input_masks.dart';
import '../../core/models.dart';

const plannedDebtPrefix = 'Prevista • Dívida • ';
bool isPlannedDebtTransaction(FinancialTransaction t) => t.description.startsWith(plannedDebtPrefix);

class _DebtPayment {
  const _DebtPayment({required this.amount, required this.date, this.accountId, this.installments=1});
  final double amount;
  final String date;
  final int? accountId;
  final int installments;
  Map<String,dynamic> toJson()=>{'amount':amount,'date':date,'accountId':accountId,'installments':installments};
  factory _DebtPayment.fromJson(Map<String,dynamic> j)=>_DebtPayment(amount:(j['amount'] as num?)?.toDouble()??0,date:(j['date']??'').toString(),accountId:(j['accountId'] as num?)?.toInt(),installments:((j['installments'] as num?)?.toInt()??1).clamp(1,360));
}

class _Debt {
  const _Debt({required this.id,required this.name,required this.creditor,required this.installmentAmount,required this.installmentCount,required this.firstDueDate,required this.originalAmount,required this.payments});
  final int id;
  final String name, creditor, firstDueDate;
  final double installmentAmount, originalAmount;
  final int installmentCount;
  final List<_DebtPayment> payments;
  double get paid=>payments.fold(0.0,(a,b)=>a+b.amount);
  double get remaining=>(originalAmount-paid).clamp(0.0,originalAmount).toDouble();
  int get paidInstallments=>payments.fold<int>(0,(a,b)=>a+b.installments).clamp(0,installmentCount).toInt();
  DateTime get due { final d=DateTime.tryParse(firstDueDate)??DateTime.now(); return DateTime(d.year,d.month+paidInstallments,d.day); }
  Map<String,dynamic> toJson()=>{'id':id,'name':name,'creditor':creditor,'installmentAmount':installmentAmount,'installmentCount':installmentCount,'firstDueDate':firstDueDate,'dueDate':firstDueDate,'nextDueDate':'${due.year}-${due.month.toString().padLeft(2,'0')}-${due.day.toString().padLeft(2,'0')}','originalAmount':originalAmount,'payments':payments.map((e)=>e.toJson()).toList()};
  factory _Debt.fromJson(Map<String,dynamic> j){
    final installment=(j['installmentAmount'] as num?)?.toDouble()??0;
    final original=(j['originalAmount'] as num?)?.toDouble()??0;
    final count=((j['installmentCount'] as num?)?.toInt()??0)>0?(j['installmentCount'] as num).toInt():(installment>0?(original/installment).ceil().clamp(1,360).toInt():1);
    final due=(j['firstDueDate']??j['dueDate']??j['nextDueDate']??DateTime.now().toIso8601String()).toString();
    return _Debt(id:(j['id'] as num).toInt(),name:(j['name']??'Dívida').toString(),creditor:(j['creditor']??'').toString(),installmentAmount:installment>0?installment:original,installmentCount:count,firstDueDate:due.substring(0,due.length>=10?10:due.length),originalAmount:original>0?original:installment*count,payments:(j['payments'] as List? ?? []).map((e)=>_DebtPayment.fromJson(Map<String,dynamic>.from(e))).toList());
  }
}

class DebtCenterController {
  final String workspaceId;
  final ApiClient api;
  final String? accessToken;
  final List<FinancialAccount> accounts;
  final List<FinancialTransaction> transactions;
  final Future<void> Function(FinancialTransaction) onSaveTransaction;
  final Map<String,FinancialTransaction> localPlanned = {};
  final ForecastStore _store;
  List<_Debt> debts=[];
  Set<int> deleted={};
  factory DebtCenterController.create({required String workspaceId,required ApiClient api,String? accessToken,required List<FinancialAccount> accounts,required List<FinancialTransaction> transactions,required Future<void> Function(FinancialTransaction) onSaveTransaction}) {
    final x=DebtCenterController._(workspaceId:workspaceId,api:api,accessToken:accessToken,accounts:accounts,transactions:transactions,onSaveTransaction:onSaveTransaction,store:ForecastStore(workspaceId));return x;
  }
  DebtCenterController._({required this.workspaceId,required this.api,this.accessToken,required this.accounts,required this.transactions,required this.onSaveTransaction,required ForecastStore store}):_store=store;
  Future<void> load() async {try{if(accessToken!=null)await _store.sync(api,accessToken!);}catch(_){} final p=await _store.read();debts=(p['debts'] as List? ?? []).map((e)=>_Debt.fromJson(Map<String,dynamic>.from(e))).toList();deleted=(p['deletedDebtIds'] as List? ?? []).map((e)=>int.tryParse(forecastId(e))).whereType<int>().toSet();debts=debts.where((d)=>!deleted.contains(d.id)).toList();}
  Future<void> save() async {await _store.update('debts',debts.map((e)=>e.toJson()).toList(),deleted);try{if(accessToken!=null)await _store.sync(api,accessToken!);}catch(_){}}
}

Future<void> showDebtCenter(BuildContext context,{required String workspaceId,required ApiClient api,String? accessToken,required List<FinancialAccount> accounts,required List<FinancialTransaction> transactions,required Future<void> Function(FinancialTransaction) onSaveTransaction,bool startNew=false}) async {
  final ctl=DebtCenterController.create(workspaceId:workspaceId,api:api,accessToken:accessToken,accounts:accounts,transactions:transactions,onSaveTransaction:onSaveTransaction);
  await ctl.load();
  bool newDebt=startNew;
  if(newDebt){await _newDebt(context,ctl);newDebt=false;}
  if(!context.mounted)return;
  await showDialog(context:context,builder:(ctx)=>StatefulBuilder(builder:(ctx,setD)=>AlertDialog(
    title:const Text('Minhas dívidas'),
    content:SizedBox(width:620,height:560,child:Column(children:[
      SizedBox(width:double.infinity,child:FilledButton.icon(onPressed:()async{await _newDebt(ctx,ctl);setD((){});},icon:const Icon(Icons.add),label:const Text('Adicionar dívida'))),
      const SizedBox(height:12),
      Expanded(child:ctl.debts.isEmpty?const Center(child:Text('Nenhuma dívida cadastrada.')):ListView.separated(itemCount:ctl.debts.length,separatorBuilder:(_,__)=>const SizedBox(height:10),itemBuilder:(_,i){final d=ctl.debts[i];final progress=d.originalAmount<=0?0.0:(d.paid/d.originalAmount).clamp(0.0,1.0);final overdue=d.remaining>0&&d.due.isBefore(DateTime.now());return Card(child:Padding(padding:const EdgeInsets.all(14),child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[Row(children:[Expanded(child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[Text(d.name,style:Theme.of(ctx).textTheme.titleMedium?.copyWith(fontWeight:FontWeight.w700)),if(d.creditor.isNotEmpty)Text(d.creditor)])),Text(d.remaining<=0?'Quitada':overdue?'Atrasada':'Em dia',style:TextStyle(fontWeight:FontWeight.w700,color:d.remaining<=0?Colors.green:overdue?Colors.red:null))]),const SizedBox(height:8),LinearProgressIndicator(value:progress),const SizedBox(height:8),Text('${d.paidInstallments}/${d.installmentCount} parcelas • ${brlText(d.installmentAmount)} cada'),Text('Pago: ${brlText(d.paid)} • Restante: ${brlText(d.remaining)}',style:const TextStyle(fontWeight:FontWeight.w700)),if(d.remaining>0)Text('Próxima: ${_brDate(d.due.toIso8601String())}'),if(d.payments.isNotEmpty)ExpansionTile(tilePadding:EdgeInsets.zero,title:Text('Histórico (${d.payments.length})'),children:d.payments.reversed.map((p){final bank=accounts.where((a)=>a.id==p.accountId).map((a)=>a.institutionName).firstOrNull??'Banco não informado';return ListTile(dense:true,title:Text(brlText(p.amount)),subtitle:Text('${_brDate(p.date)} • ${p.installments} parcela(s) • $bank'));}).toList()),Row(mainAxisAlignment:MainAxisAlignment.end,children:[TextButton(onPressed:()async{ctl.deleted.add(d.id);ctl.debts.removeWhere((x)=>x.id==d.id);await ctl.save();setD((){});},child:const Text('Excluir')),if(d.remaining>0)FilledButton.tonal(onPressed:()async{await _payDebt(ctx,ctl,d);setD((){});},child:const Text('Registrar pagamento'))])])));}))
    ])),
    actions:[TextButton(onPressed:()=>Navigator.pop(ctx),child:const Text('Fechar'))]
  )));
}

Future<void> _newDebt(BuildContext context,DebtCenterController ctl) async {
  final name=TextEditingController(), creditor=TextEditingController(), installment=TextEditingController(), count=TextEditingController(text:'12');DateTime due=DateTime.now().add(const Duration(days:30));
  final ok=await showDialog<bool>(context:context,builder:(c)=>StatefulBuilder(builder:(c,setD){final n=int.tryParse(count.text)??0;final v=brlValue(installment.text);final total=v*n;return AlertDialog(title:const Text('Adicionar dívida'),content:SizedBox(width:460,child:SingleChildScrollView(child:Column(mainAxisSize:MainAxisSize.min,children:[TextField(controller:name,decoration:const InputDecoration(labelText:'Nome da dívida *')),const SizedBox(height:14),TextField(controller:creditor,decoration:const InputDecoration(labelText:'Credor')),const SizedBox(height:14),TextField(controller:installment,onChanged:(_)=>setD((){}),keyboardType:TextInputType.number,inputFormatters:const[BrlMoneyInputFormatter()],decoration:const InputDecoration(labelText:'Valor da parcela *')),const SizedBox(height:14),TextField(controller:count,onChanged:(_)=>setD((){}),keyboardType:TextInputType.number,inputFormatters:[FilteringTextInputFormatter.digitsOnly,LengthLimitingTextInputFormatter(3)],decoration:const InputDecoration(labelText:'Quantidade de parcelas *')),const SizedBox(height:14),Container(width:double.infinity,padding:const EdgeInsets.all(12),decoration:BoxDecoration(color:Theme.of(c).colorScheme.surfaceContainerHighest,borderRadius:BorderRadius.circular(10)),child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[const Text('Total da dívida'),Text(brlText(total),style:Theme.of(c).textTheme.titleLarge?.copyWith(fontWeight:FontWeight.w700))])),const SizedBox(height:14),ListTile(contentPadding:EdgeInsets.zero,title:const Text('Primeiro vencimento'),subtitle:Text(_brDate(due.toIso8601String())),trailing:const Icon(Icons.calendar_month),onTap:()async{final d=await showDatePicker(context:c,initialDate:due,firstDate:DateTime(1900),lastDate:DateTime(2200));if(d!=null)setD(()=>due=d);})]))),actions:[TextButton(onPressed:()=>Navigator.pop(c,false),child:const Text('Cancelar')),FilledButton(onPressed:name.text.trim().isEmpty||v<=0||n<1?null:()=>Navigator.pop(c,true),child:const Text('Salvar'))]);}));
  if(ok!=true)return;final n=(int.tryParse(count.text)??1).clamp(1,360).toInt();final v=brlValue(installment.text);final debt=_Debt(id:DateTime.now().microsecondsSinceEpoch.remainder(2147483647),name:name.text.trim(),creditor:creditor.text.trim(),installmentAmount:v,installmentCount:n,firstDueDate:_iso(due),originalAmount:v*n,payments:const[]);ctl.debts=[debt,...ctl.debts];await ctl.save();
  final group='debt-plan:${debt.id}';for(var i=0;i<n;i++){final d=DateTime(due.year,due.month+i,due.day);final tx=FinancialTransaction(id:DateTime.now().microsecondsSinceEpoch.remainder(2147483647)+i,date:_iso(d),description:'$plannedDebtPrefix${debt.name} • Parcela ${i+1}/$n',amount:v,type:'expense',source:'manual',externalTransactionId:'$group:${i+1}');ctl.localPlanned[tx.externalTransactionId!]=tx;await ctl.onSaveTransaction(tx);}
}

Future<void> _payDebt(BuildContext context,DebtCenterController ctl,_Debt d) async {
  int accountIdValue=-1;DateTime date=DateTime.now();int installmentsToPay=1;
  final remainingInstallments=(d.installmentCount-d.paidInstallments).clamp(1,d.installmentCount).toInt();
  final ok=await showDialog<bool>(context:context,builder:(c)=>StatefulBuilder(builder:(c,setD){
    final first=d.paidInstallments+1;
    final last=(first+installmentsToPay-1).clamp(first,d.installmentCount).toInt();
    final count=(last-first+1).clamp(1,remainingInstallments).toInt();
    final total=(d.installmentAmount*count).clamp(0,d.remaining).toDouble();
    return AlertDialog(title:Text('Registrar pagamento • ${d.name}'),content:SizedBox(width:460,child:Column(mainAxisSize:MainAxisSize.min,children:[
      if(remainingInstallments>1)...[
        Align(alignment:Alignment.centerLeft,child:Text('Quantidade de parcelas: $installmentsToPay',style:const TextStyle(fontWeight:FontWeight.w700))),
        Slider(value:installmentsToPay.toDouble(),min:1,max:remainingInstallments.toDouble(),divisions:remainingInstallments>1?remainingInstallments-1:null,onChanged:(v)=>setD(()=>installmentsToPay=v.round().clamp(1,remainingInstallments))),
      ],
      Container(width:double.infinity,padding:const EdgeInsets.all(12),decoration:BoxDecoration(color:Theme.of(c).colorScheme.surfaceContainerHighest,borderRadius:BorderRadius.circular(10)),child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[
        Text(first==last?'Pagando parcela $first/${d.installmentCount}':'Pagando parcelas $first a $last de ${d.installmentCount}',style:const TextStyle(fontWeight:FontWeight.w700)),
        Text('Total do pagamento: ${brlText(total)}'),
      ])),
      const SizedBox(height:14),
      DropdownButtonFormField<int>(initialValue:accountIdValue<0?null:accountIdValue,items:ctl.accounts.map((a)=>DropdownMenuItem(value:a.id,child:Text(a.institutionName))).toList(),onChanged:(v)=>setD(()=>accountIdValue=v??-1),decoration:const InputDecoration(labelText:'Banco/conta do pagamento *')),
      const SizedBox(height:14),
      ListTile(contentPadding:EdgeInsets.zero,title:const Text('Data do pagamento'),subtitle:Text(_brDate(date.toIso8601String())),trailing:const Icon(Icons.calendar_month),onTap:()async{final x=await showDatePicker(context:c,initialDate:date,firstDate:DateTime(1900),lastDate:DateTime(2200));if(x!=null)setD(()=>date=x);}),
      const SizedBox(height:8),
      const Text('As parcelas selecionadas deixam de ser previstas e passam a ser saídas reais no banco escolhido.'),
    ])),actions:[TextButton(onPressed:()=>Navigator.pop(c,false),child:const Text('Cancelar')),FilledButton(onPressed:accountIdValue<0?null:()=>Navigator.pop(c,true),child:const Text('Registrar'))]);
  }));
  if(ok!=true)return;
  final first=d.paidInstallments+1;
  final last=(first+installmentsToPay-1).clamp(first,d.installmentCount).toInt();
  final count=(last-first+1).clamp(1,remainingInstallments).toInt();
  final value=(d.installmentAmount*count).clamp(0,d.remaining).toDouble();
  final p=_DebtPayment(amount:value,date:_iso(date),accountId:accountIdValue,installments:count);
  final idx=ctl.debts.indexWhere((x)=>x.id==d.id);if(idx<0)return;
  ctl.debts[idx]=_Debt(id:d.id,name:d.name,creditor:d.creditor,installmentAmount:d.installmentAmount,installmentCount:d.installmentCount,firstDueDate:d.firstDueDate,originalAmount:d.originalAmount,payments:[...d.payments,p]);await ctl.save();
  for(var installment=first;installment<=last;installment++){
    final ext='debt-plan:${d.id}:$installment';
    final planned=ctl.transactions.where((t)=>t.externalTransactionId==ext).firstOrNull??ctl.localPlanned[ext];
    final installmentValue=installment==last?(value-d.installmentAmount*(count-1)).clamp(0,d.installmentAmount).toDouble():d.installmentAmount;
    if(planned!=null){await ctl.onSaveTransaction(FinancialTransaction(id:planned.id,date:_iso(date),description:'Pagamento de dívida • ${d.name} • Parcela $installment/${d.installmentCount}',amount:installmentValue,type:'expense',accountId:accountIdValue,categoryId:planned.categoryId,source:'manual',externalTransactionId:ext));}
    else{await ctl.onSaveTransaction(FinancialTransaction(id:DateTime.now().microsecondsSinceEpoch.remainder(2147483647)+installment,date:_iso(date),description:'Pagamento de dívida • ${d.name} • Parcela $installment/${d.installmentCount}',amount:installmentValue,type:'expense',accountId:accountIdValue,source:'manual',externalTransactionId:'debt-payment:${d.id}:$installment:${DateTime.now().microsecondsSinceEpoch}'));}
  }
}

String _iso(DateTime d)=>'${d.year}-${d.month.toString().padLeft(2,'0')}-${d.day.toString().padLeft(2,'0')}';
String _brDate(String raw){final d=DateTime.tryParse(raw);return d==null?raw:'${d.day.toString().padLeft(2,'0')}/${d.month.toString().padLeft(2,'0')}/${d.year}';}
extension _FirstOrNull<E> on Iterable<E>{E? get firstOrNull=>isEmpty?null:first;}
