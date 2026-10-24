param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$SdkRoot = "$env:LOCALAPPDATA\Android\Sdk"
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = Join-Path $SdkRoot 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw "adb not found at $adb" }
$artifacts = Join-Path $projectRoot 'artifacts'
$apks = @(
    (Join-Path $projectRoot 'test-target\build\outputs\apk\debug\test-target-debug.apk'),
    (Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'),
    (Join-Path $projectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')
)
foreach ($apk in $apks) { if (-not (Test-Path -LiteralPath $apk)) { throw "Missing $apk. Run scripts/build.ps1 first." } }
$deviceState = & $adb -s $Serial get-state 2>&1
if ($LASTEXITCODE -ne 0 -or ($deviceState -join '') -ne 'device') { throw "Device $Serial is not connected and ready." }
foreach ($apk in $apks) {
    & $adb -s $Serial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "Install failed for $apk on $Serial" }
}
New-Item -ItemType Directory -Force -Path $artifacts | Out-Null
$outputPath = Join-Path $artifacts "device-tests-$Serial.txt"
$result = & $adb -s $Serial shell am instrument -w -r -e class nz.co.accountabilitycheck.AccessibilitySmokeTest nz.co.accountabilitycheck.test/androidx.test.runner.AndroidJUnitRunner 2>&1
$result | Set-Content -LiteralPath $outputPath
$result | Write-Output
if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'OK \(11 tests\)' -or ($result -join "`n") -match 'FAILURES!!!') {
    throw "Device smoke tests failed. See $outputPath"
}
Write-Output "Device smoke tests passed on $Serial. Results: $outputPath"
