param(
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$NdkVersion = '28.2.13676358',
    [string]$BuildToolsVersion = '36.0.0',
    [string]$KeyStore = $env:ONESTEPLITE_KEYSTORE,
    [string]$KeyAlias = $env:ONESTEPLITE_KEY_ALIAS,
    [string]$Python = 'python',
    [switch]$Unsigned
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
Push-Location $root
try {
if (-not $SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
if (-not $SdkPath -or -not $JavaHome) { throw 'Set ANDROID_HOME and JAVA_HOME, or pass -SdkPath and -JavaHome.' }
$javaBin = Join-Path $JavaHome 'bin'
$bt = Join-Path $SdkPath "build-tools\$BuildToolsVersion"
$android = Join-Path $SdkPath 'platforms\android-37.0\android.jar'
$xposed = Join-Path $root 'libs\xposed-api-82.jar'
$version = Get-Content -LiteralPath (Join-Path $root 'version.json') -Raw | ConvertFrom-Json
$out = Join-Path $root 'build'
$dist = Join-Path $root 'dist'
$src = Join-Path $root 'app\src\main'
$apk = Join-Path $dist "OneStepLite-$($version.name).apk"
if (Test-Path -LiteralPath $apk) { throw 'Preserve the existing APK. Use a fresh checkout for another build.' }
foreach ($path in @($android,$xposed,(Join-Path $javaBin 'javac.exe'),(Join-Path $bt 'aapt2.exe'))) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing build input: $path" }
}
if (-not $Unsigned) {
    if (-not $KeyStore -or -not $KeyAlias -or -not $env:ONESTEPLITE_STORE_PASSWORD -or -not $env:ONESTEPLITE_KEY_PASSWORD) {
        throw 'Signing requires ONESTEPLITE_KEYSTORE, ONESTEPLITE_KEY_ALIAS, ONESTEPLITE_STORE_PASSWORD and ONESTEPLITE_KEY_PASSWORD. Use -Unsigned to build without credentials.'
    }
}
[xml]$manifest = Get-Content -LiteralPath (Join-Path $src 'AndroidManifest.xml') -Raw -Encoding UTF8
$ns = 'http://schemas.android.com/apk/res/android'
# Gradle owns versionCode/versionName; the manifest only carries them for this reference build.
if ($manifest.manifest.GetAttribute('versionName',$ns)) {
    if ($manifest.manifest.GetAttribute('versionName',$ns) -ne $version.name -or
        $manifest.manifest.GetAttribute('versionCode',$ns) -ne [string]$version.code) { throw 'Version metadata mismatch.' }
}
$recommendedScope = @($manifest.manifest.application.'meta-data' | Where-Object {
    $_.GetAttribute('name',$ns) -ceq 'xposedscope'
})
if ($recommendedScope.Count -ne 1 -or
    $recommendedScope[0].GetAttribute('value',$ns) -cne 'android;com.android.systemui;com.android.settings') {
    throw 'Recommended scope must contain system framework, SystemUI and the Settings entry host.'
}
$identitySources = Join-Path $out 'generated\buildIdentity'
& $Python -X utf8 (Join-Path $root 'tools\generate_build_identity.py') --version (Join-Path $root 'version.json') --output $identitySources
if ($LASTEXITCODE -ne 0) { throw 'Build identity generation failed.' }
# Deleted source classes must never survive in the next APK.
$classOutput = [IO.Path]::GetFullPath((Join-Path $out 'classes'))
$expectedClassOutput = [IO.Path]::GetFullPath((Join-Path $root 'build\classes'))
if ($classOutput -cne $expectedClassOutput) { throw 'Unexpected class output directory.' }
if (Test-Path -LiteralPath $classOutput) { Remove-Item -LiteralPath $classOutput -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out,$dist,$classOutput,(Join-Path $out 'native'),(Join-Path $out 'dex') | Out-Null
$env:JAVA_HOME = $JavaHome
$env:ONESTEPLITE_JAVA_BIN = $javaBin
$env:ONESTEPLITE_ANDROID_JAR = $android
$env:ONESTEPLITE_CLASSES = Join-Path $out 'classes'
foreach ($tool in @('java','javac','javap','jar')) { Set-Item -Path "env:ONESTEPLITE_$($tool.ToUpperInvariant())" -Value (Join-Path $javaBin "$tool.exe") }
$sources = @(Get-ChildItem -LiteralPath (Join-Path $src 'java') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources += Join-Path $identitySources 'io\github\zerohomers0\onesteplite\BuildIdentity.java'
& (Join-Path $javaBin 'javac.exe') -encoding UTF-8 -nowarn --release 17 -classpath "$android;$xposed" -d $env:ONESTEPLITE_CLASSES @sources
if ($LASTEXITCODE -ne 0) { throw 'Full Java source compilation failed.' }
$clang = Join-Path $SdkPath "ndk\$NdkVersion\toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe"
if (-not (Test-Path -LiteralPath $clang)) { throw 'Install the documented Android NDK version.' }
$common = @('--target=aarch64-linux-android28','-std=c++17','-O2','-fPIC','-fno-exceptions','-fno-rtti','-fvisibility=hidden','-static-libstdc++','-Wall','-Wextra','-Werror','-Wl,-z,max-page-size=16384')
$native = Join-Path $out 'native\libonesteplite_raw.so'
& $clang @common -shared (Join-Path $src 'cpp\native_raw_input.cpp') '-Wl,-z,defs' '-Wl,-z,relro,-z,now' -llog -ldl -lm -o $native
if ($LASTEXITCODE -ne 0) { throw 'Native source compilation failed.' }
& $clang @common (Join-Path $root 'tests\native_raw_input_test.cpp') -ldl -lm -o (Join-Path $out 'native\native-raw-selftest')
if ($LASTEXITCODE -ne 0) { throw 'Native selftest source compilation failed.' }
& (Join-Path $bt 'aapt2.exe') compile --dir 'app\src\main\res' -o 'build\resources.zip'
if ($LASTEXITCODE -ne 0) { throw 'Resource compilation failed.' }
$base = Join-Path $out 'base.apk'
# The licence bundle ships inside app/src/main/assets and reaches the APK through
# aapt2 -A, so nothing is staged here; staging a second copy would put duplicate
# entries in the archive.
& (Join-Path $bt 'aapt2.exe') link -o 'build\base.apk' -I $android --manifest 'app\src\main\AndroidManifest.xml' -A 'app\src\main\assets' --min-sdk-version 34 --target-sdk-version 37 --version-code $version.code --version-name $version.name 'build\resources.zip'
if ($LASTEXITCODE -ne 0) { throw 'Resource linking failed.' }
$jar = Join-Path $out 'classes.jar'
& (Join-Path $javaBin 'jar.exe') --create --file $jar -C $env:ONESTEPLITE_CLASSES .
if ($LASTEXITCODE -ne 0) { throw 'Class archive failed.' }
& (Join-Path $bt 'd8.bat') --min-api 34 --lib $android --lib $xposed --output (Join-Path $out 'dex') $jar
if ($LASTEXITCODE -ne 0) { throw 'DEX compilation failed.' }
$stage = Join-Path $out 'stage'
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'lib\arm64-v8a') | Out-Null
Copy-Item -LiteralPath (Join-Path $out 'dex\classes.dex') -Destination $stage -Force
Copy-Item -LiteralPath $native -Destination (Join-Path $stage 'lib\arm64-v8a\libonesteplite_raw.so') -Force
$packed = Join-Path $out 'packed.apk'
Copy-Item -LiteralPath $base -Destination $packed -Force
& (Join-Path $javaBin 'jar.exe') --update --file $packed --no-compress -C $stage classes.dex -C $stage lib\arm64-v8a\libonesteplite_raw.so
if ($LASTEXITCODE -ne 0) { throw 'APK packaging failed.' }
if ($Unsigned) { $apk = Join-Path $dist "OneStepLite-$($version.name)-unsigned.apk" }
& (Join-Path $bt 'zipalign.exe') -f -P 16 4 $packed $apk
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
if (-not $Unsigned) {
    & (Join-Path $bt 'apksigner.bat') sign --ks $KeyStore --ks-key-alias $KeyAlias --ks-pass env:ONESTEPLITE_STORE_PASSWORD --key-pass env:ONESTEPLITE_KEY_PASSWORD --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled false $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    & (Join-Path $bt 'apksigner.bat') verify --verbose $apk
    if ($LASTEXITCODE -ne 0) { throw 'Signature verification failed.' }
}
& (Join-Path $bt 'zipalign.exe') -c -P 16 4 $apk
if ($LASTEXITCODE -ne 0) { throw 'Final APK alignment failed.' }
$packagingLines = & $Python -X utf8 (Join-Path $root 'tools\assert_module_apk.py') $apk --require-native
if ($LASTEXITCODE -ne 0) { throw 'APK packaging verification failed.' }
$packaging = @{}
foreach ($line in $packagingLines) { $pair = $line -split ': ', 2; if ($pair.Count -eq 2) { $packaging[$pair[0]] = $pair[1] } }
$identity = [ordered]@{applicationId='io.github.zerohomers0.onesteplite';version=$version.name; versionCode=$version.code;runtimeMarker=$version.runtimeMarker;sha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant();bytes=(Get-Item -LiteralPath $apk).Length;nativeSha256=(Get-FileHash -LiteralPath $native -Algorithm SHA256).Hash.ToLowerInvariant();javaSourceCount=$sources.Count;build='Full Java and native source build; no development class cache';startupPackaging=$packaging.startupPackaging;nativePackaging=$packaging.nativePackaging;deviceValidation='This rebuilt package has not been installed; behavior baseline and its prior device evidence are recorded separately in the release notes.'}
$identity | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $dist 'build-info.json') -Encoding utf8
"$($identity.sha256)  $([IO.Path]::GetFileName($apk))" | Set-Content -LiteralPath (Join-Path $dist 'SHA256SUMS.txt') -Encoding ascii
Write-Output "Built OneStepLite $($version.name), versionCode=$($version.code), SHA256=$($identity.sha256)"
} finally { Pop-Location }
