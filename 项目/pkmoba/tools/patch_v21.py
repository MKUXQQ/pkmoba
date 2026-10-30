"""0.87: swap Abomasnow for Alolan Ninetales and rebuild Lucario around his four new moves.

* Alolan Ninetales (cobblemon:ninetales, form "alolan") uses the ice-fairy kit: Ice Shard,
  Icy Wind, Aurora Veil (self guard) and Blizzard (zone). It replaces Abomasnow everywhere, and
  the removed species stop appearing in the selection column / team list.
* Lucario: Swords Dance (+36.6% attack), Meteor Mash, Thunder Punch and a much longer Extreme
  Speed, with new pixel sheets that read as fists, blades and bolts instead of thin line work.

Run from the project root: python tools/patch_v21.py
"""
import re
from pathlib import Path

ROOT = Path(".")
JAVA = ROOT / "src/main/java/com/pkmoba"
RES = ROOT / "src/main/resources/assets/pkmoba"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


# --------------------------------------------------------------------------- registry

def patch_registry() -> None:
    path = JAVA / "pokemon/SpeciesRegistry.java"
    text = '''package com.pkmoba.pokemon;

import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * PKMOBA 已实装宝可梦注册表。
 *
 * <p>新增一只宝可梦时只改这里 + 对应战斗引擎；选择界面、指令补全、队伍、头像都从这里取。
 *
 * <p>0.87: a species may be a regional form of another Cobblemon species (Alolan Ninetales is
 * {@code cobblemon:ninetales} with the {@code alolan} aspect), so each entry carries both the local
 * id used for assets/room rosters and the real Cobblemon species to spawn.
 */
public final class SpeciesRegistry {
    public record Entry(ResourceLocation id, String displayName, String portrait,
                        ResourceLocation species, @Nullable String aspect) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    static {
        add("charizard", "喷火龙");
        add("blastoise", "水箭龟");
        add("venusaur", "妙蛙花");
        add("pikachu", "皮卡丘");
        add("blaziken", "火焰鸡");
        add("gardevoir", "沙奈朵");
        add("gengar", "耿鬼");
        add("lucario", "路卡利欧");
        add("tyranitar", "班基拉斯");
        addForm("alolan_ninetales", "阿罗拉九尾", "ninetales", "alolan");
    }

    private SpeciesRegistry() {
    }

    private static void add(String path, String displayName) {
        ENTRIES.add(new Entry(ResourceLocation.fromNamespaceAndPath("cobblemon", path), displayName,
                "textures/gui/pokemon/" + path + ".png",
                ResourceLocation.fromNamespaceAndPath("cobblemon", path), null));
    }

    private static void addForm(String localPath, String displayName, String speciesPath, String aspect) {
        ENTRIES.add(new Entry(ResourceLocation.fromNamespaceAndPath("cobblemon", localPath), displayName,
                "textures/gui/pokemon/" + localPath + ".png",
                ResourceLocation.fromNamespaceAndPath("cobblemon", speciesPath), aspect));
    }

    public static List<Entry> all() {
        return List.copyOf(ENTRIES);
    }

    public static List<String> ids() {
        List<String> list = new ArrayList<>(ENTRIES.size());
        for (Entry entry : ENTRIES) list.add(entry.id().toString());
        return list;
    }

    public static boolean contains(@Nullable ResourceLocation id) {
        if (id == null) return false;
        for (Entry entry : ENTRIES) if (entry.id().equals(id)) return true;
        return false;
    }

    /** 解析 {@code charizard} / {@code cobblemon:charizard} 两种写法。 */
    @Nullable
    public static ResourceLocation parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String text = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (!text.contains(":")) text = "cobblemon:" + text;
        ResourceLocation id = ResourceLocation.tryParse(text);
        return contains(id) ? id : null;
    }

    public static String displayName(@Nullable ResourceLocation id) {
        if (id == null) return "宝可梦";
        for (Entry entry : ENTRIES) if (entry.id().equals(id)) return entry.displayName();
        return id.getPath();
    }

    @Nullable
    public static Entry byId(@Nullable ResourceLocation id) {
        if (id == null) return null;
        for (Entry entry : ENTRIES) if (entry.id().equals(id)) return entry;
        return null;
    }

    /** The Cobblemon species to spawn for a registry id (falls back to the id itself). */
    public static ResourceLocation cobbleSpecies(@Nullable ResourceLocation id) {
        Entry entry = byId(id);
        return entry == null ? id : entry.species();
    }

    /** Applies a regional form (aspect) to a freshly created Pokemon. */
    public static void applyForm(Pokemon pokemon, @Nullable ResourceLocation id) {
        Entry entry = byId(id);
        if (pokemon == null || entry == null || entry.aspect() == null) return;
        pokemon.setForcedAspects(java.util.Set.of(entry.aspect()));
        pokemon.updateForm();
    }
}
'''
    write(path, text)
    print("registry: abomasnow -> alolan_ninetales (form-aware)")


# --------------------------------------------------------------------------- spawn

def patch_spawn() -> None:
    path = JAVA / "charizard/CharizardManager.java"
    text = read(path)
    text = text.replace(
        "        Species species = PokemonSpecies.getByIdentifier(speciesId);",
        "        // 0.87: pick the concrete Cobblemon species (regional forms map through the registry).\n"
        "        Species species = PokemonSpecies.getByIdentifier(SpeciesRegistry.cobbleSpecies(speciesId));")
    text = text.replace("        pokemon.setSpecies(species);\n        pokemon.setLevel(50);",
                        "        pokemon.setSpecies(species);\n"
                        "        SpeciesRegistry.applyForm(pokemon, speciesId);\n"
                        "        pokemon.setLevel(50);")
    assert text.count("cobbleSpecies") == 2, text.count("cobbleSpecies")
    assert text.count("applyForm") == 2
    write(path, text)
    print("spawn: cobble species + form aspect applied in both spawn paths")


# --------------------------------------------------------------------------- roster

def patch_roster() -> None:
    path = JAVA / "combat/ExpansionRoster.java"
    text = read(path)
    old = '''        new Species("abomasnow", "暴雪王", 360, List.of(
            m("冰砾", Shape.LINE, 2, 4, 8, 26, 16, .7, 24),
            m("冰冻之风", Shape.CONE, 3, 7, 8, 20, 8, 40, 30),
            m("白雾", Shape.WALL, 3, 10, 12, 80, 10, 3, 0),
            m("暴风雪", Shape.POINT, 3, 9, 24, 80, 14, 4.5, 32))),'''
    new = '''        new Species("alolan_ninetales", "阿罗拉九尾", 320, List.of(
            // 0.87 ice-fairy kit: a fast shard, a chilling wind, a self guard and a blizzard zone.
            m("冰砾", Shape.LINE, 2, 4, 8, 26, 16, .75, 24),
            m("冰冻之风", Shape.CONE, 3, 6, 8, 20, 8, 42, 26),
            m("极光幕", Shape.SELF, 2, 9, 6, 14, 0, 3.6, 0),
            m("暴风雪", Shape.POINT, 3, 10, 20, 80, 14, 4.5, 40))),'''
    assert old in text
    text = text.replace(old, new)
    old_lucario = '''        new Species("lucario", "路卡利欧", 320, List.of(
            // 0.86 rework: Q is a plain ranged poke that banks one Aura, W is a point-blank palm
            // shock, E is pure mobility and R a self-centred aura detonation that spends the Aura.
            m("波导弹", Shape.LINE, 2, 4, 8, 26, 18, .85, 28),
            m("发劲", Shape.CONE, 2, 5, 4, 14, 4.2, 42, 34),
            m("神速", Shape.DASH, 2, 6, 3, 10, 8, .8, 0),
            m("波导爆发", Shape.SELF, 3, 9, 10, 20, 0, 4.6, 44))),'''
    new_lucario = '''        new Species("lucario", "路卡利欧", 320, List.of(
            // 0.87 moveset: Swords Dance buffs attack by 36.6%, then Meteor Mash / Thunder Punch
            // cash it in; Extreme Speed is a long, responsive dash that hits once on contact.
            m("剑舞", Shape.SELF, 1, 7, 4, 14, 0, 3.0, 0),
            m("彗星拳", Shape.CONE, 2, 5, 5, 16, 4.0, 45, 38),
            m("雷电拳", Shape.CONE, 2, 6, 4, 14, 3.8, 40, 28),
            m("神速", Shape.DASH, 2, 8, 3, 18, 12, .9, 22))),'''
    assert old_lucario in text
    text = text.replace(old_lucario, new_lucario)
    write(path, text)
    print("roster: alolan ninetales ice kit + lucario swords-dance kit")


# --------------------------------------------------------------------------- combat

def remove_method(text: str, signature: str) -> str:
    start = text.index(signature)
    depth = 0
    i = text.index("{", start)
    while True:
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                break
        i += 1
    end = i + 1
    while end < len(text) and text[end] == "\n":
        end += 1
    return text[:start] + text[end:]


def patch_combat() -> None:
    path = JAVA / "combat/ExpansionCombat.java"
    text = read(path)
    text = text.replace('            case "abomasnow" -> abomasnow(level, entity, s, t, move);',
                        '            case "alolan_ninetales" -> alolanNinetales(level, entity, s, t, move);')
    text = text.replace('            case "abomasnow" -> .7;', '            case "alolan_ninetales" -> .7;')
    text = remove_method(text, "    private static void abomasnow(")
    insert_at = text.index("    /**\n     * 0.54 Blaziken kit")
    ninetales = '''    /** 0.87 Alolan Ninetales: ice-fairy pressure with a self guard and a blizzard zone. */
    private static void alolanNinetales(ServerLevel l, PokemonEntity e, State s, int t,
                                       ExpansionRoster.Move m) {
        switch (s.skill) {
            case 0 -> projectile(l, e, s, m, .85, 1.0, 20);
            case 1 -> {
                if (t % 4 == 1) for (LivingEntity target : cone(l, e, s, m)) {
                    if (hit(l, e, s, target, m.damage() * .26F, 5)) {
                        ExpansionControl.slow(e, target, 30, .55);
                        target.setTicksFrozen(Math.min(140, target.getTicksFrozen() + 50));
                    }
                }
            }
            case 2 -> { // Aurora Veil: a self guard that softens incoming damage for a few seconds.
                if (t == 1) {
                    ExpansionControl.guard(e, 200, .35);
                    cue(e, s, "veil", e.position(), s.direction);
                }
                if (t == 2) finish(e, s);
            }
            default -> { // Blizzard: a ticking field that damages and chills everything inside it.
                if (t == 1) {
                    s.snow = m.duration();
                    s.snowPoint = s.point;
                    cue(e, s, "field", s.point, s.direction);
                    cue(e, s, "blizzard", s.point, s.direction);
                    finish(e, s);
                }
            }
        }
    }

'''
    text = text[:insert_at] + ninetales + text[insert_at:]
    # Lucario: swords dance buff, meteor mash, thunder punch and a long extreme speed
    start = text.index("    private static void lucario(")
    end = text.index("    /** Banks Aura", start)
    lucario = '''    private static final float SWORDS_DANCE_BONUS = .366F;
    private static final int SWORDS_DANCE_TICKS = 140;

    private static void lucario(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch (s.skill) {
            case 0 -> { // 剑舞: raises attack by 36.6% and shows the blade halo around the body.
                if (t == 1) {
                    ExpansionControl.empower(e, SWORDS_DANCE_TICKS, 1F + SWORDS_DANCE_BONUS);
                    cue(e, s, "buff", e.position(), s.direction);
                    cue(e, s, "blade", e.position(), s.direction);
                }
                if (t == 2) finish(e, s);
            }
            case 1 -> { // 彗星拳: one heavy steel punch, damage is doubled while Swords Dance is up.
                if (t == 1) {
                    cue(e, s, "attack", e.position(), s.direction);
                    cue(e, s, "meteor", e.position(), s.direction);
                }
                if (t == 5) for (LivingEntity target : cone(l, e, s, m)) {
                    if (hit(l, e, s, target, m.damage(), 1)) {
                        target.push(s.direction.x * .8, .22, s.direction.z * .8);
                        cue(e, s, "impact", target.getBoundingBox().getCenter(), s.direction);
                    }
                }
            }
            case 2 -> { // 雷电拳: a fast electric jab that chills and briefly freezes.
                if (t == 1) {
                    cue(e, s, "attack", e.position(), s.direction);
                    cue(e, s, "bolt", e.position(), s.direction);
                }
                if (t == 4) for (LivingEntity target : cone(l, e, s, m)) {
                    if (hit(l, e, s, target, m.damage(), 1)) {
                        ExpansionControl.slow(e, target, 20, .6);
                        target.setTicksFrozen(Math.min(140, target.getTicksFrozen() + 40));
                        cue(e, s, "impact", target.getBoundingBox().getCenter(), s.direction);
                    }
                }
            }
            default -> { // 神速: a long, snappy dash that lands one hit on contact.
                if (t == 1) {
                    cue(e, s, "attack", e.position(), s.direction);
                    cue(e, s, "dash", e.position(), s.direction);
                }
                if (t % 3 == 1 && t < 14) cue(e, s, "rush", e.position(), s.direction);
                dash(l, e, s, m, t, false);
            }
        }
    }
'''
    text = text[:start] + lucario + text[end:]
    write(path, text)
    print("combat: alolan ninetales + lucario kits implemented")


# --------------------------------------------------------------------------- control / damage

def patch_control() -> None:
    path = JAVA / "combat/ExpansionControl.java"
    text = read(path)
    text = text.replace('    private record Mark(UUID source, int count, long until) {}',
                        '    private record Mark(UUID source, int count, long until) {}\n'
                        '    /** 0.87 timed attack buff (Swords Dance) and damage guard (Aurora Veil). */\n'
                        '    private record Buff(long until, double power, double guard) {}')
    text = text.replace('    private static final Map<UUID, Long> IMMUNITY = new HashMap<>();',
                        '    private static final Map<UUID, Long> IMMUNITY = new HashMap<>();\n'
                        '    private static final Map<UUID, Buff> BUFF = new HashMap<>();')
    helper = '''    /** Raises the victim's outgoing damage for a while (multiplicative, refreshes on recast). */
    public static void empower(PokemonEntity source, int ticks, double power) {
        if (source == null || ticks <= 0) return;
        long now = source.level().getGameTime();
        Buff old = BUFF.get(source.getUUID());
        double guard = old != null && old.until > now ? old.guard : 1.0;
        BUFF.put(source.getUUID(), new Buff(now + ticks, Math.max(1, power), guard));
    }

    /** Softens incoming damage for a while (Aurora Veil). */
    public static void guard(PokemonEntity source, int ticks, double reduction) {
        if (source == null || ticks <= 0) return;
        long now = source.level().getGameTime();
        Buff old = BUFF.get(source.getUUID());
        double power = old != null && old.until > now ? old.power : 1.0;
        BUFF.put(source.getUUID(), new Buff(now + ticks, power, 1.0 - Math.min(.8, Math.max(0, reduction))));
    }

    public static double powerMultiplier(LivingEntity entity) {
        if (entity == null) return 1.0;
        Buff buff = BUFF.get(entity.getUUID());
        if (buff == null) return 1.0;
        if (buff.until <= entity.level().getGameTime()) {
            BUFF.remove(entity.getUUID());
            return 1.0;
        }
        return buff.power;
    }

    public static double damageTakenMultiplier(LivingEntity entity) {
        if (entity == null) return 1.0;
        Buff buff = BUFF.get(entity.getUUID());
        if (buff == null) return 1.0;
        if (buff.until <= entity.level().getGameTime()) {
            BUFF.remove(entity.getUUID());
            return 1.0;
        }
        return buff.guard;
    }

'''
    anchor = "    public static void slow(PokemonEntity source, LivingEntity target, int ticks, double factor) {"
    text = text.replace(anchor, helper + anchor)
    text = text.replace('        IMMUNITY.clear(); HYPNO.clear();', '        IMMUNITY.clear(); HYPNO.clear(); BUFF.clear();')
    text = text.replace('    public static void removeEntity(UUID entity) {', '    public static void removeEntity(UUID entity) {\n        BUFF.remove(entity);')
    write(path, text)

    damage = JAVA / "combat/CombatDamage.java"
    text = read(damage)
    needle = '''        if (source == null || !BasicAttack.validTarget(target, source) || target.level() != level
                || !Float.isFinite(amount) || amount <= 0'''
    assert needle in text
    text = text.replace(needle, '''        if (source == null || !BasicAttack.validTarget(target, source) || target.level() != level
                || !Float.isFinite(amount) || amount <= 0''')
    marker = "                || com.pkmoba.duel.DuelManager.isPreparing(target)) return false;"
    assert marker in text
    text = text.replace(marker, "                || com.pkmoba.duel.DuelManager.isPreparing(target)) return false;\n"
                                "        // 0.87 timed buffs: Swords Dance raises the attacker's output, Aurora Veil\n"
                                "        // softens what the guarded target takes. Both default to 1.0 for everyone else.\n"
                                "        amount *= (float) (ExpansionControl.powerMultiplier(source)\n"
                                "                * ExpansionControl.damageTakenMultiplier(target));\n"
                                "        if (!Float.isFinite(amount) || amount <= 0) return false;")
    write(damage, text)
    print("control: attack buff + damage guard wired into the central damage path")


# --------------------------------------------------------------------------- client fx

def patch_effects() -> None:
    path = JAVA / "client/ExpansionEffects.java"
    text = read(path)
    text = text.replace("import com.pkmoba.client.fx.AbomasnowFx;\n", "import com.pkmoba.client.fx.NinetalesFx;\n")
    text = text.replace('            case "abomasnow" -> AbomasnowFx.render(v);',
                        '            case "alolan_ninetales" -> NinetalesFx.render(v);')
    text = text.replace('            case "abomasnow" -> "ICE";',
                        '            case "alolan_ninetales" -> "ICE";')
    # new phases need explicit lifetimes (start / release / impact default to 14)
    text = text.replace('                case "palm" -> 16;',
                        '                case "palm" -> 16;\n'
                        '                case "meteor" -> 18;\n'
                        '                case "bolt" -> 14;\n'
                        '                case "dash" -> 26;\n'
                        '                case "buff" -> 18;\n'
                        '                case "blade" -> 20;\n'
                        '                case "veil" -> 20;\n'
                        '                case "blizzard" -> 30;')
    # Alolan Ninetales has no hand-authored bedrock animation set; do not ask for one.
    text = text.replace('    private static void animateAttack(PokemonEntity pokemon, String species, int skill) {',
                        '    private static void animateAttack(PokemonEntity pokemon, String species, int skill) {\n'
                        '        // 0.87: the Alolan Ninetales form ships without a pkmoba animation file, so it\n'
                        '        // relies on Cobblemon\'s own idle/walk clips instead of a missing custom one.\n'
                        '        if (species.equals("alolan_ninetales")) return;')
    write(path, text)
    print("effects: ninetales dispatch + lucario phase lifetimes")


if __name__ == "__main__":
    patch_registry()
    patch_spawn()
    patch_roster()
    patch_combat()
    patch_control()
    patch_effects()
