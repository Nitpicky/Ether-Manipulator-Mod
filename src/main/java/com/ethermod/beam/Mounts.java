package com.ethermod.beam;

import net.minecraft.world.entity.Entity;

/** Riding: the Manipulator's beam, shots and blasts pass through the user's own mount and anyone riding with them. */
public final class Mounts {
    private Mounts() {}

    /** {@code e} is the user, or shares the user's mount (the mount itself, or a fellow rider). */
    public static boolean own(Entity user, Entity e) {
        return user != null && (e == user || user.isPassenger() && e.getRootVehicle() == user.getRootVehicle());
    }
}
