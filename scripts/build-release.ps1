param(
    [string]$JavaHome = 'C:\Program Files\Java\jdk-20',
    [string]$SdkRoot = "$env:LOCALAPPDATA\Android\Sdk"
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$keytool = Join-Path $JavaHome 'bin\keytool.exe'
$buildTools = Join-Path $SdkRoot 'build-tools\36.0.0'
$zipalign = Join-Path $buildTools 'zipalign.exe'
$apksigner = Join-Path $buildTools 'apksigner.bat'
foreach ($tool in @($keytool, $zipalign, $apksigner)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Required tool not found: $tool" }
}

$userProfile = [Environment]::GetFolderPath('UserProfile')
$signingDir = Join-Path $userProfile '.android\accountability-check-signing'
$keyStore = Join-Path $signingDir 'release-key.p12'
$passwordFile = Join-Path $signingDir 'release-password.clixml'
$keyExists = Test-Path -LiteralPath $keyStore
$passwordExists = Test-Path -LiteralPath $passwordFile
if ($keyExists -ne $passwordExists) {
    throw "The release signing key and its encrypted password must both exist. Inspect $signingDir before building."
}

if (-not $keyExists) {
    New-Item -ItemType Directory -Path $signingDir -Force | Out-Null
    $randomBytes = New-Object byte[] 36
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
    $password = [Convert]::ToBase64String($randomBytes)
    $env:ACCOUNTABILITY_SIGNING_PASSWORD = $password
    try {
        & $keytool -genkeypair -noprompt -keystore $keyStore -storetype PKCS12 `
            -alias accountabilitycheck -keyalg RSA -keysize 4096 -validity 10000 `
            -dname 'CN=Accountability Check, O=JK03Coder, C=NZ' `
            '-storepass:env' ACCOUNTABILITY_SIGNING_PASSWORD `
            '-keypass:env' ACCOUNTABILITY_SIGNING_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw "Signing key generation failed with exit code $LASTEXITCODE." }
        $password | ConvertTo-SecureString -AsPlainText -Force | Export-Clixml -LiteralPath $passwordFile
        Write-Output "Created a release signing key outside the repository: $keyStore"
    } catch {
        if (Test-Path -LiteralPath $keyStore) { Remove-Item -LiteralPath $keyStore -Force }
        if (Test-Path -LiteralPath $passwordFile) { Remove-Item -LiteralPath $passwordFile -Force }
        throw
    }
} else {
    $securePassword = Import-Clixml -LiteralPath $passwordFile
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    try {
        $env:ACCOUNTABILITY_SIGNING_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

$gradleText = Get-Content -LiteralPath (Join-Path $projectRoot 'app\build.gradle.kts') -Raw
$versionMatch = [regex]::Match($gradleText, 'versionName\s*=\s*"([^"]+)"')
if (-not $versionMatch.Success) { throw 'Could not read versionName from app/build.gradle.kts.' }
$versionName = $versionMatch.Groups[1].Value
$outputDir = Join-Path $projectRoot 'artifacts\release'
$unsignedApk = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release-unsigned.apk'
$alignedApk = Join-Path $outputDir 'aligned-unsigned.apk'
$releaseApk = Join-Path $outputDir "AccountabilityCheck-v$versionName.apk"

try {
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $SdkRoot
    Push-Location $projectRoot
    try {
        & (Join-Path $projectRoot 'gradlew.bat') :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Release build or verification failed with exit code $LASTEXITCODE." }
    } finally {
        Pop-Location
    }
    if (-not (Test-Path -LiteralPath $unsignedApk)) { throw "Unsigned release APK not found: $unsignedApk" }
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
    & $zipalign -f 4 $unsignedApk $alignedApk
    if ($LASTEXITCODE -ne 0) { throw "zipalign failed with exit code $LASTEXITCODE." }
    & $apksigner sign --ks $keyStore --ks-key-alias accountabilitycheck `
        --ks-pass env:ACCOUNTABILITY_SIGNING_PASSWORD `
        --key-pass env:ACCOUNTABILITY_SIGNING_PASSWORD `
        --out $releaseApk $alignedApk
    if ($LASTEXITCODE -ne 0) { throw "APK signing failed with exit code $LASTEXITCODE." }
    & $apksigner verify --verbose --print-certs $releaseApk
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed with exit code $LASTEXITCODE." }
    $hash = Get-FileHash -LiteralPath $releaseApk -Algorithm SHA256
    Write-Output "Release APK: $releaseApk"
    Write-Output "SHA-256: $($hash.Hash)"
} finally {
    if (Test-Path -LiteralPath $alignedApk) { Remove-Item -LiteralPath $alignedApk -Force }
    Remove-Item Env:\ACCOUNTABILITY_SIGNING_PASSWORD -ErrorAction SilentlyContinue
}
