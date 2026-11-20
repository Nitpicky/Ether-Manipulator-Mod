package com.ethermod.client.beam;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.HashMap;
import java.util.Map;

/**
 * Where the nozzle of the Manipulator model is, so the beam and the orb come out of its centre.
 *
 * <p>The model's {@code muzzle_anchor} bone is sampled while the item renders, as a screen position
 * (normalised device coordinates) plus a view depth, using the exact matrices that draw it. The
 * beam renderer later turns that screen point back into a world point with the world's own
 * matrices. This matters in first person: the hand is drawn after the world, with its own field of
 * view and in view space, so a stored world position would trail the camera by a frame and drift
 * when you turn. The screen position of the nozzle does not depend on where the camera points, so
 * it stays glued to the model.
 *
 * <p>FOV effects (sprinting, Speed) widen only the world's projection, not the hand's. That is why
 * the sample is kept on screen and unprojected with the world's matrices: the beam then starts
 * where the nozzle is seen. Keeping it in view space (or unprojecting with the hand's projection)
 * would put the start at the nozzle's 3D spot, which the wider world projection shows off the
 * nozzle on screen.
 */
@OnlyIn(Dist.CLIENT)
public final class Muzzle {

    private Muzzle() {}

    /** How far ahead of the nozzle the barrel direction is sampled, in blocks. */
    private static final float BARREL_PROBE = 0.25f;

    /** One sample: the nozzle and a point further along the barrel. */
    private record Sample(float x0, float y0, float d0, float x1, float y1, float d1, long frame) {}

    private static Sample firstPerson;
    private static final Map<Integer, Sample> thirdPerson = new HashMap<>();

    /** Entity ids are per world, so forget them when the client changes world or server. */
    public static void clearCaches() {
        thirdPerson.clear();
    }

    /** The player whose model is being drawn right now (set around player rendering), or -1. */
    public static int renderingPlayer = -1;

    private static long frame;

    /** Called once per frame by the beam renderer before resolving. */
    static void nextFrame() {
        frame++;
        if (thirdPerson.size() > 32) thirdPerson.values().removeIf(s -> frame - s.frame > 20);
    }

    /**
     * Records the nozzle while the model is drawn. {@code pose} is the bone's parent matrix; {@code
     * px, py, pz} the bone pivot in blocks.
     */
    public static void capture(
            Matrix4f pose, float px, float py, float pz, boolean firstPersonView) {
        Matrix4f pv =
                new Matrix4f(RenderSystem.getProjectionMatrix())
                        .mul(RenderSystem.getModelViewMatrix());
        Matrix4f mv = RenderSystem.getModelViewMatrix();
        float[] a = project(pv, mv, pose, px, py, pz);
        float[] b = project(pv, mv, pose, px, py, pz - BARREL_PROBE);
        if (a == null || b == null) return;
        Sample s = new Sample(a[0], a[1], a[2], b[0], b[1], b[2], frame);
        if (firstPersonView) firstPerson = s;
        else if (renderingPlayer >= 0) thirdPerson.put(renderingPlayer, s);
    }

    private static float[] project(
            Matrix4f pv, Matrix4f mv, Matrix4f pose, float x, float y, float z) {
        Vector4f local = new Vector4f(x, y, z, 1f).mul(pose);
        Vector4f clip = new Vector4f(local).mul(pv);
        if (clip.w <= 1e-4f) return null;
        Vector4f view = new Vector4f(local).mul(mv);
        return new float[] {clip.x / clip.w, clip.y / clip.w, -view.z};
    }

    /**
     * The nozzle and barrel direction in world space, or null if there is no recent sample. {@code
     * proj} and {@code modelView} are the world's matrices for this frame.
     */
    static Vec3[] resolve(
            int playerId, boolean firstPersonView, Matrix4f proj, Matrix4f modelView, Vec3 camPos) {
        Sample s = firstPersonView ? firstPerson : thirdPerson.get(playerId);
        // first person is drawn after the world, so its sample is a frame old
        if (s == null || frame - s.frame > 3) return null;
        Matrix4f invProj = new Matrix4f(proj).invert();
        Matrix4f invView = new Matrix4f(modelView).invert();
        Vec3 nozzle = unproject(invProj, invView, s.x0, s.y0, s.d0);
        Vec3 ahead = unproject(invProj, invView, s.x1, s.y1, s.d1);
        if (nozzle == null || ahead == null) return null;
        Vec3 dir = ahead.subtract(nozzle);
        if (dir.lengthSqr() < 1e-8) return null;
        return new Vec3[] {nozzle.add(camPos), dir.normalize()};
    }

    /** Screen point + view depth → camera-relative world point. */
    private static Vec3 unproject(
            Matrix4f invProj, Matrix4f invView, float x, float y, float depth) {
        Vector4f near = new Vector4f(x, y, -1f, 1f).mul(invProj);
        Vector4f far = new Vector4f(x, y, 1f, 1f).mul(invProj);
        if (Math.abs(near.w) < 1e-9f || Math.abs(far.w) < 1e-9f) return null;
        near.div(near.w);
        far.div(far.w);
        float dz = far.z - near.z;
        if (Math.abs(dz) < 1e-9f) return null;
        float t = (-depth - near.z) / dz;
        Vector4f view =
                new Vector4f(
                        near.x + (far.x - near.x) * t, near.y + (far.y - near.y) * t, -depth, 1f);
        view.mul(invView);
        return new Vec3(view.x, view.y, view.z);
    }
}
