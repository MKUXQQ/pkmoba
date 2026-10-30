$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar=Join-Path $PSScriptRoot 'build/libs/pkmoba-0.25.0.jar'
$zip=[IO.Compression.ZipFile]::OpenRead($jar)
function ReadEntry($name){$e=$zip.GetEntry($name);if(!$e){throw "Missing $name"};$r=[IO.StreamReader]::new($e.Open());try{$r.ReadToEnd()}finally{$r.Dispose()}}
try{
 $meta=ReadEntry 'fabric.mod.json'|ConvertFrom-Json
 if($meta.version -ne '0.25.0'){throw 'Wrong version'}
 foreach($name in @('com/pkmoba/combat/BlastoiseCombat.class','com/pkmoba/combat/BasicAttack.class','com/pkmoba/client/WalkAnimator.class','pkmoba-refmap.json')){if(!$zip.GetEntry($name)){throw "Missing $name"}}
 foreach($i in 0..3){$e=$zip.GetEntry("assets/pkmoba/textures/gui/skills/blastoise_$i.png");if(!$e -or $e.Length -lt 50){throw "Missing water icon $i"}}
 $lang=ReadEntry 'assets/pkmoba/lang/zh_cn.json'|ConvertFrom-Json
 foreach($key in @('q','w','e','r')){if(!$lang."skill.pkmoba.blastoise.$key"){throw "Missing water skill $key"}}
 $mix=ReadEntry 'pkmoba.mixins.json'|ConvertFrom-Json
 foreach($name in @('KeyboardInputMixin','KeyMappingMixin','PlayerRendererMixin')){if($name -notin $mix.client){throw "Missing mixin $name"}}
 $result=[ordered]@{version=$meta.version;archiveChecks='PASS';clientRuntime='NOT_RUN';tests='NO_SOURCE';sha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash}
}finally{$zip.Dispose()}
$result|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $PSScriptRoot 'verification-0.25.0.json') -Encoding utf8
$result|ConvertTo-Json
