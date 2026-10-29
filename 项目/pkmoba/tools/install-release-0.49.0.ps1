param([Parameter(Mandatory=$true)][string]$Mods, [Parameter(Mandatory=$true)][string]$Archive)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$workspace = Split-Path (Split-Path $project -Parent) -Parent
$source = Join-Path $project 'build\libs\pkmoba-0.49.0.jar'
if (!(Test-Path -LiteralPath $source -PathType Leaf)) { throw 'Release jar not found' }
$resolvedMods = (Resolve-Path -LiteralPath $mods).Path
if ($resolvedMods -ne $mods) { throw "Unexpected mods target: $resolvedMods" }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($source)
try {
    $entry = $zip.GetEntry('fabric.mod.json')
    $reader = [System.IO.StreamReader]::new($entry.Open())
    try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    if ($manifest.id -ne 'pkmoba' -or $manifest.version -ne '0.49.0') { throw 'Wrong release manifest' }
    foreach ($required in @('com/pkmoba/combat/CastLock.class','com/pkmoba/client/RoomTeamScreen.class','com/pkmoba/client/RoomSettingsScreen.class','com/pkmoba/client/RoomPasswordScreen.class','com/pkmoba/duel/RoomSettingsStore.class','com/pkmoba/duel/RoomRules.class')) {
        if (!$zip.GetEntry($required)) { throw "Missing class: $required" }
    }
} finally { $zip.Dispose() }
$hash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
if (!(Test-Path -LiteralPath $archive)) { throw 'Expected archive directory missing' }
foreach ($directory in @($mods, $archive, $workspace)) {
    $target = Join-Path $directory 'pkmoba-0.49.0.jar'
    Copy-Item -LiteralPath $source -Destination $target -Force
    if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $hash) { throw "Hash mismatch: $target" }
    Write-Output "VERIFIED: $target"
}
# Only remove old PKMOBA releases after the new game copy passes verification.
$old = @(Get-ChildItem -LiteralPath $mods -File -Filter 'pkmoba-*.jar' | Where-Object { $_.Name -ne 'pkmoba-0.49.0.jar' })
foreach ($file in $old) {
    $resolved = (Resolve-Path -LiteralPath $file.FullName).Path
    if ((Split-Path $resolved -Parent) -ne $resolvedMods -or $file.Name -notmatch '^pkmoba-[0-9].*\.jar$') { throw "Unexpected deletion target: $resolved" }
    Write-Output "REMOVE OLD RELEASE: $resolved"
    Remove-Item -LiteralPath $resolved
}
Write-Output "SHA256: $hash"
Write-Output "BYTES: $((Get-Item -LiteralPath $source).Length)"
Get-ChildItem -LiteralPath $mods -File -Filter 'pkmoba-*.jar' | Select-Object Name,Length
