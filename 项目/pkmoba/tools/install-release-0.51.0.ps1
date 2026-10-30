param([Parameter(Mandatory=$true)][string]$Mods, [Parameter(Mandatory=$true)][string]$Archive)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$workspace = Split-Path (Split-Path $project -Parent) -Parent
$source = Join-Path $project 'build/libs/pkmoba-0.51.0.jar'
$resolvedMods = (Resolve-Path -LiteralPath $Mods).Path
if ($resolvedMods -ne $Mods) { throw 'Unexpected mods target' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($source)
try {
    $reader = [System.IO.StreamReader]::new($zip.GetEntry('fabric.mod.json').Open())
    try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    if ($manifest.id -ne 'pkmoba' -or $manifest.version -ne '0.51.0') { throw 'Wrong release manifest' }
    foreach ($required in @('com/pkmoba/duel/RosterVisibility.class','com/pkmoba/client/RoomTeamScreen.class','com/pkmoba/client/PixelUi.class')) {
        if (!$zip.GetEntry($required)) { throw "Missing class: $required" }
    }
} finally { $zip.Dispose() }
$hash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
if (!(Test-Path -LiteralPath $Archive -PathType Container)) { throw 'Archive missing' }
# Preflight all old jars before copying, so a running game cannot leave duplicate mod IDs.
$oldReleases = @(Get-ChildItem -LiteralPath $Mods -File -Filter 'pkmoba-*.jar' | Where-Object Name -ne 'pkmoba-0.51.0.jar')
foreach ($file in $oldReleases) {
    $resolved = (Resolve-Path -LiteralPath $file.FullName).Path
    if ((Split-Path $resolved -Parent) -ne $resolvedMods -or $file.Name -notmatch '^pkmoba-[0-9].*\.jar$') { throw 'Unexpected old release target' }
    try {
        $handle = [System.IO.File]::Open($resolved, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
        $handle.Dispose()
    } catch { throw "Old release is locked; close Minecraft before installing: $resolved" }
}
foreach ($directory in @($Mods, $Archive, $workspace)) {
    $target = Join-Path $directory 'pkmoba-0.51.0.jar'
    Copy-Item -LiteralPath $source -Destination $target -Force
    if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $hash) { throw "Hash mismatch: $target" }
    Write-Output "VERIFIED: $target"
}
foreach ($file in @(Get-ChildItem -LiteralPath $Mods -File -Filter 'pkmoba-*.jar' | Where-Object Name -ne 'pkmoba-0.51.0.jar')) {
    $resolved = (Resolve-Path -LiteralPath $file.FullName).Path
    if ((Split-Path $resolved -Parent) -ne $resolvedMods -or $file.Name -notmatch '^pkmoba-[0-9].*\.jar$') { throw 'Unexpected deletion target' }
    Write-Output "REMOVE OLD RELEASE: $resolved"
    Remove-Item -LiteralPath $resolved
}
Write-Output "SHA256: $hash"
Write-Output "BYTES: $((Get-Item -LiteralPath $source).Length)"
