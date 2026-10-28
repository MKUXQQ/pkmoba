$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { Join-Path (Split-Path -Parent $project) 'tools/jdk21' }
$output = Join-Path $project 'build/room-rule-check'
New-Item -ItemType Directory -Force $output | Out-Null
& (Join-Path $jdk 'bin/javac.exe') -encoding UTF-8 -d $output (Join-Path $project 'src/main/java/com/pkmoba/duel/RoomRules.java') (Join-Path $project 'src/main/java/com/pkmoba/duel/RosterVisibility.java') (Join-Path $PSScriptRoot 'RoomRulesCheck.java')
if ($LASTEXITCODE -ne 0) { throw 'RoomRules test compilation failed' }
& (Join-Path $jdk 'bin/java.exe') -cp $output RoomRulesCheck
if ($LASTEXITCODE -ne 0) { throw 'RoomRules regression checks failed' }
