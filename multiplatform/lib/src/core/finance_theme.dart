import 'package:flutter/material.dart';

ThemeData financeTheme(Brightness brightness) {
  final dark = brightness == Brightness.dark;
  final scheme = ColorScheme.fromSeed(
    seedColor: const Color(0xFF1479F8),
    brightness: brightness,
  ).copyWith(
    primary: Color(dark ? 0xFF65A7FF : 0xFF1479F8),
    onPrimary: Color(dark ? 0xFF052A55 : 0xFFFFFFFF),
    primaryContainer: Color(dark ? 0xFF123B6D : 0xFFEAF2FF),
    secondary: Color(dark ? 0xFFA997FF : 0xFF6D4DF4),
    surface: Color(dark ? 0xFF0C1B2D : 0xFFFFFFFF),
    onSurface: Color(dark ? 0xFFF4F7FC : 0xFF111827),
    onSurfaceVariant: Color(dark ? 0xFFB9C5D6 : 0xFF667085),
    outline: Color(dark ? 0xFF253950 : 0xFFE1E7F0),
    error: Color(dark ? 0xFFFF6F7C : 0xFFE34B58),
  );
  final popupSurface = Color(dark ? 0xFF0C1B2D : 0xFFFFFFFF);
  final elevatedSurface = Color(dark ? 0xFF12243A : 0xFFF7F9FC);
  final popupBorder = BorderSide(color: scheme.outline, width: 1);
  final rounded14 = RoundedRectangleBorder(borderRadius: BorderRadius.circular(14));
  final rounded18 = RoundedRectangleBorder(borderRadius: BorderRadius.circular(18));
  final rounded24 = RoundedRectangleBorder(borderRadius: BorderRadius.circular(24));

  return ThemeData(
    useMaterial3: true,
    brightness: brightness,
    colorScheme: scheme,
    scaffoldBackgroundColor: Color(dark ? 0xFF07111F : 0xFFF7F9FC),
    dividerColor: scheme.outline,
    cardTheme: CardThemeData(
      color: popupSurface,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      shape: rounded18.copyWith(side: popupBorder),
      margin: EdgeInsets.zero,
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: elevatedSurface,
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
      labelStyle: TextStyle(color: scheme.onSurfaceVariant),
      hintStyle: TextStyle(color: scheme.onSurfaceVariant),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide: popupBorder,
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide: popupBorder,
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide: BorderSide(color: scheme.primary, width: 1.5),
      ),
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: popupSurface,
      surfaceTintColor: Colors.transparent,
      elevation: dark ? 8 : 12,
      shadowColor: Colors.black.withValues(alpha: dark ? 0.35 : 0.12),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16), side: popupBorder),
      titleTextStyle: TextStyle(
        color: scheme.onSurface,
        fontSize: 20,
        fontWeight: FontWeight.w700,
        height: 1.2,
      ),
      contentTextStyle: TextStyle(
        color: scheme.onSurfaceVariant,
        fontSize: 14,
        height: 1.45,
      ),
      insetPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 24),
      actionsPadding: const EdgeInsets.fromLTRB(20, 12, 20, 20),
    ),
    popupMenuTheme: PopupMenuThemeData(
      color: popupSurface,
      surfaceTintColor: Colors.transparent,
      elevation: dark ? 7 : 10,
      shadowColor: Colors.black.withValues(alpha: dark ? 0.32 : 0.12),
      shape: rounded18.copyWith(side: popupBorder),
      textStyle: TextStyle(color: scheme.onSurface, fontSize: 14),
      labelTextStyle: WidgetStatePropertyAll(TextStyle(color: scheme.onSurface, fontSize: 14)),
    ),
    bottomSheetTheme: BottomSheetThemeData(
      backgroundColor: popupSurface,
      modalBackgroundColor: popupSurface,
      surfaceTintColor: Colors.transparent,
      elevation: 12,
      modalElevation: 16,
      showDragHandle: true,
      dragHandleColor: scheme.outline,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
      ),
    ),
    datePickerTheme: DatePickerThemeData(
      backgroundColor: popupSurface,
      surfaceTintColor: Colors.transparent,
      headerBackgroundColor: elevatedSurface,
      headerForegroundColor: scheme.onSurface,
      dividerColor: scheme.outline,
      shape: rounded24.copyWith(side: popupBorder),
      dayForegroundColor: WidgetStateProperty.resolveWith((states) {
        if (states.contains(WidgetState.selected)) return scheme.onPrimary;
        if (states.contains(WidgetState.disabled)) return scheme.onSurfaceVariant.withValues(alpha: 0.45);
        return scheme.onSurface;
      }),
      dayBackgroundColor: WidgetStateProperty.resolveWith((states) {
        if (states.contains(WidgetState.selected)) return scheme.primary;
        return Colors.transparent;
      }),
      todayForegroundColor: WidgetStatePropertyAll(scheme.primary),
      todayBorder: BorderSide(color: scheme.primary),
    ),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      backgroundColor: dark ? const Color(0xFF172B45) : const Color(0xFF172B4D),
      contentTextStyle: const TextStyle(color: Colors.white, fontWeight: FontWeight.w500),
      shape: rounded14,
      elevation: 8,
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size(0, 44),
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 12),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
        textStyle: const TextStyle(fontWeight: FontWeight.w700),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        minimumSize: const Size(0, 44),
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 12),
        side: popupBorder,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
        textStyle: const TextStyle(fontWeight: FontWeight.w700),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        minimumSize: const Size(0, 42),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
        textStyle: const TextStyle(fontWeight: FontWeight.w700),
      ),
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: scheme.surface,
      indicatorColor: scheme.primaryContainer,
    ),
    appBarTheme: AppBarTheme(
      backgroundColor: Color(dark ? 0xFF07111F : 0xFFF7F9FC),
      foregroundColor: scheme.onSurface,
      surfaceTintColor: Colors.transparent,
    ),
  );
}

/// Compatibility palette for existing hand-designed cards. Brand gradients retain their colors.
Color financeColor(BuildContext context,int light) {
  if(Theme.of(context).brightness!=Brightness.dark)return Color(light);
  const backgrounds={0xFFF6F8FC,0xFFF7F9FC};
  const surfaces={0xFFF7FAFF,0xFFFAF8FF,0xFFF8FBFF,0xFFF8FAFD,0xFFF4F6FA,0xFFF1F4F8,0xFFF9FAFC,0xFFF1F4F9,0xFFEAF2FF,0xFFE8EEFA,0xFFE8EEFF};
  const borders={0xFFE4E9F1,0xFFDCE6F7,0xFFE5EAF2,0xFFE9EDF4,0xFFE6EAF0,0xFFD8E6FF};
  const text={0xFF182230,0xFF111827,0xFF1F2937,0xFF263B5E,0xFF172B4D,0xFF42526E,0xFF25324A,0xFF344054,0xFF183B72,0xFF234C88,0xFF374151};
  const muted={0xFF667085,0xFF748094,0xFF7B8494,0xFF5F6B7A,0xFF7A8496,0xFF697386,0xFF6B778C,0xFF607085};
  if(backgrounds.contains(light))return const Color(0xFF07111F);
  if(surfaces.contains(light))return const Color(0xFF12243A);
  if(borders.contains(light))return const Color(0xFF253950);
  if(text.contains(light))return const Color(0xFFF4F7FC);
  if(muted.contains(light))return const Color(0xFFB9C5D6);
  if({0xFFEAFBF4,0xFFCFF4E5}.contains(light))return const Color(0xFF123A31);
  if({0xFFFFF0F2,0xFFFFDDE2}.contains(light))return const Color(0xFF40232E);
  return Color(light);
}

/// Reflow instead of shrinking accessibility text or forcing a device-wide scale.
class AdaptivePair extends StatelessWidget {
  const AdaptivePair({super.key,required this.children,this.minWidth=190,this.spacing=10});
  final List<Widget> children; final double minWidth,spacing;
  @override Widget build(BuildContext context)=>LayoutBuilder(builder:(context,c){
    final scale=MediaQuery.textScalerOf(context).scale(14)/14;
    final stacked=c.maxWidth<(minWidth*2*scale+spacing);
    return stacked?Column(crossAxisAlignment:CrossAxisAlignment.stretch,children:[for(var i=0;i<children.length;i++)...[if(i>0)SizedBox(height:spacing),children[i]]]):Row(crossAxisAlignment:CrossAxisAlignment.start,children:[for(var i=0;i<children.length;i++)...[if(i>0)SizedBox(width:spacing),Expanded(child:children[i])]]);
  });
}
