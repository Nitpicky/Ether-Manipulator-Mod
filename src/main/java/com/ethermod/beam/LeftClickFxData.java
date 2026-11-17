package com.ethermod.beam;

import com.ethermod.network.payload.LeftClickFxPayload;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The left-click effects a client is drawing, filled from {@link LeftClickFxPayload}, plus the
 * server-side send helper. Holds no client classes so both sides can load it; {@link LeftClickFx}
 * draws it.
 */
public final class LeftClickFxData {

    private LeftClickFxData() {}

    /** How long each effect lasts, in ticks. */
    static final int BURST_TICKS = 9, PULL_TICKS = 8, PUFF_TICKS = 40, CUT_TICKS = 10;

    static final class Grenade {
        Vec3 prev, pos;
        int fuse;
        long seen;
    }

    record Burst(Vec3 pos, long start) {}

    record Pull(int owner, Vec3 end, int target, long start) {}

    record Puff(Vec3 pos, long start) {}

    record Cut(Vec3 pos, Vec3 dir, long start) {}

    static final Map<Integer, Grenade> GRENADES = new HashMap<>();
    static final List<Burst> BURSTS = new ArrayList<>();
    static final List<Pull> PULLS = new ArrayList<>();
    static final List<Puff> PUFFS = new ArrayList<>();
    static final List<Cut> CUTS = new ArrayList<>();

    private static long clientTime;

    /** Client: a payload arrived. */
    public static void accept(LeftClickFxPayload p) {
        Vec3 at = new Vec3(p.pos().x(), p.pos().y(), p.pos().z());
        switch (p.kind()) {
            case LeftClickFxPayload.GRENADE -> {
                Grenade g = GRENADES.computeIfAbsent(p.id(), k -> new Grenade());
                g.prev = g.pos == null ? at : g.pos;
                g.pos = at;
                g.fuse = p.extra();
                g.seen = clientTime;
            }
            case LeftClickFxPayload.BURST -> {
                GRENADES.remove(p.id());
                BURSTS.add(new Burst(at, clientTime));
            }
            case LeftClickFxPayload.PULL -> PULLS.add(new Pull(p.owner(), at, p.extra(), clientTime));
            case LeftClickFxPayload.PUFF -> PUFFS.add(new Puff(at, clientTime));
            case LeftClickFxPayload.CUT -> {
                double a = Math.toRadians(p.extra() / 10.0);
                CUTS.add(new Cut(at, new Vec3(Math.cos(a), 0, Math.sin(a)), clientTime));
            }
            default -> {}
        }
    }

    /** Client tick: drop finished effects. */
    public static void tick(long gameTime) {
        clientTime = gameTime;
        GRENADES.values().removeIf(g -> gameTime - g.seen > 10);
        BURSTS.removeIf(b -> gameTime - b.start > BURST_TICKS);
        PULLS.removeIf(b -> gameTime - b.start > PULL_TICKS);
        PUFFS.removeIf(b -> gameTime - b.start > PUFF_TICKS);
        CUTS.removeIf(b -> gameTime - b.start > CUT_TICKS);
    }

    static long now() {
        return clientTime;
    }

    public static void clear() {
        GRENADES.clear();
        BURSTS.clear();
        PULLS.clear();
        PUFFS.clear();
        CUTS.clear();
    }

    /** Server: show an effect to everyone near {@code at}. */
    public static void send(ServerLevel sl, int kind, int id, int owner, Vec3 at, int extra) {
        PacketDistributor.sendToPlayersNear(
                sl,
                null,
                at.x,
                at.y,
                at.z,
                96,
                new LeftClickFxPayload(
                        kind, id, owner, new Vector3f((float) at.x, (float) at.y, (float) at.z), extra));
    }
}
