import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../core/input_masks.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key,required this.onLogin,required this.onRegister,required this.onForgot,required this.onReset,required this.onGoogle,required this.onGuest,required this.busy,this.error});
  final Future<void> Function(String,String) onLogin;
  final Future<String> Function({required String name,required String cpf,required String email,required String birthDate,required String sex,required String password}) onRegister;
  final Future<String> Function(String) onForgot;
  final Future<String> Function(String,String,String) onReset;
  final Future<void> Function() onGoogle;
  final Future<void> Function() onGuest;
  final bool busy; final String? error;
  @override State<LoginScreen> createState()=>_LoginScreenState();
}
class _LoginScreenState extends State<LoginScreen>{
  final email=TextEditingController(),password=TextEditingController(); bool hide=true,register=false; String? local;
  bool get _loginReady => email.text.trim().isNotEmpty && password.text.isNotEmpty;
  @override void initState(){super.initState();email.addListener(_refreshLoginState);password.addListener(_refreshLoginState);}
  void _refreshLoginState(){if(mounted)setState((){});}
  @override void dispose(){email.removeListener(_refreshLoginState);password.removeListener(_refreshLoginState);email.dispose();password.dispose();super.dispose();}
  Future<void> _registerDialog() async {final name=TextEditingController(),cpf=TextEditingController(),mail=TextEditingController(),birth=TextEditingController(),pass=TextEditingController(),confirm=TextEditingController();String sex='prefer_not_to_say';bool obscure=true;await showDialog(context:context,builder:(ctx)=>StatefulBuilder(builder:(ctx,setD)=>AlertDialog(title:const Text('Criar conta'),content:SizedBox(width:430,child:SingleChildScrollView(child:Column(mainAxisSize:MainAxisSize.min,children:[_field(name,'Nome completo'),_field(cpf,'CPF',keyboard:TextInputType.number,inputFormatters:[FilteringTextInputFormatter.digitsOnly,const CpfInputFormatter()]),_field(mail,'E-mail',keyboard:TextInputType.emailAddress),_field(birth,'Data de nascimento (DD/MM/AAAA)',keyboard:TextInputType.datetime),Padding(padding:const EdgeInsets.only(bottom:18),child:DropdownButtonFormField<String>(initialValue:sex,decoration:const InputDecoration(labelText:'Sexo'),items:const[DropdownMenuItem(value:'male',child:Text('Masculino')),DropdownMenuItem(value:'female',child:Text('Feminino')),DropdownMenuItem(value:'other',child:Text('Outro')),DropdownMenuItem(value:'prefer_not_to_say',child:Text('Prefiro não informar'))],onChanged:(v){if(v!=null)sex=v;})),_field(pass,'Senha',obscure:obscure,suffix:IconButton(onPressed:()=>setD(()=>obscure=!obscure),icon:Icon(obscure?Icons.visibility:Icons.visibility_off))),_field(confirm,'Confirmar senha',obscure:obscure),const SizedBox(height:8),const Text('Ao cadastrar, você concorda com os Termos de Uso e declara ter lido a Política de Privacidade.',style:TextStyle(fontSize:12))]))),actions:[TextButton(onPressed:()=>Navigator.pop(ctx),child:const Text('Cancelar')),FilledButton(onPressed:()async{try{if(pass.text.length<6)throw Exception('A senha deve ter pelo menos 6 caracteres.');if(pass.text!=confirm.text)throw Exception('As senhas não coincidem.');final p=birth.text.split('/');if(p.length!=3)throw Exception('Use a data no formato DD/MM/AAAA.');final iso='${p[2]}-${p[1].padLeft(2,'0')}-${p[0].padLeft(2,'0')}';final msg=await widget.onRegister(name:name.text,cpf:cpf.text,email:mail.text,birthDate:iso,sex:sex,password:pass.text);if(ctx.mounted){Navigator.pop(ctx);_message(msg+'\n\nToque em Entendido e entre normalmente.');}}catch(e){if(ctx.mounted)ScaffoldMessenger.of(ctx).showSnackBar(SnackBar(content:Text(e.toString().replaceFirst('Exception: ',''))));}},child:const Text('Cadastrar'))])));name.dispose();cpf.dispose();mail.dispose();birth.dispose();pass.dispose();confirm.dispose();}
  Widget _field(TextEditingController c,String label,{TextInputType? keyboard,bool obscure=false,Widget? suffix,List<TextInputFormatter>? inputFormatters})=>Padding(padding:const EdgeInsets.only(bottom:18),child:TextField(controller:c,keyboardType:keyboard,inputFormatters:inputFormatters,obscureText:obscure,decoration:InputDecoration(labelText:label,border:const OutlineInputBorder(),suffixIcon:suffix)));
  Future<void> _forgot() async {final mail=TextEditingController(text:email.text);await showDialog(context:context,builder:(ctx)=>AlertDialog(title:const Text('Esqueci minha senha'),content:SizedBox(width:400,child:TextField(controller:mail,keyboardType:TextInputType.emailAddress,decoration:const InputDecoration(labelText:'E-mail',border:OutlineInputBorder()))),actions:[TextButton(onPressed:()=>Navigator.pop(ctx),child:const Text('Cancelar')),FilledButton(onPressed:()async{try{final msg=await widget.onForgot(mail.text);if(ctx.mounted){Navigator.pop(ctx);_reset(mail.text,msg);}}catch(e){if(ctx.mounted)ScaffoldMessenger.of(ctx).showSnackBar(SnackBar(content:Text(e.toString().replaceFirst('Exception: ',''))));}},child:const Text('Enviar código'))]));mail.dispose();}
  Future<void> _reset(String mail,String info) async {final code=TextEditingController(),pass=TextEditingController();await showDialog(context:context,builder:(ctx)=>AlertDialog(title:const Text('Redefinir senha'),content:SizedBox(width:400,child:Column(mainAxisSize:MainAxisSize.min,children:[Text(info),const SizedBox(height:12),_field(code,'Código de 6 dígitos',keyboard:TextInputType.number),_field(pass,'Nova senha',obscure:true)])),actions:[TextButton(onPressed:()=>Navigator.pop(ctx),child:const Text('Depois')),FilledButton(onPressed:()async{try{final msg=await widget.onReset(mail,code.text,pass.text);if(ctx.mounted){Navigator.pop(ctx);_message(msg);}}catch(e){if(ctx.mounted)ScaffoldMessenger.of(ctx).showSnackBar(SnackBar(content:Text(e.toString().replaceFirst('Exception: ',''))));}},child:const Text('Alterar senha'))]));code.dispose();pass.dispose();}
  void _message(String text)=>showDialog(context:context,builder:(ctx)=>AlertDialog(content:Text(text),actions:[FilledButton(onPressed:()=>Navigator.pop(ctx),child:const Text('Entendido'))]));
  Widget _mobileLogin(BuildContext context) {
    const navy = Color(0xFF061B31);
    const blue = Color(0xFF1478FF);
    const purple = Color(0xFF6D4CFF);
    const text = Color(0xFF10233D);
    const secondary = Color(0xFF6A778C);
    const outline = Color(0xFFD7E0EC);

    InputDecoration decoration(String label, IconData icon, {Widget? suffix}) => InputDecoration(
      labelText: label,
      labelStyle: const TextStyle(color: secondary),
      prefixIcon: Icon(icon, color: secondary),
      suffixIcon: suffix,
      filled: true,
      fillColor: Colors.white,
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(20),
        borderSide: const BorderSide(color: outline),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(20),
        borderSide: const BorderSide(color: outline),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(20),
        borderSide: const BorderSide(color: blue, width: 1.5),
      ),
    );

    return Scaffold(
      body: Container(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [navy, Color(0xFF0C3569), purple],
          ),
        ),
        child: SafeArea(
          bottom: false,
          child: Column(
            children: [
              Expanded(
                flex: 36,
                child: Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Container(
                        width: 86,
                        height: 86,
                        decoration: BoxDecoration(
                          color: Colors.white,
                          borderRadius: BorderRadius.circular(24),
                          boxShadow: const [BoxShadow(color: Colors.black26, blurRadius: 22, offset: Offset(0, 10))],
                        ),
                        padding: const EdgeInsets.all(10),
                        child: Image.asset('assets/financeapp_logo.png', fit: BoxFit.contain),
                      ),
                      const SizedBox(height: 18),
                      const Text(
                        'FinanceApp',
                        style: TextStyle(color: Colors.white, fontWeight: FontWeight.w800, fontSize: 40),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        'Sua vida financeira,\nmais simples.',
                        textAlign: TextAlign.center,
                        style: TextStyle(color: Colors.white.withOpacity(0.76), fontSize: 20, height: 1.25),
                      ),
                    ],
                  ),
                ),
              ),
              Expanded(
                flex: 64,
                child: Container(
                  width: double.infinity,
                  decoration: const BoxDecoration(
                    color: Color(0xFFF8FBFF),
                    borderRadius: BorderRadius.vertical(top: Radius.circular(40)),
                  ),
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.fromLTRB(28, 30, 28, 28),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        TextField(
                          controller: email,
                          keyboardType: TextInputType.emailAddress,
                          decoration: decoration('E-mail', Icons.email_outlined),
                        ),
                        const SizedBox(height: 14),
                        TextField(
                          controller: password,
                          obscureText: hide,
                          onSubmitted: (_) => (widget.busy || !_loginReady) ? null : widget.onLogin(email.text.trim(), password.text),
                          decoration: decoration(
                            'Senha',
                            Icons.lock_outline,
                            suffix: IconButton(
                              onPressed: () => setState(() => hide = !hide),
                              icon: Icon(hide ? Icons.visibility_outlined : Icons.visibility_off_outlined, color: secondary),
                            ),
                          ),
                        ),
                        if (widget.error != null) ...[
                          const SizedBox(height: 10),
                          Text(widget.error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                        ],
                        const SizedBox(height: 18),
                        SizedBox(
                          height: 58,
                          child: FilledButton(
                            onPressed: (widget.busy || !_loginReady) ? null : () => widget.onLogin(email.text.trim(), password.text),
                            style: FilledButton.styleFrom(
                              backgroundColor: blue,
                              foregroundColor: Colors.white,
                              disabledBackgroundColor: const Color(0xFFB8C0CC),
                              disabledForegroundColor: const Color(0xFFF4F6F8),
                              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(22)),
                            ),
                            child: Text(
                              widget.busy ? 'Entrando...' : 'Entrar',
                              style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 18),
                            ),
                          ),
                        ),
                        TextButton(
                          onPressed: widget.busy ? null : _forgot,
                          child: const Text('Esqueci minha senha', style: TextStyle(color: blue, fontWeight: FontWeight.w600)),
                        ),
                        Row(
                          children: [
                            const Expanded(child: Divider(color: outline)),
                            Padding(
                              padding: const EdgeInsets.symmetric(horizontal: 12),
                              child: Text('ou', style: TextStyle(color: secondary.withOpacity(0.9))),
                            ),
                            const Expanded(child: Divider(color: outline)),
                          ],
                        ),
                        const SizedBox(height: 10),
                        SizedBox(
                          height: 56,
                          child: OutlinedButton(
                            onPressed: widget.busy ? null : widget.onGoogle,
                            style: OutlinedButton.styleFrom(
                              foregroundColor: text,
                              side: const BorderSide(color: outline),
                              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(22)),
                            ),
                            child: const Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                _GoogleGLogo(size: 24),
                                SizedBox(width: 10),
                                Text('Entrar com Google', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 16)),
                              ],
                            ),
                          ),
                        ),
                        const SizedBox(height: 18),
                        const Text('Ainda não tem uma conta?', textAlign: TextAlign.center, style: TextStyle(color: secondary)),
                        TextButton(
                          onPressed: widget.busy ? null : _registerDialog,
                          child: const Text('Criar conta', style: TextStyle(color: blue, fontWeight: FontWeight.w700)),
                        ),
                        const SizedBox(height: 8),
                        SizedBox(
                          height: 56,
                          child: OutlinedButton(
                            onPressed: widget.busy ? null : widget.onGuest,
                            style: OutlinedButton.styleFrom(
                              backgroundColor: const Color(0xFFEAF3FF),
                              foregroundColor: blue,
                              side: BorderSide.none,
                              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(22)),
                            ),
                            child: const Row(
                              children: [
                                Icon(Icons.person_outline),
                                SizedBox(width: 10),
                                Expanded(child: Text('Continuar sem cadastro', style: TextStyle(fontWeight: FontWeight.w600))),
                                Icon(Icons.chevron_right),
                              ],
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _desktopLogin(BuildContext context) {
    const navy = Color(0xFF061B31);
    const blue = Color(0xFF1478FF);
    const purple = Color(0xFF6D4CFF);
    const text = Color(0xFF10233D);
    const secondary = Color(0xFF6A778C);
    const outline = Color(0xFFD7E0EC);

    InputDecoration decoration(String label, IconData icon, {Widget? suffix}) => InputDecoration(
      labelText: label,
      labelStyle: const TextStyle(color: secondary),
      prefixIcon: Icon(icon, color: secondary),
      suffixIcon: suffix,
      filled: true,
      fillColor: Colors.white,
      border: OutlineInputBorder(borderRadius: BorderRadius.circular(18), borderSide: const BorderSide(color: outline)),
      enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(18), borderSide: const BorderSide(color: outline)),
      focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(18), borderSide: const BorderSide(color: blue, width: 1.5)),
    );

    return Scaffold(
      backgroundColor: const Color(0xFFF5F8FC),
      body: SafeArea(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(28),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 1240, maxHeight: 760),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(34),
                child: Material(
                  elevation: 16,
                  shadowColor: Colors.black26,
                  child: Row(
                    children: [
                      Expanded(
                        flex: 11,
                        child: Container(
                          decoration: const BoxDecoration(
                            gradient: LinearGradient(
                              begin: Alignment.topLeft,
                              end: Alignment.bottomRight,
                              colors: [navy, Color(0xFF0C3569), purple],
                            ),
                          ),
                          child: Stack(
                            fit: StackFit.expand,
                            children: [
                              Positioned(right: -120, top: -100, child: Container(width: 360, height: 360, decoration: BoxDecoration(shape: BoxShape.circle, color: Colors.white.withOpacity(0.05)))),
                              Positioned(left: -110, bottom: -130, child: Container(width: 400, height: 400, decoration: BoxDecoration(shape: BoxShape.circle, color: blue.withOpacity(0.12)))),
                              Positioned(right: 48, top: 48, child: Wrap(spacing: 10, runSpacing: 10, children: List.generate(12, (_) => Container(width: 5, height: 5, decoration: BoxDecoration(color: Colors.white.withOpacity(0.55), shape: BoxShape.circle))))),
                              Padding(
                                padding: const EdgeInsets.symmetric(horizontal: 64, vertical: 56),
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Row(
                                      children: [
                                        Container(
                                          width: 62,
                                          height: 62,
                                          decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(18), boxShadow: const [BoxShadow(color: Colors.black26, blurRadius: 18, offset: Offset(0, 8))]),
                                          padding: const EdgeInsets.all(8),
                                          child: Image.asset('assets/financeapp_logo.png', fit: BoxFit.contain),
                                        ),
                                        const SizedBox(width: 16),
                                        const Text('FinanceApp', style: TextStyle(color: Colors.white, fontSize: 30, fontWeight: FontWeight.w800)),
                                      ],
                                    ),
                                    const Spacer(),
                                    const Text('Bem-vindo de volta!', style: TextStyle(color: Colors.white, fontSize: 44, fontWeight: FontWeight.w800, height: 1.05)),
                                    const SizedBox(height: 18),
                                    Text('Acesse sua conta e continue cuidando da sua vida financeira.', style: TextStyle(color: Colors.white.withOpacity(0.78), fontSize: 21, height: 1.45)),
                                    const SizedBox(height: 32),
                                    Container(width: 120, height: 6, decoration: BoxDecoration(color: blue, borderRadius: BorderRadius.circular(10))),
                                    const Spacer(),
                                    Text('Sua vida financeira, mais simples.', style: TextStyle(color: Colors.white.withOpacity(0.66), fontSize: 16)),
                                  ],
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                      Expanded(
                        flex: 9,
                        child: Container(
                          color: Colors.white,
                          child: Center(
                            child: SingleChildScrollView(
                              padding: const EdgeInsets.symmetric(horizontal: 64, vertical: 48),
                              child: ConstrainedBox(
                                constraints: const BoxConstraints(maxWidth: 430),
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.stretch,
                                  children: [
                                    const Text('Entrar', style: TextStyle(color: text, fontSize: 38, fontWeight: FontWeight.w800)),
                                    const SizedBox(height: 8),
                                    const Text('Use seus dados para acessar o FinanceApp.', style: TextStyle(color: secondary, fontSize: 16)),
                                    const SizedBox(height: 32),
                                    TextField(controller: email, keyboardType: TextInputType.emailAddress, decoration: decoration('E-mail', Icons.email_outlined)),
                                    const SizedBox(height: 14),
                                    TextField(
                                      controller: password,
                                      obscureText: hide,
                                      onSubmitted: (_) => (widget.busy || !_loginReady) ? null : widget.onLogin(email.text.trim(), password.text),
                                      decoration: decoration('Senha', Icons.lock_outline, suffix: IconButton(onPressed: () => setState(() => hide = !hide), icon: Icon(hide ? Icons.visibility_outlined : Icons.visibility_off_outlined, color: secondary))),
                                    ),
                                    Align(alignment: Alignment.centerRight, child: TextButton(onPressed: widget.busy ? null : _forgot, child: const Text('Esqueci minha senha', style: TextStyle(color: blue, fontWeight: FontWeight.w600)))),
                                    if (widget.error != null) Padding(padding: const EdgeInsets.only(bottom: 8), child: Text(widget.error!, style: TextStyle(color: Theme.of(context).colorScheme.error))),
                                    SizedBox(
                                      height: 56,
                                      child: FilledButton(
                                        onPressed: (widget.busy || !_loginReady) ? null : () => widget.onLogin(email.text.trim(), password.text),
                                        style: FilledButton.styleFrom(
                                          backgroundColor: blue,
                                          foregroundColor: Colors.white,
                                          disabledBackgroundColor: const Color(0xFFB8C0CC),
                                          disabledForegroundColor: const Color(0xFFF4F6F8),
                                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
                                        ),
                                        child: Text(widget.busy ? 'Entrando...' : 'Entrar', style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 17)),
                                      ),
                                    ),
                                    const SizedBox(height: 18),
                                    Row(children: [const Expanded(child: Divider(color: outline)), Padding(padding: const EdgeInsets.symmetric(horizontal: 14), child: Text('ou', style: TextStyle(color: secondary))), const Expanded(child: Divider(color: outline))]),
                                    const SizedBox(height: 18),
                                    SizedBox(
                                      height: 54,
                                      child: OutlinedButton.icon(
                                        onPressed: widget.busy ? null : widget.onGoogle,
                                        icon: const _GoogleGLogo(size: 22),
                                        label: const Text('Entrar com Google', style: TextStyle(color: text, fontWeight: FontWeight.w600)),
                                        style: OutlinedButton.styleFrom(side: const BorderSide(color: outline), shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18))),
                                      ),
                                    ),
                                    const SizedBox(height: 18),
                                    Row(mainAxisAlignment: MainAxisAlignment.center, children: [const Text('Ainda não tem uma conta?', style: TextStyle(color: secondary)), TextButton(onPressed: widget.busy ? null : _registerDialog, child: const Text('Criar conta', style: TextStyle(color: blue, fontWeight: FontWeight.w700)))]),
                                    const SizedBox(height: 8),
                                    SizedBox(
                                      height: 54,
                                      child: OutlinedButton.icon(
                                        onPressed: widget.busy ? null : widget.onGuest,
                                        icon: const Icon(Icons.person_outline),
                                        label: const Text('Continuar sem cadastro', style: TextStyle(fontWeight: FontWeight.w600)),
                                        style: OutlinedButton.styleFrom(backgroundColor: const Color(0xFFEAF3FF), foregroundColor: blue, side: BorderSide.none, shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18))),
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        if (constraints.maxWidth <= 700) return _mobileLogin(context);
        return _desktopLogin(context);
      },
    );
  }

}

class _GoogleGLogo extends StatelessWidget {
  const _GoogleGLogo({this.size = 24});
  final double size;

  @override
  Widget build(BuildContext context) => SizedBox(
        width: size,
        height: size,
        child: CustomPaint(painter: _GoogleGLogoPainter()),
      );
}

class _GoogleGLogoPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final stroke = size.width * 0.18;
    final inset = stroke / 2;
    final rect = Rect.fromLTWH(inset, inset, size.width - stroke, size.height - stroke);
    final p = Paint()
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.butt
      ..strokeWidth = stroke;

    void arc(Color color, double startDeg, double sweepDeg) {
      p.color = color;
      canvas.drawArc(rect, startDeg * math.pi / 180, sweepDeg * math.pi / 180, false, p);
    }

    arc(const Color(0xFF4285F4), -42, 86);
    arc(const Color(0xFF34A853), 44, 84);
    arc(const Color(0xFFFBBC05), 128, 80);
    arc(const Color(0xFFEA4335), 208, 110);

    final blue = Paint()
      ..color = const Color(0xFF4285F4)
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.square
      ..strokeWidth = stroke;
    canvas.drawLine(
      Offset(size.width * 0.53, size.height * 0.52),
      Offset(size.width * 0.91, size.height * 0.52),
      blue,
    );
    canvas.drawLine(
      Offset(size.width * 0.82, size.height * 0.52),
      Offset(size.width * 0.82, size.height * 0.73),
      blue,
    );
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

