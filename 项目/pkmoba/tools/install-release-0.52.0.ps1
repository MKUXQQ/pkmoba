param([Parameter(Mandatory=$true)][string]$Mods, [Parameter(Mandatory=$true)][string]$Archive)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$workspace = Split-Path (Split-Path $project -Parent) -Parent
$source = Join-Path $project 'build\libs\pkmoba-0.52.0.jar'
$resolvedMods = (Resolve-Path -LiteralPath $Mods).Path
if ($resolvedMods -ne $Mods) { throw 'Unexpected mods directory' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($source)
try {
    $reader = [System.IO.StreamReader]::new($zip.GetEntry('fabric.mod.json').Open())
    try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    if ($manifest.id -ne 'pkmoba' -or $manifest.version -ne '0.52.0') { throw 'Wrong release manifest' }
    foreach ($name in @('ExpansionCombat','ExpansionRoster','ExpansionGeometry','ExpansionControl','ExpansionWalls')) {
        if (!$zip.GetEntry("com/pkmoba/combat/$name.class")) { throw "Missing class: $name" }
    }
    foreach ($name in @('ExpansionAnchors','ExpansionEffects')) {
        if (!$zip.GetEntry("com/pkmoba/client/$name.class")) { throw "Missing class: $name" }
    }
    foreach ($species in @('lucario','abomasnow','blaziken','gengar','tyranitar','garchomp','scizor','gardevoir')) {
        foreach ($path in @("textures/gui/pokemon/$species.png", "bedrock/pokemon/animations/pkmoba_$species.animation.json")) {
            if (!$zip.GetEntry("assets/pkmoba/$path")) { throw "Missing resource: $path" }
        }
        foreach ($slot in @('q','w','e','r')) {
            if (!$zip.GetEntry("assets/pkmoba/textures/gui/skills/${species}_$slot.png")) { throw "Missing icon: $species $slot" }
        }
    }
    Write-Output "JAR ENTRIES: $($zip.Entries.Count)"
} finally { $zip.Dispose() }
$hash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
if (!(Test-Path -LiteralPath $Archive -PathType Container)) { throw 'Archive directory missing' }
foreach ($directory in @($Mods,$Archive,$workspace)) {
    $target = Join-Path $directory 'pkmoba-0.52.0.jar'
    Copy-Item -LiteralPath $source -Destination $target -Force
    if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $hash) { throw "Hash mismatch: $target" }
    Write-Output "VERIFIED: $target"
}
# Verify every absolute deletion target and only remove older PKMOBA jars after new copy verification.
$old = @(Get-ChildItem -LiteralPath $Mods -File -Filter 'pkmoba-*.jar' | Where-Object { $_.Name -ne 'pkmoba-0.52.0.jar' })
foreach ($file in $old) {
    $resolved = (Resolve-Path -LiteralPath $file.FullName).Path
    if ((Split-Path $resolved -Parent) -ne $resolvedMods -or $file.Name -notmatch '^pkmoba-[0-9].*\.jar$') { throw "Unexpected deletion target: $resolved" }
    Write-Output "REMOVE OLD RELEASE: $resolved"
    Remove-Item -LiteralPath $resolved
}
Write-Output "SHA256: $hash"
Write-Output "BYTES: $((Get-Item -LiteralPath $source).Length)"
Get-ChildItem -LiteralPath $Mods -File -Filter 'pkmoba-*.jar' | Select-Object Name,Length
