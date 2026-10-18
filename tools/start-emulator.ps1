param([switch]$Visible)
. "$PSScriptRoot\env.ps1"
$taskAvdName = 'CragLight_API36'
$taskAvdPath = Join-Path $env:ANDROID_AVD_HOME "$taskAvdName.avd"
if (-not (Test-Path -LiteralPath (Join-Path $taskAvdPath 'config.ini'))) {
    'no' | & "$env:ANDROID_HOME\cmdline-tools\latest\bin\avdmanager.bat" create avd --name $taskAvdName --package 'system-images;android-36;google_apis;x86_64' --device 'pixel_7' --path $taskAvdPath --force
    if ($LASTEXITCODE -ne 0) { throw 'Could not create isolated CragLight emulator.' }
}
$taskLogPath = Join-Path $env:CRAGLIGHT_TOOLS 'emulator.log'
$taskErrorPath = Join-Path $env:CRAGLIGHT_TOOLS 'emulator-error.log'
$taskArgs = @('-avd', $taskAvdName, '-port', '5580', '-no-snapshot', '-no-audio', '-no-boot-anim', '-gpu', 'swiftshader_indirect', '-memory', '2048')
if (-not $Visible) { $taskArgs += '-no-window' }
$taskProcess = Start-Process -FilePath "$env:ANDROID_HOME\emulator\emulator.exe" -ArgumentList $taskArgs -WindowStyle Hidden -RedirectStandardOutput $taskLogPath -RedirectStandardError $taskErrorPath -PassThru
Write-Output "CragLight emulator PID $($taskProcess.Id); serial emulator-5580; isolated adb port 5047."
Write-Output "Logs: $taskLogPath and $taskErrorPath"
