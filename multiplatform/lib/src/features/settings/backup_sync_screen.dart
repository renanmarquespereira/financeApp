import 'package:flutter/material.dart';
import '../../core/google_drive_backup.dart';
import '../../core/models.dart';

class BackupSyncScreen extends StatefulWidget{
  const BackupSyncScreen({super.key,required this.workspaceId,required this.snapshot,required this.onRestored});
  final String workspaceId;
  final FinancialSnapshot snapshot;
  final Future<void> Function(FinancialSnapshot) onRestored;
  @override State<BackupSyncScreen> createState()=>_BackupSyncScreenState();
}

class _BackupSyncScreenState extends State<BackupSyncScreen>{
  final service=GoogleDriveBackupService();
  bool busy=false;
  int interval=0,lastAt=0;
  String? email;

  @override void initState(){super.initState();_load();}
  Future<void> _load()async{
    final i=await GoogleDriveBackupService.intervalDays();
    final l=await GoogleDriveBackupService.lastBackupAt();
    final e=await GoogleDriveBackupService.accountEmail();
    if(mounted)setState((){interval=i;lastAt=l;email=e;});
  }
  String _dateLabel(DateTime value){
    String two(int v)=>v.toString().padLeft(2,'0');
    final d=value.toLocal();
    return '${two(d.day)}/${two(d.month)}/${d.year} às ${two(d.hour)}:${two(d.minute)}';
  }
  String get lastLabel=>lastAt<=0?'Ainda não realizado':_dateLabel(DateTime.fromMillisecondsSinceEpoch(lastAt));
  String get nextLabel{
    if(interval<=0)return 'Backup automático desativado';
    if(lastAt<=0)return 'Será definido após o primeiro backup';
    return _dateLabel(DateTime.fromMillisecondsSinceEpoch(lastAt).add(Duration(days:interval)));
  }
  Future<void> _run(Future<void> Function() action)async{
    if(busy)return;setState(()=>busy=true);
    try{await action();if(mounted)ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content:Text('Concluído.')));}
    catch(e){if(mounted)ScaffoldMessenger.of(context).showSnackBar(SnackBar(content:Text(e.toString().replaceFirst('Exception: ',''))));}
    finally{if(mounted){setState(()=>busy=false);await _load();}}
  }
  Future<void> _backup()=>_run(()async{final a=await service.connect();if(a==null)return;await service.upload(workspaceId:widget.workspaceId,snapshot:widget.snapshot,account:a);});
  Future<void> _chooseRestore()async{
    await _run(()async{
      final files=await service.list();if(!mounted)return;
      if(files.isEmpty){throw Exception('Nenhum backup do FinanceApp encontrado no Google Drive.');}
      final selected=await showDialog<DriveBackupFile>(context:context,builder:(c)=>AlertDialog(title:const Text('Restaurar backup'),content:SizedBox(width:520,height:360,child:ListView(children:files.map((f)=>ListTile(title:Text(f.name),subtitle:Text(f.createdTime?.toLocal().toString()??''),onTap:()=>Navigator.pop(c,f))).toList())),actions:[TextButton(onPressed:()=>Navigator.pop(c),child:const Text('Cancelar'))]));
      if(selected==null)return;
      final mode=await showDialog<String>(context:context,builder:(c)=>AlertDialog(
        title:const Text('Como deseja restaurar?'),
        content:const Text('Já existem dados neste aparelho. Você pode manter os dados atuais e adicionar o que estiver faltando, ou substituir os dados locais pelo conteúdo do backup.'),
        actions:[
          TextButton(onPressed:()=>Navigator.pop(c),child:const Text('Cancelar')),
          TextButton(onPressed:()=>Navigator.pop(c,'replace'),child:const Text('Substituir pelos dados do backup')),
          FilledButton(onPressed:()=>Navigator.pop(c,'merge'),child:const Text('Manter dados existentes')),
        ],
      ));
      if(mode==null)return;
      final snap=await service.restore(selected.id,mode:mode);await widget.onRestored(snap);
    });
  }
  Future<void> _manage()async{
    final files=await service.list();if(!mounted)return;
    await showDialog<void>(context:context,builder:(c)=>StatefulBuilder(builder:(c,setLocal)=>AlertDialog(title:const Text('Gerenciar backups'),content:SizedBox(width:560,height:420,child:files.isEmpty?const Center(child:Text('Nenhum backup encontrado.')):ListView(children:files.map((f)=>ListTile(title:Text(f.name),subtitle:Text(f.createdTime?.toLocal().toString()??''),trailing:IconButton(icon:const Icon(Icons.delete_outline),onPressed:()async{await service.delete(f.id);files.remove(f);setLocal((){});})).toList())),actions:[FilledButton(onPressed:()=>Navigator.pop(c),child:const Text('Concluir'))]))));
  }
  @override Widget build(BuildContext context)=>Scaffold(appBar:AppBar(title:const Text('Backup e sincronização')),body:ListView(padding:const EdgeInsets.all(16),children:[
    Card(child:ListTile(leading:const Icon(Icons.phone_android),title:const Text('Dados financeiros locais'),subtitle:const Text('O backup inclui todos os Workspaces e os dados financeiros locais. O Google Drive é usado somente como backup e restauração.'))),
    Card(child:ListTile(
      leading:const Icon(Icons.account_circle_outlined),
      title:Text(email??'Conta Google não conectada'),
      subtitle:Text('Último backup: $lastLabel\nPróximo backup: $nextLabel'),
      isThreeLine:true,
      trailing:email==null?TextButton(onPressed:busy?null:()async{await service.connect();await _load();},child:const Text('Conectar')):null,
    )),
    const SizedBox(height:8),
    FilledButton.icon(onPressed:busy?null:_backup,icon:const Icon(Icons.cloud_upload_outlined),label:const Text('Fazer backup agora')),
    const SizedBox(height:10),OutlinedButton.icon(onPressed:busy?null:_chooseRestore,icon:const Icon(Icons.restore),label:const Text('Restaurar backup')),
    const SizedBox(height:10),OutlinedButton.icon(onPressed:busy?null:_manage,icon:const Icon(Icons.folder_open_outlined),label:const Text('Gerenciar backups')),
    const Divider(height:32),Text('Backup automático',style:Theme.of(context).textTheme.titleMedium),const SizedBox(height:6),const Text('O backup é executado quando o FinanceApp for aberto após o intervalo escolhido.'),const SizedBox(height:10),
    DropdownButtonFormField<int>(value:interval,decoration:const InputDecoration(labelText:'Periodicidade',border:OutlineInputBorder()),items:const[DropdownMenuItem(value:0,child:Text('Desativado')),DropdownMenuItem(value:7,child:Text('A cada 7 dias')),DropdownMenuItem(value:15,child:Text('A cada 15 dias')),DropdownMenuItem(value:30,child:Text('A cada 30 dias'))],onChanged:busy?null:(v)async{final days=v??0;if(days>0&&await service.connect()==null)return;await GoogleDriveBackupService.setIntervalDays(days);if(mounted)setState(()=>interval=days);}),
    const SizedBox(height:16),const Text('Restaurar um backup nunca acontece automaticamente. Somente você pode iniciar uma restauração.',style:TextStyle(fontWeight:FontWeight.w600)),
  ]));
}
