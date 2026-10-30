# 安装 pkmoba-0.34.0 到兴年宝可梦实例：回收旧 pkmoba-*.jar 到回收站，其他 mod 一律不动。
$ErrorActionPreference = 'Stop'

$mods = 'E:\我的世界\.minecraft\versions\兴年宝可梦\mods'
$src  = 'D:\dsh项目\宝可梦项目\项目\pkmoba\build\libs\pkmoba-0.34.0.jar'

if (-not (Test-Path -LiteralPath $mods)) { throw "mods 目录不存在：$mods" }
if (-not (Test-Path -LiteralPath $src))  { throw "构建产物不存在：$src" }

$before = Get-ChildItem -LiteralPath $mods -Filter '*.jar' | Sort-Object Name

# 只回收 pkmoba-*.jar（旧版本），其他模组不动
$old = $before | Where-Object { $_.Name -like 'pkmoba-*.jar' }
foreach ($f in $old) {
    if ($f.FullName -ne (Join-Path $mods 'pkmoba-0.34.0.jar')) {
        Add-Type -AssemblyName Microsoft.VisualBasic
        [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile(
            $f.FullName,
            [Microsoft.VisualBasic.FileIO.UIOption]::OnlyErrorDialogs,
            [Microsoft.VisualBasic.FileIO.RecycleOption]::SendToRecycleBin)
        Write-Output "Recycled $($f.Name)"
    }
}

Copy-Item -LiteralPath $src -Destination (Join-Path $mods 'pkmoba-0.34.0.jar') -Force

$target = Join-Path $mods 'pkmoba-0.34.0.jar'
$info = Get-Item -LiteralPath $target
$hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLower()
Write-Output "Installed $target"
Write-Output "Bytes $($info.Length)"
Write-Output "SHA256 $hash"

$after = Get-ChildItem -LiteralPath $mods -Filter '*.jar'
Write-Output "Other files unchanged: $($after.Count - 1)"
$after | Sort-Object Name | ForEach-Object { Write-Output "  $($_.Name)" }
