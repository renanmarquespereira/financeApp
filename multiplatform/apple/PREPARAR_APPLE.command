#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."

echo "============================================="
echo " FinanceApp - preparar iPhone/iPad e macOS"
echo "============================================="

if ! command -v flutter >/dev/null 2>&1; then
  echo "ERRO: Flutter nao encontrado neste Mac."
  read -r -p "Pressione ENTER para sair..." _
  exit 1
fi

flutter --version
flutter create . --platforms=ios,macos --project-name financeapp_multiplatform


cat > ios/Runner/AppDelegate.swift <<'SWIFT'
import Flutter
import UIKit
import UserNotifications

@main
@objc class AppDelegate: FlutterAppDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    GeneratedPluginRegistrant.register(with: self)
    UNUserNotificationCenter.current().delegate = self
    if let controller = window?.rootViewController as? FlutterViewController {
      let channel = FlutterMethodChannel(name: "financeapp/notifications", binaryMessenger: controller.binaryMessenger)
      channel.setMethodCallHandler { call, result in
        let center = UNUserNotificationCenter.current()
        if call.method == "requestPermission" {
          center.requestAuthorization(options: [.alert, .badge, .sound]) { granted, _ in
            DispatchQueue.main.async { result(granted) }
          }
          return
        }
        if call.method == "sync" {
          let args = call.arguments as? [String: Any]
          let items = args?["items"] as? [[String: Any]] ?? []
          center.getPendingNotificationRequests { requests in
            let ids = requests.map { $0.identifier }.filter { $0.hasPrefix("financeapp_") }
            center.removePendingNotificationRequests(withIdentifiers: ids)
            for item in items {
              guard let rawId = item["id"] as? String,
                    let title = item["title"] as? String,
                    let body = item["body"] as? String,
                    let year = item["year"] as? Int,
                    let month = item["month"] as? Int,
                    let day = item["day"] as? Int,
                    let hour = item["hour"] as? Int,
                    let minute = item["minute"] as? Int else { continue }
              let content = UNMutableNotificationContent()
              content.title = title
              content.body = body
              content.sound = .default
              var parts = DateComponents()
              parts.year = year; parts.month = month; parts.day = day; parts.hour = hour; parts.minute = minute
              let trigger = UNCalendarNotificationTrigger(dateMatching: parts, repeats: false)
              let request = UNNotificationRequest(identifier: "financeapp_\(rawId)", content: content, trigger: trigger)
              center.add(request)
            }
            DispatchQueue.main.async { result(true) }
          }
          return
        }
        result(FlutterMethodNotImplemented)
      }
    }
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  override func userNotificationCenter(
    _ center: UNUserNotificationCenter,
    willPresent notification: UNNotification,
    withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
  ) {
    if #available(iOS 14.0, *) {
      completionHandler([.banner, .list, .sound, .badge])
    } else {
      completionHandler([.alert, .sound, .badge])
    }
  }
}
SWIFT

/usr/libexec/PlistBuddy -c "Add :NSCameraUsageDescription string 'O FinanceApp usa a camera para ler boletos e QR Codes de despesas.'" ios/Runner/Info.plist 2>/dev/null || \
/usr/libexec/PlistBuddy -c "Set :NSCameraUsageDescription 'O FinanceApp usa a camera para ler boletos e QR Codes de despesas.'" ios/Runner/Info.plist

/usr/libexec/PlistBuddy -c "Add :NSCameraUsageDescription string 'O FinanceApp usa a camera para ler boletos e QR Codes de despesas.'" macos/Runner/Info.plist 2>/dev/null || \
/usr/libexec/PlistBuddy -c "Set :NSCameraUsageDescription 'O FinanceApp usa a camera para ler boletos e QR Codes de despesas.'" macos/Runner/Info.plist

for ent in macos/Runner/DebugProfile.entitlements macos/Runner/Release.entitlements; do
  if [ -f "$ent" ]; then
    /usr/libexec/PlistBuddy -c "Add :com.apple.security.device.camera bool true" "$ent" 2>/dev/null || \
    /usr/libexec/PlistBuddy -c "Set :com.apple.security.device.camera true" "$ent"
  fi
done

flutter pub get

echo
echo "Apple preparado com o mesmo Flutter usado no Web, incluindo leitura de boleto/QR e notificações locais."
echo "iPhone/iPad: abra ios/Runner.xcworkspace no Xcode e configure Signing."
echo "macOS: use flutter run -d macos ou abra macos/Runner.xcworkspace."
read -r -p "Pressione ENTER para sair..." _
