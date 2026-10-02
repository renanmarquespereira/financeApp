import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'models.dart';

class FinanceNotificationPreferences {
  const FinanceNotificationPreferences({
    this.payables = true,
    this.invoices = true,
    this.budgets = true,
    this.monthlySummary = false,
  });
  final bool payables, invoices, budgets, monthlySummary;
  FinanceNotificationPreferences copyWith({bool? payables,bool? invoices,bool? budgets,bool? monthlySummary})=>FinanceNotificationPreferences(
    payables:payables??this.payables,
    invoices:invoices??this.invoices,
    budgets:budgets??this.budgets,
    monthlySummary:monthlySummary??this.monthlySummary,
  );
}

class FinanceNotificationService {
  static const _channel = MethodChannel('financeapp/notifications');
  static const _prefix = '__PAYABLE_V1__|';

  static Future<FinanceNotificationPreferences> readPreferences() async {
    final p=await SharedPreferences.getInstance();
    return FinanceNotificationPreferences(
      payables:p.getBool('notification_payables')??true,
      invoices:p.getBool('notification_invoices')??true,
      budgets:p.getBool('notification_budgets')??true,
      monthlySummary:p.getBool('notification_monthly_summary')??false,
    );
  }

  static Future<void> savePreferences(FinanceNotificationPreferences v) async {
    final p=await SharedPreferences.getInstance();
    await Future.wait([
      p.setBool('notification_payables',v.payables),
      p.setBool('notification_invoices',v.invoices),
      p.setBool('notification_budgets',v.budgets),
      p.setBool('notification_monthly_summary',v.monthlySummary),
    ]);
  }

  static bool get _apple => !kIsWeb && (defaultTargetPlatform==TargetPlatform.iOS || defaultTargetPlatform==TargetPlatform.macOS);

  static Future<bool> requestPermission() async {
    if(!_apple)return false;
    try{return (await _channel.invokeMethod<bool>('requestPermission'))??false;}catch(_){return false;}
  }

  static Future<void> sync(FinancialSnapshot snapshot) async {
    if(!_apple)return;
    final settings=await readPreferences();
    final now=DateTime.now();
    final items=<Map<String,dynamic>>[];

    void add(String id,String title,String body,DateTime when){
      if(!when.isAfter(now.add(const Duration(seconds:2))))return;
      items.add({'id':id,'title':title,'body':body,'year':when.year,'month':when.month,'day':when.day,'hour':when.hour,'minute':when.minute});
    }

    if(settings.payables){
      for(final tx in snapshot.transactions){
        final meta=_decodePayable(tx.description); if(meta==null)continue;
        final due=DateTime.tryParse(tx.date)?.toLocal(); if(due==null)continue;
        final day=DateTime(due.year,due.month,due.day,9);
        add('payable_${tx.id}_${due.year}${due.month}${due.day}', 'Conta a pagar', '${meta.description} vence em ${meta.reminderDays} dia(s) • ${_money(meta.amount)}', day.subtract(Duration(days:meta.reminderDays)));
        add('payable_due_${tx.id}_${due.year}${due.month}${due.day}', 'Conta vence hoje', '${meta.description} • ${_money(meta.amount)}', day);
      }
    }

    if(settings.invoices){
      final prefs=await SharedPreferences.getInstance();
      final today=DateTime(now.year,now.month,now.day);
      for(final invoice in _realInvoices(snapshot)){
        final due=DateTime(invoice.due.year,invoice.due.month,invoice.due.day);
        final days=due.difference(today).inDays;
        final label=(invoice.card.nickname?.trim().isNotEmpty==true)?invoice.card.nickname!.trim():'${invoice.card.bankName} •••• ${invoice.card.lastFour}';
        final invoiceKey='${invoice.card.id}_${invoice.end.year}${invoice.end.month.toString().padLeft(2,'0')}${invoice.end.day.toString().padLeft(2,'0')}';
        final amount=_money(invoice.remaining);
        if(days>5){
          add('invoice_upcoming_$invoiceKey','Fatura próxima do vencimento','$label vence em 5 dias • $amount',DateTime(due.year,due.month,due.day,9).subtract(const Duration(days:5)));
          add('invoice_due_$invoiceKey','Fatura vence hoje','$label • Em aberto $amount',DateTime(due.year,due.month,due.day,9));
        }else if(days>0){
          final sent='notification_invoice_upcoming_$invoiceKey';
          if(prefs.getBool(sent)!=true){
            add('invoice_upcoming_now_$invoiceKey','Fatura próxima do vencimento','$label vence em $days dia(s) • $amount',now.add(const Duration(seconds:5)));
            await prefs.setBool(sent,true);
          }
          add('invoice_due_$invoiceKey','Fatura vence hoje','$label • Em aberto $amount',DateTime(due.year,due.month,due.day,9));
        }else if(days==0){
          final sent='notification_invoice_due_$invoiceKey';
          if(prefs.getBool(sent)!=true){
            add('invoice_due_now_$invoiceKey','Fatura vence hoje','$label • Em aberto $amount',now.add(const Duration(seconds:5)));
            await prefs.setBool(sent,true);
          }
        }else{
          final sent='notification_invoice_overdue_$invoiceKey';
          if(prefs.getBool(sent)!=true){
            add('invoice_overdue_now_$invoiceKey','Fatura vencida','$label venceu em ${_brDay(invoice.due)} • Em aberto $amount',now.add(const Duration(seconds:5)));
            await prefs.setBool(sent,true);
          }
        }
      }
    }

    if(settings.budgets){
      final monthKey='${now.year}-${now.month.toString().padLeft(2,'0')}';
      final prefs=await SharedPreferences.getInstance();
      for(final budget in snapshot.budgets.where((b)=>b.amount>0)){
        final spent=snapshot.transactions.where((t)=>t.categoryId==budget.categoryId&&t.amount<0&&_sameMonth(t.date,now)).fold<double>(0,(s,t)=>s+t.amount.abs());
        final ratio=spent/budget.amount;
        final threshold=ratio>=1?100:ratio>=.8?80:null;
        if(threshold==null)continue;
        final sentKey='notification_budget_sent_${budget.categoryId}_${monthKey}_$threshold';
        if(prefs.getBool(sentKey)==true)continue;
        final category=snapshot.categories.where((c)=>c.id==budget.categoryId).firstOrNull?.name??'Categoria';
        add('budget_${budget.categoryId}_${monthKey}_$threshold', threshold==100?'Limite atingido':'Atenção ao limite', '$category está em ${(ratio*100).round()}% • ${_money(spent)} de ${_money(budget.amount)}', now.add(const Duration(seconds:5)));
        await prefs.setBool(sentKey,true);
      }
    }

    if(settings.monthlySummary){
      final firstNext=DateTime(now.year,now.month+1,1,9);
      add('summary_${firstNext.year}_${firstNext.month}', 'Resumo mensal', 'Abra o FinanceApp para revisar entradas, saídas e categorias do mês.', firstNext);
    }

    try{await _channel.invokeMethod('sync',{'items':items});}catch(_){/* projeto Apple ainda pode nao ter sido gerado */}
  }

  static _Payable? _decodePayable(String value){
    if(!value.startsWith(_prefix))return null;
    final p=value.substring(_prefix.length).split('|'); if(p.length<3)return null;
    final days=int.tryParse(p[0]), cents=int.tryParse(p[1]); if(days==null||cents==null)return null;
    try{final d=utf8.decode(base64Decode(p.sublist(2).join('|'))).trim();if(d.isEmpty)return null;return _Payable(days.clamp(1,30).toInt(),cents/100,d);}catch(_){return null;}
  }
  static DateTime _nextDue(DateTime now,int day){
    DateTime make(int y,int m){final last=DateTime(y,m+1,0).day;return DateTime(y,m,day.clamp(1,last).toInt());}
    var d=make(now.year,now.month);if(DateTime(d.year,d.month,d.day).isBefore(DateTime(now.year,now.month,now.day)))d=make(now.month==12?now.year+1:now.year,now.month==12?1:now.month+1);return d;
  }

  static DateTime _closingForMonth(CreditCardInfo card,DateTime month){
    final last=DateTime(month.year,month.month+1,0).day;
    final day=(card.closingDay??31).clamp(1,last).toInt();
    return DateTime(month.year,month.month,day);
  }
  static DateTime _invoiceEndForDate(CreditCardInfo card,DateTime date){
    final d=DateTime(date.year,date.month,date.day);
    final close=_closingForMonth(card,d);
    return d.isAfter(close)?_closingForMonth(card,DateTime(d.year,d.month+1)):close;
  }
  static DateTime? _invoiceDueForEnd(CreditCardInfo card,DateTime end){
    final day=card.dueDay;if(day==null)return null;
    var y=end.year,m=end.month;
    if(day<=end.day){m++;if(m==13){m=1;y++;}}
    final last=DateTime(y,m+1,0).day;
    return DateTime(y,m,day.clamp(1,last).toInt());
  }
  static List<_InvoiceReminder> _realInvoices(FinancialSnapshot snapshot){
    final out=<_InvoiceReminder>[];
    for(final card in snapshot.cards.where((c)=>c.active&&c.dueDay!=null)){
      final groups=<String,List<FinancialTransaction>>{};
      final ends=<String,DateTime>{};
      for(final t in snapshot.transactions.where((t)=>t.cardId==card.id&&t.source!='card_payment')){
        final raw=DateTime.tryParse(t.date)?.toLocal();if(raw==null)continue;
        final end=_invoiceEndForDate(card,raw);
        final key='${end.year}-${end.month.toString().padLeft(2,'0')}-${end.day.toString().padLeft(2,'0')}';
        ends[key]=end;(groups[key]??=<FinancialTransaction>[]).add(t);
      }
      for(final entry in groups.entries){
        final end=ends[entry.key]!;
        final total=(-entry.value.fold<double>(0,(sum,t)=>sum+t.amount)).clamp(0,double.infinity).toDouble();
        if(total<=0.005)continue;
        final paid=snapshot.transactions.where((t)=>t.cardId==card.id&&t.source=='card_payment'&&_dayKey(t.purchaseDate)==entry.key).fold<double>(0,(sum,t)=>sum+t.amount.abs());
        final remaining=(total-paid).clamp(0,double.infinity).toDouble();if(remaining<=0.005)continue;
        final due=_invoiceDueForEnd(card,end);if(due==null)continue;
        out.add(_InvoiceReminder(card,end,due,remaining));
      }
    }
    return out;
  }
  static String? _dayKey(String? raw){final d=raw==null?null:DateTime.tryParse(raw)?.toLocal();return d==null?null:'${d.year}-${d.month.toString().padLeft(2,'0')}-${d.day.toString().padLeft(2,'0')}';}
  static String _brDay(DateTime d)=>'${d.day.toString().padLeft(2,'0')}/${d.month.toString().padLeft(2,'0')}/${d.year}';
  static bool _sameMonth(String raw,DateTime now){final d=DateTime.tryParse(raw)?.toLocal();return d!=null&&d.year==now.year&&d.month==now.month;}
  static String _money(double v)=>'R\$ ${v.toStringAsFixed(2).replaceAll('.', ',')}';
}

class _InvoiceReminder { const _InvoiceReminder(this.card,this.end,this.due,this.remaining); final CreditCardInfo card; final DateTime end,due; final double remaining; }

class _Payable { const _Payable(this.reminderDays,this.amount,this.description); final int reminderDays; final double amount; final String description; }

extension _FirstOrNull<E> on Iterable<E>{E? get firstOrNull{final i=iterator;return i.moveNext()?i.current:null;}}
