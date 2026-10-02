import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../core/api_client.dart';
import '../../core/models.dart';
import 'import_data_screen.dart';

class DataManagementScreen extends StatefulWidget {
  const DataManagementScreen({super.key,required this.snapshot,required this.onSave,required this.isGuest,required this.api,this.accessToken,required this.onAccountDeleted,required this.onRefresh,required this.onImportTransactions});
  final FinancialSnapshot snapshot;
  final Future<void> Function(FinancialSnapshot) onSave;
  final bool isGuest;
  final ApiClient api;
  final String? accessToken;
  final VoidCallback onAccountDeleted;
  final Future<void> Function() onRefresh;
  final Future<void> Function(List<FinancialTransaction>) onImportTransactions;
  @override State<DataManagementScreen> createState()=>_DataManagementScreenState();
}
class _DataManagementScreenState extends State<DataManagementScreen>{
  bool transactions=false,accounts=false,cards=false,categories=false,budgets=false,goals=false,busy=false;
  int get selectedCount=>[transactions,accounts,cards,categories,budgets,goals].where((e)=>e).length;
  Map<String,dynamic> _options({bool allFinancial=false,bool deleteAccount=false})=>{
    'transactions':allFinancial||transactions,'categories':allFinancial||categories,'accounts':allFinancial||accounts,'cards':allFinancial||cards,
    'budgets':allFinancial||budgets,'goals':allFinancial||goals,'open_finance':allFinancial,'workspace_ids':<String>[],'account_ids':<int>[],'delete_account':deleteAccount,
  };
  Future<String?> _deleteCode(String email) async {
    final controllers = List.generate(4, (_) => TextEditingController());
    final nodes = List.generate(4, (_) => FocusNode());

    final result = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Código de confirmação'),
        content: SizedBox(
          width: 420,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text('Enviamos um código de 4 dígitos para $email.'),
              const SizedBox(height: 16),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: List.generate(
                  4,
                  (i) => SizedBox(
                    width: 48,
                    child: TextField(
                      controller: controllers[i],
                      focusNode: nodes[i],
                      autofocus: i == 0,
                      textAlign: TextAlign.center,
                      keyboardType: TextInputType.number,
                      inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                      decoration: const InputDecoration(
                        counterText: '',
                        border: OutlineInputBorder(),
                      ),
                      onChanged: (value) {
                        final digits = value.replaceAll(RegExp(r'\D'), '');
                        if (digits.length > 1) {
                          final limit = digits.length > 4 ? 4 : digits.length;
                          final pasted = digits.substring(0, limit);
                          for (var j = 0; j < 4; j++) { controllers[j].text = j < pasted.length ? pasted[j] : ''; }
                          nodes[pasted.isEmpty ? 0 : (pasted.length > 4 ? 3 : pasted.length - 1)].requestFocus();
                        } else if (digits.isNotEmpty) {
                          controllers[i].text = digits[0];
                          if (i < 3) nodes[i + 1].requestFocus();
                        } else if (i > 0) {
                          nodes[i - 1].requestFocus();
                        }
                      },
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('Cancelar'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(
              dialogContext,
              controllers.map((e) => e.text).join(),
            ),
            child: const Text('Confirmar'),
          ),
        ],
      ),
    );

    for (final controller in controllers) {
      controller.dispose();
    }
    for (final node in nodes) {
      node.dispose();
    }

    return result?.length == 4 ? result : null;
  }
  Future<bool> _confirmGuest(String message) async=>await showDialog<bool>(context:context,builder:(c)=>AlertDialog(title:const Text('Confirmar exclusão'),content:Text(message),actions:[TextButton(onPressed:()=>Navigator.pop(c,false),child:const Text('Cancelar')),FilledButton(onPressed:()=>Navigator.pop(c,true),child:const Text('Apagar'))]))??false;
  Future<void> _execute(Map<String,dynamic> options,{required String label,required bool deleteAccount}) async{
    if(busy)return;
    if(widget.isGuest){
      if(deleteAccount){ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content:Text('O modo sem cadastro não possui conta de usuário para excluir.')));return;}
      if(!await _confirmGuest('$label será removido deste dispositivo/navegador.'))return;
      setState(()=>busy=true);var s=widget.snapshot;
      if(options['transactions']==true)s=s.copyWith(transactions:[]);if(options['accounts']==true)s=s.copyWith(accounts:[]);if(options['cards']==true)s=s.copyWith(cards:[]);if(options['categories']==true)s=s.copyWith(categories:[]);if(options['budgets']==true)s=s.copyWith(budgets:[]);if(options['goals']==true)s=s.copyWith(goals:[]);
      await widget.onSave(s);if(mounted){setState(()=>busy=false);ScaffoldMessenger.of(context).showSnackBar(SnackBar(content:Text('$label apagado.')));}return;
    }
    if(widget.accessToken==null)return;
    setState(()=>busy=true);
    try{final sent=await widget.api.requestDataDeletionCode(widget.accessToken!);if(!mounted)return;setState(()=>busy=false);final code=await _deleteCode((sent['email']??'seu e-mail').toString());if(code==null)return;setState(()=>busy=true);final result=await widget.api.confirmDataDeletion(widget.accessToken!,code,options,deletionToken:sent['deletion_token']?.toString());if(deleteAccount||result['login_deleted']==true){if(mounted)widget.onAccountDeleted();return;}await widget.onRefresh();if(mounted)ScaffoldMessenger.of(context).showSnackBar(SnackBar(content:Text('$label apagado.')));}catch(e){if(mounted)ScaffoldMessenger.of(context).showSnackBar(SnackBar(content:Text(e.toString().replaceFirst('Exception: ',''))));}finally{if(mounted)setState(()=>busy=false);}
  }
  @override Widget build(BuildContext context)=>Scaffold(appBar:AppBar(title:const Text('Gerenciar dados')),body:ListView(padding:const EdgeInsets.all(16),children:[
    Card(child:ListTile(leading:Icon(widget.isGuest?Icons.phone_android:Icons.mark_email_read_outlined),title:Text(widget.isGuest?'Dados locais — sem cadastro':'Exclusões protegidas por e-mail'),subtitle:Text(widget.isGuest?'Como não existe conta/e-mail, a confirmação é local.':'Antes de qualquer exclusão, enviaremos um código de 4 dígitos ao e-mail da conta.'))),
    Card(child:ListTile(leading:const Icon(Icons.upload_file_outlined),title:const Text('Importar dados'),subtitle:const Text('Excel, CSV, JSON, TXT ou XML, com pré-visualização e proteção contra duplicidades.'),trailing:const Icon(Icons.chevron_right),onTap:() async {final imported=await Navigator.of(context).push<bool>(MaterialPageRoute(builder:(_)=>ImportDataScreen(snapshot:widget.snapshot,onImportTransactions:widget.onImportTransactions,onRefresh:widget.onRefresh)));if(imported==true&&mounted){await widget.onRefresh();if(mounted)Navigator.of(context).pop(true);}})),
    const SizedBox(height:12),Text('Apagar dados selecionados',style:Theme.of(context).textTheme.titleLarge),
    _check('Transações','${widget.snapshot.transactions.length} lançamento(s)',transactions,(v)=>setState(()=>transactions=v)),_check('Contas','${widget.snapshot.accounts.length} conta(s)',accounts,(v)=>setState(()=>accounts=v)),_check('Cartões','${widget.snapshot.cards.length} cartão(ões)',cards,(v)=>setState(()=>cards=v)),_check('Categorias','${widget.snapshot.categories.length} categoria(s)',categories,(v)=>setState(()=>categories=v)),_check('Orçamentos','${widget.snapshot.budgets.length} orçamento(s)',budgets,(v)=>setState(()=>budgets=v)),_check('Metas','${widget.snapshot.goals.length} meta(s)',goals,(v)=>setState(()=>goals=v)),
    const SizedBox(height:10),FilledButton.icon(onPressed:busy||selectedCount==0?null:()=>_execute(_options(),label:'Dados selecionados',deleteAccount:false),icon:busy?const SizedBox.square(dimension:18,child:CircularProgressIndicator(strokeWidth:2)):const Icon(Icons.delete_outline),label:const Text('Apagar selecionados')),
    const Divider(height:36),Text('Exclusões completas',style:Theme.of(context).textTheme.titleLarge),const SizedBox(height:8),
    OutlinedButton.icon(onPressed:busy?null:()=>_execute(_options(allFinancial:true),label:'Todos os dados financeiros',deleteAccount:false),icon:const Icon(Icons.delete_sweep_outlined),label:const Text('Apagar todos os dados financeiros')),
    const SizedBox(height:10),if(!widget.isGuest)OutlinedButton.icon(style:OutlinedButton.styleFrom(foregroundColor:Theme.of(context).colorScheme.error,side:BorderSide(color:Theme.of(context).colorScheme.error)),onPressed:busy?null:()=>_execute(_options(allFinancial:true,deleteAccount:true),label:'Conta e todos os dados',deleteAccount:true),icon:const Icon(Icons.person_remove_outlined),label:const Text('Apagar tudo, inclusive minha conta')),
  ]));
  Widget _check(String title,String subtitle,bool value,ValueChanged<bool> change)=>Card(child:CheckboxListTile(value:value,onChanged:(v)=>change(v??false),title:Text(title),subtitle:Text(subtitle)));
}
