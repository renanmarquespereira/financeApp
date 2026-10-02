import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';
import '../../core/app_customization.dart';
import '../../core/notification_service.dart';

class AppSettingsScreen extends StatefulWidget {
  const AppSettingsScreen({super.key,required this.value,required this.themeMode,required this.onTheme,required this.onSave,required this.onCategories,required this.onDataManagement,required this.onOnboarding});
  final AppCustomization value; final ThemeMode themeMode; final ValueChanged<ThemeMode> onTheme;
  final Future<void> Function(AppCustomization) onSave; final VoidCallback onCategories,onDataManagement,onOnboarding;
  @override State<AppSettingsScreen> createState()=>_AppSettingsScreenState();
}

class _AppSettingsScreenState extends State<AppSettingsScreen>{
  late AppCustomization value; late ThemeMode themeMode;
  Future<void> _saveQueue=Future<void>.value();
  @override void initState(){super.initState();value=widget.value;themeMode=widget.themeMode;}

  void apply(AppCustomization next){
    setState(()=>value=next);
    // Mantem a ordem das alteracoes mesmo quando o usuario toca rapidamente.
    _saveQueue=_saveQueue.then((_)=>widget.onSave(next)).catchError((Object e){
      if(mounted){ScaffoldMessenger.of(context).showSnackBar(SnackBar(content:Text('Não foi possível salvar automaticamente: $e')));}
    });
  }

  Widget sw(String t,String s,bool v,ValueChanged<bool> f,{bool locked=false})=>SwitchListTile(
    dense:true,contentPadding:const EdgeInsets.symmetric(horizontal:8),title:Text(t),subtitle:Text(s),value:v,onChanged:locked?null:f);

  Widget section({required String title,required IconData icon,required List<Widget> children})=>Card(
    margin:const EdgeInsets.only(bottom:10),clipBehavior:Clip.antiAlias,
    child:ExpansionTile(
      leading:Icon(icon),title:Text(title,style:Theme.of(context).textTheme.titleMedium),
      tilePadding:const EdgeInsets.symmetric(horizontal:14),childrenPadding:const EdgeInsets.fromLTRB(8,0,8,8),
      children:children,
    ),
  );


  Future<void> _openNotifications() async {
    var prefs=await FinanceNotificationService.readPreferences();
    if(!mounted)return;
    await showDialog<void>(context:context,builder:(c)=>StatefulBuilder(builder:(c,setLocal){
      Future<void> save(FinanceNotificationPreferences next)async{prefs=next;setLocal((){});await FinanceNotificationService.savePreferences(next);}
      Widget item(String title,String subtitle,bool value,ValueChanged<bool> change)=>SwitchListTile(
        contentPadding:const EdgeInsets.symmetric(horizontal:4),title:Text(title),subtitle:Text(subtitle),value:value,onChanged:change);
      return AlertDialog(
        title:const Text('Notificações'),
        content:SizedBox(width:520,child:SingleChildScrollView(child:Column(mainAxisSize:MainAxisSize.min,children:[
          const Align(alignment:Alignment.centerLeft,child:Text('Escolha os lembretes financeiros que deseja receber.')),
          const SizedBox(height:8),
          item('Contas a pagar','Usa o prazo definido no cadastro da conta.',prefs.payables,(v)=>save(prefs.copyWith(payables:v))),
          item('Faturas de cartão','Aviso 3 dias antes e no dia do vencimento.',prefs.invoices,(v)=>save(prefs.copyWith(invoices:v))),
          item('Categorias / limites','Avisa ao chegar a 80% e 100% do limite mensal.',prefs.budgets,(v)=>save(prefs.copyWith(budgets:v))),
          item('Resumo mensal','Lembrete no primeiro dia de cada mês.',prefs.monthlySummary,(v)=>save(prefs.copyWith(monthlySummary:v))),
        ]))),
        actions:[
          if(!kIsWeb && (defaultTargetPlatform==TargetPlatform.iOS || defaultTargetPlatform==TargetPlatform.macOS))
            TextButton(onPressed:()async{await FinanceNotificationService.requestPermission();},child:const Text('Permitir notificações')),
          FilledButton(onPressed:()=>Navigator.pop(c),child:const Text('Concluir')),
        ],
      );
    }));
  }

  @override Widget build(BuildContext context)=>Scaffold(
    appBar:AppBar(title:const Text('Configurações do App')),
    body:ListView(padding:const EdgeInsets.all(16),children:[
      Text('Aparência',style:Theme.of(context).textTheme.titleLarge),const SizedBox(height:4),
      const Text('Ajuste somente a aparência e a densidade visual do aplicativo.'),const SizedBox(height:10),
      SegmentedButton<ThemeMode>(
        segments:const[
          ButtonSegment(value:ThemeMode.system,icon:Icon(Icons.settings_suggest_outlined),label:Text('Sistema')),
          ButtonSegment(value:ThemeMode.light,icon:Icon(Icons.light_mode_outlined),label:Text('Claro')),
          ButtonSegment(value:ThemeMode.dark,icon:Icon(Icons.dark_mode_outlined),label:Text('Escuro')),
        ],
        selected:{themeMode},onSelectionChanged:(v){final m=v.first;setState(()=>themeMode=m);widget.onTheme(m);},
      ),
      sw('Dashboard compacto','Reduz espaços para mostrar mais informações na tela.',value.compactDashboard,(v)=>apply(value.copyWith(compactDashboard:v))),
      const SizedBox(height:10),
      section(title:'Navegação',icon:Icons.view_carousel_outlined,children:[
        sw('Início / Dashboard','Mostrar a tela inicial.',value.showDashboardTab,(v)=>apply(value.copyWith(showDashboardTab:v))),
        sw('Transações','Mostrar lançamentos e filtros.',value.showTransactionsTab,(v)=>apply(value.copyWith(showTransactionsTab:v))),
        sw('Previsão','Mostrar previsão financeira.',value.showForecastTab,(v)=>apply(value.copyWith(showForecastTab:v))),
        sw('Inteligência','Mostrar inteligência financeira e IA.',value.showIntelligenceTab,(v)=>apply(value.copyWith(showIntelligenceTab:v))),
        sw('Bancos','Mostrar bancos e cartões.',value.showAccountsTab,(v)=>apply(value.copyWith(showAccountsTab:v))),
      ]),
      section(title:'Personalizar Dashboard',icon:Icons.dashboard_customize_outlined,children:[
        const Padding(padding:EdgeInsets.fromLTRB(8,0,8,4),child:Align(alignment:Alignment.centerLeft,child:Text('Escolha quais informações aparecem na tela Início.'))),
        sw('Saldo consolidado','Mostrar saldo das movimentações da conta.',value.showCurrentBalance,(v)=>apply(value.copyWith(showCurrentBalance:v))),
        sw('Entradas','Mostrar total de entradas.',value.showIncome,(v)=>apply(value.copyWith(showIncome:v))),
        sw('Despesas','Mostrar total de saídas.',value.showExpenses,(v)=>apply(value.copyWith(showExpenses:v))),
        sw('Ações rápidas','Novo lançamento, Transações, Planejamento e Resumo.',value.showQuickActions,(v)=>apply(value.copyWith(showQuickActions:v))),
        sw('Transações recentes','Mostrar os lançamentos mais recentes.',value.showRecentTransactions,(v)=>apply(value.copyWith(showRecentTransactions:v))),
        sw('Orçamentos mensais','Mostrar limites e consumo por categoria.',value.showMonthlyBudgets,(v)=>apply(value.copyWith(showMonthlyBudgets:v))),
        sw('Maior gasto','Mostrar o maior gasto do mês.',value.showBiggestExpense,(v)=>apply(value.copyWith(showBiggestExpense:v))),
        sw('Faturas de cartão','Mostrar as faturas/cartões no Dashboard.',value.showInvoices,(v)=>apply(value.copyWith(showInvoices:v))),
        sw('Compras nos cartões','Mostrar compras de cartão separadas do saldo.',value.showCardPurchases,(v)=>apply(value.copyWith(showCardPurchases:v))),
      ]),
      section(title:'Organização financeira',icon:Icons.tune_outlined,children:[
        ListTile(leading:const Icon(Icons.category_outlined),title:const Text('Categorias'),subtitle:const Text('Criar, consultar e excluir categorias e seus limites.'),trailing:const Icon(Icons.chevron_right),onTap:widget.onCategories),
        ListTile(leading:const Icon(Icons.delete_sweep_outlined),title:const Text('Gerenciar dados'),subtitle:const Text('Ferramentas para administrar os dados financeiros.'),trailing:const Icon(Icons.chevron_right),onTap:widget.onDataManagement),
        ListTile(leading:const Icon(Icons.notifications_active_outlined),title:const Text('Notificações'),subtitle:const Text('Contas, faturas, limites e resumo mensal.'),trailing:const Icon(Icons.chevron_right),onTap:_openNotifications),
        ListTile(leading:const Icon(Icons.slideshow_outlined),title:const Text('Ver apresentação novamente'),subtitle:const Text('Rever as principais funções do FinanceApp.'),trailing:const Icon(Icons.chevron_right),onTap:widget.onOnboarding),
      ]),
      const SizedBox(height:8),
      const Center(child:Text('As alterações são salvas automaticamente.')),
      const SizedBox(height:16),
    ]),
  );
}
