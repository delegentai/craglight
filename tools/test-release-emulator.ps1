# Installs the actual signed release only on this project's dedicated AVD.
. "$PSScriptRoot\env.ps1"
$taskAdb = "$env:ANDROID_HOME\platform-tools\adb.exe"
$taskProject = Split-Path $PSScriptRoot -Parent
$taskEvidence = Join-Path $taskProject 'evidence\release'
$taskApk = Join-Path $taskProject 'release\CragLight-1.0.0.apk'
if ((& $taskAdb -P 5047 -s emulator-5580 emu avd name) -notcontains 'CragLight_API36') { throw 'Wrong emulator.' }
if (-not (Test-Path -LiteralPath $taskApk)) { throw 'Build and verify the signed release APK first.' }
New-Item -ItemType Directory -Path $taskEvidence -Force | Out-Null
$taskWifi = (& $taskAdb -P 5047 -s emulator-5580 shell settings get global wifi_on).Trim()
$taskData = (& $taskAdb -P 5047 -s emulator-5580 shell settings get global mobile_data).Trim()
try {
    & $taskAdb -P 5047 -s emulator-5580 shell svc wifi disable
    & $taskAdb -P 5047 -s emulator-5580 shell svc data disable
    $taskInstall = & $taskAdb -P 5047 -s emulator-5580 install -r $taskApk
    if ($LASTEXITCODE -ne 0) { throw 'Signed release installation failed.' }
    $taskInstall | Set-Content -LiteralPath (Join-Path $taskEvidence 'emulator-install.txt')
    & $taskAdb -P 5047 -s emulator-5580 logcat -c
    $taskLaunch = & $taskAdb -P 5047 -s emulator-5580 shell am start -W -n org.craglight.app/.MainActivity
    if ($LASTEXITCODE -ne 0 -or ($taskLaunch -join "`n") -notmatch 'Status: ok') { throw 'Signed release launch failed.' }
    $taskLaunch | Set-Content -LiteralPath (Join-Path $taskEvidence 'emulator-launch.txt')
    Start-Sleep -Seconds 4
    & $taskAdb -P 5047 -s emulator-5580 shell uiautomator dump /sdcard/craglight-release.xml | Out-Null
    & $taskAdb -P 5047 -s emulator-5580 pull /sdcard/craglight-release.xml (Join-Path $taskEvidence 'release-ui.xml') | Out-Null
    [xml]$taskUi = Get-Content -LiteralPath (Join-Path $taskEvidence 'release-ui.xml')
    if ($taskUi.OuterXml -notmatch '828 mapped climbing places') { throw 'Release catalogue did not load offline.' }
    & $taskAdb -P 5047 -s emulator-5580 shell screencap -p /sdcard/craglight-release.png
    & $taskAdb -P 5047 -s emulator-5580 pull /sdcard/craglight-release.png (Join-Path $taskEvidence 'release-explore.png') | Out-Null
    $taskMapNode = $taskUi.SelectSingleNode('//node[@text="Map"]')
    if ($null -eq $taskMapNode -or $taskMapNode.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw 'Release map action missing.' }
    $taskMapX = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $taskMapY = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    & $taskAdb -P 5047 -s emulator-5580 shell input tap $taskMapX $taskMapY
    Start-Sleep -Seconds 3
    & $taskAdb -P 5047 -s emulator-5580 shell screencap -p /sdcard/craglight-release.png
    & $taskAdb -P 5047 -s emulator-5580 pull /sdcard/craglight-release.png (Join-Path $taskEvidence 'release-map.png') | Out-Null
    $taskProcess = (& $taskAdb -P 5047 -s emulator-5580 shell pidof org.craglight.app).Trim()
    if (-not $taskProcess) { throw 'Release process ended during map launch.' }
    $taskPackage = & $taskAdb -P 5047 -s emulator-5580 shell dumpsys package org.craglight.app
    $taskPackage | Set-Content -LiteralPath (Join-Path $taskEvidence 'emulator-package.txt')
    if (($taskPackage -join "`n") -match 'pkgFlags=.*DEBUGGABLE') { throw 'Expected a non-debuggable release.' }
    $taskCrashes = & $taskAdb -P 5047 -s emulator-5580 logcat -d -b crash
    $taskCrashes | Set-Content -LiteralPath (Join-Path $taskEvidence 'emulator-crash-log.txt')
    if (($taskCrashes -join "`n") -match 'org.craglight.app') { throw 'Release crash detected.' }
    [pscustomobject]@{ apkSha256=(Get-FileHash -LiteralPath $taskApk -Algorithm SHA256).Hash.ToLowerInvariant(); device='CragLight_API36'; api=36; releaseInstalled=$true; launch='ok'; offlineCatalogueCount=828; mapOpened=$true; debuggable=$false; processAlive=$true } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskEvidence 'emulator-result.json')
    Write-Output 'Signed release installed and launched offline; catalogue and native map opened without a crash.'
} finally {
    if ($taskWifi -ne '0') { & $taskAdb -P 5047 -s emulator-5580 shell svc wifi enable }
    if ($taskData -ne '0') { & $taskAdb -P 5047 -s emulator-5580 shell svc data enable }
}
