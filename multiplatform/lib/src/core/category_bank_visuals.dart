import 'package:flutter/material.dart';

String _visualKey(String value) {
  const from = 'áàãâäéèêëíìîïóòõôöúùûüç';
  const to = 'aaaaaeeeeiiiiooooouuuuc';
  var out = value.trim().toLowerCase();
  for (var i = 0; i < from.length; i++) {
    out = out.replaceAll(from[i], to[i]);
  }
  return out;
}

const categoryIconChoices = <String, IconData>{
  'food': Icons.restaurant_rounded,
  'groceries': Icons.fastfood_rounded,
  'transport': Icons.directions_car_rounded,
  'home': Icons.home_rounded,
  'health': Icons.local_hospital_rounded,
  'education': Icons.school_rounded,
  'shopping': Icons.shopping_bag_rounded,
  'leisure': Icons.sports_esports_rounded,
  'work': Icons.work_rounded,
  'travel': Icons.flight_rounded,
  'pets': Icons.pets_rounded,
  'gift': Icons.celebration_rounded,
  'clothes': Icons.checkroom_rounded,
  'bills': Icons.receipt_long_rounded,
  'family': Icons.favorite_rounded,
  'other': Icons.category_rounded,
};

String inferredCategoryIconId(String name) {
  final key = _visualKey(name);
  bool any(List<String> parts) => parts.any(key.contains);
  if (any(['aliment', 'restaur', 'lanche', 'comida', 'cafe', 'padaria'])) return 'food';
  if (any(['mercado', 'supermerc', 'feira'])) return 'groceries';
  if (any(['transport', 'uber', 'combust', 'gasolina', 'carro', 'veiculo', 'oficina'])) return 'transport';
  if (any(['casa', 'moradia', 'aluguel', 'condominio'])) return 'home';
  if (any(['saude', 'farmacia', 'medic', 'hospital', 'dent'])) return 'health';
  if (any(['educ', 'curso', 'faculdade', 'escola', 'livro'])) return 'education';
  if (any(['roupa', 'vestuario', 'calcado'])) return 'clothes';
  if (any(['lazer', 'jogo', 'cinema', 'stream', 'entreten'])) return 'leisure';
  if (any(['viagem', 'hotel', 'passagem'])) return 'travel';
  if (any(['pet', 'cachorro', 'gato', 'veterin'])) return 'pets';
  if (any(['presente', 'doacao'])) return 'gift';
  if (any(['salario', 'trabalho', 'renda', 'pagamento'])) return 'work';
  if (any(['conta', 'energia', 'agua', 'internet', 'telefone', 'boleto'])) return 'bills';
  if (any(['compra', 'shopping'])) return 'shopping';
  if (any(['famil', 'filho'])) return 'family';
  return 'other';
}

IconData categoryIconData(String? iconId, String categoryName) {
  final id = categoryIconChoices.containsKey(iconId) ? iconId! : inferredCategoryIconId(categoryName);
  return categoryIconChoices[id] ?? Icons.category_rounded;
}

class CategoryIconPicker extends StatelessWidget {
  const CategoryIconPicker({super.key, required this.selected, required this.categoryName, required this.onSelected});
  final String? selected;
  final String categoryName;
  final ValueChanged<String> onSelected;

  @override
  Widget build(BuildContext context) {
    final effective = selected ?? inferredCategoryIconId(categoryName);
    return SizedBox(
      height: 48,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: categoryIconChoices.length,
        separatorBuilder: (_, __) => const SizedBox(width: 8),
        itemBuilder: (context, index) {
          final entry = categoryIconChoices.entries.elementAt(index);
          final isSelected = entry.key == effective;
          return InkWell(
            borderRadius: BorderRadius.circular(12),
            onTap: () => onSelected(entry.key),
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 140),
              width: 48,
              decoration: BoxDecoration(
                color: isSelected ? Theme.of(context).colorScheme.primaryContainer : Theme.of(context).colorScheme.surfaceVariant,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: isSelected ? Theme.of(context).colorScheme.primary : Theme.of(context).colorScheme.outlineVariant, width: isSelected ? 1.5 : 1),
              ),
              child: Icon(entry.value, color: isSelected ? Theme.of(context).colorScheme.primary : Theme.of(context).colorScheme.onSurfaceVariant),
            ),
          );
        },
      ),
    );
  }
}

class BankBadge extends StatelessWidget {
  const BankBadge(this.bankName, {super.key});
  final String bankName;

  @override
  Widget build(BuildContext context) {
    final key = _visualKey(bankName);
    Color bg;
    Color fg;
    if (key.contains('nubank') || key == 'nu') { bg = const Color(0xFF820AD1); fg = Colors.white; }
    else if (key.contains('banco do brasil') || key == 'bb') { bg = const Color(0xFFFFD800); fg = const Color(0xFF163B65); }
    else if (key.contains('itau')) { bg = const Color(0xFFEC7000); fg = Colors.white; }
    else if (key.contains('santander')) { bg = const Color(0xFFEC0000); fg = Colors.white; }
    else if (key.contains('bradesco')) { bg = const Color(0xFFCC092F); fg = Colors.white; }
    else if (key.contains('caixa')) { bg = const Color(0xFF0066B3); fg = Colors.white; }
    else if (key.contains('inter')) { bg = const Color(0xFFFF7A00); fg = Colors.white; }
    else if (key.contains('c6')) { bg = const Color(0xFF242424); fg = Colors.white; }
    else if (key.contains('picpay')) { bg = const Color(0xFF21C25E); fg = const Color(0xFF062A15); }
    else if (key.contains('mercado pago')) { bg = const Color(0xFF00AEEF); fg = const Color(0xFF082A3B); }
    else if (key.contains('pagbank') || key.contains('pagseguro')) { bg = const Color(0xFF00A650); fg = Colors.white; }
    else if (key.contains('neon')) { bg = const Color(0xFF00E1FF); fg = const Color(0xFF00313A); }
    else if (key.contains('btg')) { bg = const Color(0xFF0B2B50); fg = Colors.white; }
    else if (key.contains('safra')) { bg = const Color(0xFF0B345C); fg = Colors.white; }
    else if (key.contains('sicredi')) { bg = const Color(0xFF3FAE2A); fg = Colors.white; }
    else if (key.contains('sicoob')) { bg = const Color(0xFF006B62); fg = Colors.white; }
    else { bg = Theme.of(context).colorScheme.primaryContainer; fg = Theme.of(context).colorScheme.onPrimaryContainer; }

    return Container(
      constraints: const BoxConstraints(maxWidth: 150),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(999)),
      child: Text(bankName, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: fg, fontSize: 11, fontWeight: FontWeight.w700)),
    );
  }
}
