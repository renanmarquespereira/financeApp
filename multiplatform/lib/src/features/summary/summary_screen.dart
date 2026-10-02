import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:pdf/pdf.dart';
import 'package:pdf/widgets.dart' as pw;
import 'package:printing/printing.dart';
import '../../core/models.dart';

class SummaryScreen extends StatefulWidget {
  const SummaryScreen({super.key, required this.snapshot});
  final FinancialSnapshot snapshot;
  @override State<SummaryScreen> createState()=>_SummaryScreenState();
}

class _SummaryScreenState extends State<SummaryScreen>{
  bool annual=false; int month=DateTime.now().month, year=DateTime.now().year; bool generating=false;
  static const months=['Janeiro','Fevereiro','Março','Abril','Maio','Junho','Julho','Agosto','Setembro','Outubro','Novembro','Dezembro'];
  DateTime? _date(String s){try{return DateTime.parse(s);}catch(_){try{return DateTime.parse(s.substring(0,10));}catch(_){return null;}}}
  String _dateOnly(FinancialTransaction t){final d=_date(t.date);return d==null?(t.date.length > 10 ? t.date.substring(0, 10) : t.date):'${d.day.toString().padLeft(2,'0')}/${d.month.toString().padLeft(2,'0')}/${d.year}';}
  String _dateFor(FinancialTransaction t){final d=_date(t.date);if(d==null)return t.date;final base=_dateOnly(t);return t.isCard?'$base ${d.hour.toString().padLeft(2,'0')}:${d.minute.toString().padLeft(2,'0')}':base;}
  String money(num v)=>'R\$ ${v.toStringAsFixed(2).replaceAll('.',',')}';
  List<FinancialTransaction> get periodTransactions=>widget.snapshot.transactions.where((t){final d=_date(t.date);return d!=null&&d.year==year&&(annual||d.month==month);}).toList();
  // Mesma regra usada pelo Dashboard Web: compras vinculadas a cartao nao
  // entram no caixa; pagamento de fatura entra normalmente.
  bool _isCashFlowTransaction(FinancialTransaction t)=>
      !t.isCard || t.source=='card_payment';
  List<FinancialTransaction> get cash=>periodTransactions.where(_isCashFlowTransaction).toList();
  double get income=>cash.where((t)=>t.amount>0).fold<double>(0,(a,t)=>a+t.amount.abs());
  double get expenses=>cash.where((t)=>t.amount<0).fold<double>(0,(a,t)=>a+t.amount.abs());
  // No mensal, o saldo final e o acumulado real ate o fim do mes selecionado.
  // Isso evita reconstruir o saldo por uma base paralela e garante que Setembro/2026,
  // por exemplo, feche no mesmo valor do Dashboard quando nao existem meses futuros.
  double get openingBalance {
    if(annual)return 0;
    // O saldo final mensal ja e o acumulado autoritativo ate o fim do mes.
    // Portanto, o saldo anterior deve ser obtido retirando dele o movimento
    // liquido do proprio mes. Assim tela e PDF fecham com a mesma regra do
    // relatorio Android: saldo anterior + entradas - saidas = saldo final.
    return balance - income + expenses;
  }
  double get balance {
    if(annual){
      return cash.fold<double>(0,(a,t)=>a+t.amount);
    }
    final endExclusive=month==12?DateTime(year+1,1,1):DateTime(year,month+1,1);
    return widget.snapshot.transactions.where((t){
      final d=_date(t.date);
      return d!=null && d.isBefore(endExclusive) && _isCashFlowTransaction(t);
    }).fold<double>(0,(a,t)=>a+t.amount);
  }
  List<FinancialTransaction> get cardPurchases=>periodTransactions.where((t)=>t.isCard&&t.source!='card_payment').toList();
  String get periodTitle=>annual?'Ano $year':'${months[month-1]} de $year';
  Map<String,double> get categoryTotals{final r=<String,double>{};for(final t in cash.where((t)=>t.amount<0)){final c=widget.snapshot.categories.where((c)=>c.id==t.categoryId);final n=c.isEmpty?'Sem categoria':c.first.name;r[n]=(r[n]??0)+t.amount.abs();}return r;}
  Widget _metric(String title,double value)=>Card(child:Padding(padding:const EdgeInsets.all(16),child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[Text(title),const SizedBox(height:6),Text(money(value),style:Theme.of(context).textTheme.titleLarge)])));
  @override Widget build(BuildContext context){final cats=categoryTotals.entries.toList()..sort((a,b)=>b.value.compareTo(a.value));return Scaffold(appBar:AppBar(title:const Text('Resumo')),body:ListView(padding:const EdgeInsets.all(16),children:[
    SegmentedButton<bool>(segments:const[ButtonSegment(value:false,label:Text('Mensal'),icon:Icon(Icons.calendar_month)),ButtonSegment(value:true,label:Text('Anual'),icon:Icon(Icons.calendar_view_month))],selected:{annual},onSelectionChanged:(v)=>setState(()=>annual=v.first)),const SizedBox(height:12),
    Row(children:[if(!annual)Expanded(child:DropdownButtonFormField<int>(initialValue:month,decoration:const InputDecoration(labelText:'Mês'),items:List.generate(12,(i)=>DropdownMenuItem(value:i+1,child:Text(months[i]))),onChanged:(v)=>setState(()=>month=v??month))),if(!annual)const SizedBox(width:10),Expanded(child:DropdownButtonFormField<int>(initialValue:year,decoration:const InputDecoration(labelText:'Ano'),items:List.generate(9,(i){final y=DateTime.now().year-i;return DropdownMenuItem(value:y,child:Text('$y'));}),onChanged:(v)=>setState(()=>year=v??year)))]),const SizedBox(height:14),
    Row(crossAxisAlignment:CrossAxisAlignment.start,children:[Expanded(child:Wrap(spacing:8,runSpacing:8,children:[SizedBox(width:240,child:_metric('Entradas',income)),SizedBox(width:240,child:_metric('Despesas (saídas)',expenses)),SizedBox(width:240,child:_metric('Saldo consolidado',balance))])),const SizedBox(width:12),FilledButton.icon(onPressed:generating?null:_generatePdf,icon:generating?const SizedBox.square(dimension:18,child:CircularProgressIndicator(strokeWidth:2)):const Icon(Icons.picture_as_pdf),label:Text(generating?'Gerando PDF...':'Gerar PDF'))]),
    if(!annual) Padding(padding:const EdgeInsets.only(top:6),child:Text('Saldo anterior: ${money(openingBalance)}  •  Saldo final: ${money(balance)}',style:Theme.of(context).textTheme.bodySmall)),
    const SizedBox(height:18),Text('Entrada x saída',style:Theme.of(context).textTheme.titleMedium),Card(child:Padding(padding:const EdgeInsets.all(16),child:Column(children:[LinearProgressIndicator(value:(income+expenses)==0?0:income/(income+expenses)),const SizedBox(height:8),Row(mainAxisAlignment:MainAxisAlignment.spaceBetween,children:[Text('Entradas: ${money(income)}'),Text('Saídas: ${money(expenses)}')])]))),
    const SizedBox(height:14),Text('Gastos por categoria',style:Theme.of(context).textTheme.titleMedium),if(cats.isEmpty)const Card(child:ListTile(title:Text('Nenhuma saída registrada no período.')))else ...cats.take(12).map((e)=>Card(child:ListTile(title:Text(e.key),trailing:Text(money(e.value))))),
    _txSection('Entradas',cash.where((t)=>t.type=='income').toList()),_txSection('Despesas (saídas)',cash.where((t)=>t.type!='income').toList()),
    const SizedBox(height:12),Text('Despesas com cartão',style:Theme.of(context).textTheme.titleMedium),if(cardPurchases.isEmpty)const Card(child:ListTile(title:Text('Nenhuma compra com cartão no período.')))else ...widget.snapshot.cards.where((c)=>cardPurchases.any((t)=>t.cardId==c.id)).map((c){final tx=cardPurchases.where((t)=>t.cardId==c.id).toList();return Card(child:ExpansionTile(title:Text('Cartão final ${c.lastFour}'),subtitle:Text(c.bankName),trailing:Text(money(tx.fold<double>(0,(a,t)=>a+t.amount.abs()))),children:tx.map((t)=>ListTile(title:Text(t.description),subtitle:Text(_dateFor(t)),trailing:Text(money(t.amount.abs())))).toList()));}),
    const SizedBox(height:24)
  ]));}
  Widget _txSection(String title,List<FinancialTransaction> items)=>Column(crossAxisAlignment:CrossAxisAlignment.start,children:[const SizedBox(height:14),Text(title,style:Theme.of(context).textTheme.titleMedium),if(items.isEmpty)Card(child:ListTile(title:Text('Nenhum lançamento em $title.')))else ...items.take(100).map((t)=>Card(child:ListTile(title:Text(t.description),subtitle:Text(_dateOnly(t)),trailing:Text(money(t.amount.abs())))))]);

  Future<void> _generatePdf() async{setState(()=>generating=true);try{final bytes=await _pdf();await Printing.sharePdf(bytes:bytes,filename:'financeapp_resumo_${annual?'anual':'mensal'}_$year${annual?'':'_${month.toString().padLeft(2,'0')}'}.pdf');}finally{if(mounted)setState(()=>generating=false);}}
  List<pw.Widget> _pdfTxSection(String title,List<FinancialTransaction> items){
    final out=<pw.Widget>[pw.SizedBox(height:14),pw.Text(title,style:pw.TextStyle(fontSize:14,fontWeight:pw.FontWeight.bold)),pw.SizedBox(height:5)];
    if(items.isEmpty){out.add(pw.Text('Nenhum lançamento no período.'));return out;}
    final groups=<int,List<FinancialTransaction>>{};
    if(annual){for(final t in items){final d=_date(t.date);if(d!=null)(groups[d.month]??=[]).add(t);}}
    else{groups[month]=items;}
    for(final m in groups.keys.toList()..sort()){
      final rows=groups[m]!..sort((a,b)=>(_date(a.date)??DateTime(1900)).compareTo(_date(b.date)??DateTime(1900)));
      if(annual)out.addAll([pw.SizedBox(height:8),pw.Text(months[m-1],style:pw.TextStyle(fontSize:11,fontWeight:pw.FontWeight.bold,color:PdfColors.blue800)),pw.SizedBox(height:3)]);
      out.add(pw.TableHelper.fromTextArray(headers:['Data','Descrição','Valor'],data:rows.map((t)=>[_dateOnly(t),t.description,money(t.amount.abs())]).toList(),headerStyle:pw.TextStyle(fontWeight:pw.FontWeight.bold),cellStyle:const pw.TextStyle(fontSize:8)));
    }
    return out;
  }
  Future<Uint8List> _pdf() async{final doc=pw.Document();final cats=categoryTotals.entries.toList()..sort((a,b)=>b.value.compareTo(a.value));pw.Widget moneyBox(String title,double v)=>pw.Container(width:160,padding:const pw.EdgeInsets.all(12),decoration:pw.BoxDecoration(border:pw.Border.all(color:PdfColors.grey300),borderRadius:pw.BorderRadius.circular(6)),child:pw.Column(crossAxisAlignment:pw.CrossAxisAlignment.start,children:[pw.Text(title,style:const pw.TextStyle(fontSize:9)),pw.SizedBox(height:4),pw.Text(money(v),style:pw.TextStyle(fontSize:15,fontWeight:pw.FontWeight.bold))]));
    final content=<pw.Widget>[
      pw.Container(padding:const pw.EdgeInsets.all(18),decoration:pw.BoxDecoration(color:PdfColors.blue800,borderRadius:pw.BorderRadius.circular(8)),child:pw.Column(crossAxisAlignment:pw.CrossAxisAlignment.start,children:[pw.Text('Resumo financeiro',style:pw.TextStyle(color:PdfColors.white,fontSize:22,fontWeight:pw.FontWeight.bold)),pw.Text(periodTitle,style:const pw.TextStyle(color:PdfColors.white,fontSize:11)),pw.SizedBox(height:5),pw.Text('Compras no cartão aparecem na seção própria; somente o pagamento da fatura afeta o saldo consolidado.',style:const pw.TextStyle(color:PdfColors.white,fontSize:8))])),pw.SizedBox(height:14),pw.Wrap(spacing:8,runSpacing:8,children:[moneyBox('Entradas',income),moneyBox('Despesas (saídas)',expenses),moneyBox('SALDO',balance)]),if(!annual)pw.Padding(padding:const pw.EdgeInsets.only(top:6),child:pw.Text('Saldo anterior: ${money(openingBalance)}  •  Saldo final: ${money(balance)}',style:const pw.TextStyle(fontSize:8,color:PdfColors.grey700))),
      pw.SizedBox(height:16),pw.Text('Entradas x Saídas',style:pw.TextStyle(fontSize:14,fontWeight:pw.FontWeight.bold)),pw.SizedBox(height:6),pw.Text('Entradas ${money(income)}   •   Saídas ${money(expenses)}'),
      pw.SizedBox(height:16),pw.Text('Gastos por categoria',style:pw.TextStyle(fontSize:14,fontWeight:pw.FontWeight.bold)),if(cats.isEmpty)pw.Text('Nenhuma saída registrada no período.')else pw.TableHelper.fromTextArray(headers:['Categoria','Total'],data:cats.map((e)=>[e.key,money(e.value)]).toList(),headerStyle:pw.TextStyle(fontWeight:pw.FontWeight.bold),cellStyle:const pw.TextStyle(fontSize:8)),
      ..._pdfTxSection('Entradas',cash.where((t)=>t.type=='income').toList()),..._pdfTxSection('Despesas (saídas)',cash.where((t)=>t.type!='income').toList()),
      pw.SizedBox(height:16),pw.Text('Despesas com cartão',style:pw.TextStyle(fontSize:14,fontWeight:pw.FontWeight.bold)),
    ];
    if(cardPurchases.isEmpty){content.add(pw.Text('Nenhuma compra com cartão no período.'));}else{for(final card in widget.snapshot.cards.where((card)=>cardPurchases.any((t)=>t.cardId==card.id))){final tx=cardPurchases.where((t)=>t.cardId==card.id).toList()..sort((a,b)=>(_date(a.date)??DateTime(1900)).compareTo(_date(b.date)??DateTime(1900)));content.addAll([pw.SizedBox(height:8),pw.Text('Cartão final ${card.lastFour} • ${card.bankName}',style:pw.TextStyle(fontWeight:pw.FontWeight.bold)),pw.TableHelper.fromTextArray(headers:['Data/hora','Descrição','Valor'],data:tx.map((t)=>[_dateFor(t),t.description,money(t.amount.abs())]).toList(),headerStyle:pw.TextStyle(fontWeight:pw.FontWeight.bold),cellStyle:const pw.TextStyle(fontSize:8))]);}}
    doc.addPage(pw.MultiPage(pageFormat:PdfPageFormat.a4,margin:const pw.EdgeInsets.all(34),header:(c)=>pw.Column(crossAxisAlignment:pw.CrossAxisAlignment.start,children:[pw.Text('Finance App',style:pw.TextStyle(fontSize:13,fontWeight:pw.FontWeight.bold,color:PdfColors.blue800)),pw.Text('Resumo financeiro • $periodTitle',style:const pw.TextStyle(fontSize:9,color:PdfColors.grey700)),pw.SizedBox(height:8)]),build:(c)=>content));return doc.save();}
}
