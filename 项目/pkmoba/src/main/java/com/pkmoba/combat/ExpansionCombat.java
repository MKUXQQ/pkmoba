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
        if (s == null || s.skill < 0) return false;
        // Sandstorm and deployed snow fields do not root their caster for their whole lifespan.
        String id = species(entity).id();
        return !(id.equals("tyranitar") && s.skill == 2 && s.age > species(entity).move(2).windup());
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
        MoveOrderService.cancelPending(entity); entity.getNavigation().stop(); entity.setDeltaMovement(Vec3.ZERO);
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
                if(CombatDamage.apply(level,entity,target,8,TypeEffectiveness.MoveType.ICE))
                    ExpansionControl.slow(entity,target,15,Math.max(.4,.8-(80-s.snow)*.005));
            }
            s.snow--;
        }
        tickPoison(level, entity, s);
        if (DuelManager.isPreparing(entity) || !entity.isAlive()) { clearTransient(entity); return; }
        if (ExpansionControl.sleeping(entity)) {if(s.skill>=0)finish(entity,s);return;}
        if (s.skill < 0) return;
        var move = species(entity).move(s.skill);
        s.age++;
        FacingService.request(entity, FacingService.Priority.CHANNEL, s.direction, 360);
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
            case 0 -> projectile(l,e,s,m, .8, 1.4, 0);
            case 1 -> { if (t == 1) for (LivingEntity target : cone(l,e,s,m)) if (hit(l,e,s,target,m.damage(),1)) ExpansionControl.slow(e,target,30,.65); }
            case 2 -> dash(l,e,s,m,t,true);
            case 3 -> {
                if (s.target == null && t <= 10) dash(l,e,s,m,t,true);
                LivingEntity target = s.target;
                if (target != null && target.isAlive() && e.distanceTo(target) <= 3.5 && ExpansionGeometry.visible(l,e,e.getBoundingBox().getCenter(),target.getBoundingBox().getCenter()) && t % 5 == 0) {
                    if (hit(l,e,s,target,11,6) && s.hit.getOrDefault(target.getUUID(),0) == 6)
                        target.push(s.direction.x * .65,.12,s.direction.z * .65);
                }
            }
        }
    }
    private static void abomasnow(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch (s.skill) {
            case 0 -> projectile(l,e,s,m,.7,.8,30);
            case 1 -> { if (t % 4 == 1) for (LivingEntity target : cone(l,e,s,m)) if (hit(l,e,s,target,6,5)) ExpansionControl.slow(e,target,8,.65); }
            case 2 -> { /* Temporary collision volume is maintained by ExpansionWalls, not map blocks. */
                if (t == 1 && ExpansionWalls.create(e,s.point,s.direction,m.radius(),m.duration()))
                    cue(e,s,"wall",s.point,s.direction);
            }
            case 3 -> { if(t==1) {
                s.snow=m.duration();s.snowPoint=s.point;cue(e,s,"field",s.point,s.direction);finish(e,s);
            } }
        }
    }
    private static void blaziken(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch (s.skill) {
            case 0 -> { if (t == 1) { ExpansionGeometry.move(e,s.direction.scale(.5)); coneHit(l,e,s,m,28,1); } }
            case 1 -> { if (t == 1 || t == 10) { if(t == 10) ExpansionGeometry.move(e,s.direction.scale(.65)); coneHit(l,e,s,m,18,2); } }
            case 2 -> { dash(l,e,s,m,t,false); if(t == m.duration()) s.speed = 60; }
            case 3 -> { dash(l,e,s,m,t,true); if(s.target != null && !s.recoil) {
                s.recoil = true; e.setHealth(Math.max(1,e.getHealth() - 8));
            } }
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
            case 2 -> { if(t == 1) for(LivingEntity target : cone(l,e,s,m)) ExpansionControl.sleep(e,target,30); }
            case 3 -> {
                dash(l,e,s,m,t,true);
                if(s.target != null && !s.recoil) {
                    s.recoil = true;
                    // Sleep eligibility is captured before the initial hit wakes the target.
                    if(s.dream) { CombatDamage.apply(l,e,s.target,18,TypeEffectiveness.MoveType.PSYCHIC); e.heal(12); cue(e,s,"heal",s.target.position(),e.position()); }
                }
            }
        }
    }
    private static void tyranitar(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch(s.skill) {
            case 0 -> { if(t == 1) for(LivingEntity target : around(l,e,s.point,m.radius()))
                if(hit(l,e,s,target,m.damage(),1)) ExpansionControl.slow(e,target,30,.65); }
            case 1 -> { if(t == 1) { ExpansionGeometry.move(e,s.direction.scale(.4)); coneHit(l,e,s,m,m.damage(),1); } }
            case 2 -> { if(t==1) {s.sand=m.duration();cue(e,s,"field",e.position(),s.direction);finish(e,s);} }
            case 3 -> { if(t % 5 == 1) {
                Vec3 spike = s.origin.add(s.direction.scale(2 + (t/5)*2.4));
                cue(e,s,"spike",spike,s.direction);
                for(LivingEntity target : around(l,e,spike,m.radius())) {
                    int n=s.hit.getOrDefault(target.getUUID(),0);
                    hit(l,e,s,target,n == 0 ? 30 : n == 1 ? 18 : 12,4);
                }
            } }
        }
    }
    private static void garchomp(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch(s.skill) {
            case 0 -> { if(t == 1 || t == 6) coneHit(l,e,s,m,15,2); }
            case 1 -> { if(t == 1) for(LivingEntity target : around(l,e,e.position(),m.radius()))
                if(hit(l,e,s,target,m.damage(),1)) ExpansionControl.slow(e,target,35,.6); }
            case 2 -> {
                if(t < m.duration()) ExpansionGeometry.move(e,s.direction.scale(m.range()/(m.duration()-1)));
                if(t == m.duration()) for(LivingEntity target : around(l,e,e.position(),m.radius())) hit(l,e,s,target,m.damage(),1);
            }
            case 3 -> { dash(l,e,s,m,t,true); if(s.target != null && !s.recoil) {
                s.recoil=true; s.target.push(s.direction.x*.7,.15,s.direction.z*.7);
            } }
        }
    }
    private static void scizor(ServerLevel l, PokemonEntity e, State s, int t, ExpansionRoster.Move m) {
        switch(s.skill) {
            case 0 -> { dash(l,e,s,m,t,true); if(s.buff>0 && s.target != null && !s.recoil && t>=5 && e.distanceTo(s.target)<3.5
                    && ExpansionGeometry.visible(l,e,e.getBoundingBox().getCenter(),s.target.getBoundingBox().getCenter())) {
                s.recoil=true; hit(l,e,s,s.target,10,2); cue(e,s,"followup",s.target.position(),s.direction);
            } }
            case 1 -> { if(t==1 || t==8 || t==15) {
                for(LivingEntity target : cone(l,e,s,m)) {
                    int n=s.hit.getOrDefault(target.getUUID(),0);
                    hit(l,e,s,target,10+n*2+(s.buff>0 && t==15?8:0),3);
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
            float amount=species(e).id().equals("lucario") && s.skill==3 ? 0 : m.damage();
            if(amount==0 || hit(l,e,s,target,amount,1)) { s.target=target; if(stopOnHit) {s.stopped=true; break;} }
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
        ExpansionControl.removeEntity(e.getUUID()); ExpansionWalls.remove(e.getUUID());
    }
    public static void reset(PokemonEntity e) { if(e!=null) {clearTransient(e);STATES.remove(e.getUUID());} }
    public static void clearReserve(UUID owner,String speciesId) {
        var species=ExpansionRoster.find(speciesId);
        if(owner!=null && species!=null)RESERVES.remove(new ReserveKey(owner,species.id()));
    }
    public static void clearReserves(UUID owner) {if(owner!=null)RESERVES.keySet().removeIf(key->key.owner().equals(owner));}
    public static void resetAll() {STATES.clear();RESERVES.clear();ExpansionControl.resetAll();ExpansionWalls.resetAll();}
    public static void syncHud(ServerPlayer player,PokemonEntity e) {
        if(player==null || !ServerPlayNetworking.canSend(player,SkillStatePayload.TYPE)) return;
        State s=state(e); Vec3 c=CharizardCombat.arenaCenter();
        var m=s.skill<0?null:species(e).move(s.skill);
        float progress=m==null?0:Math.min(1,s.age/(float)Math.max(1,m.windup()));
        ServerPlayNetworking.send(player,new SkillStatePayload(e.getHealth(),e.getMaxHealth(),s.points,
            s.cd[0],s.cd[1],s.cd[2],s.cd[3],channel(e),progress,CharizardCombat.distanceToBorder(e),
            (float)c.x,(float)c.y,(float)c.z,(float)CharizardCombat.arenaRadius()));
    }
    private static void cue(PokemonEntity e,State s,String phase,Vec3 p,Vec3 d) {
        if(!(e.level() instanceof ServerLevel level) || !ExpansionGeometry.finite(p) || !ExpansionGeometry.finite(d)) return;
        String key="pkmoba:expansion/"+species(e).id()+"/"+Math.max(0,s.skill)+"/"+phase+"/"+e.getId();
        var payload=new EffectPayload(key,p.x,p.y,p.z,d.x,d.y,d.z);
        for(ServerPlayer player:level.players()) if(player.distanceToSqr(p)<128*128 && ServerPlayNetworking.canSend(player,EffectPayload.TYPE)) ServerPlayNetworking.send(player,payload);
    }
    private static final class State {
        int skill=-1,age,regen,buff,speed,sand,snow; float points=10;
        final int[] cd=new int[4];
        final Map<UUID,Integer> hit=new HashMap<>(),poison=new HashMap<>();
        Vec3 origin=Vec3.ZERO,direction=new Vec3(0,0,1),point=Vec3.ZERO,projectile=Vec3.ZERO,launch=Vec3.ZERO,snowPoint=Vec3.ZERO;
        LivingEntity target; boolean stopped,recoil,dream;
    }
}
