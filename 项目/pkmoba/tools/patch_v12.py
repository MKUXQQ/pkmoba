"""0.78 server pass: fire-and-forget Moonblast, shorter Gardevoir cooldowns, Blaziken Flame Mark kit."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
src = ROOT / "src/main/java/com/pkmoba"

# ---------------------------------------------------------------- roster
roster = src / "combat/ExpansionRoster.java"
text = roster.read_text(encoding="utf-8")
old_blaze = """        new Species("blaziken", "火焰鸡", 300, List.of(
            // 0.54 rework: Q/W/E/R all feed the Flame Mark loop (apply, detonate, refund, refresh).
            m("火焰踢", Shape.DASH, 2, 4, 4, 8, 6, .9, 26),
            m("二连踢", Shape.CONE, 2, 5, 4, 14, 3.8, 45, 34),
            m("蓄能焰袭", Shape.DASH, 2, 8, 3, 10, 8, .8, 10),
            m("闪焰冲锋", Shape.DASH, 3, 9, 18, 14, 14, 1, 40))),"""
new_blaze = """        new Species("blaziken", "火焰鸡", 300, List.of(
            // 0.78 rework: Q throws a flaming kick that brands the target with a Flame Mark, and only
            // E still dashes. W cashes the mark for damage + cooldown, E cashes it for healing and
            // R cashes it for a much bigger blast, so the four moves play as one loop.
            m("火焰踢", Shape.LINE, 2, 4, 4, 8, 14, .9, 26),
            m("二连踢", Shape.CONE, 2, 5, 4, 12, 3.8, 45, 34),
            m("蓄能焰袭", Shape.DASH, 2, 7, 3, 10, 8, .8, 14),
            m("闪焰冲锋", Shape.POINT, 3, 9, 12, 10, 14, 3.4, 42))),"""
assert old_blaze in text
text = text.replace(old_blaze, new_blaze)
old_gard = """            m("精神强念", Shape.POINT, 2, 5, 4, 32, 13, 4.0, 14),
            m("月亮之力", Shape.POINT, 3, 8, 10, 28, 16, 4.2, 48),
            m("瞬间移动", Shape.BLINK, 2, 9, 4, 6, 10, .5, 0),
            m("魔法闪耀", Shape.SELF, 2, 11, 6, 16, 7, 4.0, 34)))"""
new_gard = """            m("精神强念", Shape.POINT, 2, 3, 4, 32, 13, 4.0, 14),
            m("月亮之力", Shape.POINT, 3, 5, 10, 28, 16, 4.2, 48),
            m("瞬间移动", Shape.BLINK, 2, 5, 4, 6, 10, .5, 0),
            m("魔法闪耀", Shape.SELF, 2, 7, 6, 16, 7, 4.0, 34)))"""
assert old_gard in text
text = text.replace(old_gard, new_gard)
roster.write_text(text, encoding="utf-8")

# ---------------------------------------------------------------- combat
combat = src / "combat/ExpansionCombat.java"
text = combat.read_text(encoding="utf-8")

# state fields
old_state = """        LivingEntity target; boolean stopped,recoil,dream,deferDamage;"""
new_state = """        LivingEntity target; boolean stopped,recoil,dream,deferDamage,markedOnce;
        /** 0.78: a released Moonblast lands on its own clock, independent of the running skill. */
        long moonDue; Vec3 moonPoint=Vec3.ZERO;"""
assert old_state in text
text = text.replace(old_state, new_state)

# reset the one-shot flags on cast
old_cast_reset = """        s.hit.clear(); s.target = null; s.stopped = false; s.recoil = false;"""
new_cast_reset = """        s.hit.clear(); s.target = null; s.stopped = false; s.recoil = false; s.markedOnce = false;"""
assert old_cast_reset in text
text = text.replace(old_cast_reset, new_cast_reset)

# pending moon processing in tick(), before sleep/abort checks
old_tick = """        if (DuelManager.isPreparing(entity) || !entity.isAlive()) { clearTransient(entity); return; }
        if (ExpansionControl.sleeping(entity)) {if(s.skill>=0)finish(entity,s);return;}"""
new_tick = """        // 0.78: Moonblast is fire-and-forget. Once the moon leaves the sky it lands on its own
        // clock, so starting Psychic while it is airborne can no longer cancel it.
        if (s.moonDue > 0 && now >= s.moonDue) {
            s.moonDue = 0;
            var moon = species(entity).move(1);
            cue(entity, 1, "impact", s.moonPoint, s.direction);
            for (LivingEntity target : around(level, entity, s.moonPoint, moon.radius()))
                CombatDamage.apply(level, entity, target, moon.damage(),
                        TypeEffectiveness.moveType(entity, 1));
        }
        if (DuelManager.isPreparing(entity) || !entity.isAlive()) { clearTransient(entity); return; }
        if (ExpansionControl.sleeping(entity)) {if(s.skill>=0)finish(entity,s);return;}"""
assert old_tick in text
text = text.replace(old_tick, new_tick)

# gardevoir moonblast becomes fire-and-forget
old_moon = """            case 1 -> {
                // 月亮之力 Moonblast: a real 3D moon drops straight down onto the aim point, so the
                // cast is a heavy vertical slam - the same 3D model the earlier versions used.
                if (t == 1) cue(e, s, "start", s.point, s.direction);
                if (t <= 26) {
                    double f = Math.min(1, t / 26.0);
                    Vec3 top = s.point.add(0, 8.5, 0);
                    cue(e, s, "moon", top.lerp(s.point, f * f), s.direction);
                } else if (t == 27 && !s.stopped) {
                    s.stopped = true;
                    cue(e, s, "impact", s.point, s.direction);
                    for (LivingEntity target : around(l, e, s.point, m.radius()))
                        hit(l, e, s, target, m.damage(), 1);
                    finish(e, s);
                }
            }"""
new_moon = """            case 1 -> {
                // 月亮之力 Moonblast: the moon is released and falls on its own clock (see tick), so
                // the player can chain Psychic into it and the moon still lands where it was aimed.
                if (t == 1) {
                    s.moonPoint = s.point;
                    s.moonDue = l.getGameTime() + 26;
                    cue(e, s, "launch", s.point, s.direction);
                }
            }"""
assert old_moon in text
text = text.replace(old_moon, new_moon)

# blaze mark helpers + blaziken rewrite
old_blaze_method = text[text.index("    private static void blaziken(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {"):]
old_blaze_method = old_blaze_method[:old_blaze_method.index("\n    }\n") + len("\n    }\n")]
new_blaze_method = """    private static final int BLAZE_MARK_TICKS = 240;

    /** Brands a victim with the Flame Mark. The invisible effect is also what the client badges. */
    private static boolean blazeMark(LivingEntity target) {
        if (target.hasEffect(net.minecraft.world.effect.MobEffects.HERO_OF_THE_VILLAGE)) return false;
        target.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.HERO_OF_THE_VILLAGE, BLAZE_MARK_TICKS, 0,
                false, false, false));
        return true;
    }

    private static boolean takeBlazeMark(LivingEntity target) {
        if (!target.hasEffect(net.minecraft.world.effect.MobEffects.HERO_OF_THE_VILLAGE)) return false;
        target.removeEffect(net.minecraft.world.effect.MobEffects.HERO_OF_THE_VILLAGE);
        return true;
    }

    private static void dropBlazeMarks(ServerLevel level) {
        for (var entity : level.getAllEntities()) {
            if (entity instanceof LivingEntity living) {
                if (living.hasEffect(net.minecraft.world.effect.MobEffects.HERO_OF_THE_VILLAGE))
                    living.removeEffect(net.minecraft.world.effect.MobEffects.HERO_OF_THE_VILLAGE);
            }
        }
    }

    private static void blaziken(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        // 0.78 rework: only E dashes now. Q throws a flaming kick that brands the victim, and the
        // other three moves cash that brand in differently: W = damage + cooldown refund,
        // E = self heal, R = a much bigger blast.
        switch (s.skill) {
            case 0 -> { // 火焰踢: a burning kick is thrown forward, and whatever it hits is branded.
                if (t == 1) {
                    cue(e, s, "attack", e.position(), s.direction);
                    cue(e, s, "kick", launchPoint(e, s.direction), s.direction);
                }
                projectile(l, e, s, m, 1.15, 1.6, 0);
                if (s.stopped && !s.markedOnce) {
                    s.markedOnce = true;
                    for (UUID id : s.hit.keySet()) {
                        if (l.getEntity(id) instanceof LivingEntity victim && victim.isAlive()
                                && blazeMark(victim))
                            cue(e, s, "brand", victim.position(), s.direction);
                    }
                }
            }
            case 1 -> { // 二连踢: two kicks; a branded victim detonates for bonus damage and refunds Q.
                if (t == 1 || t == 8) {
                    boolean second = t == 8;
                    if (second) ExpansionGeometry.move(e, s.direction.scale(.55));
                    cue(e, s, "attack", e.position(), s.direction);
                    float amount = second ? m.damage() * .72F : m.damage() * .4F;
                    for (LivingEntity target : meleeTargets(l, e, s.direction, m)) {
                        float total = amount;
                        if (takeBlazeMark(target)) {
                            total += 14;
                            s.cd[0] = Math.max(0, s.cd[0] - 40);
                            cue(e, s, "detonate", target.getBoundingBox().getCenter(), s.direction);
                        }
                        meleeHit(l, e, s, target, total, 2);
                    }
                }
            }
            case 2 -> { // 蓄能焰袭: the one remaining dash; brand detonation heals the caster.
                if (t == 1) cue(e, s, "attack", e.position(), s.direction);
                dash(l, e, s, m, t, false);
                if (s.target != null && !s.recoil) {
                    s.recoil = true;
                    Vec3 at = s.target.getBoundingBox().getCenter();
                    boolean branded = takeBlazeMark(s.target);
                    hit(l, e, s, s.target, m.damage() + 2, 1);
                    s.speed = BLAZE_HASTE_TICKS;
                    if (branded) {
                        e.heal(18F);
                        cue(e, s, "detonate", at, s.direction);
                        cue(e, s, "heal", at, e.position());
                    }
                    cue(e, s, "impact", at, s.direction);
                    cue(e, s, "haste", e.position(), s.direction);
                }
            }
            default -> { // 闪焰冲锋: charged explosion at the aim point. Branded victims take +60%
                         // and the blast gains a second, wider ring.
                if (t == 1) cue(e, s, "charge", s.point, s.direction);
                if (t < 9 && t % 3 == 0) cue(e, s, "charge", s.point, s.direction);
                if (t == 9 && !s.stopped) {
                    s.stopped = true;
                    cue(e, s, "impact", s.point, s.direction);
                    boolean branded = false;
                    for (LivingEntity target : around(l, e, s.point, m.radius())) {
                        float damage = m.damage();
                        if (takeBlazeMark(target)) {
                            damage *= 1.6F;
                            branded = true;
                        }
                        hit(l, e, s, target, damage, 1);
                    }
                    if (branded) {
                        cue(e, s, "detonate", s.point, s.direction);
                        for (LivingEntity target : around(l, e, s.point, m.radius() * 1.5))
                            hit(l, e, s, target, 12, 1);
                    }
                    finish(e, s);
                }
            }
        }
    }
"""
text = text.replace(old_blaze_method, new_blaze_method)

# clear pending moon when the caster is reset
old_clear = """            finish(e,s); s.buff=0;s.speed=0;s.sand=0;s.snow=0;s.poison.clear();s.hit.clear();s.target=null;"""
new_clear = """            finish(e,s); s.buff=0;s.speed=0;s.sand=0;s.snow=0;s.poison.clear();s.hit.clear();s.target=null;
            s.moonDue=0;s.moonPoint=Vec3.ZERO;"""
assert old_clear in text
text = text.replace(old_clear, new_clear)

combat.write_text(text, encoding="utf-8")
print("server patched")
