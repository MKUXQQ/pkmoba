$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar=Join-Path $PSScriptRoot 'build/libs/pkmoba-0.27.0.jar'
$zip=[IO.Compression.ZipFile]::OpenRead($jar)
function ReadEntry($name){$e=$zip.GetEntry($name);if(!$e){throw "Missing $name"};$r=[IO.StreamReader]::new($e.Open());try{$r.ReadToEnd()}finally{$r.Dispose()}}
try{
$meta=ReadEntry 'fabric.mod.json'|ConvertFrom-Json
if($meta.version -ne '0.27.0'){throw 'Wrong version'}
foreach($name in @('com/pkmoba/combat/BlastoiseCombat.class','com/pkmoba/combat/BlastoiseCombat$Drop.class','com/pkmoba/combat/BlastoiseCombat$Blade.class','com/pkmoba/combat/CombatDamage.class','com/pkmoba/client/ShellAnimator.class','com/pkmoba/client/WaterEffects.class','assets/pkmoba/textures/effect/water_smooth.png','pkmoba-refmap.json')){if(!$zip.GetEntry($name)){throw "Missing $name"}}
$lang=ReadEntry 'assets/pkmoba/lang/zh_cn.json'|ConvertFrom-Json
if(!$lang.'skill.pkmoba.blastoise.w'){throw 'Missing W translation'}
$english=ReadEntry 'assets/pkmoba/lang/en_us.json'|ConvertFrom-Json
if($english.'skill.pkmoba.blastoise.q' -ne 'Water Blade'){throw 'Wrong Q translation'}
$animation=ReadEntry 'assets/pkmoba/bedrock/animations/pkmoba_blastoise.animation.json'|ConvertFrom-Json
if(!$animation.animations.'animation.pkmoba.blastoise.shell_spin'){throw 'Missing shell spin'}
if(@($zip.Entries|Where-Object FullName -like '*.py').Count){throw 'Unexpected development script'}
$result=[ordered]@{version=$meta.version;archiveChecks='PASS';clientRuntime='NOT_RUN';tests='NO_SOURCE';sha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash}
}finally{$zip.Dispose()}
$result|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $PSScriptRoot 'verification-0.27.0.json') -Encoding utf8
$result|ConvertTo-Json
