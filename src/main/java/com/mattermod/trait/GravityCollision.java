package com.mattermod.trait;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;

import java.util.*;

/** Sweeps the actual rotated box, resolving gravity before the two lateral axes. */
public final class GravityCollision {
    private GravityCollision() {}

    private static double component(Vec3 v, int axis) {
        return axis == 0 ? v.x : axis == 1 ? v.y : v.z;
    }

    private static Vec3 axis(int axis, double distance) {
        return axis == 0
                ? new Vec3(distance, 0, 0)
                : axis == 1 ? new Vec3(0, distance, 0) : new Vec3(0, 0, distance);
    }

    private static int index(Direction.Axis axis) {
        return axis == Direction.Axis.X ? 0 : axis == Direction.Axis.Y ? 1 : 2;
    }

    private static Vec3 sweep(AABB box, Vec3 wanted, List<VoxelShape> shapes, int vertical) {
        int a = (vertical + 1) % 3, b = (vertical + 2) % 3;
        if (Math.abs(component(wanted, b)) > Math.abs(component(wanted, a))) {
            int t = a;
            a = b;
            b = t;
        }
        double[] out = {wanted.x, wanted.y, wanted.z};
        for (int n : new int[] {vertical, a, b}) {
            out[n] = Shapes.collide(Direction.Axis.values()[n], box, shapes, out[n]);
            box = box.move(axis(n, out[n]));
        }
        return new Vec3(out[0], out[1], out[2]);
    }

    public static Vec3 resolve(Player p, Vec3 wanted) {
        var down = MovementPhysics.down(p);
        Vec3 up = MovementPhysics.vector(down.getOpposite());
        int vertical = index(down.getAxis());
        AABB box = p.getBoundingBox();
        double step =
                p.getAbilities().flying
                        ? 0
                        : p.getAttributeValue(
                                net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT);
        var shapes = new ArrayList<VoxelShape>();
        p.level()
                .getCollisions(p, box.expandTowards(wanted).expandTowards(up.scale(step)))
                .forEach(shapes::add);
        shapes.add(p.level().getWorldBorder().getCollisionShape());
        Vec3 result = sweep(box, wanted, shapes, vertical);
        Vec3 lateral = wanted.subtract(up.scale(wanted.dot(up))),
                moved = result.subtract(up.scale(result.dot(up)));
        if (step > 0
                && (p.onGround()
                        || wanted.dot(up) < 0 && Math.abs(result.dot(up) - wanted.dot(up)) > .00001)
                && moved.lengthSqr() + .00001 < lateral.lengthSqr()) {
            Vec3 lift = sweep(box, up.scale(step), shapes, vertical);
            AABB raised = box.move(lift);
            Vec3 across = sweep(raised, lateral, shapes, vertical);
            Vec3 settle =
                    sweep(
                            raised.move(across),
                            up.scale(-lift.dot(up) + Math.min(0, wanted.dot(up))),
                            shapes,
                            vertical);
            Vec3 candidate = lift.add(across).add(settle);
            if (across.lengthSqr() > moved.lengthSqr() + .00001) result = candidate;
        }
        return result;
    }
}
