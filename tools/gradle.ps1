# All build/cache/tool state stays in the CragLight workspace.
. "$PSScriptRoot\env.ps1"
$projectDirectory = Split-Path $PSScriptRoot -Parent
Push-Location $projectDirectory
try {
    & "$env:CRAGLIGHT_TOOLS\gradle-8.11.1\bin\gradle.bat" --no-daemon @args
    $result = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $result
