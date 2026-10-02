param(
    [Parameter(Mandatory=$true)]
    [string]$ApiBaseUrl
)

$ErrorActionPreference = "Stop"

if (-not $ApiBaseUrl.StartsWith("https://")) {
    throw "Para o APK beta externo, informe uma URL HTTPS. Ex.: https://financeapp-api.onrender.com/"
}
if (-not $ApiBaseUrl.EndsWith("/")) { $ApiBaseUrl += "/" }

$AndroidDir = Join-Path $PSScriptRoot "android"
if (-not (Test-Path $AndroidDir)) { throw "Pasta android nao encontrada em $PSScriptRoot" }

Push-Location $AndroidDir
try {
    if (Test-Path ".\\gradlew.bat") {
        & .\\gradlew.bat assembleDebug "-PAPI_BASE_URL=$ApiBaseUrl"
    } elseif (Get-Command gradle -ErrorAction SilentlyContinue) {
        & gradle assembleDebug "-PAPI_BASE_URL=$ApiBaseUrl"
    } else {
        throw "Gradle Wrapper nao esta presente e o comando gradle nao foi encontrado. Abra a pasta android no Android Studio e gere o APK passando API_BASE_URL=$ApiBaseUrl."
    }
    if ($LASTEXITCODE -ne 0) { throw "Falha ao gerar o APK." }

    $Apk = Join-Path $AndroidDir "app\\build\\outputs\\apk\\debug\\app-debug.apk"
    if (-not (Test-Path $Apk)) { throw "Build terminou, mas o APK nao foi encontrado em $Apk" }
    Write-Host ""
    Write-Host "APK BETA GERADO:" -ForegroundColor Green
    Write-Host $Apk -ForegroundColor Cyan
    Write-Host "API: $ApiBaseUrl"
} finally {
    Pop-Location
}
