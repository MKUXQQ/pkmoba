$ErrorActionPreference = 'Stop'
$mods = 'E:\我的世界\.minecraft\versions\兴年宝可梦\mods'
$src = 'D:\dsh项目\宝可梦项目\项目\pkmoba\build\libs\pkmoba-0.29.0.jar'
if (-not (Test-Path -LiteralPath $src)) { throw "source jar missing: $src" }

$old = Get-ChildItem -LiteralPath $mods -Filter 'pkmoba-*.jar' -File
foreach ($f in $old) {
    if ($f.Name -ne 'pkmoba-0.29.0.jar') {
        Add-Type -AssemblyName Microsoft.VisualBasic
        [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile($f.FullName,
            [Microsoft.VisualBasic.FileIO.UIOption]::OnlyErrorDialogs,
            [Microsoft.VisualBasic.FileIO.RecycleOption]::SendToRecycleBin)
        "Recycled $($f.Name)"
    }
}

$dest = Join-Path $mods 'pkmoba-0.29.0.jar'
Copy-Item -LiteralPath $src -Destination $dest -Force
$item = Get-Item -LiteralPath $dest
$hash = (Get-FileHash -LiteralPath $dest -Algorithm SHA256).Hash
"Installed $($item.FullName)"
"Bytes $($item.Length)"
"SHA256 $hash"
$others = Get-ChildItem -LiteralPath $mods -Filter '*.jar' -File | Where-Object { $_.Name -ne 'pkmoba-0.29.0.jar' }
"Other files unchanged: $($others.Count)"
