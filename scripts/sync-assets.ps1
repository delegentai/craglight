$ErrorActionPreference = 'Stop'
$projectPath = Split-Path $PSScriptRoot -Parent
$sourcePath = Join-Path $projectPath 'data/packs'
$assetPath = Join-Path $projectPath 'app/src/main/assets/packs'
New-Item -ItemType Directory -Path $assetPath -Force | Out-Null
$manifest = Get-Content -LiteralPath (Join-Path $sourcePath 'manifest.json') -Raw | ConvertFrom-Json
foreach ($name in @('manifest.json','all-crags.json','basemap.geojson')) {
    Copy-Item -LiteralPath (Join-Path $sourcePath $name) -Destination (Join-Path $assetPath $name) -Force
}
foreach ($region in $manifest.regions) {
    $sourceFile = Join-Path $sourcePath $region.files.json
    $checksum = (Get-FileHash -LiteralPath $sourceFile -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($checksum -ne $region.sha256.json) { throw "Checksum mismatch for $($region.id)" }
    Copy-Item -LiteralPath $sourceFile -Destination (Join-Path $assetPath $region.files.json) -Force
    $tileFile = Join-Path $sourcePath $region.files.mbtiles
    if ((Get-FileHash -LiteralPath $tileFile -Algorithm SHA256).Hash.ToLowerInvariant() -ne $region.sha256.mbtiles) { throw "Tile checksum mismatch for $($region.id)" }
    Copy-Item -LiteralPath $tileFile -Destination (Join-Path $assetPath $region.files.mbtiles) -Force
}
if (Test-Path -LiteralPath (Join-Path $sourcePath 'photos')) {
    $photoTarget = [IO.Path]::GetFullPath((Join-Path (Split-Path $assetPath -Parent) 'photos'))
    $expectedTarget = [IO.Path]::GetFullPath((Join-Path $projectPath 'app/src/main/assets/photos'))
    if ($photoTarget -ne $expectedTarget) { throw 'Unexpected photo asset destination' }
    New-Item -ItemType Directory -Path $photoTarget -Force | Out-Null
    $photoNames = @(Get-ChildItem -LiteralPath (Join-Path $sourcePath 'photos') -File | ForEach-Object { $_.Name })
    Get-ChildItem -LiteralPath $photoTarget -File | Where-Object { $_.Name -notin $photoNames } | Remove-Item
    Copy-Item -LiteralPath (Join-Path $sourcePath 'photos') -Destination (Split-Path $assetPath -Parent) -Recurse -Force
}
Write-Output "Bundled verified catalogue and $($manifest.regions.Count) regional fallback packs."
& python (Join-Path $projectPath 'pipeline/write_credits.py')
if ($LASTEXITCODE -ne 0) { throw 'Offline source credits could not be refreshed' }
