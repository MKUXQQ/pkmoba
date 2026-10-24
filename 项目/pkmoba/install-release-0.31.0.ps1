$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$mods = 'E:\我的世界\.minecraft\versions\兴年宝可梦\mods'
$src  = Join-Path $PSScriptRoot 'build\libs\pkmoba-0.31.0.jar'
if (-not (Test-Path -LiteralPath $src)) { throw "build artifact missing: $src" }
$rb = (New-Object -ComObject Shell.Application).NameSpace(10)
Get-ChildItem -LiteralPath $mods -Filter 'pkmoba-*.jar' | ForEach-Object {
    Write-Output ("Recycled " + $_.Name)
    $rb.MoveHere($_.FullName, 4)
}
Copy-Item -LiteralPath $src -Destination (Join-Path $mods 'pkmoba-0.31.0.jar') -Force
$dst = Join-Path $mods 'pkmoba-0.31.0.jar'
Write-Output ("Installed " + $dst)
Write-Output ("Bytes " + (Get-Item -LiteralPath $dst).Length)
Write-Output ("SHA256 " + (Get-FileHash -LiteralPath $dst -Algorithm SHA256).Hash)
Write-Output ("Other files unchanged: " + (Get-ChildItem -LiteralPath $mods -Filter '*.jar' | Where-Object { $_.Name -notlike 'pkmoba-*' }).Count)
