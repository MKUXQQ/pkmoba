$ErrorActionPreference = "Stop"
$src = "D:\dsh项目\宝可梦项目\项目\pkmoba\build\libs\pkmoba-0.30.0.jar"
$mods = "E:\我的世界\.minecraft\versions\兴年宝可梦\mods"
if (-not (Test-Path -LiteralPath $src)) { throw "missing $src" }
if (-not (Test-Path -LiteralPath $mods)) { throw "missing $mods" }
$minecraftRunning = $false
try {
    $minecraftRunning = [bool](Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -like '*minecraft*' })
} catch { }
if ($minecraftRunning) { throw "Minecraft 正在运行，请先退出游戏再安装" }
Add-Type -AssemblyName Microsoft.VisualBasic
$before = Get-ChildItem -LiteralPath $mods -File | Select-Object -ExpandProperty Name
foreach ($old in Get-ChildItem -LiteralPath $mods -File -Filter "pkmoba-*.jar") {
    [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile($old.FullName,
        [Microsoft.VisualBasic.FileIO.UIOption]::OnlyErrorDialogs,
        [Microsoft.VisualBasic.FileIO.RecycleOption]::SendToRecycleBin)
    "Recycled $($old.Name)"
}
Copy-Item -LiteralPath $src -Destination (Join-Path $mods "pkmoba-0.30.0.jar") -Force
$dst = Join-Path $mods "pkmoba-0.30.0.jar"
"Installed $dst"
"Size: $((Get-Item -LiteralPath $dst).Length)"
"sha256: $((Get-FileHash -LiteralPath $dst -Algorithm SHA256).Hash)"
$after = Get-ChildItem -LiteralPath $mods -File | Select-Object -ExpandProperty Name
"Other files unchanged: $((Compare-Object $before $after | Where-Object { $_.SideIndicator -eq '==' }).Count)"
