"""0.86: drop Garchomp and Scizor, rebuild Lucario's whole kit in chunky pixel style.

* Garchomp / Scizor are removed everywhere: registry, roster, combat, effects dispatch, types,
  bot spacing, overhead bar, assets, animations and language keys.
* Lucario: new four-move kit (Aura Sphere / Force Palm / Extreme Speed / Aura Storm) with new
  steel-blue pixel sheets, new icons, a new client renderer and new server timings.

Run from the project root: python tools/patch_v19.py
"""
import re
from pathlib import Path

ROOT = Path(".")
JAVA = ROOT / "src/main/java/com/pkmoba"
RES = ROOT / "src/main/resources/assets/pkmoba"


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


def patch_registry() -> None:
    path = JAVA / "pokemon/SpeciesRegistry.java"
    text = path.read_text(encoding="utf-8")
    for line in ('        add("scizor", "巨钳螳螂");\n', '        add("garchomp", "烈咬陆鲨");\n'):
        assert line in text, line
        text = text.replace(line, "")
    path.write_text(text, encoding="utf-8")
    print("registry: scizor + garchomp removed")


def patch_roster() -> None:
    path = JAVA / "combat/ExpansionRoster.java"
    text = path.read_text(encoding="utf-8")
    for start_marker in ('        new Species("garchomp"', '        new Species("scizor"'):
        start = text.index(start_marker)
        end = text.index('        new Species("', start + 10)
        text = text[:start] + text[end:]
    # 0.86 Lucario: aura sphere / force palm / extreme speed / aura storm, all retuned for the new
    # pixel sheets (a fast poke, a short palm blast, a mobility dash and a self-centred burst).
    old = '''        new Species("lucario", "路卡利欧", 300, List.of(
            // 0.55 rework: Q builds Aura (max 3), W spends all stacks for +12% each, R spends all
            // stacks for +18% each. E is mobility only and deals no damage by design.
            m("波导弹", Shape.LINE, 2, 4, 8, 28, 18, .65, 26),
            m("骨棒乱打", Shape.CONE, 3, 7, 6, 18, 4, 55, 44),
            m("神速", Shape.DASH, 2, 7, 3, 10, 7, .8, 0),
            m("近身打", Shape.CONE, 3, 9, 10, 12, 3.4, 70, 34))),'''
    new = '''        new Species("lucario", "路卡利欧", 320, List.of(
            // 0.86 rework: Q is a plain ranged poke that banks one Aura, W is a point-blank palm
            // shock, E is pure mobility and R a self-centred aura detonation that spends the Aura.
            m("波导弹", Shape.LINE, 2, 4, 8, 26, 18, .85, 28),
            m("发劲", Shape.CONE, 2, 5, 4, 14, 4.2, 42, 34),
            m("神速", Shape.DASH, 2, 6, 3, 10, 8, .8, 0),
            m("波导爆发", Shape.SELF, 3, 9, 10, 20, 0, 4.6, 44))),'''
    assert old in text, "lucario roster block not found"
    text = text.replace(old, new)
    path.write_text(text, encoding="utf-8")
    print("roster: garchomp/scizor removed, lucario kit rebuilt")


def patch_combat() -> None:
    path = JAVA / "combat/ExpansionCombat.java"
    text = path.read_text(encoding="utf-8")
    for line in ('            case "garchomp" -> garchomp(level, entity, s, t, move);\n',
                 '            case "scizor" -> scizor(level, entity, s, t, move);\n'):
        assert line in text, line
        text = text.replace(line, "")
    for signature in ("    private static void garchomp(", "    private static void scizor("):
        text = remove_method(text, signature)
    start = text.index("    private static void lucario(")
    end = text.index("    /** Banks Aura", start)
    lucario = '''    private static void lucario(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch (s.skill) {
            case 0 -> { // 波导弹: one clean aura sphere; every hit banks a single Aura layer.
                if (t == 1) cue(e, s, "launch", e.position(), s.direction);
                projectile(l, e, s, m, .95, 1.15, 0);
                if (s.stopped && !s.recoil) {
                    s.recoil = true;
                    gainAura(e, s, Math.min(1, Math.max(1, s.hit.size())));
                }
            }
            case 1 -> { // 发劲: a palm thrust that fires two short shock rings out of the palm.
                if (t == 1) {
                    cue(e, s, "attack", e.position(), s.direction);
                    cue(e, s, "palm", e.position(), s.direction);
                }
                if (t == 2 || t == 6) {
                    for (LivingEntity target : cone(l, e, s, m)) {
                        if (hit(l, e, s, target, t == 2 ? m.damage() * .45F : m.damage() * .55F, 2)) {
                            target.push(s.direction.x * .7, .16, s.direction.z * .7);
                            cue(e, s, "impact", target.getBoundingBox().getCenter(), s.direction);
                        }
                    }
                }
            }
            case 2 -> { // 神速: mobility only, the body sprints and the aura streaks behind it.
                if (t == 1) cue(e, s, "attack", e.position(), s.direction);
                if (t % 3 == 1) cue(e, s, "rush", e.position(), s.direction);
                dash(l, e, s, m, t, false);
            }
            default -> { // 波导爆发: gather the aura, then detonate it around the body.
                if (t == 1) cue(e, s, "charge", e.position(), s.direction);
                if (t >= 2 && t <= 8 && t % 2 == 0) cue(e, s, "grow", e.position(), s.direction);
                if (t == 9 && !s.stopped) {
                    s.stopped = true;
                    int spent = takeAura(s);
                    s.bonus = 1F + AURA_R_BONUS * spent;
                    cue(e, s, "impact", e.position(), s.direction);
                    cue(e, s, "detonate", e.position(), s.direction);
                    for (LivingEntity target : around(l, e, e.position(), m.radius()))
                        hit(l, e, s, target, m.damage() * s.bonus, 1);
                    finish(e, s);
                }
            }
        }
    }
'''
    text = text[:start] + lucario + text[end:]
    path.write_text(text, encoding="utf-8")
    print("combat: garchomp/scizor handlers removed, lucario rebuilt")


def patch_effects() -> None:
    path = JAVA / "client/ExpansionEffects.java"
    text = path.read_text(encoding="utf-8")
    text = text.replace('            case "blaziken", "garchomp" -> true;\n',
                        '            case "blaziken" -> true;\n')
    text = text.replace('            case "scizor" -> skill < 3;\n', "")
    text = text.replace('            case "garchomp" -> switch (skill) { case 0 -> "DRAGON"; case 1 -> "QUAKE"; case 2 -> "QUAKE"; default -> "DRAGON"; };\n', "")
    text = text.replace('            case "scizor" -> switch (skill) { case 0 -> "FIST"; case 1 -> "BLADE"; case 2 -> "RUSH"; default -> "BLADE"; };\n', "")
    text = text.replace('            case "garchomp" -> GarchompFx.render(v);\n', "")
    text = text.replace('            case "scizor" -> ScizorFx.render(v);\n', "")
    text = remove_block(text, '                // Bullet Punch is one beat on the server; chain extra jabs client-side so it reads\n')
    text = remove_block(text, '            if (phase.equals("buff") && species.id().equals("scizor") && skill == 3) {\n')
    # 0.86: no vanilla particles on Lucario either - the aura kit is drawn from its own sheets and
    # the sprite particles were what made it look like a blue cloud rather than crisp pixel art.
    text = text.replace('if (!s.id.equals("gengar") && !s.id.equals("gardevoir") && !s.id.equals("pikachu")) {',
                        'if (!s.id.equals("gengar") && !s.id.equals("gardevoir") && !s.id.equals("pikachu")\n                    && !s.id.equals("lucario")) {')
    # a cue for a species that no longer exists must not throw
    text = text.replace("            double radius = ExpansionRoster.find(s.id).move(s.skill).radius();",
                        "            var move = ExpansionRoster.find(s.id);\n"
                        "            if (move == null) continue;\n"
                        "            double radius = move.move(s.skill).radius();")
    # new Lucario cue lifetimes
    text = text.replace('                case "aura1", "aura2", "aura3" -> 14;',
                        '                case "aura1", "aura2", "aura3" -> 14;\n'
                        '                // 0.86 Lucario: palm blast, sprint streaks and the aura detonation.\n'
                        '                case "palm" -> 16;\n'
                        '                case "rush" -> 12;\n'
                        '                case "grow" -> 14;')
    path.write_text(text, encoding="utf-8")
    print("effects: garchomp/scizor wiring removed, lucario phases added")


def remove_block(text: str, start_marker: str) -> str:
    start = text.index(start_marker)
    depth = 0
    i = start
    opened = False
    while i < len(text):
        if text[i] == "{":
            depth += 1
            opened = True
        elif text[i] == "}":
            depth -= 1
            if opened and depth == 0:
                end = i + 1
                while end < len(text) and text[end] == "\n":
                    end += 1
                return text[:start] + text[end:]
        i += 1
    raise AssertionError("unbalanced block")


def patch_misc() -> None:
    types = JAVA / "combat/TypeEffectiveness.java"
    text = types.read_text(encoding="utf-8")
    for line in ('            case "garchomp" -> MoveType.DRAGON;\n',
                 '            case "scizor" -> MoveType.STEEL;\n',
                 '            case "garchomp" -> skill==1 || skill==2?MoveType.GROUND:MoveType.DRAGON;\n',
                 '            case "scizor" -> skill==0?MoveType.STEEL:skill==3?MoveType.NORMAL:MoveType.BUG;\n',
                 '            case "garchomp" -> dual(move, "dragon", "ground");\n',
                 '            case "scizor" -> dual(move, "bug", "steel");\n'):
        text = text.replace(line, "")
    types.write_text(text, encoding="utf-8")

    bot = JAVA / "duel/BotDuelService.java"
    text = bot.read_text(encoding="utf-8")
    text = text.replace('            case "lucario", "blaziken", "scizor", "garchomp" -> 2.6;',
                        '            case "lucario", "blaziken" -> 2.6;')
    bot.write_text(text, encoding="utf-8")

    bar = JAVA / "client/PokemonOverheadBar.java"
    text = bar.read_text(encoding="utf-8")
    text = text.replace('if (species.contains("tyranitar") || species.contains("garchomp")) {',
                        'if (species.contains("tyranitar")) {')
    bar.write_text(text, encoding="utf-8")
    print("types / bot / overhead bar cleaned")


def patch_lang() -> None:
    for name, lucario in (("zh_cn.json", ('发劲', '波导爆发')), ("en_us.json", ('Force Palm', 'Aura Storm'))):
        path = RES / "lang" / name
        text = path.read_text(encoding="utf-8")
        lines = [line for line in text.splitlines(keepends=True)
                 if not re.search(r'skill\.pkmoba\.(garchomp|scizor)\.', line)]
        text = "".join(lines)
        text = text.replace('"skill.pkmoba.lucario.w": "骨棒乱打"', f'"skill.pkmoba.lucario.w": "{lucario[0]}"')
        text = text.replace('"skill.pkmoba.lucario.w": "Bone Rush"', f'"skill.pkmoba.lucario.w": "{lucario[0]}"')
        text = text.replace('"skill.pkmoba.lucario.r": "近身打"', f'"skill.pkmoba.lucario.r": "{lucario[1]}"')
        text = text.replace('"skill.pkmoba.lucario.r": "Close Combat"', f'"skill.pkmoba.lucario.r": "{lucario[1]}"')
        path.write_text(text, encoding="utf-8")
    print("lang cleaned + lucario names updated")


def delete_assets() -> None:
    removed = 0
    for pattern_dir, patterns in (
            (RES / "textures/effect", ("garchomp_*", "scizor_*")),
            (RES / "textures/effect/expansion", ("garchomp_*", "scizor_*", "lucario_*")),
            (RES / "textures/gui/skills", ("garchomp_*", "scizor_*")),
            (RES / "bedrock/pokemon/animations", ("pkmoba_garchomp.animation.json", "pkmoba_scizor.animation.json")),
    ):
        if not pattern_dir.exists():
            continue
        for pattern in patterns:
            for path in pattern_dir.glob(pattern):
                path.unlink()
                removed += 1
    # old Lucario sheets are replaced by the six new ones
    for path in list((RES / "textures/effect").glob("lucario_*")):
        if path.stem not in {"lucario_orb", "lucario_band", "lucario_ring", "lucario_star",
                             "lucario_shard", "lucario_glow"}:
            path.unlink()
            removed += 1
    for fx in (JAVA / "client/fx/GarchompFx.java", JAVA / "client/fx/ScizorFx.java"):
        if fx.exists():
            fx.unlink()
            removed += 1
    print(f"assets removed: {removed}")


if __name__ == "__main__":
    patch_registry()
    patch_roster()
    patch_combat()
    patch_effects()
    patch_misc()
    patch_lang()
    delete_assets()
