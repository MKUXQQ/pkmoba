$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$path = Join-Path $PSScriptRoot 'build/libs/pkmoba-0.22.0.jar'
$zip = [IO.Compression.ZipFile]::OpenRead($path)
function ReadEntry($name) {
  $entry=$zip.GetEntry($name); if (!$entry) {throw "Missing entry: $name"}
  $reader=[IO.StreamReader]::new($entry.Open()); try {$reader.ReadToEnd()} finally {$reader.Dispose()}
}
try {
  $meta=ReadEntry 'fabric.mod.json' | ConvertFrom-Json
  if ($meta.version -ne '0.22.0') {throw 'Wrong version'}
  $mixins=ReadEntry 'pkmoba.mixins.json' | ConvertFrom-Json
  foreach ($name in @('StarterOverlayMixin','HudMixin','KeyMappingMixin','PlayerRendererMixin')) {
    if ($name -notin $mixins.client) {throw "Missing mixin registration $name"}
    if (!$zip.GetEntry("com/pkmoba/mixin/$name.class")) {throw "Missing class $name"}
  }
  foreach ($name in @('flamethrower','fire_spin','fly','fire_blast')) {
    $entry=$zip.GetEntry("assets/pkmoba/textures/gui/skills/$name.png")
    if (!$entry -or $entry.Length -lt 50) {throw "Missing/empty icon $name"}
  }
  foreach ($name in @('com/pkmoba/client/SkillPresentation.class','pkmoba-refmap.json')) {
    if (!$zip.GetEntry($name)) {throw "Missing $name"}
  }
  $result=[ordered]@{version=$meta.version; classes=@($zip.Entries | Where-Object FullName -like '*.class').Count; archiveChecks='PASS'; clientRuntime='NOT_RUN'; tests='NO_SOURCE'; sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
} finally {$zip.Dispose()}
$result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'verification-0.22.0.json') -Encoding utf8
$result | ConvertTo-Json
