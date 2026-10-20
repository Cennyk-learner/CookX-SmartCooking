# Builds the release APK and re-signs it with v1 + v2 + v3 signatures.
# AGP drops v1 (JAR) signing when minSdk >= 24, but some vendor installers still reject APKs without it.
# Requires keystore.properties (storeFile / storePassword / keyAlias / keyPassword), which is not committed.
param(
    [string]$Output = "app\build\outputs\apk\release\CookX-demo.apk",
    [switch]$SkipBuild
)
$ErrorActionPreference = "Stop"
Push-Location $PSScriptRoot
$aligned = $null
$previousStorePass = $env:COOKX_RELEASE_STORE_PASS
$previousKeyPass = $env:COOKX_RELEASE_KEY_PASS
try {
    $props = @{}
    Get-Content -LiteralPath keystore.properties | Where-Object { $_ -match "^\s*[^#!].*=" } | ForEach-Object {
        $k, $v = $_ -split "=", 2
        $props[$k.Trim()] = $v.Trim()
    }
    foreach ($required in @("storeFile", "storePassword", "keyAlias", "keyPassword")) {
        if ([string]::IsNullOrWhiteSpace($props[$required])) { throw "Missing signing property: $required" }
    }
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
    $buildTools = Get-ChildItem -LiteralPath "$sdk\build-tools" -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    if (-not $buildTools -or [version]$buildTools.Name -lt [version]"35.0.0") {
        throw "Android build-tools 35.0.0 or newer is required for 16 KB alignment."
    }
    if (-not $SkipBuild) {
        & .\gradlew.bat :app:assembleRelease --no-daemon
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
    }
    $source = (Resolve-Path -LiteralPath "app\build\outputs\apk\release\app-release.apk").Path
    $destination = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Output)
    if ($source -eq $destination) { throw "Output must be separate from the Gradle APK." }
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    $aligned = Join-Path (Split-Path -Parent $source) ("aligned-" + [guid]::NewGuid().ToString("N") + ".apk")
    & "$($buildTools.FullName)\zipalign.exe" -f -P 16 4 $source $aligned
    if ($LASTEXITCODE -ne 0) { throw "APK alignment failed" }
    # Pass secret values through inherited environment variables, never command-line arguments.
    $env:COOKX_RELEASE_STORE_PASS = $props.storePassword
    $env:COOKX_RELEASE_KEY_PASS = $props.keyPassword
    & "$($buildTools.FullName)\apksigner.bat" sign --ks $props.storeFile --ks-key-alias $props.keyAlias `
        --ks-pass env:COOKX_RELEASE_STORE_PASS --key-pass env:COOKX_RELEASE_KEY_PASS `
        --min-sdk-version 21 --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true `
        --v4-signing-enabled false --out $destination $aligned
    if ($LASTEXITCODE -ne 0) { throw "APK signing failed" }
    $verification = & "$($buildTools.FullName)\apksigner.bat" verify --min-sdk-version 21 --verbose --print-certs $destination
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed" }
    foreach ($scheme in 1..3) {
        if (-not ($verification -match "Verified using v$scheme scheme .*: true")) { throw "Missing v$scheme signature" }
    }
    $verification | Select-String "Verified using v[123] |certificate SHA-256 digest"
    & "$($buildTools.FullName)\zipalign.exe" -c -P 16 4 $destination
    if ($LASTEXITCODE -ne 0) { throw "Signed APK alignment verification failed" }
    Write-Host "Signed APK: $destination"
    Get-FileHash -LiteralPath $destination -Algorithm SHA256
} finally {
    $env:COOKX_RELEASE_STORE_PASS = $previousStorePass
    $env:COOKX_RELEASE_KEY_PASS = $previousKeyPass
    if ($aligned -and (Test-Path -LiteralPath $aligned)) { Remove-Item -LiteralPath $aligned }
    Pop-Location
}
