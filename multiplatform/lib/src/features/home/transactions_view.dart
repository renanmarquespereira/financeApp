import 'dart:convert';
import 'package:flutter/material.dart';
import '../../core/models.dart';
import '../../core/category_bank_visuals.dart';
import 'debt_center.dart';


class _TxStatusInfo {
  const _TxStatusInfo(this.label,this.color,this.description,this.amount);
  final String label,description;
  final Color color;
  final double amount;
}

_TxStatusInfo? _txStatus(FinancialTransaction t){
  final raw=t.description;
  final due=DateTime.tryParse(t.date);
  final today=DateTime.now();
  final day=DateTime(today.year,today.month,today.day);
  if(raw.startsWith('__PAYABLE_V1__|')){
    final parts=raw.substring('__PAYABLE_V1__|'.length).split('|');
    if(parts.length>=3){
      final cents=int.tryParse(parts[1])??0;
      String desc='Conta a pagar';
      try{desc=utf8.decode(base64Decode(parts.sublist(2).join('|'))).trim();}catch(_){}
      final overdue=due!=null&&DateTime(due.year,due.month,due.day).isBefore(day);
      return _TxStatusInfo(overdue?'Vencida':'Pendente',overdue?Colors.red:Colors.orange,desc,-cents/100.0);
    }
  }
  if(raw.startsWith('Prevista • Dívida • ')){
    final overdue=due!=null&&DateTime(due.year,due.month,due.day).isBefore(day);
    return _TxStatusInfo(overdue?'Vencida':'Pendente',overdue?Colors.red:Colors.orange,raw.substring('Prevista • Dívida • '.length).trim(),t.amount);
  }
  if(raw.startsWith('Conta paga •'))return _TxStatusInfo('Paga',Colors.green,raw.split('•').skip(1).join('•').trim(),t.amount);
  if(raw.startsWith('Pagamento de dívida •'))return _TxStatusInfo('Paga',Colors.green,raw,t.amount);
  return null;
}

class TransactionsView extends StatefulWidget {
  const TransactionsView({
    super.key,
    required this.snapshot,
    required this.onEdit,
    required this.onDelete,
    required this.onAddCard,
    required this.onAttachments,
  });

  final FinancialSnapshot snapshot;
  final Future<void> Function(FinancialTransaction?) onEdit;
  final Future<void> Function(int) onDelete;
  final Future<void> Function() onAddCard;
  final Future<void> Function(FinancialTransaction) onAttachments;

  @override
  State<TransactionsView> createState() => _TransactionsViewState();
}

class _TransactionsViewState extends State<TransactionsView> {
  final search = TextEditingController();
  String source = 'all', type = 'all', amount = 'all', view = 'expenses', period = 'all';
  int? accountId, categoryId, cardId;
  DateTime? startDate, endDate;
  bool newest = true;
  late DateTime selectedMonth;

  @override
  void initState() {
    super.initState();
    final now = DateTime.now();
    selectedMonth = DateTime(now.year, now.month);
    final activeCards = widget.snapshot.cards.where((c) => c.active).toList();
    if (activeCards.isNotEmpty) cardId = activeCards.first.id;
  }

  @override
  void didUpdateWidget(covariant TransactionsView oldWidget) {
    super.didUpdateWidget(oldWidget);
    final activeCards = widget.snapshot.cards.where((c) => c.active).toList();
    if (activeCards.isEmpty) {
      cardId = null;
    } else if (cardId == null || !activeCards.any((c) => c.id == cardId)) {
      cardId = activeCards.first.id;
    }
  }

  String norm(String s) => s.toLowerCase().replaceAll(RegExp(r'[áàãâä]'), 'a').replaceAll(RegExp(r'[éèêë]'), 'e').replaceAll(RegExp(r'[íìîï]'), 'i').replaceAll(RegExp(r'[óòõôö]'), 'o').replaceAll(RegExp(r'[úùûü]'), 'u').replaceAll('ç', 'c');
  DateTime? dateOf(String raw) { try { return DateTime.parse(raw); } catch (_) { return null; } }
  String money(num v) => 'R\$ ${v.toStringAsFixed(2).replaceAll('.', ',')}';
  String fmt(DateTime d) => '${d.day.toString().padLeft(2, '0')}/${d.month.toString().padLeft(2, '0')}/${d.year}';
  String timeOf(FinancialTransaction t) { if(!t.date.contains('T')&&!t.date.contains(' '))return '--:--'; final d=dateOf(t.date); return d==null?'--:--':'${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}'; }
  String monthLabel(DateTime d) => '${const ['Janeiro','Fevereiro','Março','Abril','Maio','Junho','Julho','Agosto','Setembro','Outubro','Novembro','Dezembro'][d.month - 1]} de ${d.year}';
  String monthSearchText(DateTime d){final names=const ['janeiro','fevereiro','marco','abril','maio','junho','julho','agosto','setembro','outubro','novembro','dezembro'];return '${names[d.month-1]} ${d.month.toString().padLeft(2,'0')} ${d.year} ${d.month.toString().padLeft(2,'0')}/${d.year}';}
  String sourceLabel(String s) => norm(s).contains('open') ? 'Open Finance' : 'Manual';
  String accountName(FinancialTransaction t) {
    if (isPlannedDebtTransaction(t)) {
      final creditor = debtCreditorFromTransactionDescription(t.description);
      if (creditor != null && creditor.isNotEmpty) return creditor;
    }
    return widget.snapshot.accounts.where((e) => e.id == t.accountId).map((e) => e.institutionName).firstOrNull ??
        (t.isCard ? widget.snapshot.cards.where((e) => e.id == t.cardId).map((e) => e.bankName).firstOrNull : null) ??
        'Sem banco';
  }
  FinanceCategory? categoryOf(FinancialTransaction t) => widget.snapshot.categories.where((e) => e.id == t.categoryId).firstOrNull;
  String categoryName(FinancialTransaction t) => categoryOf(t)?.name ?? 'Sem categoria';

  bool get hasFilters => source != 'all' || type != 'all' || amount != 'all' || accountId != null || categoryId != null || period != 'all' || startDate != null || endDate != null;
  List<String> get filterLabels { final x=<String>[]; if(source!='all')x.add(source=='manual'?'Manual':'Open Finance'); if(type!='all')x.add(type=='income'?'Entradas':'Saídas'); if(amount!='all')x.add({'100':'Até R\$ 100','100_500':'R\$ 100–500','500_1000':'R\$ 500–1.000','1000':'Acima de R\$ 1.000'}[amount]!); if(accountId!=null)x.add('Conta: ${widget.snapshot.accounts.where((e)=>e.id==accountId).map((e)=>e.institutionName).firstOrNull??''}'); if(categoryId!=null)x.add('Categoria: ${widget.snapshot.categories.where((e)=>e.id==categoryId).map((e)=>e.name).firstOrNull??''}'); if(period!='all')x.add({'today':'Hoje','7d':'7 dias','30d':'30 dias','month':'Este mês','custom':'${startDate==null?'...':fmt(startDate!)} a ${endDate==null?'...':fmt(endDate!)}'}[period]!); return x; }
  bool inPeriod(DateTime? d) { if(d==null)return period=='all'; final now=DateTime.now(); final today=DateTime(now.year,now.month,now.day); final day=DateTime(d.year,d.month,d.day); if(period=='today')return day==today; if(period=='7d')return !day.isBefore(today.subtract(const Duration(days:6)))&&!day.isAfter(today); if(period=='30d')return !day.isBefore(today.subtract(const Duration(days:29)))&&!day.isAfter(today); if(period=='month')return d.year==now.year&&d.month==now.month; if(period=='custom'){if(startDate!=null&&day.isBefore(DateTime(startDate!.year,startDate!.month,startDate!.day)))return false;if(endDate!=null&&day.isAfter(DateTime(endDate!.year,endDate!.month,endDate!.day)))return false;} return true; }

  List<FinancialTransaction> get visible {
    final q=norm(search.text.trim());
    final r=widget.snapshot.transactions.where((t){
      final d=dateOf(t.date);
      if(d==null)return false;
      if(q.isEmpty&&(d.year!=selectedMonth.year || d.month!=selectedMonth.month))return false;
      if(view=='expenses'&&(t.isCard||t.source=='card_purchase')&&t.source!='card_payment')return false;
      if(view=='cards'&&(t.description.startsWith('Prevista • Dívida • ') || !t.isCard || t.cardId!=cardId))return false;
      if(source=='manual'&&t.source!='manual'||source=='open_finance'&&t.source!='open_finance')return false;
      if(type=='income'&&t.type!='income'||type=='expense'&&t.type=='income')return false;
      final a=t.amount.abs(); if(amount=='100'&&a>100||amount=='100_500'&&(a<=100||a>500)||amount=='500_1000'&&(a<=500||a>1000)||amount=='1000'&&a<=1000)return false;
      if(accountId!=null&&t.accountId!=accountId||categoryId!=null&&t.categoryId!=categoryId)return false;
      if(!inPeriod(d))return false;
      if(q.isNotEmpty){final text=norm([t.description,t.date,fmt(d),timeOf(t),monthSearchText(d),money(t.amount.abs()),categoryName(t),accountName(t),sourceLabel(t.source),t.type=='income'?'entrada':'saida'].join(' '));if(!text.contains(q))return false;}
      return true;
    }).toList();
    r.sort((a,b){final da=dateOf(a.date)??DateTime(1900),db=dateOf(b.date)??DateTime(1900);return newest?db.compareTo(da):da.compareTo(db);});
    return r;
  }

  void clearFilters()=>setState((){source='all';type='all';amount='all';accountId=null;categoryId=null;period='all';startDate=null;endDate=null;});
  void moveMonth(int delta)=>setState(()=>selectedMonth=DateTime(selectedMonth.year,selectedMonth.month+delta));

  @override
  Widget build(BuildContext context) {
    final cards=widget.snapshot.cards.where((c)=>c.active).toList();
    final items=visible;
    return Column(children:[
      Padding(padding:const EdgeInsets.fromLTRB(12,12,12,6),child:Row(children:[Expanded(child:TextField(controller:search,onChanged:(_)=>setState((){}),decoration:InputDecoration(hintText:'Pesquisar transações',prefixIcon:const Icon(Icons.search),suffixIcon:search.text.isEmpty?null:IconButton(onPressed:(){search.clear();setState((){});},icon:const Icon(Icons.close)),border:const OutlineInputBorder()))),const SizedBox(width:6),IconButton.filledTonal(tooltip:'Filtros',onPressed:_filters,icon:Badge(isLabelVisible:hasFilters,child:const Icon(Icons.filter_list))),const SizedBox(width:6),PopupMenuButton<bool>(tooltip:'Ordenar',icon:Icon(newest?Icons.arrow_downward:Icons.arrow_upward),onSelected:(v)=>setState(()=>newest=v),itemBuilder:(_)=>const[PopupMenuItem(value:true,child:Text('Mais recentes primeiro')),PopupMenuItem(value:false,child:Text('Mais antigas primeiro'))])])),
      if(hasFilters)SizedBox(height:42,child:ListView(scrollDirection:Axis.horizontal,padding:const EdgeInsets.symmetric(horizontal:12),children:[Padding(padding:const EdgeInsets.only(right:6),child:ActionChip(avatar:const Icon(Icons.filter_alt_outlined,size:16),label:const Text('Filtros aplicados'),onPressed:_filters)),...filterLabels.map((e)=>Padding(padding:const EdgeInsets.only(right:6),child:Chip(label:Text(e)))),OutlinedButton.icon(style:OutlinedButton.styleFrom(foregroundColor:Theme.of(context).colorScheme.error,side:BorderSide(color:Theme.of(context).colorScheme.error)),onPressed:clearFilters,icon:const Icon(Icons.filter_alt_off,size:16),label:const Text('Limpar filtros'))])),
      Padding(padding:const EdgeInsets.symmetric(horizontal:12),child:Row(children:[Expanded(child:ChoiceChip(label:const Center(child:Text('Despesas')),selected:view=='expenses',onSelected:(_)=>setState(()=>view='expenses'))),const SizedBox(width:8),Expanded(child:ChoiceChip(label:const Center(child:Text('Cartões')),selected:view=='cards',onSelected:(_)=>setState((){view='cards';if(cards.isNotEmpty)cardId??=cards.first.id;})))])),
      Padding(padding:const EdgeInsets.fromLTRB(12,10,12,6),child:Row(children:[IconButton(onPressed:()=>moveMonth(-1),icon:const Icon(Icons.chevron_left)),Expanded(child:Column(children:[Text(const ['JANEIRO','FEVEREIRO','MARÇO','ABRIL','MAIO','JUNHO','JULHO','AGOSTO','SETEMBRO','OUTUBRO','NOVEMBRO','DEZEMBRO'][selectedMonth.month-1],textAlign:TextAlign.center,style:Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight:FontWeight.w800,letterSpacing:.8)),Text('${selectedMonth.year}',style:Theme.of(context).textTheme.bodySmall)])),IconButton(onPressed:()=>moveMonth(1),icon:const Icon(Icons.chevron_right))])),
      Padding(
        padding: const EdgeInsets.fromLTRB(12,0,12,8),
        child: Card(
          margin: EdgeInsets.zero,
          child: ListTile(
            leading: const Icon(Icons.calendar_month),
            title: Text(
              selectedMonth.year==DateTime.now().year && selectedMonth.month==DateTime.now().month
                  ? 'Movimentação do mês vigente • ${selectedMonth.year}'
                  : 'Movimentação de ${monthLabel(selectedMonth)}',
              style: const TextStyle(fontWeight: FontWeight.w700),
            ),
            subtitle: Text('Período exibido: ${monthLabel(selectedMonth)}'),
          ),
        ),
      ),
      if(view=='cards'&&cards.isNotEmpty)Padding(padding:const EdgeInsets.fromLTRB(12,0,12,6),child:DropdownButtonFormField<int>(value:cardId,decoration:const InputDecoration(labelText:'Cartão',border:OutlineInputBorder()),items:cards.map((c)=>DropdownMenuItem(value:c.id,child:Text('${c.nickname?.isNotEmpty==true?c.nickname!:c.bankName} • ${c.brand} • final ${c.lastFour}'))).toList(),onChanged:(v)=>setState(()=>cardId=v))),
      Expanded(child:view=='cards'&&cards.isEmpty?_noCards(context):items.isEmpty?Center(child:Text(view=='cards'?'Nenhuma transação deste cartão em ${monthLabel(selectedMonth)}.':'Nenhuma transação em ${monthLabel(selectedMonth)}.')):ListView.builder(padding:const EdgeInsets.fromLTRB(12,10,12,90),itemCount:items.length,itemBuilder:(c,i)=>Padding(padding:const EdgeInsets.only(bottom:10),child:_transactionCard(context,items[i])))),
    ]);
  }

  Widget _noCards(BuildContext context)=>Center(child:Padding(padding:const EdgeInsets.all(24),child:Column(mainAxisSize:MainAxisSize.min,children:[Icon(Icons.credit_card_off,size:48,color:Theme.of(context).colorScheme.outline),const SizedBox(height:12),Text('Nenhum cartão cadastrado',style:Theme.of(context).textTheme.titleMedium),const SizedBox(height:6),const Text('Cadastre um cartão para ver as transações.',textAlign:TextAlign.center),const SizedBox(height:16),FilledButton.icon(onPressed:()async{await widget.onAddCard();if(mounted)setState((){});},icon:const Icon(Icons.add),label:const Text('Cadastrar cartão'))])));

  Widget _transactionCard(BuildContext context,FinancialTransaction t){
    final status=_txStatus(t);
    final displayAmount=status?.amount??t.amount;
    final displayDescription=status?.description??t.description;
    final income=t.type=='income';
    final valueColor=income?Colors.green:Colors.red;
    final dateLabel=fmt(dateOf(t.date)??DateTime.now());
    final meta=t.isCard
        ? '$dateLabel - ${timeOf(t)} - ${income?'Entrada':'Saída'} - ${sourceLabel(t.source)}'
        : '$dateLabel - ${income?'Entrada':'Saída'} - ${sourceLabel(t.source)}';
    final scheme=Theme.of(context).colorScheme;
    return Card(
      margin:EdgeInsets.zero,
      // Mantem o mesmo efeito flutuante usado em Ultimas transacoes da Home.
      // Um pouco mais de contraste aqui evita que a sombra suma sobre o fundo claro
      // da pagina de Transacoes em telas de celular.
      elevation:4,
      shadowColor:Colors.black.withOpacity(.22),
      surfaceTintColor:Colors.transparent,
      color:scheme.surface,
      shape:RoundedRectangleBorder(
        borderRadius:BorderRadius.circular(16),
        side:BorderSide(color:scheme.outlineVariant.withOpacity(.55)),
      ),
      child:ListTile(
        shape:RoundedRectangleBorder(borderRadius:BorderRadius.circular(16)),
        onTap:()=>widget.onEdit(t),
        contentPadding:const EdgeInsets.fromLTRB(16,8,6,8),
        leading:Icon(
          t.isCard?Icons.credit_card:(income?Icons.arrow_downward:Icons.arrow_upward),
          color:income?Colors.green:Colors.red,
        ),
        title:Text(
          displayDescription,
          maxLines:1,
          overflow:TextOverflow.ellipsis,
          style:Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight:FontWeight.w600),
        ),
        subtitle:Column(
          crossAxisAlignment:CrossAxisAlignment.start,
          mainAxisSize:MainAxisSize.min,
          children:[
            const SizedBox(height:4),
            Wrap(
              crossAxisAlignment:WrapCrossAlignment.center,
              spacing:8,
              runSpacing:4,
              children:[
                BankBadge(accountName(t)),
                Row(mainAxisSize:MainAxisSize.min,children:[
                  Icon(categoryIconData(categoryOf(t)?.icon,categoryName(t)),size:15,color:scheme.onSurfaceVariant),
                  const SizedBox(width:4),
                  Text(categoryName(t),style:Theme.of(context).textTheme.bodySmall),
                ]),
              ],
            ),
            const SizedBox(height:4),
            Text(meta,maxLines:1,overflow:TextOverflow.ellipsis),
            if(status!=null)...[const SizedBox(height:6),Align(alignment:Alignment.centerLeft,child:Container(padding:const EdgeInsets.symmetric(horizontal:9,vertical:3),decoration:BoxDecoration(color:status.color.withOpacity(.13),borderRadius:BorderRadius.circular(999)),child:Text(status.label,style:TextStyle(color:status.color,fontWeight:FontWeight.w700,fontSize:11)))),const SizedBox(height:7)],
          ],
        ),
        isThreeLine:true,
        trailing:Row(
          mainAxisSize:MainAxisSize.min,
          children:[
            Text(
              '${income?'':'-'}${money(displayAmount.abs())}',
              style:Theme.of(context).textTheme.titleLarge?.copyWith(color:valueColor,fontWeight:FontWeight.w800),
            ),
            PopupMenuButton<String>(
              onSelected:(v){if(v=='edit')widget.onEdit(t);if(v=='attachments')widget.onAttachments(t);if(v=='delete')widget.onDelete(t.id);},
              itemBuilder:(_)=>const[PopupMenuItem(value:'edit',child:Text('Editar')),PopupMenuItem(value:'attachments',child:Text('Comprovantes')),PopupMenuItem(value:'delete',child:Text('Excluir'))],
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _filters()async{String s=source,t=type,a=amount,p=period;int? acc=accountId,cat=categoryId;DateTime? start=startDate,end=endDate;final ok=await showDialog<bool>(context:context,builder:(c)=>StatefulBuilder(builder:(c,set)=>AlertDialog(title:const Text('Filtros'),content:SizedBox(width:480,child:SingleChildScrollView(child:Column(mainAxisSize:MainAxisSize.min,children:[DropdownButtonFormField<String>(initialValue:p,decoration:const InputDecoration(labelText:'Data / período'),items:const[DropdownMenuItem(value:'all',child:Text('Qualquer período')),DropdownMenuItem(value:'today',child:Text('Hoje')),DropdownMenuItem(value:'7d',child:Text('Últimos 7 dias')),DropdownMenuItem(value:'30d',child:Text('Últimos 30 dias')),DropdownMenuItem(value:'month',child:Text('Este mês')),DropdownMenuItem(value:'custom',child:Text('Período personalizado'))],onChanged:(v)=>set(()=>p=v??'all')),if(p=='custom')Row(children:[Expanded(child:ListTile(title:const Text('De'),subtitle:Text(start==null?'Selecionar':fmt(start!)),onTap:()async{final d=await showDatePicker(context:c,initialDate:start??DateTime.now(),firstDate:DateTime(2000),lastDate:DateTime(2100));if(d!=null)set(()=>start=d);})),Expanded(child:ListTile(title:const Text('Até'),subtitle:Text(end==null?'Selecionar':fmt(end!)),onTap:()async{final d=await showDatePicker(context:c,initialDate:end??DateTime.now(),firstDate:DateTime(2000),lastDate:DateTime(2100));if(d!=null)set(()=>end=d);}))]),DropdownButtonFormField<String>(initialValue:s,decoration:const InputDecoration(labelText:'Origem'),items:const[DropdownMenuItem(value:'all',child:Text('Todas')),DropdownMenuItem(value:'open_finance',child:Text('Open Finance')),DropdownMenuItem(value:'manual',child:Text('Manuais'))],onChanged:(v)=>set(()=>s=v??'all')),DropdownButtonFormField<String>(initialValue:t,decoration:const InputDecoration(labelText:'Tipo'),items:const[DropdownMenuItem(value:'all',child:Text('Todos')),DropdownMenuItem(value:'income',child:Text('Entradas')),DropdownMenuItem(value:'expense',child:Text('Saídas'))],onChanged:(v)=>set(()=>t=v??'all')),DropdownButtonFormField<String>(initialValue:a,decoration:const InputDecoration(labelText:'Valor'),items:const[DropdownMenuItem(value:'all',child:Text('Todos')),DropdownMenuItem(value:'100',child:Text('Até R\$ 100')),DropdownMenuItem(value:'100_500',child:Text('R\$ 100–500')),DropdownMenuItem(value:'500_1000',child:Text('R\$ 500–1.000')),DropdownMenuItem(value:'1000',child:Text('Acima de R\$ 1.000'))],onChanged:(v)=>set(()=>a=v??'all')),DropdownButtonFormField<int?>(initialValue:acc,decoration:const InputDecoration(labelText:'Banco/conta'),items:[const DropdownMenuItem<int?>(value:null,child:Text('Todos')),...widget.snapshot.accounts.map((e)=>DropdownMenuItem<int?>(value:e.id,child:Text(e.institutionName)))],onChanged:(v)=>set(()=>acc=v)),DropdownButtonFormField<int?>(initialValue:cat,decoration:const InputDecoration(labelText:'Categoria'),items:[const DropdownMenuItem<int?>(value:null,child:Text('Todas')),...widget.snapshot.categories.map((e)=>DropdownMenuItem<int?>(value:e.id,child:Text(e.name)))],onChanged:(v)=>set(()=>cat=v))]))),actions:[TextButton(onPressed:()=>Navigator.pop(c,false),child:const Text('Cancelar')),FilledButton(onPressed:()=>Navigator.pop(c,true),child:const Text('Aplicar'))])));if(ok==true)setState((){source=s;type=t;amount=a;period=p;accountId=acc;categoryId=cat;startDate=start;endDate=end;});}
}
