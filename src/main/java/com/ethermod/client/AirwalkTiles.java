package com.ethermod.client;

import com.ethermod.trait.MovementPhysics;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Airwalk's floor, made visible: a pale glass pane with glowing purple veins under each block cell your feet
 * cover, on the floor's plane, seen from above and below. Tiles fade in as you step onto them and
 * out once you leave, so the floor you walk on draws itself as you go.
 */
public final class AirwalkTiles {

    private AirwalkTiles() {}

    private record Cell(int a, int b, Direction down, double plane) {}

    /** Each tile and how far it has faded in (0..1). */
    private static final Map<Cell, Float> TILES = new HashMap<>();

    private static long last = System.nanoTime();

    private static final float FADE_IN = .12f, FADE_OUT = .45f; // seconds

    @SubscribeEvent
    public static void render(RenderLevelStageEvent ev) {
        if (ev.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (ShaderCompat.inShadowPass()) return;
        var mc = Minecraft.getInstance();
        var p = mc.player;
        long nanos = System.nanoTime();
        float dt = Math.min(.1f, (nanos - last) / 1e9f);
        last = nanos;
        if (p == null || mc.level == null) {
            TILES.clear();
            return;
        }
        Direction down = MovementPhysics.down(p);
        Vec3 up = MovementPhysics.vector(down.getOpposite());
        java.util.Set<Cell> lit = new java.util.HashSet<>();
        double height = p.position().dot(up) - ClientMobility.airFloorLevel();
        // shown while standing on it, or hopping just above it
        if (ClientMobility.airFloorArmed()
                && (ClientMobility.onAirFloor() || height > 0 && height < 1.3 && !p.onGround())) {
            var box = p.getBoundingBox();
            Direction.Axis axis = down.getAxis();
            Direction.Axis[] side = others(axis);
            double sign = up.get(axis);
            double plane = ClientMobility.airFloorLevel() * sign;
            for (int a = (int) Math.floor(box.min(side[0]) + .001);
                    a <= (int) Math.floor(box.max(side[0]) - .001);
                    a++)
                for (int b = (int) Math.floor(box.min(side[1]) + .001);
                        b <= (int) Math.floor(box.max(side[1]) - .001);
                        b++) lit.add(new Cell(a, b, down, plane));
        }
        for (Cell c : lit) TILES.putIfAbsent(c, 0f);
        TILES.replaceAll(
                (c, f) -> lit.contains(c) ? Math.min(1, f + dt / FADE_IN) : f - dt / FADE_OUT);
        TILES.values().removeIf(f -> f <= 0);
        TILES.keySet().removeIf(c -> c.down() != down);
        if (TILES.isEmpty()) return;

        Vec3 cam = ev.getCamera().getPosition();
        PoseStack ps = ev.getPoseStack();
        ps.pushPose();
        var pose = ps.last();
        var buffers = mc.renderBuffers().bufferSource();
        float time =
                (mc.level.getGameTime() + ev.getPartialTick().getGameTimeDeltaPartialTick(false))
                        * .1f;
        // the pane: pale glass with a firmer rim (writes depth, drawn once: the render type
        // shows both sides, so a second face on the same plane would fight with the first)
        RenderType paneType = RenderType.entityTranslucent(PANE);
        VertexConsumer pane = buffers.getBuffer(paneType);
        for (var e : TILES.entrySet())
            tile(pane, pose, e.getKey(), e.getValue(), up, cam, time, .002, 236, 228, 255, 230, false);
        buffers.endBatch(paneType);
        // the veins: the rim again in Gravitic purple, additive and full bright, which shader
        // packs treat as emissive; lifted a little more so it never ties with the pane
        RenderType veinType = RenderType.eyes(VEINS);
        VertexConsumer veins = buffers.getBuffer(veinType);
        int col = com.ethermod.item.OrbMode.TETHER.color;
        for (var e : TILES.entrySet())
            tile(veins, pose, e.getKey(), e.getValue(), up, cam, time, .006,
                    col >> 16 & 255, col >> 8 & 255, col & 255, 255, true);
        buffers.endBatch(veinType);
        ps.popPose();
    }

    private static final ResourceLocation PANE =
            ResourceLocation.fromNamespaceAndPath("ethermod", "textures/misc/airwalk_tile.png");
    private static final ResourceLocation VEINS =
            ResourceLocation.fromNamespaceAndPath("ethermod", "textures/misc/airwalk_veins.png");

    private static void tile(
            VertexConsumer vc,
            PoseStack.Pose pose,
            Cell c,
            float f,
            Vec3 up,
            Vec3 cam,
            float time,
            double lift,
            int r,
            int g,
            int b,
            int maxAlpha,
            boolean sides) {
        f = f * f * (3 - 2 * f);
        float shimmer = .85f + .15f * (float) Math.sin(time + c.a() * .7 + c.b() * 1.3);
        int alpha = (int) (maxAlpha * f * shimmer);
        Direction.Axis axis = c.down().getAxis();
        Direction.Axis[] side = others(axis);
        double plane = c.plane() + up.get(axis) * lift;
        int[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        // the vein render type culls back faces, so it gets both windings (the pane's doesn't
        // cull, and a second face there would fight with the first)
        for (int n = 0; n < (sides ? 8 : 4); n++) {
            int i = n < 4 ? n : 7 - n;
            double[] xyz = new double[3];
            xyz[axis.ordinal()] = plane;
            xyz[side[0].ordinal()] = c.a() + uv[i][0];
            xyz[side[1].ordinal()] = c.b() + uv[i][1];
            vc.addVertex(pose, (float) (xyz[0] - cam.x), (float) (xyz[1] - cam.y), (float) (xyz[2] - cam.z))
                    .setColor(r, g, b, alpha)
                    .setUv(uv[i][0], uv[i][1])
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(LightTexture.FULL_BRIGHT)
                    .setNormal(pose, (float) up.x, (float) up.y, (float) up.z);
        }
    }

    private static Direction.Axis[] others(Direction.Axis axis) {
        return switch (axis) {
            case X -> new Direction.Axis[] {Direction.Axis.Y, Direction.Axis.Z};
            case Y -> new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Z};
            case Z -> new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Y};
        };
    }
}
