param(
  [string]$ApiBaseUrl = "http://localhost:8000",
  [int]$Port = 8080
)
$ErrorActionPreference = "Stop"
Write-Host "FinanceApp Web persistente: http://localhost:$Port" -ForegroundColor Cyan
Write-Host "IMPORTANTE: este modo usa o seu navegador normal. O 'flutter run -d chrome' usa perfil temporario e pode perder o armazenamento ao encerrar." -ForegroundColor Yellow
flutter pub get
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Abra http://localhost:$Port no Chrome/Edge. Para parar, Ctrl+C nesta janela." -ForegroundColor Green
flutter run -d web-server --web-hostname localhost --web-port $Port --dart-define=API_BASE_URL=$ApiBaseUrl
