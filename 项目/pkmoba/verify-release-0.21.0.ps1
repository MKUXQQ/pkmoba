$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$path=Join-Path $PSScriptRoot 'build/libs/pkmoba-0.21.0.jar'
$zip=[IO.Compression.ZipFile]::OpenRead($path)
try {
  $required=@('fabric.mod.json','pkmoba.mixins.json','pkmoba-refmap.json','com/pkmoba/network/BasicAttackPayload.class','com/pkmoba/network/TornadoPayload.class','com/pkmoba/client/TornadoEffects.class','com/pkmoba/client/CameraCommands.class','com/pkmoba/client/CameraSettings.class','com/pkmoba/combat/BasicAttack.class','com/pkmoba/client/Targeting.class')
  foreach($name in $required){if(!$zip.GetEntry($name)){throw "Missing jar entry: $name"}}
  $reader=[IO.StreamReader]::new($zip.GetEntry('fabric.mod.json').Open())
  try{$meta=$reader.ReadToEnd() | ConvertFrom-Json}finally{$reader.Dispose()}
  if($meta.version -ne '0.21.0'){throw 'Wrong artifact version'}
  'JarVersion='+$meta.version
  'Classes='+@($zip.Entries | Where-Object FullName -like '*.class').Count
  'RequiredEntries=PASS'
}finally{$zip.Dispose()}
Get-FileHash -LiteralPath $path -Algorithm SHA256 | Format-List
