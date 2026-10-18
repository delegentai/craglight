$ErrorActionPreference = 'Continue'
$env:CRAGLIGHT_TOOLS = 'C:\DevTmp\craglight-tools'
$env:JAVA_HOME = Join-Path $env:CRAGLIGHT_TOOLS 'jdk-17'
$env:ANDROID_HOME = Join-Path $env:CRAGLIGHT_TOOLS 'android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $env:CRAGLIGHT_TOOLS 'gradle-home'
$env:ANDROID_USER_HOME = Join-Path $env:CRAGLIGHT_TOOLS 'android-user'
$env:ANDROID_EMULATOR_HOME = $env:ANDROID_USER_HOME
$env:ANDROID_AVD_HOME = Join-Path $env:CRAGLIGHT_TOOLS 'avd'
Remove-Item Env:ANDROID_SDK_HOME -ErrorAction SilentlyContinue
$env:TEMP = Join-Path $env:CRAGLIGHT_TOOLS 'temp'
$env:TMP = $env:TEMP
$env:ANDROID_SERIAL = 'emulator-5580'
$env:ANDROID_ADB_SERVER_PORT = '5047'
$env:ADB_SERVER_SOCKET = 'tcp:localhost:5047'
$env:JAVA_TOOL_OPTIONS = '-Duser.home=C:\DevTmp\craglight-tools\java-home'
$env:GRADLE_OPTS = '-Dorg.gradle.daemon=false'
foreach ($directory in @($env:GRADLE_USER_HOME, $env:ANDROID_USER_HOME, $env:ANDROID_AVD_HOME, $env:TEMP, 'C:\DevTmp\craglight-tools\java-home')) {
    New-Item -ItemType Directory -Path $directory -Force -ErrorAction Stop | Out-Null
}
$taskPaths = @("$env:JAVA_HOME\bin", "$env:ANDROID_HOME\platform-tools", "$env:ANDROID_HOME\emulator", "$env:ANDROID_HOME\cmdline-tools\latest\bin", "$env:CRAGLIGHT_TOOLS\gradle-8.11.1\bin")
$env:Path = ($taskPaths -join ';') + ';' + $env:Path
