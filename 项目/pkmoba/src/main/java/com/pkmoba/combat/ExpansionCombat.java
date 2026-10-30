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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-authoritative expansion engine. Species dispatch is explicit: shared geometry is not shared skills. */
public final class ExpansionCombat {
    /** 0.101 (m15488): hypnosis puts the victim to sleep for ten seconds, badge stays thirty. */
    private static final int SLEEP_TICKS = 200;
    private static final int HYPNO_BADGE_TICKS = 600;
    private static final int SLUDGE_TICKS = 200;
    private static final int POISON_TICKS = 80;
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
        if (entity == null || entity.getPokemon() == null) return null;
        var identifier = entity.getPokemon().getSpecies().getResourceIdentifier();
        ExpansionRoster.Species found = ExpansionRoster.find(identifier.toString());
        if (found != null) return found;
        // 0.87: regional forms spawn as their base Cobblemon species (Alolan Ninetales is
        // cobblemon:ninetales + the alolan aspect), so fall back to the registry's local id.
        var local = com.pkmoba.pokemon.SpeciesRegistry.localForCobble(identifier);
        return local == null ? null : ExpansionRoster.find(local.toString());
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
        if (ExpansionControl.sleeping(entity)) return true;
        State s = STATES.get(entity.getUUID());
        if (s == null || s.skill < 0) return false;
        // 0.101 (m15488): only the short wind-up roots the body. Once the wind-up is over the
        // Pokemon may run again, so the recovery frames can never trap it in place.
        var move = species(entity).move(s.skill);
        return s.age <= Math.max(1, move.windup());
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
        // 0.75: skills may interrupt each other. The user wants Moonblast to follow a successful
        // Psychic hold, so a running channel must not lock the caster out of its next move.
        return s.cd[index] == 0 && s.points >= species(entity).move(index).cost();
    }
    public static void castForBot(PokemonEntity entity, Skill skill, Vec3 aim) {
        if (canCastForBot(entity, skill)) cast(BotDuelService.actor(CharizardManager.getControlledOwner(entity)), entity, skill, aim);
    }
    public static void cast(ServerPlayer player, PokemonEntity entity, Skill skill, Vec3 aim) {
        if (skill == null || !ExpansionGeometry.finite(aim) || !canCast(player, entity, skill.ordinal())) return;
        State s = state(entity);
        if (s.skill >= 0) {
            // Interrupt the running channel cleanly and clear its client scene, otherwise the
            // previous visual keeps playing while a different skill resolves.
            cue(entity, s, "end", entity.position(), s.direction);
            CastLock.release(entity);
            s.skill = -1;
        }
        var move = species(entity).move(skill.ordinal());
        s.skill = skill.ordinal(); s.age = 0; s.points -= move.cost(); s.cd[s.skill] = move.cooldown();
        s.origin = entity.position(); s.direction = ExpansionGeometry.direction(entity, aim);
        // 0.76: SELF moves (Dazzling Gleam) are cast from the caster's body and nothing else - the
        // crosshair is ignored completely, so they can never be placed on another creature or point.
        s.point = move.shape() == ExpansionRoster.Shape.SELF
                ? entity.position() : ExpansionGeometry.point(entity, aim, move.range());
        s.projectile = launchPoint(entity,s.direction);
        s.launch=s.projectile;
        s.hit.clear(); s.target = null; s.stopped = false; s.recoil = false; s.markedOnce = false;
        // Blaziken applies its own damage per skill so a detonated mark can add its bonus; the
        // shared dash helper therefore only moves the body and records the contact target.
        s.deferDamage = species(entity).id().equals("blaziken");
        // Snapshot geometry and cancel only the stale destination. Movement remains available during casts;
        // dash moves below own displacement for their active window.
        MoveOrderService.cancelPending(entity);
        CastLock.Shape shape = switch (move.shape()) {
            case POINT, WALL, BLINK -> CastLock.Shape.POINT;
            case DASH -> CastLock.Shape.DASH;
            case SELF -> CastLock.Shape.SELF;
            default -> CastLock.Shape.LINE;
        };
        CastLock.begin(entity, shape, s.direction, s.point, move.windup() + move.duration() + 2);
        // 0.104 (m15857): kill residual navigation/momentum the instant the skill is accepted, so the
        // model never slides while the wind-up plays (client interpolation keeps a leftover step).
        entity.getNavigation().stop();
        entity.setDeltaMovement(entity.getDeltaMovement().scale(.15));
        entity.hurtMarked = true;
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
            // 0.103 (m15857): the sandstorm now really bites - wider reach, heavier pulses and a
            // visible hit cue on every pulse so the damage is readable in game.
            if (s.sand % 10 == 0) {
                double reach = 3.2;
                for (LivingEntity target : around(level, entity, entity.position(), reach)) {
                    if (CombatDamage.apply(level, entity, target, 9,
                            TypeEffectiveness.MoveType.ROCK)) {
                        ExpansionControl.slow(entity, target, 20, .65);
                        cue(entity, 2, "hit", target.getBoundingBox().getCenter(), s.direction);
                    }
                }
                cue(entity, 2, "pulse", entity.position(), s.direction);
            }
            s.sand--;
        }
        if(s.snow>0) {
            if(s.snow%10==0)for(LivingEntity target:around(level,entity,s.snowPoint,species(entity).move(3).radius())) {
                if(CombatDamage.apply(level,entity,target,species(entity).move(3).damage()/8.0F,TypeEffectiveness.MoveType.ICE))
                    ExpansionControl.slow(entity,target,15,Math.max(.4,.8-(80-s.snow)*.005));
            }
            s.snow--;
        }
        // Psychic well (autonomous): drag, hold, then one crush - independent of the skill slot.
        if (s.pull > 0) {
            int left = s.pull;
            int elapsed = 32 - left;
            double radius = Math.max(2.2, species(entity).move(0).radius());
            if (elapsed <= 26) {
                for (LivingEntity target : around(level, entity, s.pullPoint, radius)) {
                    Vec3 to = s.pullPoint.subtract(target.position());
                    double dist = to.length();
                    if (dist < .35 || dist > radius + .6) continue;
                    double force = .11 + .58 * Math.max(0, 1 - dist / (radius + .6));
                    Vec3 push = to.normalize().scale(force);
                    target.setDeltaMovement(target.getDeltaMovement().add(push.x, 0, push.z));
                    target.hurtMarked = true;
                }
            }
            if (elapsed <= 16) {
                if (elapsed % 2 == 0) cue(entity, 0, "pull", s.pullPoint, s.direction);
            } else if (elapsed <= 26) {
                if (elapsed % 3 == 0) cue(entity, 0, "hold", s.pullPoint, s.direction);
            } else if (left == 5) {
                cue(entity, 0, "impact", s.pullPoint, s.direction);
                for (LivingEntity target : around(level, entity, s.pullPoint, Math.min(3.2, radius)))
                    CombatDamage.apply(level, entity, target, 14, TypeEffectiveness.MoveType.PSYCHIC);
            }
            s.pull--;
        }
        if (s.mist > 0) {
            // White mist: everything inside the cloud is slowed while it lasts.
            if (s.mist % 5 == 0) for (LivingEntity target : around(level, entity, s.mistPoint,
                    species(entity).move(2).radius() * 1.35)) {
                ExpansionControl.slow(entity, target, 20, .5);
            }
            s.mist--;
        }
        // 0.101 (m15488): lingering sludge pool. Ten seconds of visible mud, four seconds of
        // poison per victim, and the victim list is cleared as soon as it leaves the pool.
        if (s.sludge > 0) {
            if (s.sludge % 20 == 0 && level instanceof ServerLevel) {
                SkillSounds.sludgeBubble(level, s.sludgePoint);
            }
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
        tickPoison(level, entity, s);
        // 0.78: Moonblast is fire-and-forget. Once the moon leaves the sky it lands on its own
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
        if (ExpansionControl.sleeping(entity)) {if(s.skill>=0)finish(entity,s);return;}
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
        // 0.102 (m15730): expansion mons lock their direction at cast time and fire where the
        // player clicked - the skill does NOT keep chasing the cursor afterwards. Only Charizard
        // and Blastoise keep the live-aim behaviour (they are driven by their own controllers).
        if (move.shape() != ExpansionRoster.Shape.DASH) {
            FacingService.request(entity, FacingService.Priority.CHANNEL, s.direction, 12);
        }
        // Flare Blitz telegraph: a red warning lane is re-cued while the charge builds so the
        // victim can step out of it instead of being hit by an invisible 0.9 s wind-up.
        if (s.skill == 3 && species(entity).id().equals("blaziken") && s.age <= move.windup() && s.age % 4 == 0)
            cue(entity, s, "warn", s.point, s.direction);
        // 0.130: Petal Blizzard gathers petals while it charges, so the cast is not a silent freeze.
        if (s.skill == 3 && species(entity).id().equals("meowscarada") && s.age <= move.windup() && s.age % 2 == 1)
            cue(entity, s, "charge", s.point, s.direction);
        if (s.age <= move.windup()) return;
        int t = s.age - move.windup();
        if (t == 1) cue(entity, s, "release", s.point, s.direction);
        switch (species(entity).id()) {
            case "lucario" -> lucario(level, entity, s, t, move);
            case "alolan_ninetales" -> alolanNinetales(level, entity, s, t, move);
            case "blaziken" -> blaziken(level, entity, s, t, move);
            case "gengar" -> gengar(level, entity, s, t, move);
            case "tyranitar" -> tyranitar(level, entity, s, t, move);
            case "gardevoir" -> gardevoir(level, entity, s, t, move);
            case "greninja" -> greninja(level, entity, s, t, move);
            case "meowscarada" -> meowscarada(level, entity, s, t, move);
            case "ceruledge" -> ceruledge(level, entity, s, t, move);
            case "hisuian_zoroark" -> hisuianZoroark(level, entity, s, t, move);
            case "krookodile" -> krookodile(level, entity, s, t, move);
            case "armarouge" -> armarouge(level, entity, s, t, move);
            default -> throw new IllegalStateException("Unsupported expansion species");
        }
        if (s.skill >= 0 && t >= move.duration()) finish(entity, s);
    }
    private static final float SWORDS_DANCE_BONUS = .366F;
    private static final int SWORDS_DANCE_TICKS = 160; // 8 s, per the user
    /** 0.125 Greninja 诡计: the spec's +30% for eight seconds, applied through the shared buff table. */
    private static final double NASTY_PLOT_BONUS = 1.3;
    private static final int NASTY_PLOT_TICKS = 160;

    private static void lucario(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch (s.skill) {
            case 0 -> { // 剑舞: raises attack by 36.6% and shows the blade halo around the body.
                if (t == 1) {
                    ExpansionControl.empower(e, SWORDS_DANCE_TICKS, 1F + SWORDS_DANCE_BONUS);
                    buffCue(e, "swords", SWORDS_DANCE_TICKS);
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
            default -> { // 神速: one clean snap along the aim line, then a single contact hit.
                if (t == 1) {
                    Vec3 start = e.position();
                    Vec3 step = ExpansionGeometry.direction(e, s.point);
                    double reach = Math.max(0, Math.min(m.range(), start.distanceTo(s.point)));
                    // Move in slices so a wall stops the leap instead of teleporting through it.
                    double left = reach;
                    while (left > 1E-3) {
                        double slice = Math.min(1.6, left);
                        double moved = ExpansionGeometry.move(e, step.scale(slice));
                        left -= Math.max(moved, .25);
                    }
                    cue(e, s, "start", start, e.position());
                    cue(e, s, "arrive", e.position(), step);
                    for (LivingEntity target : around(l, e, e.position(), Math.max(1.3, m.radius() + .5))) {
                        if (hit(l, e, s, target, m.damage(), 1)) {
                            target.push(step.x * .5, .2, step.z * .5);
                            cue(e, s, "impact", target.getBoundingBox().getCenter(), step);
                        }
                    }
                    finish(e, s);
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
    /** 0.87 Alolan Ninetales: ice-fairy pressure with a self guard and a blizzard zone. */
    private static void alolanNinetales(ServerLevel l, PokemonEntity e, State s, int t,
                                       ExpansionRoster.Move m) {
        switch (s.skill) {
            case 0 -> { // 冷冻干燥: a frozen droplet shard; water targets take double damage.
                if (t == 1) {
                    s.projectile = launchPoint(e, s.direction);
                    s.launch = s.projectile;
                    cue(e, s, "start", e.position(), s.direction);
                }
                if (t <= 1 || s.stopped) return;
                double lane = s.origin.y + .6;
                double descent = Math.max(-.22, Math.min(.14, lane - s.projectile.y));
                double remaining = Math.max(0, m.range() - s.projectile.subtract(s.launch).horizontalDistance());
                Vec3 intended = s.projectile.add(s.direction.scale(1.55)).add(0, descent, 0);
                Vec3 next = ExpansionGeometry.clipProjectile(l, e, s.projectile, intended);
                List<LivingEntity> targets = ExpansionGeometry.swept(l, e, s.projectile, next, .65);
                boolean blocked = next.distanceToSqr(intended) > .0001;
                s.projectile = next;
                cue(e, s, "projectile", next, s.direction);
                if (!targets.isEmpty()) {
                    LivingEntity first = targets.getFirst();
                    Vec3 impact = first.getBoundingBox().getCenter();
                    for (LivingEntity target : around(l, e, impact, .95)) {
                        float amount = m.damage();
                        if (waterTarget(target)) amount *= 2.0F;
                        if (hit(l, e, s, target, amount, 1)) {
                            ExpansionControl.slow(e, target, 30, .6);
                            if (l.random.nextFloat() < .10F) {
                                target.setTicksFrozen(Math.min(200, target.getTicksFrozen() + 90));
                                ExpansionControl.slow(e, target, 60, .35);
                                cue(e, s, "frost", target.getBoundingBox().getCenter(), s.direction);
                            }
                        }
                    }
                    s.stopped = true;
                    cue(e, s, "impact", impact, s.direction);
                } else if (blocked || remaining <= 1.55) {
                    s.stopped = true;
                    cue(e, s, "impact", next, s.direction);
                }
            }
            case 1 -> { // 极光幕: raises an aurora screen for five seconds (damage halved).
                if (t == 1) {
                    ExpansionControl.guard(e, 100, .5);
                    cue(e, s, "veil", e.position(), s.direction);
                }
                if (t >= 2) finish(e, s);
            }
            case 2 -> { // 暴风雪: six seconds of drifting storm that keeps chipping and slowing.
                if (t == 1) {
                    s.snow = 120;
                    s.snowPoint = s.point;
                    cue(e, s, "field", s.point, s.direction);
                    finish(e, s);
                }
            }
            default -> { // 太晶爆发: a crystal beam fired straight down the aim line.
                if (t == 1) {
                    cue(e, s, "start", e.position(), s.direction);
                }
                if (t >= 4 && t <= 12) {
                    Vec3 start = launchPoint(e, s.direction);
                    Vec3 end = ExpansionGeometry.clipProjectile(l, e, start, s.point);
                    cue(e, s, "beam", start, end);
                    if (t == 8) {
                        for (LivingEntity target : around(l, e, end, Math.max(1.2, m.radius() + 1.0))) {
                            hit(l, e, s, target, m.damage(), 2);
                        }
                        cue(e, s, "impact", end, s.direction);
                    }
                }
                if (t >= 13) finish(e, s);
            }
        }
    }

    /** Stamps 烈焰印记: an invisible HERO_OF_THE_VILLAGE effect, stacking up to three. */
    private static void brandBlaze(PokemonEntity caster, LivingEntity victim) {
        if (caster == null || victim == null) return;
        var existing = victim.getEffect(net.minecraft.world.effect.MobEffects.HERO_OF_THE_VILLAGE);
        int amp = existing == null ? 0 : Math.min(2, existing.getAmplifier() + 1);
        victim.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.HERO_OF_THE_VILLAGE, MARK_TICKS, amp,
                false, false, false));
        MARKS.put(victim.getUUID(), new Mark(caster.getUUID(), victim.level().getGameTime() + MARK_TICKS));
        StatusBadges.send(victim, "mark", amp + 1, MARK_TICKS);
    }

    private static boolean blazeMark(LivingEntity victim) {
        return victim != null && victim.hasEffect(net.minecraft.world.effect.MobEffects.HERO_OF_THE_VILLAGE);
    }

    private static boolean takeBlazeMark(LivingEntity victim) {
        if (!blazeMark(victim)) return false;
        victim.removeEffect(net.minecraft.world.effect.MobEffects.HERO_OF_THE_VILLAGE);
        MARKS.remove(victim.getUUID());
        StatusBadges.clear(victim);
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
        // 0.80 fire-foot kit: Q brands with a thrown burning foot, W double-kicks it, E dashes and
        // paints a low fire wall behind itself, R leaps through the air onto the clicked point.
        switch (s.skill) {
            case 0 -> { // 火焰踢: a straight kick wrapped in flame, brands on contact.
                if (t == 2) {
                    ExpansionGeometry.move(e, s.direction.scale(.5));
                    cue(e, s, "attack", e.position(), s.direction);
                    for (LivingEntity target : meleeTargets(l, e, s.direction, m)) {
                        meleeHit(l, e, s, target, m.damage(), 1);
                        brandBlaze(e, target);
                    }
                    cue(e, s, "spark", e.position().add(s.direction.scale(1.2)), s.direction);
                }
            }
            case 1 -> { // 冲天拳: an uppercut that launches the target straight up.
                if (t == 3) {
                    cue(e, s, "attack", e.position(), s.direction);
                    for (LivingEntity target : meleeTargets(l, e, s.direction, m)) {
                        meleeHit(l, e, s, target, m.damage(), 1);
                        Vec3 up = target.getDeltaMovement().add(0, .62, 0);
                        target.setDeltaMovement(up.x, up.y, up.z);
                        target.hurtMarked = true;
                        brandBlaze(e, target);
                    }
                    cue(e, s, "spark", e.position().add(s.direction.scale(.8)), s.direction);
                }
            }
            case 2 -> { // 飞膝踢: hop up, then drive the knee into the clicked point.
                final int rise = 4;
                final int dive = 9;
                if (t == 1) {
                    s.origin = e.position();
                    e.setDeltaMovement(Vec3.ZERO);
                    cue(e, s, "start", e.position(), s.direction);
                }
                if (t <= rise) {
                    Vec3 up = s.origin.add(0, .75 * (t - 1) / (double) rise, 0);
                    e.teleportTo(up.x, up.y, up.z);
                    e.setDeltaMovement(Vec3.ZERO);
                    e.xo = up.x; e.yo = up.y - .2; e.zo = up.z;
                    e.resetFallDistance();
                    cue(e, s, "rise", up, s.direction);
                } else if (t < rise + dive) {
                    double f = Math.min(1.0, (t - rise) / (double) dive);
                    Vec3 from = s.origin.add(0, .75, 0);
                    Vec3 wanted = from.add(s.point.subtract(from).scale(f));
                    e.teleportTo(wanted.x, wanted.y, wanted.z);
                    e.setDeltaMovement(Vec3.ZERO);
                    e.resetFallDistance();
                    e.xo = wanted.x; e.yo = wanted.y; e.zo = wanted.z;
                    cue(e, s, "charge", wanted, s.direction);
                } else {
                    cue(e, s, "impact", s.point, s.direction);
                    for (LivingEntity target : around(l, e, s.point, Math.max(1.6, m.radius())))
                        meleeHit(l, e, s, target, m.damage(), 1);
                    finish(e, s);
                }
            }
            default -> { // 闪焰冲锋: full-body fiery charge; whatever it runs through gets burned.
                final int charge = 12;
                if (t == 1) {
                    cue(e, s, "start", e.position(), s.direction);
                    s.origin = e.position();
                }
                if (t <= charge) {
                    double step = ExpansionGeometry.move(e, s.direction.scale(.55));
                    if (step < 1e-3 && t > 2) {
                        cue(e, s, "impact", e.position(), s.direction);
                        for (LivingEntity target : around(l, e, e.position(), m.radius()))
                            meleeHit(l, e, s, target, m.damage() * .5F, 1);
                        finish(e, s);
                        return;
                    }
                    cue(e, s, "trail", e.position(), s.direction);
                    for (LivingEntity target : meleeTargets(l, e, s.direction, m))
                        meleeHit(l, e, s, target, m.damage() * .35F, 1);
                } else {
                    cue(e, s, "impact", e.position(), s.direction);
                    for (LivingEntity target : around(l, e, e.position(), m.radius() * 1.3))
                        meleeHit(l, e, s, target, m.damage() * .5F, 1);
                    finish(e, s);
                }
            }
        }
    }

    /** Psychic drag: no damage, the pull only gets stronger the closer a victim already is. */
    private static void dragToCentre(ServerLevel l, PokemonEntity e, State s, ExpansionRoster.Move m) {
        for (LivingEntity target : around(l, e, s.point, m.radius() + 1.5)) {
            if (target == e) continue;
            Vec3 to = s.point.subtract(target.position()).multiply(1, 0, 1);
            double d = to.length();
            if (d < 1.0) continue;
            double speed = .11 + .58 * Math.max(0, 1 - d / (m.radius() + .6));
            Vec3 step = to.scale(Math.min(.85, speed) / d);
            target.setDeltaMovement(step.x, Math.max(0, target.getDeltaMovement().y * .2), step.z);
            target.hurtMarked = true;
        }
    }

    private static void gengar(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch (s.skill) {
            case 0 -> projectile(l,e,s,m,1.25,1.6,0);
            case 1 -> {
                if (!s.stopped && lob(l, e, s, t, m, 1.9)) {
                    s.stopped = true;
                    // 0.101 (m15488): the bomb always detonates at the landing point, hit or miss,
                    // and the puddle stays for ten seconds. Anyone stepping in is poisoned for
                    // four seconds - once per victim per pool, so it cannot refresh every tick.
                    cue(e, s, "impact", s.point, s.direction);
                    SkillSounds.sludgeSplat(l, s.point);
                    for (LivingEntity target : around(l, e, s.point, m.radius()))
                        hit(l, e, s, target, m.damage(), 1);
                    s.sludge = SLUDGE_TICKS;
                    s.sludgePoint = s.point;
                    s.sludgePoisoned.clear();
                    cue(e, s, "field", s.point, s.direction);
                }
            }
            case 2 -> {
                // 0.68 催眠术: a two second spray of hypnotic rings. Each spray leaves at most one
                // mark on a victim; the third mark puts it to sleep, so it always takes three casts
                // against the same target and no lucky single hit can end the fight.
                if (t == 1) {
                    cue(e, s, "start", s.point, s.direction);
                    if (e.level() instanceof ServerLevel sl) SkillSounds.sludgeLaunch(sl, e.position());
                }
                if (t <= 38 && t % 2 == 0) cue(e, s, "stream", s.point, s.direction);
                if (t <= 38 && (t - 1) % 4 == 0) {
                    Vec3 from = launchPoint(e, s.direction);
                    for (LivingEntity target : ExpansionGeometry.swept(l, e, from, s.point, Math.max(.9, m.radius()))) {
                        if (s.marked.contains(target.getUUID())) continue;
                        s.marked.add(target.getUUID());
                        int marks = ExpansionControl.addHypnoMark(e, target, HYPNO_BADGE_TICKS);
                        cue(e, s, "mark" + Math.min(3, marks), target.getBoundingBox().getCenter(), s.direction);
                        // 0.101 (m15488): a hit sleeps the victim immediately for ten seconds; the
                        // third mark on the same victim always lands, ignoring the sleep immunity.
                        boolean slept = ExpansionControl.sleep(e, target, SLEEP_TICKS);
                        if (!slept && marks >= 3) slept = ExpansionControl.sleepForce(e, target, SLEEP_TICKS);
                        if (slept) cue(e, s, "sleep", target.getBoundingBox().getCenter(), s.direction);
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
                    // 食梦 only drains a sleeping victim: heavy damage plus health back to Gengar.
                    // Anything awake only takes a light hit and gives nothing back.
                    boolean asleep = ExpansionControl.sleeping(s.target);
                    float drainDamage = asleep ? m.damage() * 1.5F : m.damage() * .5F;
                    if (hit(l, e, s, s.target, drainDamage, 1)) {
                        cue(e, s, "melee", s.target.getBoundingBox().getCenter(), s.direction);
                        if (asleep) {
                            float healed = Math.min(e.getMaxHealth() - e.getHealth(), drainDamage * .8F);
                            if (healed > 0) e.heal(healed);
                            ExpansionControl.wake(s.target);
                            cue(e, s, "heal", s.target.position(), e.position());
                        }
                    }
                }
            }
        }
    }
    private static void tyranitar(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch(s.skill) {
            case 0 -> {
                // 岩崩: three boulders tumble along an arc from Tyranitar to the aimed point.
                if(t == 1) cue(e, s, "start", e.position(), s.direction);
                if(t >= 2 && t <= 14) {
                    double p = (t - 2) / 12.0;
                    Vec3 at = s.origin.add(s.point.subtract(s.origin).scale(p))
                            .add(0, Math.sin(Math.PI * p) * 2.2, 0);
                    cue(e, s, "projectile", at, s.direction);
                }
                if(t == 15) {
                    cue(e, s, "impact", s.point, s.direction);
                    for(LivingEntity target : around(l,e,s.point,m.radius()))
                        if(hit(l,e,s,target,m.damage(),1)) ExpansionControl.slow(e,target,30,.7);
                    finish(e,s);
                }
            }
            case 1 -> {
                // 咬碎: lunge in and bite once, leaving the target slowed.
                if(t == 1) {
                    cue(e,s,"attack",e.position(),s.direction);
                    ExpansionGeometry.move(e,s.direction.scale(.55));
                    meleeHitAll(l,e,s,m,m.damage(),1);
                }
                if(t == 3) {
                    cue(e,s,"impact",s.point,s.direction);
                    for(LivingEntity target : around(l,e,s.point,m.radius())) ExpansionControl.slow(e,target,40,.6);
                    finish(e,s);
                }
            }
            case 2 -> {
                // 沙暴: the field ticks in tick(); just start it.
                if(t==1) { s.sand=m.duration(); cue(e,s,"field",e.position(),s.direction); finish(e,s); }
            }
            default -> {
                // 地震 (Earthquake): the ground ruptures around Tyranitar in pulses.
                if(t == 1) {
                    cue(e,s,"start",e.position(),s.direction);
                    cue(e,s,"quake",e.position(),s.direction);
                }
                if(t > 1 && t % 6 == 2) {
                    cue(e,s,"quake",e.position(),s.direction);
                    for(LivingEntity target : around(l,e,e.position(),m.radius())) {
                        if(hit(l,e,s,target,m.damage() * .38F,3)) {
                            ExpansionControl.slow(e,target,30,.55);
                            Vec3 up = target.position().subtract(e.position()).normalize().scale(.55).add(0,.42,0);
                            target.setDeltaMovement(target.getDeltaMovement().add(up));
                            target.hurtMarked = true;
                        }
                    }
                }
                if(t == 26) { cue(e,s,"impact",e.position(),s.direction); finish(e,s); }
            }
        }
    }

    private static void gardevoir(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch (s.skill) {
            case 0 -> {
                // 精神强念 Psychic: a gravity well at the aim point. Every creature inside it is
                // dragged towards the centre for the whole channel, then crushed once at the end.
                // Nothing is locked on: the well sits exactly where the player aimed.
                // 0.103 (m15857): Psychic becomes an autonomous well so Moonblast no longer cancels
                // it - both skills can be chained in either order and neither disappears.
                if (t == 1) {
                    s.pull = 32;
                    s.pullPoint = s.point;
                    cue(e, s, "start", s.point, s.direction);
                    finish(e, s);
                }
            }
            case 1 -> {
                // 月亮之力 Moonblast: the moon is released and falls on its own clock (see tick), so
                // the player can chain Psychic into it and the moon still lands where it was aimed.
                if (t == 1) {
                    s.moonPoint = s.point;
                    s.moonDue = l.getGameTime() + 26;
                    cue(e, s, "launch", s.point, s.direction);
                }
            }
            case 2 -> {
                // 瞬间移动 Teleport: leave a phantom trail, appear under a ring of light.
                if (t == 1) {
                    Vec3 start = e.position();
                    ExpansionGeometry.move(e, s.point.subtract(start).multiply(1, 0, 1));
                    cue(e, s, "blink", start, e.position());
                    finish(e, s);
                }
            }
            case 3 -> {
                // 魔法闪耀 Dazzling Gleam: a flash out of the caster's own body, no projectile and no
                // aim travel. It is a normal skill, so no ultimate shake or hitstop is attached.
                if (t == 1) cue(e, s, "start", e.position(), s.direction);
                if (t == 6 && !s.stopped) {
                    s.stopped = true;
                    Vec3 centre = e.position().add(0, 1.0, 0);
                    cue(e, s, "impact", centre, s.direction);
                    for (LivingEntity target : around(l, e, centre, m.radius()))
                        hit(l, e, s, target, m.damage(), 1);
                    finish(e, s);
                }
            }
        }
    }
    /**
     * Greninja 2.0 (m00465): every one of the four is a clean-sheet rebuild.
     *
     * <p>Q throws five shuriken two ticks apart and each one is simulated on its own line with its
     * own victim ledger, so several can cut the same target. W is a five-tick lunge whose body is
     * moved through {@link ExpansionGeometry#move} (walls still stop it) and which slashes once on
     * arrival. E is a real projectile that explodes on contact or at max range. R is 诡计, a pure
     * self buff: no damage, +30% on Q/W/E for eight seconds through the shared timed-buff table.
     */
    private static void greninja(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch (s.skill) {
            case 0 -> {
                // 2.3 (m00903): every star leaves a hand and flies straight down the aim line, so
                // the barrage matches where the crosshair points. A small fan keeps five stars from
                // stacking on one line; 0.95/tick is fast but still readable.
                if (t == 1) {
                    s.launch = launchPoint(e, s.direction);
                    s.shots.clear();
                    cue(e, s, "start", s.launch, s.direction);
                    // 0.35 is roughly a hand's reach either side of the body centre line.
                    s.point = new Vec3(-s.direction.z, 0, s.direction.x).normalize().scale(.35);
                }
                if (t >= 1 && t <= 13 && t % 3 == 1) {
                    int k = (t - 1) / 3;
                    double spread = (k - 2) * .055;
                    Vec3 dir = s.direction.yRot((float) spread);
                    Shot shot = new Shot();
                    // Alternating hands, slight vertical offset: the star visibly leaves the fist.
                    Vec3 hand = s.launch.add(s.point.scale((k % 2 == 0) ? -1 : 1)).add(0, .18, 0);
                    shot.pos = ExpansionGeometry.clipProjectile(l, e, s.launch, hand);
                    shot.dir = dir;
                    s.shots.add(shot);
                }
                boolean done = !s.shots.isEmpty();
                int index = 0;
                for (Shot shot : s.shots) {
                    if (shot.dead) { index++; continue; }
                    done = false;
                    double step = .95;
                    Vec3 intended = shot.pos.add(shot.dir.scale(step));
                    Vec3 next = ExpansionGeometry.clipProjectile(l, e, shot.pos, intended);
                    for (LivingEntity target : ExpansionGeometry.swept(l, e, shot.pos, next, .55)) {
                        if (!shot.struck.add(target.getUUID())) continue;
                        if (!CombatDamage.apply(l, e, target, m.damage(), TypeEffectiveness.moveType(e, 0))) continue;
                        ExpansionControl.wake(target);
                        s.hit.merge(target.getUUID(), 1, Integer::sum);
                        Vec3 at = target.getBoundingBox().getCenter();
                        // One impact voice per four ticks keeps five rapid throws from rattling.
                        if (s.age - s.sfx >= 4) { s.sfx = s.age; cue(e, s, "impact", at, shot.dir); }
                        else cue(e, s, "spark", at, shot.dir);
                    }
                    shot.pos = next;
                    if (next.distanceToSqr(intended) > .0001) {
                        shot.dead = true;
                        cue(e, s, "spark", next, shot.dir);
                    } else cue(e, s, "shuriken" + index, next, shot.dir);
                    index++;
                    if (shot.pos.distanceTo(s.launch) >= m.range()) shot.dead = true;
                }
                if (done) finish(e, s);
                else if (t > m.duration()) finish(e, s);
            }
            case 1 -> { // 暗袭要害: crouch, lunge, slash once on arrival. Range/ticks come from roster.
                if (t == 1) {
                    s.launch = launchPoint(e, s.direction);
                    cue(e, s, "start", s.launch, s.direction);
                }
                int dashTicks = Math.max(1, m.duration());
                if (t >= 1 && t <= dashTicks) {
                    double left = Math.max(0, m.range() - e.position().distanceTo(s.origin));
                    double step = Math.min(m.range() / dashTicks, left);
                    double moved = ExpansionGeometry.move(e, s.direction.scale(step));
                    if (moved < step * .5) s.recoil = true;
                    cue(e, s, "streak", e.position().add(0, .9, 0), s.direction);
                }
                if (t == dashTicks) {
                    Vec3 at = e.position().add(0, .7, 0).add(s.direction.scale(.8));
                    cue(e, s, "attack", e.position(), s.direction);
                    cue(e, s, "impact", at, s.direction);
                    for (LivingEntity target : around(l, e, at, m.radius()))
                        meleeHit(l, e, s, target, m.damage(), 1);
                }
            }
            case 2 -> { // 恶之波动: a dark orb flies the aim line and bursts on contact.
                if (t == 1) {
                    s.launch = launchPoint(e, s.direction);
                    s.projectile = s.launch;
                    cue(e, s, "start", s.launch, s.direction);
                }
                if (t > 1 && !s.stopped) {
                    double travelled = s.projectile.subtract(s.launch).horizontalDistance();
                    double step = Math.min(1.1, Math.max(0, m.range() - travelled));
                    Vec3 intended = s.projectile.add(s.direction.scale(step));
                    Vec3 next = ExpansionGeometry.clipProjectile(l, e, s.projectile, intended);
                    List<LivingEntity> swept = ExpansionGeometry.swept(l, e, s.projectile, next, .5);
                    s.projectile = next;
                    cue(e, s, "projectile", next, s.direction);
                    boolean blocked = next.distanceToSqr(intended) > .0001;
                    if (!swept.isEmpty() || blocked || step < 1.1) {
                        s.stopped = true;
                        Vec3 center = swept.isEmpty() ? next : swept.getFirst().getBoundingBox().getCenter();
                        LivingEntity direct = swept.isEmpty() ? null : swept.getFirst();
                        cue(e, s, "impact", center, s.direction);
                        for (LivingEntity target : around(l, e, center, m.radius())) {
                            float amount = target == direct ? m.damage() : m.damage() * .7F;
                            hit(l, e, s, target, amount, 1);
                        }
                    }
                }
            }
            default -> { // 诡计 (m00617): a self buff, no damage - eight seconds of +30% on Q/W/E.
                if (t == 1) {
                    s.launch = launchPoint(e, s.direction);
                    // The shared timed-buff table is what CombatDamage multiplies with, so every
                    // skill fired during the window is boosted by the server, never the client.
                    ExpansionControl.empower(e, NASTY_PLOT_TICKS, NASTY_PLOT_BONUS);
                    buffCue(e, "plot", NASTY_PLOT_TICKS);
                    cue(e, s, "buff", e.position(), s.direction);
                    cue(e, s, "impact", e.position(), s.direction);
                    finish(e, s);
                }
            }
        }
    }

    /** One airborne water shuriken: its own line, its own victim ledger, its own lifetime. */
    private static final class Shot {
        Vec3 pos = Vec3.ZERO, dir = Vec3.ZERO;
        /** 0.130 homing leaves keep the victim they were locked onto; null means fly straight. */
        java.util.UUID target;
        boolean dead;
        final java.util.Set<java.util.UUID> struck = new java.util.HashSet<>();
    }

    /** 0.130 Flower Trick is a guaranteed critical hit; the project has no crit roll of its own. */
    private static final float FLOWER_CRIT = 1.5F;

    /** Meowscarada (magic cat) 2.0 (m00915): homing leaves, U-turn, Flower Trick, Petal Blizzard. */
    private static void meowscarada(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch (s.skill) {
            case 0 -> { // Leaf storm: four leaves leave the paw one every other tick and bend onto the lock-on.
                if (t == 1) {
                    s.launch = launchPoint(e, s.direction);
                    s.shots.clear();
                    s.target = lockOn(l, e, s.direction, m.range());
                    cue(e, s, "start", s.launch, s.direction);
                }
                if (t >= 1 && t <= 7 && (t & 1) == 1) {
                    int k = (t - 1) / 2;
                    Vec3 side = new Vec3(-s.direction.z, 0, s.direction.x);
                    Vec3 spawn = s.launch;
                    if (side.lengthSqr() > 1e-4)
                        spawn = spawn.add(side.normalize().scale((k - 1.5) * .26)).add(0, k % 2 == 0 ? .10 : -.06, 0);
                    Shot shot = new Shot();
                    shot.pos = ExpansionGeometry.clipProjectile(l, e, s.launch, spawn);
                    shot.dir = s.direction;
                    if (s.target != null && s.target.isAlive() && BasicAttack.validTarget(s.target, e))
                        shot.target = s.target.getUUID();
                    s.shots.add(shot);
                }
                int index = 0;
                for (Shot shot : s.shots) {
                    index++;
                    if (shot.dead) continue;
                    Vec3 dir = steer(shot, l, e, .18);
                    Vec3 intended = shot.pos.add(dir.scale(1.0));
                    Vec3 next = ExpansionGeometry.clipProjectile(l, e, shot.pos, intended);
                    boolean blocked = next.distanceToSqr(intended) > 1e-4;
                    boolean cut = false;
                    for (LivingEntity target : ExpansionGeometry.swept(l, e, shot.pos, next, m.radius())) {
                        if (!shot.struck.add(target.getUUID())) continue;
                        if (CombatDamage.apply(l, e, target, m.damage(), TypeEffectiveness.moveType(e, 0))) {
                            s.hit.merge(target.getUUID(), 1, Integer::sum);
                            cue(e, s, "leafHit", target.getBoundingBox().getCenter(), dir);
                            cut = true;
                        }
                    }
                    shot.dir = dir;
                    shot.pos = next;
                    if (blocked || next.distanceTo(s.launch) >= m.range()) {
                        shot.dead = true;
                        cue(e, s, "leafFade", next, dir);
                    } else if (!cut) {
                        cue(e, s, "leaf" + (index & 3), next, dir);
                    }
                }
            }
            case 1 -> { // U-turn: cut across, then snap back to where the dash began.
                final int dash = 5;
                if (t == 1) {
                    s.stopped = false;
                    s.markedOnce = false;
                    cue(e, s, "start", e.position(), s.direction);
                }
                if (t <= dash && !s.stopped) {
                    Vec3 from = e.position().add(0, .5, 0);
                    double step = Math.min(m.range() / dash,
                            Math.max(0, m.range() - e.position().distanceTo(s.origin)));
                    ExpansionGeometry.move(e, s.direction.scale(step));
                    Vec3 to = e.position().add(0, .5, 0);
                    for (LivingEntity target : ExpansionGeometry.swept(l, e, from, to, m.radius())) {
                        if (s.hit.getOrDefault(target.getUUID(), 0) >= 1) continue;
                        if (CombatDamage.apply(l, e, target, m.damage(), TypeEffectiveness.moveType(e, 1))) {
                            s.hit.put(target.getUUID(), 1);
                            s.markedOnce = true;
                            cue(e, s, "cut", target.getBoundingBox().getCenter(), s.direction);
                        }
                    }
                    cue(e, s, "trail", e.position().add(0, .8, 0), s.direction);
                }
                if (t == dash) {
                    if (s.markedOnce) {
                        ExpansionControl.haste(e, 30, 1.2);
                        cue(e, s, "haste", e.position().add(0, .9, 0), s.direction);
                    }
                    cue(e, s, "impact", e.position().add(0, .8, 0), s.direction);
                }
                if (t == dash + 1) {
                    Vec3 back = s.origin.subtract(e.position());
                    if (back.lengthSqr() > 1.0E-4) {
                        ExpansionGeometry.move(e, back);
                    }
                    cue(e, s, "blink", e.position().add(0, .9, 0), s.direction);
                }
            }
            case 2 -> { // Flower Trick: a real flying flower, which blooms at 1.5x on contact.
                if (t == 1) {
                    s.launch = launchPoint(e, s.direction);
                    s.projectile = s.launch;
                    s.stopped = false;
                    cue(e, s, "start", s.launch, s.direction);
                }
                if (t > 1 && !s.stopped) {
                    double step = Math.min(1.2, Math.max(0, m.range() - s.projectile.distanceTo(s.launch)));
                    Vec3 intended = s.projectile.add(s.direction.scale(step));
                    Vec3 next = ExpansionGeometry.clipProjectile(l, e, s.projectile, intended);
                    boolean blocked = next.distanceToSqr(intended) > 1e-4;
                    List<LivingEntity> swept = ExpansionGeometry.swept(l, e, s.projectile, next, .45);
                    s.projectile = next;
                    cue(e, s, "flower", next, s.direction);
                    if (!swept.isEmpty() || blocked || step < 1.2) {
                        s.stopped = true;
                        LivingEntity direct = swept.isEmpty() ? null : swept.getFirst();
                        Vec3 center = direct == null ? next : direct.getBoundingBox().getCenter();
                        cue(e, s, "bloom", center, s.direction);
                        if (direct != null) {
                            // Direct victim takes the full crit, everyone else in the bloom 70% of it.
                            for (LivingEntity target : around(l, e, center, m.radius())) {
                                float amount = (target == direct ? m.damage() : m.damage() * .7F) * FLOWER_CRIT;
                                if (hit(l, e, s, target, amount, 1))
                                    cue(e, s, "petalHit", target.getBoundingBox().getCenter(), s.direction);
                            }
                        }
                    }
                }
            }
            default -> { // Petal Blizzard: a storm over the aim point, five pulses three ticks apart.
                if (t == 1) {
                    s.launch = launchPoint(e, s.direction);
                    Vec3 aim = e.position().add(s.direction.scale(m.range()));
                    s.point = ExpansionGeometry.clipProjectile(l, e, s.launch, aim);
                    cue(e, s, "start", s.point, s.direction);
                }
                if (t >= 1 && t <= m.duration() && (t - 1) % 3 == 0) {
                    cue(e, s, "pulse", s.point, s.direction);
                    for (LivingEntity target : around(l, e, s.point, m.radius()))
                        if (hit(l, e, s, target, m.damage(), 5))
                            cue(e, s, "petalHit", target.getBoundingBox().getCenter(), s.direction);
                }
                if (t == m.duration()) cue(e, s, "fade", s.point, s.direction);
            }
        }
    }

    /** Nearest legal enemy roughly along the aim line, used to lock the homing leaves on. */
    private static LivingEntity lockOn(ServerLevel l, PokemonEntity e, Vec3 direction, double range) {
        Vec3 eye = e.getBoundingBox().getCenter();
        LivingEntity best = null;
        double bestDot = .55;
        for (LivingEntity target : around(l, e, e.position().add(direction.scale(range * .5)), range * .5 + 2)) {
            Vec3 to = target.getBoundingBox().getCenter().subtract(eye);
            if (to.lengthSqr() < 1e-4) continue;
            double dot = to.normalize().dot(direction);
            if (dot > bestDot) {
                bestDot = dot;
                best = target;
            }
        }
        return best;
    }

    /** Turn a leaf toward its lock-on by at most {@code maxAngle} radians: no instant reversals. */
    private static Vec3 steer(Shot shot, ServerLevel l, PokemonEntity e, double maxAngle) {
        if (shot.target == null) return shot.dir;
        if (!(l.getEntity(shot.target) instanceof LivingEntity lock) || !BasicAttack.validTarget(lock, e)) {
            shot.target = null;
            return shot.dir;
        }
        Vec3 want = lock.getBoundingBox().getCenter().subtract(shot.pos);
        if (want.lengthSqr() < 1e-4) return shot.dir;
        Vec3 desired = want.normalize();
        double angle = Math.acos(Math.max(-1, Math.min(1, shot.dir.dot(desired))));
        if (angle <= maxAngle) return desired;
        double sin = Math.sin(angle);
        if (sin < 1e-4) return shot.dir;
        return shot.dir.scale(Math.sin(angle - maxAngle) / sin)
                .add(desired.scale(Math.sin(maxAngle) / sin)).normalize();
    }

    /** Authority uses model-scaled emission offsets; clients refine cosmetics using animated locators. */
    private static Vec3 launchPoint(PokemonEntity entity,Vec3 direction) {
        String id=species(entity).id();
        double height=switch(id) {
            case "lucario" -> .65;
            case "gengar" -> .52;
            case "gardevoir" -> .67;
            case "alolan_ninetales" -> .7;
            default -> .6;
        };
        return entity.position().add(0,entity.getBbHeight()*height,0)
                .add(direction.scale(Math.min(.85,entity.getBbWidth()*.5+.2)));
    }
    private static boolean lob(ServerLevel level,PokemonEntity entity,State state,int tick,ExpansionRoster.Move move) {
        return lob(level, entity, state, tick, move, 1);
    }

    /** {@code speed} > 1 shortens the flight so the same arc arrives sooner. */
    private static boolean lob(ServerLevel level,PokemonEntity entity,State state,int tick,ExpansionRoster.Move move,
                               double speed) {
        double fraction=Math.min(1,tick*speed/(double)move.duration());
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
    /** 0.132: Swords Dance lasts eight seconds and never stacks. */
    private static final int DANCE_TICKS = 160;

    private static ExpansionRoster.Move scaled(ExpansionRoster.Move m, float factor) {
        return new ExpansionRoster.Move(m.name(), m.shape(), m.cost(), m.cooldown(), m.windup(),
                m.duration(), m.range(), m.radius(), m.damage() * factor);
    }

    private static void ceruledge(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        float power = (float) ExpansionControl.skillPower(e);
        switch (s.skill) {
            case 0 -> { // 精神利刃: a violet blade thrown straight down the aim line.
                if (t == 1) {
                    Vec3 hand = e.position().add(0, 1.0, 0).add(s.direction.scale(.7));
                    s.launch = hand;
                    s.projectile = hand;
                    s.stopped = false;
                    cue(e, s, "attack", hand, s.direction);
                }
                if (t >= 2) projectile(l, e, s, scaled(m, power), 1.2, .9, 0);
            }
            case 1 -> { // 影子偷袭: sink into the shadow, move for real, then reappear and slash.
                final int dash = 5;
                if (t == 1) {
                    s.stopped = false;
                    cue(e, s, "vanish", e.position(), s.direction);
                }
                if (t <= dash && !s.stopped) {
                    dash(l, e, s, scaled(m, power), t, true);
                    cue(e, s, "wisp", e.position().add(0, .35, 0), s.direction);
                }
                if (t == dash) cue(e, s, "appear", e.position().add(0, .8, 0), s.direction);
                if (t == dash + 1) {
                    for (LivingEntity target : meleeTargets(l, e, s.direction, m)) {
                        meleeHit(l, e, s, target, m.damage() * power, 1);
                    }
                    cue(e, s, "cross", e.position().add(0, .9, 0), s.direction);
                }
            }
            case 2 -> { // 悔念剑: three separate swings, each healing for 40% of the health it took.
                int[] at = {1, 4, 7};
                for (int i = 0; i < at.length; i++) {
                    if (t != at[i]) continue;
                    float amount = (i == 2 ? 30F : 25F) * power;
                    cue(e, s, i == 2 ? "cross" : (i == 0 ? "slash_l" : "slash_r"), e.position(), s.direction);
                    for (LivingEntity target : meleeTargets(l, e, s.direction, m)) {
                        float before = target.getHealth();
                        if (!hit(l, e, s, target, amount, i + 1)) continue;
                        float dealt = Math.max(0, before - target.getHealth());
                        if (dealt <= 0) continue;
                        cue(e, s, "drain", target.getBoundingBox().getCenter(), s.direction);
                        // Only the health that was actually lost comes back, and never past the cap.
                        e.setHealth(Math.min(e.getMaxHealth(), e.getHealth() + dealt * .4F));
                        cue(e, s, "heal", e.position().add(0, .9, 0), s.direction);
                    }
                }
            }
            default -> { // 剑舞: cross the blades, then burn for eight seconds (+25% attacks, +20% skills).
                if (t == 1) {
                    ExpansionControl.dance(e, DANCE_TICKS);
                    buffCue(e, "blades", DANCE_TICKS);
                    cue(e, s, "buff", e.position(), s.direction);
                    cue(e, s, "dance", e.position(), s.direction);
                }
                if (t == 2) finish(e, s);
            }
        }
    }


    /** 0.133 Hisuian Zoroark: claw dash, malice ring, shadow ball and the three-illusion ultimate. */
    private static void hisuianZoroark(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        float power = (float) ExpansionControl.skillPower(e);
        switch (s.skill) {
            case 0 -> { // 暗影爪: crouch, then a real 4.5 block dash that cuts once, ending in a cross slash.
                final int dashTicks = 5;
                if (t == 1) {
                    s.stopped = false;
                    cue(e, s, "sink", e.position(), s.direction);
                }
                if (t <= dashTicks && !s.stopped) {
                    Vec3 from = e.position().add(0, .5, 0);
                    double step = Math.min(m.range() / dashTicks,
                            Math.max(0, m.range() - e.position().distanceTo(s.origin)));
                    ExpansionGeometry.move(e, s.direction.scale(step));
                    Vec3 to = e.position().add(0, .5, 0);
                    for (LivingEntity target : ExpansionGeometry.swept(l, e, from, to, .75)) {
                        if (!hit(l, e, s, target, claws(e, target, m.damage() * power), 1)) continue;
                        Malice.mark(l, e, target);
                        cue(e, s, "claw", target.getBoundingBox().getCenter(), s.direction);
                    }
                    cue(e, s, "wisp", e.position().add(0, .35, 0), s.direction);
                }
                if (t == dashTicks + 1) {
                    cue(e, s, "cross", e.position().add(0, .9, 0), s.direction);
                    Vec3 center = e.position().add(s.direction.scale(1.0));
                    for (LivingEntity target : around(l, e, center, 1.0)) {
                        if (!hit(l, e, s, target, claws(e, target, m.damage() * power), 1)) continue;
                        Malice.mark(l, e, target);
                    }
                }
            }
            case 1 -> { // 冤冤相报: the grudge ring bursts out of the body, slows and brands.
                if (t == 1) cue(e, s, "gather", e.position(), s.direction);
                if (t == 2) {
                    cue(e, s, "burst", e.position(), s.direction);
                    for (LivingEntity target : around(l, e, e.position().add(0, .5, 0), m.radius())) {
                        if (!hit(l, e, s, target, claws(e, target, m.damage() * power), 1)) continue;
                        Malice.mark(l, e, target);
                        ExpansionControl.slow(e, target, 24, .8);
                    }
                }
            }
            case 2 -> { // 暗影球: a real flying orb, 55 on the body and 35 in the blast ring.
                if (t == 1) {
                    Vec3 hand = e.position().add(0, 1.0, 0).add(s.direction.scale(.7));
                    s.launch = hand;
                    s.projectile = hand;
                    s.stopped = false;
                    cue(e, s, "charge", hand, s.direction);
                }
                if (t >= 2 && !s.stopped) {
                    double lane = s.origin.y + .55;
                    double descent = Math.max(-.22, Math.min(.12, lane - s.projectile.y));
                    double remaining = Math.max(0, m.range() - s.projectile.subtract(s.launch).horizontalDistance());
                    Vec3 intended = s.projectile.add(s.direction.scale(Math.min(1.2, remaining))).add(0, descent, 0);
                    Vec3 next = ExpansionGeometry.clipProjectile(l, e, s.projectile, intended);
                    List<LivingEntity> targets = ExpansionGeometry.swept(l, e, s.projectile, next, .6);
                    boolean blocked = next.distanceToSqr(intended) > .0001;
                    s.projectile = next;
                    cue(e, s, "projectile", next, s.direction);
                    if (!targets.isEmpty()) {
                        LivingEntity first = targets.getFirst();
                        Vec3 impact = first.getBoundingBox().getCenter();
                        for (LivingEntity target : around(l, e, impact, 2.5)) {
                            float base = (target == first ? 55F : 35F) * power;
                            if (!hit(l, e, s, target, claws(e, target, base), 1)) continue;
                            Malice.mark(l, e, target);
                        }
                        s.stopped = true;
                        cue(e, s, "impact", impact, s.direction);
                    } else if (blocked || remaining <= 1.2) s.stopped = true;
                }
            }
            default -> { // 幻象分身: three real clones fan out, then the body lands one 90 damage cross.
                if (t == 1) cue(e, s, "gather", e.position(), s.direction);
                if (t == 2) {
                    Illusions.spawn(l, e, 3);
                    cue(e, s, "split", e.position(), s.direction);
                }
                if (t > 2 && t % 8 == 0 && t < 76) cue(e, s, "orbit", e.position(), s.direction);
                if (t == 76) {
                    cue(e, s, "rush", e.position(), s.direction);
                }
                if (t == 80) {
                    cue(e, s, "final", e.position(), s.direction);
                    Vec3 center = e.position().add(s.direction.scale(3.0));
                    for (LivingEntity target : around(l, e, center, 3.0)) {
                        if (!hit(l, e, s, target, claws(e, target, m.damage() * power), 1)) continue;
                        Malice.mark(l, e, target);
                        if (stacks(target) >= 3) cue(e, s, "detonate", target.getBoundingBox().getCenter(), s.direction);
                    }
                }
                if (t >= 82) Illusions.clear(e);
            }
        }
    }

    /** Skill damage after the victim's malice stacks; the server is the only thing that can add them. */
    private static float claws(PokemonEntity e, LivingEntity target, float base) {
        return (float) (base * Malice.multiplier(e, target));
    }

    private static int stacks(LivingEntity target) {
        return Malice.multiplier(target, target) > 1 ? 3 : 0;
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
    /** Freeze-Dry's signature: water defenders take double instead of resisting. */
    private static boolean waterTarget(LivingEntity target) {
        if (!(target instanceof PokemonEntity pokemon) || pokemon.getPokemon() == null) return false;
        String path = pokemon.getPokemon().getSpecies().getResourceIdentifier().getPath();
        return TypeEffectiveness.waterDefender(path);
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
            float amount=s.deferDamage ? 0 : m.damage();
            if(amount==0 || (hit(l,e,s,target,amount,1) && (species(e).id().equals("lucario") || species(e).id().equals("blaziken") || species(e).id().equals("gengar") || true))) {
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
        double mult = ExpansionControl.movementMultiplier(e)*ExpansionControl.hasteMultiplier(e)
                *(s!=null && s.speed>0?1.25:1);
        var type = species(e);
        if(type!=null && type.id().equals("alolan_ninetales")) mult*=1.3;
        return mult;
    }
    /**
     * 0.134 Krookodile. Crunch lunges a short step and bites a lane 3.5 blocks long, breaking the
     * victim's guard; Dig burrows along real collision for 7 blocks and bursts out in a 2 block
     * ring; Earthquake lands once in a placed 4 block zone and slows what it hits; Outrage is five
     * independently re-aimed swings, then the original move's one second of fatigue.
     */
    /**
     * 0.135 Armarouge: fire/psychic artillery. Q is a piercing psyshock lance, W a real flame dash
     * that only grants its speed buff when it connects, E the twin arm cannons (55 direct / 35
     * splash), R a five pulse Expanding Force field that re-rolls its hit table every pulse so a
     * target that steps out stops taking damage and a target that steps back in gets hit again.
     */
    private static void armarouge(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch (s.skill) {
            case 0 -> { // 精神冲击: a psyshock lance that pierces, one hit per target, walls stop it.
                if (t == 1) {
                    s.projectile = launchPoint(e, s.direction);
                    s.launch = s.projectile;
                    cue(e, s, "start", s.projectile, s.direction);
                } else if (!s.stopped && t <= m.duration()) {
                    double step = Math.min(1.3, Math.max(0, m.range() - s.projectile.distanceTo(s.launch)));
                    Vec3 intended = s.projectile.add(s.direction.scale(step));
                    Vec3 next = ExpansionGeometry.clipProjectile(l, e, s.projectile, intended);
                    for (LivingEntity target : ExpansionGeometry.swept(l, e, s.projectile, next, .8)) {
                        if (hit(l, e, s, target, m.damage(), 1)) {
                            cue(e, s, "impact", target.getBoundingBox().getCenter(), s.direction);
                        }
                    }
                    s.projectile = next;
                    cue(e, s, "psy", next, s.direction);
                    if (next.distanceToSqr(intended) > 1E-4 || step <= 0) s.stopped = true;
                    if (s.stopped) finish(e, s);
                }
            }
            case 1 -> { // 火焰旋涡: six ticks of real movement, damage and the buff only on a hit.
                if (t == 1) {
                    s.stopped = false;
                    cue(e, s, "start", e.position(), s.direction);
                }
                if (t <= 6 && !s.stopped) {
                    Vec3 from = e.position().add(0, .5, 0);
                    double step = Math.min(m.range() / 6.0,
                            Math.max(0, m.range() - e.position().distanceTo(s.origin)));
                    ExpansionGeometry.move(e, s.direction.scale(step));
                    Vec3 to = e.position().add(0, .5, 0);
                    for (LivingEntity target : ExpansionGeometry.swept(l, e, from, to, 1.5)) {
                        if (hit(l, e, s, target, m.damage(), 1)) {
                            ExpansionControl.haste(e, 30, 1.2);
                            target.push(s.direction.x * .2, .15, s.direction.z * .2);
                            cue(e, s, "impact", target.getBoundingBox().getCenter(), s.direction);
                        }
                    }
                    cue(e, s, "flame", e.position().add(0, .6, 0), s.direction);
                    if (e.position().distanceTo(s.origin) >= m.range() - .12) s.stopped = true;
                }
            }
            case 2 -> { // 铠甲炮: one heavy shell, 55 on the body, 35 in the 2.5 block blast.
                if (t == 1) {
                    s.projectile = launchPoint(e, s.direction);
                    s.launch = s.projectile;
                    cue(e, s, "start", s.projectile, s.direction);
                } else if (!s.stopped && t <= m.duration()) {
                    double step = Math.min(1.0, Math.max(0, m.range() - s.projectile.distanceTo(s.launch)));
                    Vec3 intended = s.projectile.add(s.direction.scale(step));
                    Vec3 next = ExpansionGeometry.clipProjectile(l, e, s.projectile, intended);
                    List<LivingEntity> swept = ExpansionGeometry.swept(l, e, s.projectile, next, 1.0);
                    LivingEntity direct = swept.isEmpty() ? null : swept.getFirst();
                    boolean blocked = next.distanceToSqr(intended) > 1E-4 || step <= 0;
                    s.projectile = next;
                    cue(e, s, "cannon", next, s.direction);
                    if (direct != null || blocked) {
                        Vec3 centre = direct != null ? direct.getBoundingBox().getCenter() : next;
                        if (direct != null) hit(l, e, s, direct, m.damage(), 1);
                        for (LivingEntity target : around(l, e, centre, 2.5)) {
                            if (target != direct) hit(l, e, s, target, 35F, 1);
                        }
                        cue(e, s, "impact", centre, s.direction);
                        s.stopped = true;
                        finish(e, s);
                    }
                }
            }
            default -> { // 扩展力量: five independent pulses, each with its own hit table.
                if (t == 1) {
                    cue(e, s, "start", s.point, s.direction);
                    cue(e, s, "rune", s.point, s.direction);
                }
                if (t <= 13 && (t - 1) % 3 == 0) {
                    s.hit.clear();
                    int pulse = ((t - 1) / 3) + 1;
                    for (LivingEntity target : around(l, e, s.point, m.radius())) {
                        if (hit(l, e, s, target, m.damage(), 1)) {
                            cue(e, s, "pulse" + pulse, target.getBoundingBox().getCenter(), s.direction);
                        }
                    }
                    cue(e, s, "pulse" + pulse, s.point, s.direction);
                    // Only the fifth pulse is allowed to shake the camera.
                    if (pulse == 5) cue(e, s, "quake", s.point, s.direction);
                }
            }
        }
    }

    private static void krookodile(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch (s.skill) {
            case 0 -> { // Crunch
                if (t == 1) {
                    s.stopped = false;
                    cue(e, s, "bite_wind", e.position().add(0, .9, 0), s.direction);
                }
                if (t <= 3 && !s.stopped) {
                    ExpansionGeometry.move(e, s.direction.scale(.55));
                    cue(e, s, "bite_step", e.position().add(0, .8, 0), s.direction);
                }
                if (t == 4) {
                    Vec3 from = e.position().add(0, .8, 0);
                    Vec3 to = from.add(s.direction.scale(m.range()));
                    boolean landed = false;
                    for (LivingEntity target : ExpansionGeometry.swept(l, e, from, to, m.radius())) {
                        if (hit(l, e, s, target, m.damage(), 1)) {
                            ExpansionControl.defenseDown(target, 60, .10);
                            landed = true;
                            cue(e, s, "bite", target.getBoundingBox().getCenter(), s.direction);
                        }
                    }
                    cue(e, s, landed ? "impact" : "bite_miss", from.add(s.direction.scale(1.6)), s.direction);
                }
                if (t >= m.duration()) finish(e, s);
            }
            case 1 -> { // Dig
                if (t == 1) {
                    s.stopped = false;
                    cue(e, s, "dig_down", e.position(), s.direction);
                }
                if (t <= 8 && !s.stopped) {
                    double step = Math.min(.9, Math.max(0, m.range() - e.position().distanceTo(s.origin)));
                    ExpansionGeometry.move(e, s.direction.scale(step));
                    cue(e, s, "dig_move", e.position(), s.direction);
                }
                if (t == 9) {
                    cue(e, s, "dig_up", e.position(), s.direction);
                    for (LivingEntity target : around(l, e, e.position(), m.radius())) {
                        if (hit(l, e, s, target, m.damage(), 1)) {
                            target.setDeltaMovement(0, .48, 0);
                            target.hurtMarked = true;
                            cue(e, s, "dig_hit", target.getBoundingBox().getCenter(), s.direction);
                        }
                    }
                }
                if (t == 10) cue(e, s, "impact", e.position(), s.direction);
                if (t >= m.duration()) finish(e, s);
            }
            case 2 -> { // Earthquake
                if (t <= 6 && t % 2 == 1) cue(e, s, "quake_charge", s.point, s.direction);
                if (t == 7) {
                    cue(e, s, "quake", s.point, s.direction);
                    for (LivingEntity target : around(l, e, s.point, m.radius())) {
                        if (hit(l, e, s, target, m.damage(), 1)) {
                            ExpansionControl.slow(e, target, 24, .8);
                            cue(e, s, "quake_hit", target.getBoundingBox().getCenter(), s.direction);
                        }
                    }
                }
                if (t >= m.duration()) finish(e, s);
            }
            default -> { // Outrage
                if (t == 1) {
                    s.hit.clear();
                    cue(e, s, "rage_start", e.position(), s.direction);
                }
                int[] beats = {1, 11, 21, 31, 41};
                for (int i = 0; i < beats.length; i++) {
                    if (t != beats[i]) continue;
                    // The player may steer between swings: every beat re-reads the current aim.
                    Vec3 aim = AimTracker.aimOf(e);
                    if (aim != null) s.direction = ExpansionGeometry.direction(e, aim);
                    s.hit.clear();
                    Vec3 from = e.position().add(0, .8, 0);
                    Vec3 to = from.add(s.direction.scale(m.range()));
                    cue(e, s, i == beats.length - 1 ? "rage_final" : "rage_hit" + i, from, s.direction);
                    for (LivingEntity target : ExpansionGeometry.swept(l, e, from, to, m.radius())) {
                        if (hit(l, e, s, target, m.damage(), 1))
                            cue(e, s, "rage_land", target.getBoundingBox().getCenter(), s.direction);
                    }
                }
                if (t == 47) {
                    // Outrage's own drawback: one second at 85% speed, control never taken away.
                    ExpansionControl.slow(e, e, 20, .85);
                    cue(e, s, "tired", e.position(), s.direction);
                }
                if (t >= m.duration()) finish(e, s);
            }
        }
    }

    private static void finish(PokemonEntity e, State s) {
        // Gardevoir's Psychic pull resolves as an independent field (s.pull); sending "end" here
        // used to wipe its visuals the moment a second skill (Moonblast) was cast.
        boolean keepScene = s.skill == 0 && "gardevoir".equals(species(e) == null ? "" : species(e).id());
        if(s.skill>=0 && !keepScene) cue(e,s,"end",e.position(),s.direction);
        s.skill=-1; CastLock.release(e);
    }
    public static void clearTransient(PokemonEntity e) {
        State s=STATES.get(e.getUUID());
        if(s!=null) {
            cue(e,s,"clear",e.position(),Vec3.ZERO);
            finish(e,s); s.buff=0;s.speed=0;s.sand=0;s.snow=0;s.poison.clear();s.hit.clear();s.target=null;
            s.moonDue=0;s.moonPoint=Vec3.ZERO;
        }
        // A retreat, faint or match end must not leave live marks behind for the next round.
        if (s != null) { s.aura = 0; s.auraExpire = 0; s.bonus = 1F; }
        MARKS.values().removeIf(mark -> mark.caster().equals(e.getUUID()));
        MARKS.remove(e.getUUID());
        Malice.clear(e.getUUID());
        Illusions.clear(e);
        ExpansionControl.removeEntity(e.getUUID()); ExpansionWalls.remove(e.getUUID());
    }
    public static void reset(PokemonEntity e) { if(e!=null) {clearTransient(e);STATES.remove(e.getUUID());} }
    public static void clearReserve(UUID owner,String speciesId) {
        var species=ExpansionRoster.resolve(speciesId);
        if(owner!=null && species!=null)RESERVES.remove(new ReserveKey(owner,species.id()));
    }
    public static void clearReserves(UUID owner) {if(owner!=null)RESERVES.keySet().removeIf(key->key.owner().equals(owner));}
    public static void resetAll() {STATES.clear();RESERVES.clear();MARKS.clear();Malice.clear();Illusions.clearAll();ExpansionControl.resetAll();ExpansionWalls.resetAll();}
    public static void syncHud(ServerPlayer player,PokemonEntity e) {
        // 0.139.4 (m03841)：人机释放技能时 player 是 match.actor（玩家本人），但那份连接可能已被
        // 换掉/还没建好，Fabric 的 canSend 会在内部 NPE（Cannot read field "field_14140" because
        // "handler" is null）把整个服务端线程打崩。这里自己判连接，异常一律当作“发不了”。
        if(player==null || player.connection == null || e == null || !sendsSkillState(player)) return;
        State s=state(e); Vec3 c=CharizardCombat.arenaCenter();
        var m=s.skill<0?null:species(e).move(s.skill);
        float progress=m==null?0:Math.min(1,s.age/(float)Math.max(1,m.windup()));
        ServerPlayNetworking.send(player,new SkillStatePayload(e.getHealth(),e.getMaxHealth(),s.points,
            s.cd[0],s.cd[1],s.cd[2],s.cd[3],channel(e),progress,CharizardCombat.distanceToBorder(e),
            (float)c.x,(float)c.y,(float)c.z,(float)CharizardCombat.arenaRadius()));
    }
    /** 0.139.4：canSend 包一层，第三方 mod（Flashback 等）注入过的变体可能抛异常。 */
    private static boolean sendsSkillState(ServerPlayer player) {
        try {
            return ServerPlayNetworking.canSend(player, SkillStatePayload.TYPE);
        } catch (RuntimeException error) {
            return false;
        }
    }
    /** 0.87: tells the owning client that its fighter has a timed self buff, so the HUD can show it. */
    static void buffCue(PokemonEntity e, String label, int ticks) {
        if (!(e.level() instanceof ServerLevel level)) return;
        var payload = new EffectPayload("pkmoba:buff/" + label + "/" + ticks, e.getId(), 0, 0, 0, 0, 0);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(e) < 128 * 128 && ServerPlayNetworking.canSend(player, EffectPayload.TYPE))
                ServerPlayNetworking.send(player, payload);
        }
    }

    /** 0.133.6: illusions cast their own Shadow Claw, so they need the same cue path. */
    public static void illusionCue(PokemonEntity ghost, int skill, String phase, Vec3 p, Vec3 d) {
        if (ghost != null) cue(ghost, skill, phase, p, d);
    }

    private static void cue(PokemonEntity e,State s,String phase,Vec3 p,Vec3 d) { cue(e,Math.max(0,s.skill),phase,p,d); }
    private static void cue(PokemonEntity e,int skill,String phase,Vec3 p,Vec3 d) {
        if(!(e.level() instanceof ServerLevel level) || !ExpansionGeometry.finite(p) || !ExpansionGeometry.finite(d)) return;
        // 0.102 (m15731): voice every move - a cast sound on "start" and a matching hit sound on
        // "impact"/"field" so each skill is audible, not just visible.
        ExpansionRoster.Species soundSpecies = species(e);
        if (soundSpecies != null) {
            if (phase.equals("start")) {
                SkillSounds.cast(level, e, soundSpecies.id(), skill);
            } else if (phase.equals("impact") || phase.equals("field") || phase.equals("quake")
                    || phase.equals("melee") || phase.equals("hit") || phase.equals("brand")
                    || phase.equals("detonate") || phase.equals("haste")) {
                SkillSounds.impact(level, e, soundSpecies.id(), skill);
            }
        }
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
        LivingEntity target; boolean stopped,recoil,dream,deferDamage,markedOnce;
        float spin; double chargeLeft;
        /** 0.124 Greninja Q: one entry per thrown water shuriken, each flown independently. */
        final java.util.List<Shot> shots = new java.util.ArrayList<>();
        /** 0.124: last tick a hit voice played, so rapid multi-hits do not rattle. */
        int sfx;
        /** 0.78: a released Moonblast lands on its own clock, independent of the running skill. */
        long moonDue; Vec3 moonPoint=Vec3.ZERO;
        /** 0.101: a sludge pool that outlives the bomb (ten seconds) and who has been poisoned by it. */
        int sludge; Vec3 sludgePoint=Vec3.ZERO;
        /** 0.103: autonomous Psychic well (keeps running even if another skill is cast). */
        int pull; Vec3 pullPoint=Vec3.ZERO;
        final java.util.Set<java.util.UUID> sludgePoisoned=new java.util.HashSet<>();
        /** Victims already given one hypnosis mark by the current spray. */
        final java.util.Set<java.util.UUID> marked = new java.util.HashSet<>();
    }
}
