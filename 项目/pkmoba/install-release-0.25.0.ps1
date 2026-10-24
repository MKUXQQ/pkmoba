$ErrorActionPreference='Stop'
$mods='E:\我的世界\.minecraft\versions\兴年宝可梦\mods'
$source=Join-Path $PSScriptRoot 'build/libs/pkmoba-0.25.0.jar'
$target=Join-Path $mods 'pkmoba-0.25.0.jar'
$resolved=(Resolve-Path -LiteralPath $mods).Path
if($resolved -ne $mods){throw 'Unexpected directory'}
if(Get-Process java,javaw -ErrorAction SilentlyContinue){throw 'Java running; close game before installation'}
& (Join-Path $PSScriptRoot 'verify-release-0.25.0.ps1')
$old=@(Get-ChildItem -LiteralPath $mods -Filter 'pkmoba-*.jar'|Where-Object Name -ne 'pkmoba-0.25.0.jar')
foreach($file in $old){if($file.DirectoryName -ne $resolved){throw 'Unexpected recycle path'}}
if(Test-Path -LiteralPath $target){throw 'Target already exists; inspect first'}
Copy-Item -LiteralPath $source -Destination $target
$hash=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
if((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $hash){throw 'Hash mismatch; old jars preserved'}
Add-Type -AssemblyName Microsoft.VisualBasic
foreach($file in $old){[Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile($file.FullName,[Microsoft.VisualBasic.FileIO.UIOption]::OnlyErrorDialogs,[Microsoft.VisualBasic.FileIO.RecycleOption]::SendToRecycleBin);Write-Output ('Recycled: '+$file.FullName)}
$remaining=@(Get-ChildItem -LiteralPath $mods -Filter 'pkmoba*.jar')
if($remaining.Count -ne 1 -or $remaining[0].Name -ne 'pkmoba-0.25.0.jar'){throw 'Unexpected PKMOBA set'}
Write-Output ('Installed: '+$target)
Write-Output ('SHA256: '+$hash)
