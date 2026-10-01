param(
    [string]$JavaHome = 'C:\Program Files\Java\jdk-20',
    [string]$SdkRoot = "$env:LOCALAPPDATA\Android\Sdk"
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$javaExe = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExe)) { throw "JDK not found: $javaExe. Pass -JavaHome with a supported JDK (17–23)." }
if (-not (Test-Path -LiteralPath (Join-Path $SdkRoot 'platforms\android-36'))) {
    throw "Android SDK 36 not found under $SdkRoot. Pass -SdkRoot."
}
$javaInfo = & $javaExe -version 2>&1
if ($LASTEXITCODE -ne 0) { throw "Unable to launch JDK at $JavaHome" }
if (-not (($javaInfo -join ' ') -match 'version "(17|18|19|20|21|22|23)\.')) {
    throw "This Gradle setup needs JDK 17–23; found: $($javaInfo[0])"
}

$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $SdkRoot
Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :test-target:assembleDebug :app:assembleDebugAndroidTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Android build or verification failed with exit code $LASTEXITCODE." }
    Write-Output "Built: $(Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk')"
} finally {
    Pop-Location
}
