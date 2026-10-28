$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { Join-Path (Split-Path -Parent $project) 'tools/jdk21' }
$javac = Join-Path $jdk 'bin/javac.exe'
$java = Join-Path $jdk 'bin/java.exe'
if (-not (Test-Path $javac)) { $javac = (Get-Command javac.exe -ErrorAction Stop).Source }
if (-not (Test-Path $java)) { $java = (Get-Command java.exe -ErrorAction Stop).Source }
$output = Join-Path $project 'build/room-rule-check'
New-Item -ItemType Directory -Force $output | Out-Null
# Compile the real registry rather than copying its species count into a test stub.
# Only Minecraft's identifier value type and Nullable annotation need lightweight shims.
$shims = Join-Path $output 'shims'
New-Item -ItemType Directory -Force (Join-Path $shims 'net/minecraft/resources'), (Join-Path $shims 'javax/annotation') | Out-Null
$resource = Join-Path $shims 'net/minecraft/resources/ResourceLocation.java'
@'
package net.minecraft.resources;
public record ResourceLocation(String namespace, String path) {
    public static ResourceLocation fromNamespaceAndPath(String namespace, String path) {
        return new ResourceLocation(namespace, path);
    }
    public static ResourceLocation tryParse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        int colon = raw.indexOf(':');
        return colon < 0 ? new ResourceLocation("minecraft", raw)
            : new ResourceLocation(raw.substring(0, colon), raw.substring(colon + 1));
    }
    public String getPath() { return path; }
    @Override public String toString() { return namespace + ":" + path; }
}
'@ | Set-Content -Encoding ascii $resource
$nullable = Join-Path $shims 'javax/annotation/Nullable.java'
'package javax.annotation; public @interface Nullable {}' | Set-Content -Encoding ascii $nullable
& $javac -encoding UTF-8 -d $output $resource $nullable (Join-Path $project 'src/main/java/com/pkmoba/pokemon/SpeciesRegistry.java') (Join-Path $project 'src/main/java/com/pkmoba/duel/RoomRules.java') (Join-Path $project 'src/main/java/com/pkmoba/duel/RosterVisibility.java') (Join-Path $PSScriptRoot 'RoomRulesCheck.java')
if ($LASTEXITCODE -ne 0) { throw 'RoomRules test compilation failed' }
& $java -cp $output RoomRulesCheck
if ($LASTEXITCODE -ne 0) { throw 'RoomRules regression checks failed' }
