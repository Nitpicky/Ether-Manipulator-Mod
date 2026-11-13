package com.mattermod.beam;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * The outer surface of a block's shape, the way vanilla's selection outline follows it: a stair is
 * an L, a slab half a cube, a carpet a thin sheet. The shape's boxes are cut on their own
 * breakpoints into cells, and only cell faces with nothing behind them are kept, so the boxes a
 * shape is built from never show their seams. A face on the block's border is also dropped when
 * the neighbouring highlighted block fills the other side, so a group reads as one shape.
 */
final class ShapeSurface {

    private ShapeSurface() {}

    /** Whether a world point lies inside another highlighted block's shape. */
    interface Filled {
        boolean at(double x, double y, double z);
    }

    /** One axis-aligned face: its outward direction and the rectangle (world coordinates). */
    interface Sink {
        void face(Direction d, double x0, double y0, double z0, double x1, double y1, double z1);
    }

    static boolean contains(List<AABB> boxes, double x, double y, double z) {
        for (AABB b : boxes)
            if (x > b.minX && x < b.maxX && y > b.minY && y < b.maxY && z > b.minZ && z < b.maxZ)
                return true;
        return false;
    }

    /** The distinct box edges along one axis, sorted: the cell boundaries. */
    private static double[] cuts(List<AABB> boxes, Direction.Axis axis) {
        java.util.TreeSet<Double> set = new java.util.TreeSet<>();
        for (AABB b : boxes) {
            set.add(Math.round(b.min(axis) * 4096) / 4096.0);
            set.add(Math.round(b.max(axis) * 4096) / 4096.0);
        }
        double[] out = new double[set.size()];
        int i = 0;
        for (double v : set) out[i++] = v;
        return out;
    }

    static void faces(VoxelShape shape, BlockPos pos, Filled outside, Sink sink) {
        if (shape.isEmpty()) return;
        List<AABB> boxes = shape.toAabbs();
        double[] xs = cuts(boxes, Direction.Axis.X),
                ys = cuts(boxes, Direction.Axis.Y),
                zs = cuts(boxes, Direction.Axis.Z);
        int nx = xs.length - 1, ny = ys.length - 1, nz = zs.length - 1;
        if (nx <= 0 || ny <= 0 || nz <= 0) return;
        boolean[] full = new boolean[nx * ny * nz];
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < ny; j++)
                for (int k = 0; k < nz; k++)
                    full[(i * ny + j) * nz + k] =
                            contains(
                                    boxes,
                                    (xs[i] + xs[i + 1]) / 2,
                                    (ys[j] + ys[j + 1]) / 2,
                                    (zs[k] + zs[k + 1]) / 2);
        int px = pos.getX(), py = pos.getY(), pz = pos.getZ();
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < ny; j++)
                for (int k = 0; k < nz; k++) {
                    if (!full[(i * ny + j) * nz + k]) continue;
                    double x0 = px + xs[i], x1 = px + xs[i + 1];
                    double y0 = py + ys[j], y1 = py + ys[j + 1];
                    double z0 = pz + zs[k], z1 = pz + zs[k + 1];
                    double cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, cz = (z0 + z1) / 2;
                    for (Direction d : Direction.values()) {
                        int ii = i + d.getStepX(), jj = j + d.getStepY(), kk = k + d.getStepZ();
                        boolean inside = ii >= 0 && jj >= 0 && kk >= 0 && ii < nx && jj < ny && kk < nz;
                        if (inside && full[(ii * ny + jj) * nz + kk]) continue;
                        // the face's plane, and a point just across it
                        double fx = d.getStepX() > 0 ? x1 : d.getStepX() < 0 ? x0 : cx;
                        double fy = d.getStepY() > 0 ? y1 : d.getStepY() < 0 ? y0 : cy;
                        double fz = d.getStepZ() > 0 ? z1 : d.getStepZ() < 0 ? z0 : cz;
                        double e = 1e-3;
                        if (outside.at(
                                fx + d.getStepX() * e, fy + d.getStepY() * e, fz + d.getStepZ() * e))
                            continue;
                        switch (d.getAxis()) {
                            case X -> sink.face(d, fx, y0, z0, fx, y1, z1);
                            case Y -> sink.face(d, x0, fy, z0, x1, fy, z1);
                            case Z -> sink.face(d, x0, y0, fz, x1, y1, fz);
                        }
                    }
                }
    }
}
