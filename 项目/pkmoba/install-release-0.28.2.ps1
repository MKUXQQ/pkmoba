$ErrorActionPreference='Stop'
$mods='E:\我的世界\.minecraft\versions\兴年宝可梦\mods'
$source=Join-Path $PSScriptRoot 'build/libs/pkmoba-0.28.2.jar'
$target=Join-Path $mods 'pkmoba-0.28.2.jar'
if((Resolve-Path -LiteralPath $mods).Path -ne $mods){throw 'Unexpected directory'}
if(Get-Process java,javaw -ErrorAction SilentlyContinue){throw 'Java running; close game first'}
if(Test-Path -LiteralPath $target){throw 'Target exists; inspect first'}
$old=@(Get-ChildItem -LiteralPath $mods -Filter 'pkmoba-*.jar')
foreach($file in $old){if($file.DirectoryName -ne $mods){throw 'Unexpected recycle path'}}
$others=@{}
Get-ChildItem -LiteralPath $mods -File | Where-Object {$_.Name -notlike 'pkmoba*.jar'} | ForEach-Object {$others[$_.Name]=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
Copy-Item -LiteralPath $source -Destination $target
$hash=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
if((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $hash){throw 'Hash mismatch; old jars preserved'}
Add-Type -AssemblyName Microsoft.VisualBasic
foreach($file in $old){[Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile($file.FullName,[Microsoft.VisualBasic.FileIO.UIOption]::OnlyErrorDialogs,[Microsoft.VisualBasic.FileIO.RecycleOption]::SendToRecycleBin);Write-Output ('Recycled: '+$file.Name)}
$remaining=@(Get-ChildItem -LiteralPath $mods -Filter 'pkmoba*.jar')
if($remaining.Count -ne 1 -or $remaining[0].Name -ne 'pkmoba-0.28.2.jar'){throw 'Unexpected PKMOBA set'}
foreach($name in $others.Keys){if((Get-FileHash -LiteralPath (Join-Path $mods $name) -Algorithm SHA256).Hash -ne $others[$name]){throw 'Other mod changed'}}
Write-Output ('Installed: '+$remaining[0].Name+'; bytes='+$remaining[0].Length)
Write-Output ('SHA256: '+$hash)
Write-Output ('Other files unchanged: '+$others.Count)
