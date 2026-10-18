$ErrorActionPreference = 'Stop'
$projectPath = Split-Path $PSScriptRoot -Parent
. (Join-Path $projectPath 'tools/env.ps1')
$ErrorActionPreference = 'Stop'
$releasePath = Join-Path $projectPath 'release'
$evidencePath = Join-Path $projectPath 'evidence/release'
New-Item -ItemType Directory -Path $releasePath,$evidencePath -Force | Out-Null
$apk = Join-Path $releasePath 'CragLight-1.0.0.apk'
$bundle = Join-Path $releasePath 'CragLight-1.0.0.aab'
Copy-Item -LiteralPath (Join-Path $projectPath 'app/build/outputs/apk/release/app-release.apk') -Destination $apk -Force
Copy-Item -LiteralPath (Join-Path $projectPath 'app/build/outputs/bundle/release/app-release.aab') -Destination $bundle -Force
$signer = Join-Path $env:ANDROID_HOME 'build-tools/36.0.0/apksigner.bat'
$certificateOutput = & $signer verify --verbose --print-certs $apk 2>&1
if ($LASTEXITCODE -ne 0) { throw 'Release APK signature verification failed' }
$certificateOutput | Set-Content -LiteralPath (Join-Path $evidencePath 'apk-signature.txt') -Encoding utf8
if (($certificateOutput -join "`n") -notmatch 'CN=CragLight Android Release') { throw 'Unexpected APK signing identity' }
$bundleOutput = & "$env:JAVA_HOME/bin/jarsigner.exe" -verify $bundle 2>&1
if ($LASTEXITCODE -ne 0 -or ($bundleOutput -join "`n") -notmatch 'jar verified') { throw 'AAB signature verification failed' }
$bundleOutput | Set-Content -LiteralPath (Join-Path $evidencePath 'aab-signature.txt') -Encoding utf8
$badging = & "$env:ANDROID_HOME/build-tools/36.0.0/aapt2.exe" dump badging $apk 2>&1
if ($LASTEXITCODE -ne 0 -or ($badging -join "`n") -notmatch "package: name='org.craglight.app'") { throw 'Wrong release package' }
$badging | Set-Content -LiteralPath (Join-Path $evidencePath 'apk-badging.txt') -Encoding utf8
& python (Join-Path $projectPath 'tools/check-native-alignment.py') $apk --require-apk-zip-alignment
if ($LASTEXITCODE -ne 0) { throw 'Native page-alignment verification failed' }
@($apk,$bundle) | ForEach-Object { $f=Get-Item -LiteralPath $_; '{0}  {1}' -f (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant(),$f.Name } | Set-Content -LiteralPath (Join-Path $releasePath 'SHA256SUMS.txt') -Encoding ascii
Write-Output 'Release APK/AAB signatures, package ID and native alignment verified.'
