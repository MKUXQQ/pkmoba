$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$source=Join-Path $root '../宝可梦工程文件/cobblemon-1.8.2/common/src/main/resources/assets/cobblemon/bedrock/pokemon/animations/0006_charizard/charizard.animation.json'
$fast=Join-Path $root 'src/main/resources/assets/pkmoba/bedrock/pokemon/animations/pkmoba_melee.animation.json'
$a=(Get-Content -LiteralPath $source -Raw|ConvertFrom-Json).animations.'animation.charizard.physical'
$b=(Get-Content -LiteralPath $fast -Raw|ConvertFrom-Json).animations.'animation.pkmoba_melee.physical'
if($a.animation_length/2 -ne $b.animation_length){throw 'Duration mismatch'}
$count=0
foreach($bone in $a.bones.PSObject.Properties){
 foreach($channel in $bone.Value.PSObject.Properties){
  $original=$channel.Value
  $modified=$b.bones.($bone.Name).($channel.Name)
  foreach($frame in $original.PSObject.Properties){
   $time=0.0
   if([double]::TryParse($frame.Name,[Globalization.NumberStyles]::Float,[Globalization.CultureInfo]::InvariantCulture,[ref]$time)){
    $key=($time/2).ToString('0.################',[Globalization.CultureInfo]::InvariantCulture)
    $match=@($modified.PSObject.Properties|Where-Object { [double]::Parse($_.Name,[Globalization.CultureInfo]::InvariantCulture) -eq ($time/2) })
    if($match.Count -ne 1){throw "Missing frame $($bone.Name)/$($channel.Name)/$key"}
    if(($frame.Value|ConvertTo-Json -Depth 50 -Compress) -ne ($match[0].Value|ConvertTo-Json -Depth 50 -Compress)){throw 'Motion values changed'}
    $count++
   }
  }
 }
}
Write-Output "PASS: $count keyframes mapped to 2x speed with identical motion values"
$code=Get-Content -LiteralPath (Join-Path $root 'src/main/java/com/pkmoba/combat/BasicAttack.java') -Raw
if($code -match 'displayClientMessage'){throw 'Combat still shows actionbar text'}
if($code -notmatch 'WINDUP_TICKS = 13' -or $code -notmatch 'hitAt = now \+ WINDUP_TICKS'){throw 'Missing delayed strike'}
Write-Output 'PASS: delayed hit and silent actionbar source checks (not gameplay tests)'
