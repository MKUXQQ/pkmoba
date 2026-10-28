package com.pkmoba.client.fx;

import com.pkmoba.client.ExpansionAnchors;
import net.minecraft.world.phys.Vec3;

/** Per-cue view data handed to a per-skill visual class. */
public final class FxView {
    public final Fx fx;
    public final ExpansionAnchors.Set anchors;
    public final int skill;
    public final String phase;
    public final Vec3 pos;
    public final Vec3 dir;
    public final Vec3 owner;
    public final double time;
    public final double progress;
    public final double age;
    public final double radius;
    public final float fade;

    public FxView(Fx fx, ExpansionAnchors.Set anchors, int skill, String phase, Vec3 pos, Vec3 dir,
                  Vec3 owner, double time, double progress, double age, double radius, float fade) {
        this.fx = fx;
        this.anchors = anchors;
        this.skill = skill;
        this.phase = phase;
        this.pos = pos;
        this.dir = dir;
        this.owner = owner;
        this.time = time;
        this.progress = progress;
        this.age = age;
        this.radius = radius;
        this.fade = fade;
    }

    public boolean is(String want) {
        return phase.equals(want);
    }

    public boolean in(String... wants) {
        for (String w : wants) if (phase.equals(w)) return true;
        return false;
    }

    public Vec3 chest() { return anchors == null ? owner.add(0, 1, 0) : anchors.chest(); }
    public Vec3 head() { return anchors == null ? owner.add(0, 1.6, 0) : anchors.head(); }
    public Vec3 hand() { return anchors == null ? owner.add(0, 1, 0) : anchors.handPrimary(); }
    public Vec3 offHand() { return anchors == null ? owner.add(0, 1, 0) : anchors.handSecondary(); }
    public Vec3 feet() { return anchors == null ? owner : anchors.footPrimary(); }

    /** Point in front of the caster's chest used for release bursts. */
    public Vec3 front(double distance) {
        return chest().add(dir.scale(distance));
    }
}
