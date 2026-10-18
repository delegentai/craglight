$ErrorActionPreference = 'Stop'
$projectPath = Split-Path $PSScriptRoot -Parent
. (Join-Path $projectPath 'tools/env.ps1')
$privatePath = Join-Path $projectPath 'private'
$keystorePath = Join-Path $privatePath 'craglight-release.jks'
$propertiesPath = Join-Path $privatePath 'signing.properties'
if ((Test-Path -LiteralPath $keystorePath) -or (Test-Path -LiteralPath $propertiesPath)) { throw 'Signing material already exists. Never overwrite a release identity.' }
New-Item -ItemType Directory -Path $privatePath -Force | Out-Null
$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $privatePath /inheritance:r /grant:r ('{0}:(OI)(CI)F' -f $identity) | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Unable to restrict signing folder access' }
$randomBytes = New-Object byte[] 36
[System.Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
$secret = [Convert]::ToBase64String($randomBytes)
$env:CRAGLIGHT_NEW_KEY_PASSWORD = $secret
try {
    & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -keystore $keystorePath -storetype JKS -storepass:env CRAGLIGHT_NEW_KEY_PASSWORD -keypass:env CRAGLIGHT_NEW_KEY_PASSWORD -alias craglight-release -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=CragLight Android Release' -noprompt
    if ($LASTEXITCODE -ne 0) { throw 'Key generation failed' }
    $properties = "storeFile=private/craglight-release.jks`nstorePassword=$secret`nkeyAlias=craglight-release`nkeyPassword=$secret`n"
    [System.IO.File]::WriteAllText($propertiesPath, $properties, [System.Text.UTF8Encoding]::new($false))
    Write-Output 'Created a new CragLight signing identity. Key and passwords are private and excluded from Git.'
} finally { Remove-Item Env:CRAGLIGHT_NEW_KEY_PASSWORD -ErrorAction SilentlyContinue; $secret = $null; $properties = $null }
