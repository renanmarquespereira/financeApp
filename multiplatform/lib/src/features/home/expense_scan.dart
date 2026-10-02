import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

class ScannedExpenseDraft {
  const ScannedExpenseDraft({required this.description,required this.rawCode,this.amount,this.dueDate,this.kind='code'});
  final String description;
  final String rawCode;
  final double? amount;
  final DateTime? dueDate;
  final String kind;
}

class ExpenseCodeParser {
  static const _banks=<String,String>{
    '001':'Banco do Brasil','033':'Santander','077':'Banco Inter','104':'Caixa',
    '237':'Bradesco','260':'Nubank','341':'Itaú','422':'Safra','756':'Sicoob',
  };

  static ScannedExpenseDraft? parse(String rawValue){
    final raw=rawValue.trim(); if(raw.isEmpty)return null;
    final pix=_pix(raw); if(pix!=null)return pix;
    final digits=raw.replaceAll(RegExp(r'\D'),'');
    if(digits.length==44&&!digits.startsWith('8'))return _bankBarcode(digits,raw);
    if(digits.length==47)return _typedLine(digits,raw);
    if(digits.length==44||digits.length==48)return ScannedExpenseDraft(description:'Conta / boleto lido',rawCode:raw,kind:'boleto');
    return ScannedExpenseDraft(description:'Despesa lida por código',rawCode:raw);
  }

  static ScannedExpenseDraft _bankBarcode(String d,String raw){
    final bank=_banks[d.substring(0,3)];
    final cents=int.tryParse(d.substring(9,19));
    final factor=int.tryParse(d.substring(5,9));
    return ScannedExpenseDraft(description:bank==null?'Boleto bancário':'Boleto • $bank',rawCode:raw,amount:(cents!=null&&cents>0)?cents/100:null,dueDate:_factorDate(factor),kind:'boleto');
  }
  static ScannedExpenseDraft _typedLine(String d,String raw){
    final bank=_banks[d.substring(0,3)];
    final cents=int.tryParse(d.substring(37,47));
    final factor=int.tryParse(d.substring(33,37));
    return ScannedExpenseDraft(description:bank==null?'Boleto bancário':'Boleto • $bank',rawCode:raw,amount:(cents!=null&&cents>0)?cents/100:null,dueDate:_factorDate(factor),kind:'boleto');
  }
  static DateTime? _factorDate(int? factor){
    if(factor==null||factor<=0)return null;
    final base=factor>=1000?DateTime.utc(2022,5,29):DateTime.utc(1997,10,7);
    return base.add(Duration(days:factor));
  }
  static ScannedExpenseDraft? _pix(String raw){
    if(!raw.startsWith('000201')||!raw.toUpperCase().contains('BR.GOV.BCB.PIX'))return null;
    final fields=_tlv(raw); final merchant=fields['59']?.trim(); final city=fields['60']?.trim();
    final amount=double.tryParse((fields['54']??'').replaceAll(',','.'));
    final who=(merchant?.isNotEmpty??false)?merchant:((city?.isNotEmpty??false)?city:null);
    return ScannedExpenseDraft(description:who==null?'Pix':'Pix • $who',rawCode:raw,amount:(amount!=null&&amount>0)?amount:null,kind:'pix');
  }
  static Map<String,String> _tlv(String s){
    final out=<String,String>{}; var i=0;
    while(i+4<=s.length){final id=s.substring(i,i+2);final len=int.tryParse(s.substring(i+2,i+4));if(len==null)break;final start=i+4,end=start+len;if(end>s.length)break;out[id]=s.substring(start,end);i=end;}
    return out;
  }
}

class ExpenseScannerPage extends StatefulWidget {
  const ExpenseScannerPage({super.key});
  @override State<ExpenseScannerPage> createState()=>_ExpenseScannerPageState();
}
class _ExpenseScannerPageState extends State<ExpenseScannerPage>{
  final MobileScannerController controller=MobileScannerController();
  bool handled=false;
  @override void dispose(){controller.dispose();super.dispose();}
  void _onDetect(BarcodeCapture capture){
    if(handled)return;
    final raw=capture.barcodes.map((b)=>b.rawValue).whereType<String>().firstWhere((v)=>v.trim().isNotEmpty,orElse:()=> '');
    if(raw.isEmpty)return;
    final draft=ExpenseCodeParser.parse(raw); if(draft==null)return;
    handled=true; controller.stop(); Navigator.of(context).pop(draft);
  }
  @override Widget build(BuildContext context)=>Scaffold(
    backgroundColor:Colors.black,
    appBar:AppBar(title:const Text('Ler boleto / QR Code'),backgroundColor:Colors.black,foregroundColor:Colors.white),
    body:Stack(fit:StackFit.expand,children:[
      MobileScanner(controller:controller,onDetect:_onDetect),
      Center(child:Container(width:300,height:190,decoration:BoxDecoration(border:Border.all(color:Colors.white,width:3),borderRadius:BorderRadius.circular(20)))),
      Positioned(left:24,right:24,bottom:32,child:Container(padding:const EdgeInsets.all(14),decoration:BoxDecoration(color:Colors.black.withOpacity(.65),borderRadius:BorderRadius.circular(14)),child:const Text('Aponte a câmera para o código de barras do boleto ou para o QR Code Pix. Você poderá conferir os dados antes de salvar.',textAlign:TextAlign.center,style:TextStyle(color:Colors.white)))),
    ]),
  );
}
