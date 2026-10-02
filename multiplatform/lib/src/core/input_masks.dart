import 'package:flutter/services.dart';

class BrlMoneyInputFormatter extends TextInputFormatter {
  const BrlMoneyInputFormatter();
  @override
  TextEditingValue formatEditUpdate(TextEditingValue oldValue, TextEditingValue newValue) {
    final digits = newValue.text.replaceAll(RegExp(r'\D'), '');
    if (digits.isEmpty) return const TextEditingValue(text: '');
    final cents = int.parse(digits);
    final reais = cents ~/ 100;
    final centavos = cents % 100;
    final groups = reais.toString().replaceAllMapped(RegExp(r'\B(?=(\d{3})+(?!\d))'), (m) => '.');
    final text = 'R\$ $groups,${centavos.toString().padLeft(2, '0')}';
    return TextEditingValue(text: text, selection: TextSelection.collapsed(offset: text.length));
  }
}

double brlValue(String text) {
  final digits = text.replaceAll(RegExp(r'\D'), '');
  if (digits.isEmpty) return 0;
  return int.parse(digits) / 100.0;
}

String brlText(num value) {
  final cents = (value.abs() * 100).round();
  final reais = cents ~/ 100;
  final centavos = cents % 100;
  final groups = reais.toString().replaceAllMapped(RegExp(r'\B(?=(\d{3})+(?!\d))'), (m) => '.');
  return 'R\$ $groups,${centavos.toString().padLeft(2, '0')}';
}

class CpfInputFormatter extends TextInputFormatter {
  const CpfInputFormatter();
  @override
  TextEditingValue formatEditUpdate(TextEditingValue oldValue, TextEditingValue newValue) {
    var d = newValue.text.replaceAll(RegExp(r'\D'), '');
    if (d.length > 11) d = d.substring(0, 11);
    final b = StringBuffer();
    for (var i = 0; i < d.length; i++) {
      if (i == 3 || i == 6) b.write('.');
      if (i == 9) b.write('-');
      b.write(d[i]);
    }
    final text = b.toString();
    return TextEditingValue(text: text, selection: TextSelection.collapsed(offset: text.length));
  }
}

String cpfText(String? value) {
  final d = (value ?? '').replaceAll(RegExp(r'\D'), '');
  if (d.length != 11) return value ?? '';
  return '${d.substring(0,3)}.${d.substring(3,6)}.${d.substring(6,9)}-${d.substring(9)}';
}

class BrDateInputFormatter extends TextInputFormatter {
  const BrDateInputFormatter();
  @override
  TextEditingValue formatEditUpdate(TextEditingValue oldValue, TextEditingValue newValue) {
    var d = newValue.text.replaceAll(RegExp(r'\D'), '');
    if (d.length > 8) d = d.substring(0, 8);
    final b = StringBuffer();
    for (var i = 0; i < d.length; i++) {
      if (i == 2 || i == 4) b.write('/');
      b.write(d[i]);
    }
    final text = b.toString();
    return TextEditingValue(text: text, selection: TextSelection.collapsed(offset: text.length));
  }
}

String brDateText(String? value) {
  final raw = value ?? '';
  final iso = DateTime.tryParse(raw);
  if (iso != null) return '${iso.day.toString().padLeft(2,'0')}/${iso.month.toString().padLeft(2,'0')}/${iso.year}';
  return raw;
}

String? isoDateFromBr(String value) {
  final p = value.split('/');
  if (p.length != 3) return value.isEmpty ? null : value;
  final d = int.tryParse(p[0]), m = int.tryParse(p[1]), y = int.tryParse(p[2]);
  if (d == null || m == null || y == null) return null;
  final date = DateTime(y,m,d);
  if (date.day != d || date.month != m || date.year != y) return null;
  return '${y.toString().padLeft(4,'0')}-${m.toString().padLeft(2,'0')}-${d.toString().padLeft(2,'0')}';
}
