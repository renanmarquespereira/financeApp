import 'package:flutter/material.dart';

class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key, required this.onFinish});
  final Future<void> Function() onFinish;

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> {
  int page = 0;

  static const pages = <({IconData icon, String title, String text})>[
    (
      icon: Icons.account_balance_wallet_outlined,
      title: 'Sua vida financeira em um só lugar',
      text:
          'Acompanhe entradas, despesas e saldo consolidado com uma visão simples do que acontece com o seu dinheiro.',
    ),
    (
      icon: Icons.credit_card_rounded,
      title: 'Bancos e cartões organizados',
      text:
          'Centralize contas, cartões e faturas por Workspace sem misturar compras no cartão com o saldo das contas.',
    ),
    (
      icon: Icons.show_chart_rounded,
      title: 'Planeje antes de gastar',
      text:
          'Use categorias, metas, previsão financeira e contas a pagar para enxergar o mês antes que ele termine.',
    ),
    (
      icon: Icons.auto_awesome_rounded,
      title: 'Inteligência para ajudar nas decisões',
      text:
          'Use os recursos de IA do FinanceApp para entender seus dados, criar planos e acompanhar cenários financeiros.',
    ),
  ];

  Future<void> _done() async => widget.onFinish();

  @override
  Widget build(BuildContext context) {
    final current = pages[page];
    final last = page == pages.length - 1;
    return Scaffold(
      body: Container(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [Color(0xFF071B33), Color(0xFF0D376A), Color(0xFF5145C8)],
          ),
        ),
        child: SafeArea(
          child: LayoutBuilder(
            builder: (context, constraints) {
              final wide = constraints.maxWidth >= 720;
              final horizontal = wide ? 48.0 : 22.0;
              return Stack(
                children: [
                  Align(
                    alignment: Alignment.topRight,
                    child: Padding(
                      padding: const EdgeInsets.all(10),
                      child: TextButton(
                        onPressed: _done,
                        style: TextButton.styleFrom(foregroundColor: Colors.white),
                        child: const Text('Pular'),
                      ),
                    ),
                  ),
                  Center(
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 560),
                      child: Padding(
                        padding: EdgeInsets.symmetric(horizontal: horizontal),
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Container(
                              width: wide ? 108 : 92,
                              height: wide ? 108 : 92,
                              decoration: BoxDecoration(
                                color: Colors.white.withValues(alpha: .14),
                                borderRadius: BorderRadius.circular(28),
                                border: Border.all(color: Colors.white.withValues(alpha: .22)),
                              ),
                              child: Icon(current.icon, color: Colors.white, size: wide ? 54 : 46),
                            ),
                            const SizedBox(height: 28),
                            Text(
                              current.title,
                              textAlign: TextAlign.center,
                              style: TextStyle(
                                color: Colors.white,
                                fontSize: wide ? 34 : 28,
                                height: 1.15,
                                fontWeight: FontWeight.w800,
                              ),
                            ),
                            const SizedBox(height: 14),
                            Text(
                              current.text,
                              textAlign: TextAlign.center,
                              style: TextStyle(
                                color: Colors.white.withValues(alpha: .86),
                                fontSize: wide ? 18 : 16,
                                height: 1.5,
                              ),
                            ),
                            const SizedBox(height: 30),
                            Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: List.generate(
                                pages.length,
                                (i) => AnimatedContainer(
                                  duration: const Duration(milliseconds: 180),
                                  margin: const EdgeInsets.symmetric(horizontal: 4),
                                  width: i == page ? 28 : 7,
                                  height: 7,
                                  decoration: BoxDecoration(
                                    color: i == page ? Colors.white : Colors.white.withValues(alpha: .32),
                                    borderRadius: BorderRadius.circular(99),
                                  ),
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                  Align(
                    alignment: Alignment.bottomCenter,
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 560),
                      child: Padding(
                        padding: EdgeInsets.fromLTRB(horizontal, 12, horizontal, 20),
                        child: Row(
                          children: [
                            if (page > 0) ...[
                              Expanded(
                                child: OutlinedButton(
                                  onPressed: () => setState(() => page--),
                                  style: OutlinedButton.styleFrom(
                                    foregroundColor: Colors.white,
                                    side: BorderSide(color: Colors.white.withValues(alpha: .45)),
                                    minimumSize: const Size.fromHeight(52),
                                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                                  ),
                                  child: const Text('Voltar'),
                                ),
                              ),
                              const SizedBox(width: 12),
                            ],
                            Expanded(
                              child: FilledButton(
                                onPressed: () async {
                                  if (last) {
                                    await _done();
                                  } else {
                                    setState(() => page++);
                                  }
                                },
                                style: FilledButton.styleFrom(
                                  backgroundColor: Colors.white,
                                  foregroundColor: const Color(0xFF123B6D),
                                  minimumSize: const Size.fromHeight(52),
                                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                                ),
                                child: Text(last ? 'Começar' : 'Próximo', style: const TextStyle(fontWeight: FontWeight.w800)),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}
