import 'package:flutter/material.dart';

/// Seletor de categorias com busca em tempo real, sem limite de itens.
Future<int?> showSearchableCategoryPicker({
  required BuildContext context,
  required List<MapEntry<int, String>> categories,
  int? selectedId,
  Future<int?> Function()? onAdd,
  Future<int?> Function()? onAdd,
}) async {
  String query = '';
  return showDialog<int?>(
    context: context,
    builder: (dialogContext) => StatefulBuilder(
      builder: (context, refresh) {
        String normalize(String input) {
          const from = 'áàâãäéèêëíìîïóòôõöúùûüç';
          const to = 'aaaaaeeeeiiiiooooouuuuc';
          final lower = input.toLowerCase();
          return lower.split('').map((c) {
            final index = from.indexOf(c);
            return index < 0 ? c : to[index];
          }).join();
        }
        final displayed = categories.where((e) => normalize(e.value).contains(normalize(query))).toList();
        return AlertDialog(
          title: const Text('Selecionar categoria'),
          content: SizedBox(
            width: 390,
            height: 390,
            child: Column(children: [
              Row(children: [
                Expanded(child: TextField(
                  autofocus: true,
                  decoration: const InputDecoration(
                    prefixIcon: Icon(Icons.search),
                    hintText: 'Pesquisar categoria...',
                    border: OutlineInputBorder(),
                  ),
                  onChanged: (value) => refresh(() => query = value),
                )),
                if (onAdd != null) IconButton.filledTonal(
                  tooltip: 'Nova categoria',
                  icon: const Icon(Icons.add),
                  onPressed: () async {
                    final createdId = await onAdd();
                    if (createdId != null && dialogContext.mounted) {
                      Navigator.pop(dialogContext, createdId);
                    }
                  },
                ),
              ]),
              const SizedBox(height: 12),
              Expanded(child: ListView(children: [
                ListTile(
                  title: const Text('Sem categoria'),
                  onTap: () => Navigator.pop(dialogContext, -1),
                ),
                ...displayed.map((e) => ListTile(
                  title: Text(e.value),
                  trailing: selectedId == e.key ? const Icon(Icons.check) : null,
                  onTap: () => Navigator.pop(dialogContext, e.key),
                )),
                if (displayed.isEmpty) const ListTile(title: Text('Nenhuma categoria encontrada')),
              ])),
            ]),
          ),
          actions: [TextButton(onPressed: () => Navigator.pop(dialogContext), child: const Text('Cancelar'))],
        );
      },
    ),
  );
}
