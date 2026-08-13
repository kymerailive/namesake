package net.namesake.verb;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** The production {@link VerbSender}: a thin read-only view of a connected player. */
public record ServerVerbSender(ServerPlayer player) implements VerbSender {

    @Override
    public UUID id() {
        return player.getUUID();
    }

    @Override
    public String name() {
        return player.getGameProfile().getName();
    }

    @Override
    public ResourceKey<Level> dimension() {
        return player.level().dimension();
    }

    @Override
    public Vec3 eyePosition() {
        return player.getEyePosition();
    }

    @Override
    public double interactionRange() {
        return player.entityInteractionRange();
    }

    @Override
    public long tickCount() {
        return player.serverLevel().getServer().getTickCount();
    }
}
