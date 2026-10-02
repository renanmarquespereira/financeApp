import 'dart:html' as html;
import 'dart:js_util' as js_util;
import 'dart:typed_data';

Future<bool> saveBytesInBrowser(Uint8List bytes, String fileName, String contentType) async {
  final blob = html.Blob(<dynamic>[bytes], contentType);
  final url = html.Url.createObjectUrlFromBlob(blob);
  try {
    final anchor = html.AnchorElement(href: url)
      ..download = fileName
      ..style.display = 'none';
    html.document.body?.append(anchor);
    anchor.click();
    anchor.remove();
    return true;
  } finally {
    html.Url.revokeObjectUrl(url);
  }
}

Future<bool> viewBytesInBrowser(Uint8List bytes, String fileName, String contentType) async {
  final blob = html.Blob(<dynamic>[bytes], contentType);
  final url = html.Url.createObjectUrlFromBlob(blob);
  final opened = html.window.open(url, '_blank');
  if (opened == null) {
    html.Url.revokeObjectUrl(url);
    return false;
  }
  Future<void>.delayed(const Duration(minutes: 2), () => html.Url.revokeObjectUrl(url));
  return true;
}

Future<bool> shareBytesInBrowser(Uint8List bytes, String fileName, String contentType) async {
  final navigator = html.window.navigator;
  if (!js_util.hasProperty(navigator, 'share')) return false;
  final file = html.File(<Object>[bytes], fileName, <String, dynamic>{'type': contentType});
  final data = js_util.jsify(<String, dynamic>{
    'title': fileName,
    'text': 'Comprovante do FinanceApp',
    'files': <html.File>[file],
  });
  if (js_util.hasProperty(navigator, 'canShare')) {
    final canShare = js_util.callMethod<bool>(navigator, 'canShare', <dynamic>[data]);
    if (!canShare) return false;
  }
  await js_util.promiseToFuture<void>(js_util.callMethod<dynamic>(navigator, 'share', <dynamic>[data]));
  return true;
}
