package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.pkmoba.charizard.CharizardManager;
import com.pkmoba.duel.BotDuelService;
import com.pkmoba.duel.DuelManager;
import com.pkmoba.godmode.ServerGodMode;
import com.pkmoba.move.MoveOrderService;
import com.pkmoba.network.EffectPayload;
import com.pkmoba.network.SkillStatePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-authoritative expansion engine. Species dispatch is explicit: shared geometry is not shared skills. */
public final class ExpansionCombat {
    private static final Map<UUID, State> STATES = new HashMap<>();
    private record ReserveKey(UUID owner,String species) {}
    private record Reserve(float points,int[] cooldowns,long savedAt) {}
    private static final Map<ReserveKey,Reserve> RESERVES=new HashMap<>();
    // 0.54 Blaziken: 烈焰印记. A mark is per-victim and remembers its caster, so two Blaziken in
    // one arena can never detonate each other's stacks.
    private record Mark(UUID caster,long expire) {}
    private static final Map<UUID,Mark> MARKS=new HashMap<>();
    private static final int MARK_TICKS=100;      // 5 s to spend the mark
    private static final float BLAZE_Q=26.0F,BLAZE_Q_BONUS=14.0F;
    private static final float BLAZE_W_FIRST=14.0F,BLAZE_W_SECOND=20.0F,BLAZE_W_BONUS=14.0F;
    private static final float BLAZE_E=10.0F;
    private static final float BLAZE_R=40.0F,BLAZE_R_BONUS=16.0F;
    private static final int BLAZE_HASTE_TICKS=80; // 4 s of +25% movement, refreshed never stacked
    // 0.55 Lucario: 波导蓄势. Stacks live on the caster (not the victim): Q builds up to 3, W spends
    // all of them for +12% each, R spends all of them for +18% each. Nothing refreshes itself.
    private static final int AURA_MAX=3;
    private static final int AURA_TICKS=160;      // 8 s to spend the charge
    private static final int AURA_PER_CAST=2;     // a single Aura Sphere can never bank more than 2
    private static final float AURA_W_BONUS=.12F, AURA_R_BONUS=.18F;
    private static final float[] LUCARIO_W_BEATS={9.0F,9.0F,11.0F,15.0F}; // 4 hit Bone Rush, last one heavy
    private ExpansionCombat() {}
    public static ExpansionRoster.Species species(PokemonEntity entity) {
        return entity == null || entity.getPokemon() == null ? null
                : ExpansionRoster.find(entity.getPokemon().getSpecies().getResourceIdentifier().toString());
    }
    public static boolean supports(PokemonEntity entity) { return species(entity) != null; }
    private static State state(PokemonEntity entity) {
        return STATES.computeIfAbsent(entity.getUUID(), id -> {
            State state=new State();
            UUID owner=CharizardManager.getControlledOwner(entity);
            var entry=species(entity);
            Reserve saved=owner==null || entry==null?null:RESERVES.remove(new ReserveKey(owner,entry.id()));
            if(saved!=null) {
                long elapsed=Math.max(0,entity.level().getGameTime()-saved.savedAt());
                state.points=Math.min(10,saved.points()+elapsed/20);
                for(int i=0;i<4;i++)state.cd[i]=(int)Math.max(0,saved.cooldowns()[i]-elapsed);
            }
            return state;
        });
    }
    /** Recall preserves only resources, never active hazards, control, targets or cast locks. */
    public static void rememberRecall(PokemonEntity entity) {
        if(!supports(entity) || !entity.isAlive())return;
        UUID owner=CharizardManager.getControlledOwner(entity);
        State state=STATES.get(entity.getUUID());
        if(owner!=null && state!=null) RESERVES.put(new ReserveKey(owner,species(entity).id()),
                new Reserve(state.points,state.cd.clone(),entity.level().getGameTime()));
    }
    public static boolean isBusy(PokemonEntity entity) {
        State s = entity == null ? null : STATES.get(entity.getUUID());
        return s != null && s.skill >= 0;
    }
    public static boolean blocksMovement(PokemonEntity entity) {
        if (entity == null) return false;
        State s = STATES.get(entity.getUUID());
        if (ExpansionControl.sleeping(entity)) return true;
        // 站桩输出（用户 m05844）：引导期间锁地面移动；位移类技能（E 冲刺）由技能 tick 自己
        // 写坐标，本身不需要地面指令，所以一起锁掉是对的。
        return s != null && s.skill >= 0;
    }
    public static int channel(PokemonEntity entity) {
        State s = entity == null ? null : STATES.get(entity.getUUID());
        return s == null || s.skill < 0 ? 0 : species(entity).channel(s.skill);
    }
    public static int cooldown(PokemonEntity entity, int index) { return state(entity).cd[index]; }
    public static void updateAim(PokemonEntity entity, Vec3 aim) { /* AimTracker owns idle aim; casts snapshot it. */ }
    private static boolean authorized(ServerPlayer player, PokemonEntity entity) {
        return player != null && supports(entity) && entity.isAlive() && player.level() == entity.level()
                && player.getUUID().equals(CharizardManager.getControlledOwner(entity))
                && (BotDuelService.isBot(entity) ? player == BotDuelService.actor(player.getUUID()) : ServerGodMode.isActive(player));
    }
    public static boolean canCastForBot(PokemonEntity entity, Skill skill) {
        if (!supports(entity) || skill == null || !BotDuelService.isBot(entity)) return false;
        return canCast(BotDuelService.actor(CharizardManager.getControlledOwner(entity)), entity, skill.ordinal());
    }
    private static boolean canCast(ServerPlayer player, PokemonEntity entity, int index) {
        if (!authorized(player, entity) || DuelManager.isPreparing(entity)
                || com.pkmoba.pokemon.BallThrow.locked(entity) || ExpansionControl.sleeping(entity)) return false;
        State s = state(entity);
        return s.skill < 0 && s.cd[index] == 0 && s.points >= species(entity).move(index).cost();
    }
    public static void castForBot(PokemonEntity entity, Skill skill, Vec3 aim) {
        if (canCastForBot(entity, skill)) cast(BotDuelService.actor(CharizardManager.getControlledOwner(entity)), entity, skill, aim);
    }
    public static void cast(ServerPlayer player, PokemonEntity entity, Skill skill, Vec3 aim) {
        if (skill == null || !ExpansionGeometry.finite(aim) || !canCast(player, entity, skill.ordinal())) return;
        State s = state(entity);
        var move = species(entity).move(skill.ordinal());
        s.skill = skill.ordinal(); s.age = 0; s.points -= move.cost(); s.cd[s.skill] = move.cooldown();
        s.origin = entity.position(); s.direction = ExpansionGeometry.direction(entity, aim);
        s.point = ExpansionGeometry.point(entity, aim, move.range());
        s.projectile = launchPoint(entity,s.direction);
        s.launch=s.projectile;
        s.hit.clear(); s.target = null; s.stopped = false; s.recoil = false;
        // Blaziken applies its own damage per skill so a detonated mark can add its bonus; the
        // shared dash helper therefore only moves the body and records the contact target.
        s.deferDamage = species(entity).id().equals("blaziken");
        // Snapshot geometry and cancel only the stale destination. Movement remains available during casts;
        // dash moves below own displacement for their active window.
        MoveOrderService.cancelPending(entity);
        CastLock.Shape shape = switch (move.shape()) {
            case POINT, WALL, BLINK -> CastLock.Shape.POINT;
            case DASH -> CastLock.Shape.DASH;
            default -> CastLock.Shape.LINE;
        };
        CastLock.begin(entity, shape, s.direction, s.point, move.windup() + move.duration() + 2);
        cue(entity, s, "start", s.point, s.direction);
        syncHud(player, entity);
    }
    public static void tick(ServerLevel level, PokemonEntity entity) {
        if (!supports(entity)) return;
        State s = state(entity);
        long now = level.getGameTime();
        if (!MARKS.isEmpty() && now % 20 == 0) MARKS.values().removeIf(mark -> mark.expire() <= now);
        for (int i = 0; i < 4; i++) if (s.cd[i] > 0) s.cd[i]--;
        if (++s.regen >= 20) { s.regen = 0; s.points = Math.min(10, s.points + 1); }
        if (s.buff > 0) s.buff--;
        if (s.speed > 0) s.speed--;
        if (s.sand > 0) {
            if (s.sand % 10 == 0) for (LivingEntity target:around(level,entity,entity.position(),species(entity).move(2).radius()))
                CombatDamage.apply(level,entity,target,4,TypeEffectiveness.MoveType.ROCK);
            s.sand--;
        }
        if(s.snow>0) {
            if(s.snow%10==0)for(LivingEntity target:around(level,entity,s.snowPoint,species(entity).move(3).radius())) {
                if(CombatDamage.apply(level,entity,target,species(entity).move(3).damage()/8.0F,TypeEffectiveness.MoveType.ICE))
                    ExpansionControl.slow(entity,target,15,Math.max(.4,.8-(80-s.snow)*.005));
            }
            s.snow--;
        }
        if (s.mist > 0) {
            // White mist: everything inside the cloud is slowed while it lasts.
            if (s.mist % 5 == 0) for (LivingEntity target : around(level, entity, s.mistPoint,
                    species(entity).move(2).radius() * 1.35)) {
                ExpansionControl.slow(entity, target, 20, .5);
            }
            s.mist--;
        }
        tickPoison(level, entity, s);
        if (DuelManager.isPreparing(entity) || !entity.isAlive()) { clearTransient(entity); return; }
        if (ExpansionControl.sleeping(entity)) {if(s.skill>=0)finish(entity,s);return;}
        // Marks outlive the cast that placed them, so the yellow flame glyph is refreshed here
        // rather than inside one skill handler.
        if (species(entity).id().equals("blaziken") && now % 10 == 0) refreshMarkCues(level, entity);
        // Aura stacks time out on their own and the ring count is re-cued at 2 Hz so the client can
        // never show a layer the server no longer has.
        if (s.aura > 0) {
            if (s.auraExpire <= now) { s.aura = 0; s.auraExpire = 0; }
            else if (species(entity).id().equals("lucario") && now % 10 == 0)
                cue(entity, 0, "aura" + s.aura, entity.position(), entity.getLookAngle());
        }
        if (s.skill < 0) return;
        var move = species(entity).move(s.skill);
        s.age++;
        // CastLock owns geometry only; ordinary movement may continue while the move executes.
        // DASH handlers are the sole owners of skill displacement.
        if (move.shape() != ExpansionRoster.Shape.DASH) FacingService.request(entity, FacingService.Priority.CHANNEL, s.direction, 360);
        // Flare Blitz telegraph: a red warning lane is re-cued while the charge builds so the
        // victim can step out of it instead of being hit by an invisible 0.9 s wind-up.
        if (s.skill == 3 && species(entity).id().equals("blaziken") && s.age <= move.windup() && s.age % 4 == 0)
            cue(entity, s, "warn", s.point, s.direction);
        if (s.age <= move.windup()) return;
        int t = s.age - move.windup();
        if (t == 1) cue(entity, s, "release", s.point, s.direction);
        switch (species(entity).id()) {
            case "lucario" -> lucario(level, entity, s, t, move);
            case "abomasnow" -> abomasnow(level, entity, s, t, move);
            case "blaziken" -> blaziken(level, entity, s, t, move);
            case "gengar" -> gengar(level, entity, s, t, move);
            case "tyranitar" -> tyranitar(level, entity, s, t, move);
            case "garchomp" -> garchomp(level, entity, s, t, move);
            case "scizor" -> scizor(level, entity, s, t, move);
            case "gardevoir" -> gardevoir(level, entity, s, t, move);
            default -> throw new IllegalStateException("Unsupported expansion species");
        }
        if (s.skill >= 0 && t >= move.duration()) finish(entity, s);
    }
    private static void lucario(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch (s.skill) {
            case 0 -> { // 波导弹: ranged poke that banks Aura (at most 2 per cast, 3 total).
                projectile(l,e,s,m, .8, 1.4, 0);
                if (s.stopped && !s.recoil) {
                    s.recoil = true;
                    int gained = Math.min(AURA_PER_CAST, s.hit.size());
                    if (gained > 0) gainAura(e, s, gained);
                }
            }
            case 1 -> { // 骨棒乱打: four club beats, all Aura spent once at the first beat.
                if (t == 1) {
                    int spent = takeAura(s);
                    s.bonus = 1F + AURA_W_BONUS * spent;
                    cue(e, 1, "spend" + spent, e.position(), s.direction);
                }
                if (t == 1 || t == 6 || t == 11 || t == 16) {
                    int beat = t == 1 ? 0 : t == 6 ? 1 : t == 11 ? 2 : 3;
                    cue(e, s, "attack", e.position(), s.direction);
                    for (LivingEntity target : cone(l, e, s, m))
                        hit(l, e, s, target, LUCARIO_W_BEATS[beat] * s.bonus, 4);
                    // The finisher shakes the ground; the earlier beats only spark on contact.
                    if (beat == 3) cue(e, 1, "melee", s.point, s.direction);
                }
            }
            case 2 -> { // 神速: pure mobility, it never deals damage and never gains Aura.
                if (t == 1) cue(e, s, "attack", e.position(), s.direction);
                dash(l, e, s, m, t, false);
            }
            case 3 -> { // 近身打: one close-range punch that spends every stack for +18% each.
                if (t == 1) {
                    int spent = takeAura(s);
                    float amount = m.damage() * (1F + AURA_R_BONUS * spent);
                    cue(e, 3, "spend" + spent, e.position(), s.direction);
                    cue(e, s, "attack", e.position(), s.direction);
                    for (LivingEntity target : cone(l, e, s, m))
                        if (hit(l, e, s, target, amount, 1)) {
                            target.push(s.direction.x * .55, .18, s.direction.z * .55);
                            cue(e, 3, "impact", target.getBoundingBox().getCenter(), s.direction);
                        }
                }
            }
        }
    }
    /** Banks Aura: capped at 3, refreshes the window instead of stacking past the cap. */
    private static void gainAura(PokemonEntity caster, State state, int stacks) {
        if (caster == null || stacks <= 0) return;
        state.aura = Math.min(AURA_MAX, state.aura + stacks);
        state.auraExpire = caster.level().getGameTime() + AURA_TICKS;
        cue(caster, 0, "aura" + state.aura, caster.position(), caster.getLookAngle());
    }
    /** Spends the whole charge; returns how many layers were actually burned. */
    private static int takeAura(State state) {
        int spent = state.aura;
        state.aura = 0;
        state.auraExpire = 0;
        return spent;
    }
    private static void abomasnow(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch (s.skill) {
            case 0 -> projectile(l,e,s,m,.7,.8,30);
            case 1 -> { if (t % 4 == 1) for (LivingEntity target : cone(l,e,s,m)) if (hit(l,e,s,target,6,5)) {
                ExpansionControl.slow(e, target, 25, .55);
                // Frostbite: briefly freezes the victim (ice overlay + pinned shiver).
                target.setTicksFrozen(Math.min(140, target.getTicksFrozen() + 60));
            } }
            case 2 -> { /* Temporary collision volume is maintained by ExpansionWalls, not map blocks. */
                if (t == 1 && ExpansionWalls.create(e,s.point,s.direction,m.radius(),m.duration())) {
                    s.mist = m.duration();
                    s.mistPoint = s.point;
                    cue(e,s,"wall",s.point,s.direction);
                }
            }
            case 3 -> { if(t==1) {
                s.snow=m.duration();s.snowPoint=s.point;cue(e,s,"field",s.point,s.direction);finish(e,s);
            } }
        }
    }
    /**
     * 0.54 Blaziken kit — the Flame Mark (烈焰印记) loop.
     *
     * <p>Q applies a mark, W's second kick detonates it and refunds E, E detonates it for a short
     * haste, R detonates it and refreshes Q/W/E. A detonated mark is always consumed first, so a
     * rotation can never double-dip and no skill refreshes itself.
     */
    private static void blaziken(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch (s.skill) {
            case 0 -> { // 火焰踢: short lunging kick, applies or detonates the mark.
                if (t == 1) cue(e, s, "attack", e.position(), s.direction);
                dash(l, e, s, m, t, true);
                if (s.target != null && !s.recoil) {
                    s.recoil = true;
                    LivingEntity target = s.target;
                    boolean marked = consumeMark(e, target);
                    hit(l, e, s, target, BLAZE_Q + (marked ? BLAZE_Q_BONUS : 0), 2);
                    if (marked) {
                        cue(e, 0, "detonate", target.getBoundingBox().getCenter(), s.direction);
                    } else {
                        applyMark(e, target);
                        cue(e, 0, "mark", target.getBoundingBox().getCenter(), s.direction);
                    }
                }
            }
            case 1 -> { // 二连踢: first kick plain, second kick detonates and refunds E.
                if (t == 1 || t == 10) {
                    boolean second = t == 10;
                    if (second) ExpansionGeometry.move(e, s.direction.scale(.65));
                    cue(e, s, "attack", e.position(), s.direction);
                    for (LivingEntity target : meleeTargets(l, e, s.direction, m)) {
                        boolean marked = second && marked(e, target);
                        float amount = (second ? BLAZE_W_SECOND : BLAZE_W_FIRST)
                                + (marked ? BLAZE_W_BONUS : 0);
                        if (meleeHit(l, e, s, target, amount, 2) && marked) {
                            consumeMark(e, target);
                            s.cd[2] = 0;
                            cue(e, 1, "detonate", target.getBoundingBox().getCenter(), s.direction);
                            cue(e, 1, "refresh", e.position(), s.direction);
                        }
                    }
                }
            }
            case 2 -> { // 蓄能焰袭: dash through, detonating a mark for haste instead of damage.
                if (t == 1) cue(e, s, "attack", e.position(), s.direction);
                dash(l, e, s, m, t, false);
                if (s.target != null && !s.recoil) {
                    s.recoil = true;
                    LivingEntity target = s.target;
                    boolean marked = consumeMark(e, target);
                    hit(l, e, s, target, BLAZE_E, 2);
                    if (marked) {
                        s.speed = BLAZE_HASTE_TICKS;
                        cue(e, 2, "detonate", target.getBoundingBox().getCenter(), s.direction);
                        cue(e, 2, "haste", e.position(), s.direction);
                    }
                }
            }
            case 3 -> { // 闪焰冲锋: charged charge attack; a detonated mark refreshes Q/W/E.
                if (t == 1) cue(e, s, "attack", e.position(), s.direction);
                dash(l, e, s, m, t, true);
                if (s.target != null && !s.recoil) {
                    s.recoil = true;
                    LivingEntity target = s.target;
                    boolean marked = consumeMark(e, target);
                    hit(l, e, s, target, BLAZE_R + (marked ? BLAZE_R_BONUS : 0), 2);
                    cue(e, s, "impact", target.getBoundingBox().getCenter(), s.direction);
                    if (marked) {
                        s.cd[0] = 0;
                        s.cd[1] = 0;
                        s.cd[2] = 0; // R itself keeps its own cooldown: a skill never refreshes itself
                        cue(e, 3, "detonate", target.getBoundingBox().getCenter(), s.direction);
                        cue(e, 3, "refresh", e.position(), s.direction);
                    }
                }
            }
        }
    }
    // ---- Flame Mark bookkeeping ------------------------------------------------------------
    static void applyMark(PokemonEntity caster, LivingEntity target) {
        if (caster == null || target == null || !target.isAlive()) return;
        MARKS.put(target.getUUID(), new Mark(caster.getUUID(), caster.level().getGameTime() + MARK_TICKS));
    }
    /** True while the victim carries a live mark from that caster. */
    static boolean marked(PokemonEntity caster, LivingEntity target) {
        if (caster == null || target == null) return false;
        Mark mark = MARKS.get(target.getUUID());
        if (mark == null) return false;
        if (mark.expire() <= caster.level().getGameTime()) {
            MARKS.remove(target.getUUID());
            return false;
        }
        return mark.caster().equals(caster.getUUID());
    }
    /** Consumes the mark; false when there was nothing of that caster's to spend. */
    static boolean consumeMark(PokemonEntity caster, LivingEntity target) {
        if (!marked(caster, target)) return false;
        MARKS.remove(target.getUUID());
        return true;
    }
    /** 2 Hz re-cue so the client can draw the mark on a moving victim. */
    private static void refreshMarkCues(ServerLevel level, PokemonEntity caster) {
        if (MARKS.isEmpty()) return;
        for (var entry : MARKS.entrySet()) {
            Mark mark = entry.getValue();
            if (!mark.caster().equals(caster.getUUID())) continue;
            if (mark.expire() <= level.getGameTime()) continue;
            if (!(level.getEntity(entry.getKey()) instanceof LivingEntity target) || !target.isAlive()) continue;
            Vec3 at = target.getBoundingBox().getCenter();
            cue(caster, 0, "mark", at, target.position().subtract(caster.position()).normalize());
        }
    }
    private static void gengar(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch (s.skill) {
            case 0 -> projectile(l,e,s,m,.7,1.6,0);
            case 1 -> { if(!s.stopped && lob(l,e,s,t,m)) {
                s.stopped=true;cue(e,s,"impact",s.point,s.direction);
                for(LivingEntity target : around(l,e,s.point,m.radius()))
                    if(hit(l,e,s,target,m.damage(),1)) s.poison.put(target.getUUID(),61);
            } }
            case 2 -> {
                // 0.66 催眠光: one beam scans forward and the victim only falls asleep after it has
                // been lit three times — a single graze no longer puts anyone under.
                if (t <= 30 && (t - 1) % 10 == 0) {
                    int layer = (t - 1) / 10 + 1;
                    Vec3 from = launchPoint(e, s.direction);
                    cue(e, s, "ray" + layer, s.point, s.direction);
                    for (LivingEntity target : ExpansionGeometry.swept(l, e, from, s.point, Math.max(.9, m.radius()))) {
                        int lit = Math.min(3, s.hit.merge(target.getUUID(), 1, Integer::sum));
                        cue(e, s, "lit" + lit, target.getBoundingBox().getCenter(), s.direction);
                        if (lit >= 3 && ExpansionControl.sleep(e, target, 100))
                            cue(e, s, "sleep", target.getBoundingBox().getCenter(), s.direction);
                    }
                }
            }
            case 3 -> {
                // 0.66 食梦: blink straight onto the victim. If it was already asleep the strike
                // drains it — damage now, health back to Gengar.
                if (t == 1) {
                    Vec3 start = e.position();
                    Vec3 dest = s.target != null ? s.target.position() : s.point;
                    Vec3 flat = new Vec3(dest.x - start.x, 0, dest.z - start.z);
                    double len = flat.length();
                    if (len > .01) ExpansionGeometry.move(e, flat.scale(Math.max(0, len - 1.35) / len));
                    cue(e, s, "blink", start, e.position());
                    s.dream = false;
                    for (LivingEntity target : meleeTargets(l, e, s.direction, m)) {
                        s.target = target;
                        s.dream = ExpansionControl.sleeping(target);
                        break;
                    }
                } else if (t == 6 && !s.recoil && s.target != null && s.target.isAlive()) {
                    s.recoil = true;
                    if (hit(l, e, s, s.target, m.damage(), 1)) {
                        cue(e, s, "melee", s.target.getBoundingBox().getCenter(), s.direction);
                        if (s.dream) {
                            e.heal(14);
                            cue(e, s, "heal", s.target.position(), e.position());
                        }
                    }
                }
            }
        }
    }
    private static void tyranitar(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch(s.skill) {
            case 0 -> { if(t == 1) for(LivingEntity target : around(l,e,s.point,m.radius()))
                if(hit(l,e,s,target,m.damage(),1)) ExpansionControl.slow(e,target,30,.65); }
            case 1 -> { if(t == 1) { cue(e,s,"attack",e.position(),s.direction); ExpansionGeometry.move(e,s.direction.scale(.4)); meleeHitAll(l,e,s,m,m.damage(),1); } }
            case 2 -> { if(t==1) {s.sand=m.duration();cue(e,s,"field",e.position(),s.direction);finish(e,s);} }
            case 3 -> { if(t % 5 == 1) {
                Vec3 spike = s.origin.add(s.direction.scale(2 + (t/5)*2.4));
                cue(e,s,"spike",spike,s.direction);
                for(LivingEntity target : around(l,e,spike,m.radius())) {
                    int n=s.hit.getOrDefault(target.getUUID(),0);
                    hit(l,e,s,target,m.damage() * (n == 0 ? 0.45F : n == 1 ? 0.25F : 0.15F),4);
                }
            } }
        }
    }
    private static void garchomp(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch(s.skill) {
            case 0 -> { if(t == 1 || t == 6) { cue(e,s,"attack",e.position(),s.direction); meleeHitAll(l,e,s,m,15,2); } }
            case 1 -> { if(t == 1) { cue(e,s,"attack",e.position(),s.direction); for(LivingEntity target : meleeTargets(l,e,s.direction,m))
                if(meleeHit(l,e,s,target,m.damage(),1)) ExpansionControl.slow(e,target,35,.6); } }
            case 2 -> {
                if(t < m.duration()) ExpansionGeometry.move(e,s.direction.scale(m.range()/(m.duration()-1)));
                if(t == m.duration()) { cue(e,s,"attack",e.position(),s.direction); for(LivingEntity target : meleeTargets(l,e,s.direction,m)) meleeHit(l,e,s,target,m.damage(),1); }
            }
            case 3 -> { dash(l,e,s,m,t,true); if(s.target != null && !s.recoil) {
                s.recoil=true; s.target.push(s.direction.x*.7,.15,s.direction.z*.7);
            } }
        }
    }
    private static void scizor(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch(s.skill) {
            case 0 -> { if (t == 1) cue(e,s,"attack",e.position(),s.direction); dash(l,e,s,m,t,true); if(s.buff>0 && s.target != null && !s.recoil && t>=5 && e.distanceTo(s.target)<3.5
                    && ExpansionGeometry.visible(l,e,e.getBoundingBox().getCenter(),s.target.getBoundingBox().getCenter())) {
                s.recoil=true; meleeHit(l,e,s,s.target,10,2);
            } }
            case 1 -> { if(t==1 || t==8 || t==15) {
                 cue(e,s,"attack",e.position(),s.direction);
                for(LivingEntity target : cone(l,e,s,m)) {
                    int n=s.hit.getOrDefault(target.getUUID(),0);
                    meleeHit(l,e,s,target,10+n*2+(s.buff>0 && t==15?8:0),3);
                }
            } }
            case 2 -> { if(t<=9) dash(l,e,s,m,t,false); else ExpansionGeometry.move(e,s.direction.scale(-3.0/9)); }
            case 3 -> { if(t==1) { s.buff=m.duration(); cue(e,s,"buff",e.position(),s.direction); finish(e,s); } }
        }
    }
    private static void gardevoir(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch(s.skill) {
            case 0 -> projectile(l,e,s,m,.75,.6,25);
            case 1 -> { if(!s.stopped && lob(l,e,s,t,m)) {
                s.stopped=true;cue(e,s,"impact",s.point,s.direction);
                for(LivingEntity target : around(l,e,s.point,m.radius())) hit(l,e,s,target,m.damage(),1);
            } }
            case 2 -> { if(t==1) { Vec3 start=e.position(); ExpansionGeometry.move(e,s.point.subtract(start).multiply(1,0,1)); cue(e,s,"blink",start,e.position()); } }
            case 3 -> { if(t==1) for(LivingEntity target : around(l,e,s.point,m.radius())) hit(l,e,s,target,m.damage(),1); }
        }
    }
    /** Authority uses model-scaled emission offsets; clients refine cosmetics using animated locators. */
    private static Vec3 launchPoint(PokemonEntity entity,Vec3 direction) {
        String id=species(entity).id();
        double height=switch(id) {
            case "lucario" -> .65;
            case "gengar" -> .52;
            case "gardevoir" -> .67;
            case "abomasnow" -> .7;
            default -> .6;
        };
        return entity.position().add(0,entity.getBbHeight()*height,0)
                .add(direction.scale(Math.min(.85,entity.getBbWidth()*.5+.2)));
    }
    private static boolean lob(ServerLevel level,PokemonEntity entity,State state,int tick,ExpansionRoster.Move move) {
        double fraction=Math.min(1,tick/(double)move.duration());
        Vec3 next=state.launch.lerp(state.point.add(0,.1,0),fraction)
                .add(0,Math.sin(fraction*Math.PI)*3,0);
        Vec3 clipped=ExpansionGeometry.clipProjectile(level,entity,state.projectile,next);
        state.projectile=clipped;
        cue(entity,state,"lob",clipped,state.direction);
        if(clipped.distanceToSqr(next)>.002) {state.point=clipped;return true;}
        return tick>=move.duration();
    }
    private static List<LivingEntity> around(ServerLevel l, PokemonEntity e, Vec3 p, double r) {
        return l.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(p,p).inflate(r,3,r),
            target -> BasicAttack.validTarget(target,e)
                && target.position().subtract(p).horizontalDistanceSqr() <= Math.pow(r+target.getBbWidth()*.5,2)
                && ExpansionGeometry.visible(l,e,p.add(0,.4,0),target.getBoundingBox().getCenter()));
    }
    private static List<LivingEntity> cone(ServerLevel l, PokemonEntity e, State s, ExpansionRoster.Move m) {
        return around(l,e,e.position(),m.range()).stream().filter(target -> ExpansionGeometry.inCone(e,target,s.direction,m.range(),m.radius())).toList();
    }
    private static void coneHit(ServerLevel l, PokemonEntity e, State s, ExpansionRoster.Move m, float amount, int cap) {
        for(LivingEntity target : cone(l,e,s,m)) hit(l,e,s,target,amount,cap);
    }
    private static List<LivingEntity> meleeTargets(ServerLevel l, PokemonEntity e, Vec3 direction, ExpansionRoster.Move m) {
        double radius = Math.max(1.15, Math.min(2.25, m.radius() + e.getBbWidth() * .35));
        Vec3 center = e.position().add(direction.multiply(radius * .45, 0, radius * .45));
        return around(l, e, center, radius);
    }
    private static void meleeHitAll(ServerLevel l, PokemonEntity e, State s, ExpansionRoster.Move m, float amount, int cap) {
        for (LivingEntity target : meleeTargets(l, e, s.direction, m)) meleeHit(l, e, s, target, amount, cap);
    }
    private static boolean meleeHit(ServerLevel l, PokemonEntity e, State s, LivingEntity target, float amount, int cap) {
        boolean ok = hit(l, e, s, target, amount, cap);
        if (ok) cue(e, s, "melee", target.getBoundingBox().getCenter(), s.direction);
        return ok;
    }
    private static boolean hit(ServerLevel l, PokemonEntity e, State s, LivingEntity target, float amount, int cap) {
        int count=s.hit.getOrDefault(target.getUUID(),0);
        if(count>=cap || !CombatDamage.apply(l,e,target,amount,TypeEffectiveness.moveType(e,s.skill))) return false;
        s.hit.put(target.getUUID(),count+1);
        cue(e,s,"impact",target.getBoundingBox().getCenter(),s.direction);
        ExpansionControl.wake(target);
        return true;
    }
    private static void projectile(ServerLevel l, PokemonEntity e, State s, ExpansionRoster.Move m, double speed, double burst, int slow) {
        if(s.stopped) return;
        // Settle from animated hand height into a low combat lane so Pikachu-sized bodies
        // are not passed over by a tall caster's permanently horizontal projectile.
        double lane=s.origin.y+.55;
        double descent=Math.max(-.22,Math.min(.12,lane-s.projectile.y));
        double remaining=Math.max(0,m.range()-s.projectile.subtract(s.launch).horizontalDistance());
        Vec3 intended=s.projectile.add(s.direction.scale(Math.min(speed,remaining))).add(0,descent,0);
        Vec3 next=ExpansionGeometry.clipProjectile(l,e,s.projectile,intended);
        List<LivingEntity> targets=ExpansionGeometry.swept(l,e,s.projectile,next,.55);
        boolean blocked=next.distanceToSqr(intended)>.0001;
        s.projectile=next;
        cue(e,s,"projectile",next,s.direction);
        if(!targets.isEmpty()) {
            LivingEntity first=targets.getFirst();
            Vec3 impact=first.getBoundingBox().getCenter();
            for(LivingEntity target:around(l,e,impact,burst)) if(hit(l,e,s,target,m.damage(),1) && slow>0) ExpansionControl.slow(e,target,slow,.7);
            s.stopped=true; cue(e,s,"impact",impact,s.direction);
        } else if(blocked || remaining<=speed) s.stopped=true;
    }
    private static void dash(ServerLevel l, PokemonEntity e, State s, ExpansionRoster.Move m, int t, boolean stopOnHit) {
        if(s.stopped) return;
        Vec3 before=e.position();
        double speed=Math.min(m.range()/Math.min(12,m.duration()),Math.max(0,m.range()-before.distanceTo(s.origin)));
        Vec3 from=before.add(0,.5,0);
        Vec3 intended=from.add(s.direction.scale(speed));
        List<LivingEntity> candidates=ExpansionGeometry.swept(l,e,from,intended,.75);
        if(stopOnHit && !candidates.isEmpty()) {
            LivingEntity first=candidates.getFirst();
            Vec3 contact=first.getBoundingBox().inflate(.75).contains(from)?from
                    :first.getBoundingBox().inflate(.75).clip(from,intended).orElse(intended);
            speed=Math.min(speed,Math.max(0,from.distanceTo(contact)-.05));
        }
        double moved=ExpansionGeometry.move(e,s.direction.scale(speed));
        Vec3 to=e.position().add(0,.5,0);
        for(LivingEntity target:ExpansionGeometry.swept(l,e,from,to.add(s.direction.scale(.1)),.75)) {
            s.dream=ExpansionControl.sleeping(target);
            float amount=species(e).id().equals("lucario") && s.skill==3 ? 0 : (s.deferDamage ? 0 : m.damage());
            if(amount==0 || (hit(l,e,s,target,amount,1) && (species(e).id().equals("lucario") || species(e).id().equals("blaziken") || species(e).id().equals("gengar") || species(e).id().equals("garchomp") || species(e).id().equals("scizor")))) {
                s.target=target;
                if (amount > 0) cue(e,s,"melee",target.getBoundingBox().getCenter(),s.direction);
                if(stopOnHit) {s.stopped=true; break;}
            }
        }
        if(moved<speed*.5 || before.distanceTo(s.origin)>=m.range()) s.stopped=true;
    }
    private static void tickPoison(ServerLevel l, PokemonEntity e, State s) {
        var iterator=s.poison.entrySet().iterator();
        while(iterator.hasNext()) {
            var entry=iterator.next(); int left=entry.getValue()-1;
            var found=l.getEntity(entry.getKey());
            if(left<=0 || !(found instanceof LivingEntity target) || !target.isAlive()) {iterator.remove();continue;}
            entry.setValue(left);
            if(left%20==0) CombatDamage.apply(l,e,target,3,TypeEffectiveness.MoveType.POISON);
        }
    }
    public static double movementMultiplier(PokemonEntity e) {
        State s=STATES.get(e.getUUID());
        return ExpansionControl.movementMultiplier(e)*(s!=null && s.speed>0?1.25:1);
    }
    private static void finish(PokemonEntity e, State s) {
        if(s.skill>=0) cue(e,s,"end",e.position(),s.direction);
        s.skill=-1; CastLock.release(e);
    }
    public static void clearTransient(PokemonEntity e) {
        State s=STATES.get(e.getUUID());
        if(s!=null) {
            cue(e,s,"clear",e.position(),Vec3.ZERO);
            finish(e,s); s.buff=0;s.speed=0;s.sand=0;s.snow=0;s.poison.clear();s.hit.clear();s.target=null;
        }
        // A retreat, faint or match end must not leave live marks behind for the next round.
        if (s != null) { s.aura = 0; s.auraExpire = 0; s.bonus = 1F; }
        MARKS.values().removeIf(mark -> mark.caster().equals(e.getUUID()));
        MARKS.remove(e.getUUID());
        ExpansionControl.removeEntity(e.getUUID()); ExpansionWalls.remove(e.getUUID());
    }
    public static void reset(PokemonEntity e) { if(e!=null) {clearTransient(e);STATES.remove(e.getUUID());} }
    public static void clearReserve(UUID owner,String speciesId) {
        var species=ExpansionRoster.find(speciesId);
        if(owner!=null && species!=null)RESERVES.remove(new ReserveKey(owner,species.id()));
    }
    public static void clearReserves(UUID owner) {if(owner!=null)RESERVES.keySet().removeIf(key->key.owner().equals(owner));}
    public static void resetAll() {STATES.clear();RESERVES.clear();MARKS.clear();ExpansionControl.resetAll();ExpansionWalls.resetAll();}
    public static void syncHud(ServerPlayer player,PokemonEntity e) {
        if(player==null || !ServerPlayNetworking.canSend(player,SkillStatePayload.TYPE)) return;
        State s=state(e); Vec3 c=CharizardCombat.arenaCenter();
        var m=s.skill<0?null:species(e).move(s.skill);
        float progress=m==null?0:Math.min(1,s.age/(float)Math.max(1,m.windup()));
        ServerPlayNetworking.send(player,new SkillStatePayload(e.getHealth(),e.getMaxHealth(),s.points,
            s.cd[0],s.cd[1],s.cd[2],s.cd[3],channel(e),progress,CharizardCombat.distanceToBorder(e),
            (float)c.x,(float)c.y,(float)c.z,(float)CharizardCombat.arenaRadius()));
    }
    private static void cue(PokemonEntity e,State s,String phase,Vec3 p,Vec3 d) { cue(e,Math.max(0,s.skill),phase,p,d); }
    private static void cue(PokemonEntity e,int skill,String phase,Vec3 p,Vec3 d) {
        if(!(e.level() instanceof ServerLevel level) || !ExpansionGeometry.finite(p) || !ExpansionGeometry.finite(d)) return;
        String key="pkmoba:expansion/"+species(e).id()+"/"+skill+"/"+phase+"/"+e.getId();
        var payload=new EffectPayload(key,p.x,p.y,p.z,d.x,d.y,d.z);
        for(ServerPlayer player:level.players()) if(player.distanceToSqr(p)<128*128 && ServerPlayNetworking.canSend(player,EffectPayload.TYPE)) ServerPlayNetworking.send(player,payload);
    }
    private static final class State {
        int skill=-1,age,regen,buff,speed,sand,snow,mist,aura; long auraExpire;
        float points=10, bonus=1F;
        final int[] cd=new int[4];
        final Map<UUID,Integer> hit=new HashMap<>(),poison=new HashMap<>();
        Vec3 origin=Vec3.ZERO,direction=new Vec3(0,0,1),point=Vec3.ZERO,projectile=Vec3.ZERO,launch=Vec3.ZERO,snowPoint=Vec3.ZERO,mistPoint=Vec3.ZERO;
        LivingEntity target; boolean stopped,recoil,dream,deferDamage;
    }
}
