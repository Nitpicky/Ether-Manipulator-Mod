package com.ethermod.client;

import static com.ethermod.client.UiSettings.p;
import static com.ethermod.client.UpgradeVisuals.*;

import java.util.Arrays;

/**
 * Pure maths and pixel passes for the trait-panel animations: the downward reveal wave when a
 * panel (re)opens, the routed cross-fade when a pick changes, and the circuit-trace distances.
 */
final class KPanelFx {
    private KPanelFx() {}

    static void switchPixels(
            Raster r, Palette pal, double age, int[] route, int length, int[] groups) {
        for (int i = 0; i < r.pixels.length; i++) {
            int yy = i / 146, x = i % 146, target = r.pixels[i];
            if (age < .05) {
                r.pixels[i] =
                        x == 0 || x == 145 || yy == 0 || yy == 39 ? p().border() : p().panel();
                continue;
            }
            if (age < .125) {
                r.pixels[i] = pal.dim();
                continue;
            }
            if (age < .2) {
                int base =
                        x == 0 || x == 145 || yy == 0 || yy == 39 ? pal.border() : pal.panel();
                r.pixels[i] = mix(pal.dim(), base, (age - .125) / .075);
                continue;
            }
            if (x == 0 || x == 145 || yy == 0 || yy == 39) continue;
            double edge = waveEdge(age), distance = edge - yy;
            if (distance < 0) {
                r.pixels[i] = pal.panel();
                continue;
            }
            int owner = r.owners[i];
            double resolved = Math.clamp((distance - 2) / 12, 0, 1), pulse = 0;
            if (owner >= 0) {
                int bottom =
                        owner < 5
                                ? 33
                                : owner < 14
                                        ? 15 + ((owner - 5) % 3) * 17
                                        : owner < 16 ? 25 : 31;
                double lo = .2, hi = .88;
                for (int k = 0; k < 18; k++) {
                    double mid = (lo + hi) / 2;
                    if (waveEdge(mid) < bottom + 14) lo = mid;
                    else hi = mid;
                }
                resolved = Math.clamp((age - hi - .035) / .13, 0, 1);
                double burst = 1.23 + groups[owner] * .09;
                if (age >= burst)
                    pulse =
                            age < burst + .035
                                    ? 1
                                    : 1 - Math.clamp((age - burst - .035) / .055, 0, 1);
            }
            resolved = Math.floor(resolved * 5 + 1e-8) / 5;
            if (route[i] >= 0) {
                double progress =
                        Math.clamp(((age - .91) / .28 * (length + 8) - route[i]) / 8, 0, 1);
                target = mix(pal.panel(), target, progress * progress * (3 - 2 * progress));
            }
            if (pulse > 0) target = mix(target, liftTone(target, pal), pulse);
            r.pixels[i] = mix(pal.accent(), target, resolved);
        }
    }

    static void traitFade(
            Raster r,
            Palette pal,
            int[] path,
            int length,
            double elapsed,
            int[] previous,
            int[] oldLink,
            int[] newLink,
            int newChoice) {
        for (int i = 0; i < r.pixels.length; i++) {
            int old = previous[i], target = r.pixels[i];
            if (oldLink != null && (oldLink[i] >= 0 || newLink[i] >= 0)) {
                if (elapsed < .12) {
                    r.pixels[i] = mix(old, pal.panel(), elapsed / .12);
                    continue;
                }
                if (elapsed < .13) {
                    r.pixels[i] = pal.panel();
                    continue;
                }
                if (newLink[i] >= 0) {
                    int total = 0;
                    for (int d : newLink) total = Math.max(total, d);
                    double f =
                            Math.clamp(
                                    ((elapsed - .13) / .15 * (total + 8) - newLink[i]) / 8,
                                    0,
                                    1);
                    r.pixels[i] = mix(pal.panel(), target, f * f * (3 - 2 * f));
                } else r.pixels[i] = mix(pal.panel(), target, (elapsed - .13) / .15);
                continue;
            }
            if (old == target) continue;
            double amount = Math.clamp(elapsed / .12, 0, 1);
            if (path[i] >= 0 && newChoice >= 0) {
                double progress =
                        Math.clamp((elapsed / .28 * (length + 8) - path[i]) / 8, 0, 1);
                amount = progress * progress * (3 - 2 * progress);
            }
            r.pixels[i] = mix(old, target, amount);
        }
    }

    static int[] connection(int tier, int n) {
        int[] d = new int[146 * 40];
        Arrays.fill(d, -1);
        route(
                d,
                new int[] {41, 85, 129}[tier] + 11,
                11 + n * 17,
                new int[] {63, 107}[tier] - 1,
                20,
                0);
        return d;
    }

    static int[] selectionRoute(int tier, int n, int stage) {
        int[] d = new int[146 * 40];
        Arrays.fill(d, -1);
        if (tier < 0 || n < 0) return d;
        int x = new int[] {41, 85, 129}[tier],
                source = tier == 0 ? 29 : new int[] {73, 117}[tier - 1];
        int len = route(d, source, 20, x, 11 + n * 17, 0);
        for (int yy = 6 + n * 17; yy < 17 + n * 17; yy++)
            for (int xx = x; xx < x + 11; xx++) d[yy * 146 + xx] = len + xx - x;
        len += 11;
        if (tier < 2 && tier + 1 < stage) {
            int cx = new int[] {63, 107}[tier];
            len = route(d, x + 11, 11 + n * 17, cx - 1, 20, len);
            for (int yy = 15; yy < 26; yy++)
                for (int xx = cx; xx < cx + 11; xx++) d[yy * 146 + xx] = len + xx - cx;
        }
        return d;
    }

    static double waveEdge(double age) {
        double e = age - .2;
        if (e <= .5) return 38 * Math.pow(Math.max(0, e / .5), 2.2);
        double p = Math.clamp((e - .5) / .18, 0, 1);
        return 38 + 16 * (1 - Math.pow(1 - p, 2.4));
    }

    static int liftTone(int colour, Palette pal) {
        int nearest = 0;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < pal.ramp().length; i++) {
            long d = 0;
            for (int shift : new int[] {0, 8, 16}) {
                int delta = ((colour >> shift) & 255) - ((pal.ramp()[i] >> shift) & 255);
                d += (long) delta * delta;
            }
            if (d < best) {
                best = d;
                nearest = i;
            }
        }
        return pal.ramp()[Math.min(nearest + 1, pal.ramp().length - 1)];
    }

    static int route(int[] d, int x, int y, int ex, int ey, int start) {
        int mid = (x + ex) / 2;
        int[][] points = {{x, y}, {mid, y}, {mid, ey}, {ex, ey}};
        for (int p = 1; p < points.length; p++) {
            x = points[p - 1][0];
            y = points[p - 1][1];
            for (; ; ) {
                if (x >= 0 && x < 146 && y >= 0 && y < 40) d[y * 146 + x] = start++;
                if (x == points[p][0] && y == points[p][1]) break;
                if (x != points[p][0]) x += Integer.signum(points[p][0] - x);
                else y += Integer.signum(points[p][1] - y);
            }
        }
        return start;
    }
}
