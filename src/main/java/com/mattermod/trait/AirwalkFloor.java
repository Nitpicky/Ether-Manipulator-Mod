package com.mattermod.trait;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Airwalk's floor as a real surface for the local player: the movement collision stops a fall at
 * the floor's height, so you stand on it like on a block (walking bob, jumps, Gravity Skate all
 * work). Set by the client each tick (see ClientMobility); never active on the server, which
 * takes the client's position and ground flag.
 */
public final class AirwalkFloor {

    private AirwalkFloor() {}

    /** The local player it holds, whether it is out, and its height along "up". */
    public static volatile Player owner;

    public static volatile boolean armed;
    public static volatile double level;

    /** Shortens a collided movement so the feet stop on the floor instead of falling through. */
    public static Vec3 clamp(Entity e, Vec3 moved) {
        if (!armed || e != owner || !(e instanceof Player p) || !p.level().isClientSide)
            return moved;
        Vec3 up = MovementPhysics.vector(MovementPhysics.down(p).getOpposite());
        double fall = moved.dot(up);
        if (fall >= 0) return moved;
        double now = p.position().dot(up);
        if (now < level - 1e-4) return moved; // already below it (it was moved down)
        double after = now + fall;
        if (after >= level) return moved;
        return moved.subtract(up.scale(after - level));
    }
}
