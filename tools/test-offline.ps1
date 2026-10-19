# The dedicated AVD is the only permitted target. Original network settings are restored.
. "$PSScriptRoot\env.ps1"
$taskAdb = "$env:ANDROID_HOME\platform-tools\adb.exe"
$taskAvd = & $taskAdb -P 5047 -s emulator-5580 emu avd name
if ($taskAvd -notcontains 'CragLight_API36') { throw 'Expected the isolated CragLight_API36 AVD on emulator-5580.' }
$taskWifi = (& $taskAdb -P 5047 -s emulator-5580 shell settings get global wifi_on).Trim()
$taskMobile = (& $taskAdb -P 5047 -s emulator-5580 shell settings get global mobile_data).Trim()
$taskExit = 1
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    & $taskAdb -P 5047 -s emulator-5580 shell svc wifi disable
    & $taskAdb -P 5047 -s emulator-5580 shell svc data disable
    $taskWifiDisabled = (& $taskAdb -P 5047 -s emulator-5580 shell settings get global wifi_on).Trim()
    $taskMobileDisabled = (& $taskAdb -P 5047 -s emulator-5580 shell settings get global mobile_data).Trim()
    if ($taskWifiDisabled -ne '0' -or $taskMobileDisabled -ne '0') { throw 'Emulator networking was not disabled.' }
    New-Item -ItemType Directory -Path 'evidence\ui-test' -Force | Out-Null
    & $taskAdb -P 5047 -s emulator-5580 shell dumpsys connectivity | Out-File -LiteralPath 'evidence\ui-test\offline-network.txt'
    & "$env:CRAGLIGHT_TOOLS\gradle-8.11.1\bin\gradle.bat" --no-daemon :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=org.craglight.app.AppFlowTest,org.craglight.app.community.PhotoImportTest' --console=plain
    $taskExit = $LASTEXITCODE
    New-Item -ItemType Directory -Path 'evidence\ui-test' -Force | Out-Null
    # UTP collects additionalTestOutputDir before uninstalling the test APKs.
    $taskScreenshots = Join-Path (Get-Location) 'app\build\outputs\connected_android_test_additional_output\debugAndroidTest\connected'
    if (Test-Path -LiteralPath $taskScreenshots) {
        Get-ChildItem -LiteralPath $taskScreenshots -Recurse -File | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination 'evidence\ui-test' -Force
        }
    }
    Get-ChildItem -LiteralPath 'app\build\outputs\androidTest-results\connected\debug' -File -Filter 'TEST-*.xml' | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination 'evidence\ui-test\result.xml' -Force
    }
} finally {
    if ($taskWifi -ne '0') { & $taskAdb -P 5047 -s emulator-5580 shell svc wifi enable }
    if ($taskMobile -ne '0') { & $taskAdb -P 5047 -s emulator-5580 shell svc data enable }
    Pop-Location
}
exit $taskExit
