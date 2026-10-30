"""0.101: feel pass, hypnosis/sludge rewrite, quake shake, switch-zone fix, CD clamp."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def sub(path, old, new):
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"anchor missing in {path}:\n{old[:120]}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


def add_after(path, anchor, addition):
    sub(path, anchor, anchor + addition)


# ---------------------------------------------------------------- ExpansionCombat
EC = "src/main/java/com/pkmoba/combat/ExpansionCombat.java"

add_after(EC, "public final class ExpansionCombat {\n", """    /** 0.101 (m15488): hypnosis puts the victim to sleep for ten seconds, badge stays thirty. */
    private static final int SLEEP_TICKS = 200;
    private static final int HYPNO_BADGE_TICKS = 600;
    private static final int SLUDGE_TICKS = 200;
    private static final int POISON_TICKS = 80;
""")

sub(EC, """    public static boolean blocksMovement(PokemonEntity entity) {
        if (entity == null) return false;
        State s = STATES.get(entity.getUUID());
        if (ExpansionControl.sleeping(entity)) return true;
        // 站桩输出（用户 m05844）：引导期间锁地面移动；位移类技能（E 冲刺）由技能 tick 自己
        // 写坐标，本身不需要地面指令，所以一起锁掉是对的。
        return s != null && s.skill >= 0;
    }""", """    public static boolean blocksMovement(PokemonEntity entity) {
        if (entity == null) return false;
        if (ExpansionControl.sleeping(entity)) return true;
        State s = STATES.get(entity.getUUID());
        if (s == null || s.skill < 0) return false;
        // 0.101 (m15488): only the short wind-up roots the body. Once the wind-up is over the
        // Pokemon may run again, so the recovery frames can never trap it in place.
        var move = species(entity).move(s.skill);
        return s.age <= Math.max(1, move.windup());
    }""")

sub(EC, """        if (s.skill >= 0) {
            // Interrupt the running channel cleanly: release its cast lock so the new skill owns it.
            CastLock.release(entity);
            s.skill = -1;
        }""", """        if (s.skill >= 0) {
            // Interrupt the running channel cleanly and clear its client scene, otherwise the
            // previous visual keeps playing while a different skill resolves.
            cue(entity, s, "end", entity.position(), s.direction);
            CastLock.release(entity);
            s.skill = -1;
        }""")

sub(EC, """        if (move.shape() != ExpansionRoster.Shape.DASH) FacingService.request(entity, FacingService.Priority.CHANNEL, s.direction, 360);""",
       """        // 0.101 (m15488): the skill direction follows the cursor while the move runs instead of
        // being frozen for the whole channel. Geometry is mirrored into CastLock so the hit volume
        // and the particles always agree with where the mouse points.
        if (move.shape() != ExpansionRoster.Shape.DASH) {
            Vec3 aim = AimTracker.aimOf(entity);
            if (aim != null) {
                Vec3 want = ExpansionGeometry.direction(entity, aim);
                // The facing service smooths the body turn; the skill itself tracks the cursor.
                s.direction = want;
                if (s.age <= move.windup() && move.shape() != ExpansionRoster.Shape.SELF)
                    s.point = ExpansionGeometry.point(entity, aim, move.range());
                CastLock.steer(entity, s.direction,
                        move.shape() == ExpansionRoster.Shape.SELF ? null : s.point);
            }
            FacingService.request(entity, FacingService.Priority.CHANNEL, s.direction, 8);
        }""")

# sludge bomb always explodes and leaves a ten second pool
sub(EC, """            case 1 -> { if(!s.stopped && lob(l,e,s,t,m,1.9)) {
                s.stopped=true;cue(e,s,"impact",s.point,s.direction);
                for(LivingEntity target : around(l,e,s.point,m.radius()))
                    if(hit(l,e,s,target,m.damage(),1)) {
                        s.poison.put(target.getUUID(),61);
                        // 重置版污泥炸弹：炸开的泥浆黏住目标，毒伤之外再压一段移速。
                        ExpansionControl.slow(e,target,40,.68);
                    }
            } }""", """            case 1 -> {
                if (!s.stopped && lob(l, e, s, t, m, 1.9)) {
                    s.stopped = true;
                    // 0.101 (m15488): the bomb always detonates at the landing point, hit or miss,
                    // and the puddle stays for ten seconds. Anyone stepping in is poisoned for
                    // four seconds - once per victim per pool, so it cannot refresh every tick.
                    cue(e, s, "impact", s.point, s.direction);
                    for (LivingEntity target : around(l, e, s.point, m.radius()))
                        hit(l, e, s, target, m.damage(), 1);
                    s.sludge = SLUDGE_TICKS;
                    s.sludgePoint = s.point;
                    s.sludgePoisoned.clear();
                    cue(e, s, "field", s.point, s.direction);
                }
            }""")

sub(EC, """                        int marks = ExpansionControl.addHypnoMark(e, target, 200);
                        cue(e, s, "mark" + Math.min(3, marks), target.getBoundingBox().getCenter(), s.direction);
                        if (marks >= 3 && ExpansionControl.sleep(e, target, 100)) {
                            ExpansionControl.clearHypno(target);
                            cue(e, s, "sleep", target.getBoundingBox().getCenter(), s.direction);
                        }""", """                        int marks = ExpansionControl.addHypnoMark(e, target, HYPNO_BADGE_TICKS);
                        cue(e, s, "mark" + Math.min(3, marks), target.getBoundingBox().getCenter(), s.direction);
                        // 0.101 (m15488): a hit sleeps the victim immediately for ten seconds; the
                        // third mark on the same victim always lands, ignoring the sleep immunity.
                        boolean slept = ExpansionControl.sleep(e, target, SLEEP_TICKS);
                        if (!slept && marks >= 3) slept = ExpansionControl.sleepForce(e, target, SLEEP_TICKS);
                        if (slept) cue(e, s, "sleep", target.getBoundingBox().getCenter(), s.direction);""")

sub(EC, """        tickPoison(level, entity, s);""", """        // 0.101 (m15488): lingering sludge pool. Ten seconds of visible mud, four seconds of
        // poison per victim, and the victim list is cleared as soon as it leaves the pool.
        if (s.sludge > 0) {
            s.sludge--;
            var pool = species(entity).move(1);
            double reach = pool.radius();
            for (LivingEntity target : around(level, entity, s.sludgePoint, reach)) {
                if (!s.sludgePoisoned.add(target.getUUID())) continue;
                s.poison.put(target.getUUID(), POISON_TICKS);
                ExpansionControl.slow(entity, target, POISON_TICKS, .7);
                cue(entity, 1, "poison", target.getBoundingBox().getCenter(), s.direction);
            }
            s.sludgePoisoned.removeIf(id -> {
                var found = level.getEntity(id);
                return !(found instanceof LivingEntity le) || !le.isAlive()
                        || le.position().distanceToSqr(s.sludgePoint) > Math.pow(reach + 1.4, 2);
            });
        }
        tickPoison(level, entity, s);""")

sub(EC, "        long moonDue; Vec3 moonPoint=Vec3.ZERO;", """        long moonDue; Vec3 moonPoint=Vec3.ZERO;
        /** 0.101: a sludge pool that outlives the bomb (ten seconds) and who has been poisoned by it. */
        int sludge; Vec3 sludgePoint=Vec3.ZERO;
        final java.util.Set<java.util.UUID> sludgePoisoned=new java.util.HashSet<>();""")

# ---------------------------------------------------------------- ExpansionControl
CT = "src/main/java/com/pkmoba/combat/ExpansionControl.java"
sub(CT, """        com.pkmoba.combat.StatusBadges.send(target, "sleep", 0, bounded);""",
       """        // Badge outlives the control by design: the mark stays on screen for thirty seconds.
        com.pkmoba.combat.StatusBadges.send(target, "sleep", 0, Math.max(bounded, 600));""")
sub(CT, """        com.pkmoba.combat.StatusBadges.send(target, "pip", count, bounded);""",
       """        com.pkmoba.combat.StatusBadges.send(target, "pip", count, Math.max(bounded, 600));""")
add_after(CT, """    public static void clearHypno(LivingEntity target) {""",
          """    /** 0.101: the third hypnosis mark must always land, even inside the sleep immunity window. */
    public static boolean sleepForce(PokemonEntity source, LivingEntity target, int ticks) {
        if (source == null || target == null || ticks <= 0) return false;
        IMMUNITY.remove(target.getUUID());
        return sleep(source, target, ticks);
    }
""")

print("patch_v30 core written")
