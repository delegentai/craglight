# Public weather/release smoke tests on the dedicated CragLight AVD only.
. "$PSScriptRoot\env.ps1"
$taskAdb = "$env:ANDROID_HOME\platform-tools\adb.exe"
$taskAvd = & $taskAdb -P 5047 -s emulator-5580 emu avd name
if ($taskAvd -notcontains 'CragLight_API36') { throw 'Expected the isolated CragLight_API36 AVD.' }
$taskWifi = (& $taskAdb -P 5047 -s emulator-5580 shell settings get global wifi_on).Trim()
$taskMobile = (& $taskAdb -P 5047 -s emulator-5580 shell settings get global mobile_data).Trim()
$taskExit = 1
$taskGps = $null
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    & $taskAdb -P 5047 -s emulator-5580 shell svc wifi enable
    & $taskAdb -P 5047 -s emulator-5580 shell svc data enable
    # Periodic emulator GPS fixes continue through compilation and the network checks.
    $taskGps = Start-Job -ArgumentList $taskAdb -ScriptBlock {
        param($gpsAdb)
        for ($gpsIndex = 0; $gpsIndex -lt 120; $gpsIndex++) {
            & $gpsAdb -P 5047 -s emulator-5580 emu geo fix -4.075 53.066 | Out-Null
            Start-Sleep -Seconds 5
        }
    }
    & "$env:CRAGLIGHT_TOOLS\gradle-8.11.1\bin\gradle.bat" --no-daemon :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=org.craglight.app.OnlineServicesTest' '-Pandroid.testInstrumentationRunnerArguments.allowNetwork=true' --console=plain
    $taskExit = $LASTEXITCODE
    New-Item -ItemType Directory -Path 'evidence\online-test' -Force | Out-Null
    $taskScreenshots = Join-Path (Get-Location) 'app\build\outputs\connected_android_test_additional_output\debugAndroidTest\connected'
    if (Test-Path -LiteralPath $taskScreenshots) {
        Get-ChildItem -LiteralPath $taskScreenshots -Recurse -File -Filter '*.png' | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination 'evidence\online-test' -Force
        }
    }
    Get-ChildItem -LiteralPath 'app\build\outputs\androidTest-results\connected\debug' -File -Filter 'TEST-*.xml' | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination 'evidence\online-test\result.xml' -Force
    }
} finally {
    if ($null -ne $taskGps) { Stop-Job -Job $taskGps; Remove-Job -Job $taskGps }
    if ($taskWifi -eq '0') { & $taskAdb -P 5047 -s emulator-5580 shell svc wifi disable }
    if ($taskMobile -eq '0') { & $taskAdb -P 5047 -s emulator-5580 shell svc data disable }
    Pop-Location
}
exit $taskExit
