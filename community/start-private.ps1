param([string]$GoogleClientId = '', [int]$Port = 8765, [switch]$TrustLoopbackProxy)
$ErrorActionPreference = 'Stop'
$communityRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$statePath = Join-Path $communityRoot '.state'
New-Item -ItemType Directory -Path $statePath -Force | Out-Null
# Apply an explicit ACL only to this independent service's private state directory.
$userSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
$stateAcl = New-Object System.Security.AccessControl.DirectorySecurity
$stateAcl.SetAccessRuleProtection($true, $false)
$inheritFlags = [System.Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit'
$propFlags = [System.Security.AccessControl.PropagationFlags]::None
$stateAcl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($userSid, 'FullControl', $inheritFlags, $propFlags, 'Allow')))
$systemSid = New-Object System.Security.Principal.SecurityIdentifier('S-1-5-18')
$stateAcl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($systemSid, 'FullControl', $inheritFlags, $propFlags, 'Allow')))
Set-Acl -LiteralPath $statePath -AclObject $stateAcl
$env:CRAGLIGHT_COMMUNITY_STATE = $statePath
$env:CRAGLIGHT_GOOGLE_CLIENT_ID = $GoogleClientId
$env:CRAGLIGHT_PORT = "$Port"
$env:CRAGLIGHT_TRUST_LOOPBACK_PROXY = $(if ($TrustLoopbackProxy) { '1' } else { '0' })
Push-Location $communityRoot
try { & (Join-Path $communityRoot '.venv\Scripts\python.exe') (Join-Path $communityRoot 'app.py'); exit $LASTEXITCODE }
finally { Pop-Location }
