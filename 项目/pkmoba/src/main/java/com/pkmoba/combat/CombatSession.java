package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Per-controlled-Pokemon session identity and lifecycle boundary.
 *
 * <p>The combat engines keep their legacy implementation details private, but
 * all future state routing is keyed by this stable entity/owner pair rather
 * than by a process-wide "active" Pokemon.  This small value object is also
 * safe to create before the entity is registered by a battle room.</p>
 */
public final class CombatSession {
    private final UUID entityId;
    private UUID ownerId;
    private PokemonEntity entity;
    private Vec3 lastAim = Vec3.ZERO;
    private boolean disposed;

    public CombatSession(PokemonEntity entity, UUID ownerId) {
        if (entity == null) throw new IllegalArgumentException("entity");
        this.entity = entity;
        this.entityId = entity.getUUID();
        this.ownerId = ownerId;
    }

    public UUID entityId() { return entityId; }
    public UUID ownerId() { return ownerId; }
    public PokemonEntity entity() { return entity; }
    public Vec3 lastAim() { return lastAim; }
    public boolean disposed() { return disposed; }

    public void bind(PokemonEntity entity, UUID ownerId) {
        if (entity == null || !entityId.equals(entity.getUUID())) {
            throw new IllegalArgumentException("entity identity");
        }
        this.entity = entity;
        if (ownerId != null) this.ownerId = ownerId;
        this.disposed = false;
    }

    public void aim(Vec3 aim) {
        if (aim != null && Double.isFinite(aim.x) && Double.isFinite(aim.y) && Double.isFinite(aim.z)) {
            this.lastAim = aim;
        }
    }

    public void dispose() {
        disposed = true;
        entity = null;
        lastAim = Vec3.ZERO;
    }
}
