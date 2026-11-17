package com.ethermod.item;

/**
 * The body's five-tone ramp for any chosen colour. The tones are measured from a reference red
 * ramp built around {@code #b93634}: a colour takes that neutral tone's place, and the other four
 * keep the reference's lightness steps and chroma falloff (in OKLCH) plus a hue shift: the
 * highlight leans warm, the shades lean cool, more the deeper they go. Greys get a faint
 * warm/cool tint so they don't look flat. Mirrors model-v3/palette.py, which renders the previews.
 */
public final class BodyPalette {

    private BodyPalette() {}

    /** The reference ramp the steps are measured from: highlight, neutral, shades 1-3. */
    private static final int[] REFERENCE = {0xDB483A, 0xB93634, 0x9E2927, 0x87211F, 0x6B1E1E};

    /** The default body colour: a mid tone of the vanilla copper tools. */
    public static final int COPPER = 0xD27A62;

    /** The tones the texture is painted with: {@code ramp(COPPER)}. Dyes swap these pixels. */
    public static final int[] PAINTED = {0xF1906B, 0xD27A62, 0xB96958, 0xA45D51, 0x89524C};

    public static final int NEUTRAL = 1;

    /**
     * The Default colour follows the tier's material: the painted copper at Prototype, then the
     * vanilla tool tones of iron, gold (Augmented) and netherite (highlight, neutral, shades 1-3).
     */
    private static final int[] IRON = {0xD8D8D8, 0xBEBEBE, 0xAAAAAA, 0x969696, 0x808080};

    private static final int[] GOLD = {0xFDFF76, 0xEAEE57, 0xEACF36, 0xE9B115, 0xE2A314};
    private static final int[] NETHERITE = {0x867B86, 0x706770, 0x5D565D, 0x51444E, 0x45393F};

    /** The material ramp for a tier's Default colour; null at Prototype (the painted copper). */
    public static int[] material(ManipulatorStage stage) {
        return switch (stage) {
            case STONE -> null;
            case IRON -> IRON.clone();
            case DIAMOND -> GOLD.clone();
            case NETHERITE -> NETHERITE.clone();
        };
    }

    private static final double WARM = 85, COOL = 265;

    /** Largest hue difference per tone, in OKLab units. */
    private static final double HUE_STEP = 0.012;
    private static final double[] SHIFT = {8, 0, -3, -6, -10};
    private static final double[] TINT = {0.012, 0, 0.010, 0.016, 0.022};
    private static final double[] DL = new double[5], CR = new double[5];
    private static final double BASE_L;

    static {
        double[] n = oklch(REFERENCE[NEUTRAL]);
        BASE_L = n[0];
        for (int i = 0; i < 5; i++) {
            double[] t = oklch(REFERENCE[i]);
            DL[i] = t[0] - n[0];
            CR[i] = t[1] / n[1];
        }
    }

    /**
     * The black dye is hand-tuned: generated near-black tones merge into one dark blob, so it
     * takes the model's carbon frame greys instead (two darker steps added). Only the exact dye.
     */
    private static final int BLACK_DYE = 0x1D1D21;

    private static final int[] BLACK_RAMP = {0x514C50, 0x444244, 0x383838, 0x2F2D2F, 0x252325};

    /** The five tones (0xRRGGBB) for a body colour; the neutral tone is the colour itself. */
    public static int[] ramp(int rgb) {
        if ((rgb & 0xFFFFFF) == BLACK_DYE) return BLACK_RAMP.clone();
        double[] lch = oklch(rgb);
        double l = lch[0], c = lch[1], h = lch[2];
        double grey = Math.max(0, 1 - c / 0.06);
        int[] out = new int[5];
        for (int i = 0; i < 5; i++) {
            if (i == NEUTRAL) {
                out[i] = rgb & 0xFFFFFF;
                continue;
            }
            double dl = DL[i] * Math.min(1, 0.35 + 0.65 * l / BASE_L); // dark: closer tones
            if (dl > 0) dl = Math.min(dl, Math.max(0, 0.97 - l) * 0.7);
            double li = Math.max(0.04, l + dl);
            double anchor = SHIFT[i] > 0 ? WARM : COOL;
            double ci = c * CR[i];
            // The hue turn is capped as a perceptual hue difference (chroma x angle): vivid
            // colours turn only a few degrees (a vivid red turned 8 degrees reads as orange),
            // greyish ones can take the full turn.
            double turnMax = Math.min(Math.abs(SHIFT[i]), Math.toDegrees(HUE_STEP / Math.max(ci, 1e-6)));
            double hi = toward(h, anchor, turnMax);
            if (dl > 0) {
                // sRGB holds less chroma the lighter a colour gets (less still after the warm
                // turn), so a plain lift got its chroma clipped toward white. The highlight
                // trades lift for colour instead: 90% of the base chroma, at least 40% of the
                // lift, and the hue turn shrinks first for colours on the gamut edge.
                double want = Math.min(ci, c * 0.9), floor = l + dl * 0.4;
                for (double turn : new double[] {1, 0.5, 0}) {
                    hi = toward(h, anchor, turnMax * turn);
                    if (inGamut(linear(floor, ci, hi))) break;
                }
                if (!inGamut(linear(li, want, hi))) {
                    double lo = floor, up = li;
                    for (int k = 0; k < 24; k++) {
                        double mid = (lo + up) / 2;
                        if (inGamut(linear(mid, want, hi))) lo = mid;
                        else up = mid;
                    }
                    li = lo;
                }
                if (!inGamut(linear(li, ci, hi))) ci = want;
            }
            double a =
                    ci * Math.cos(Math.toRadians(hi))
                            + grey * TINT[i] * Math.cos(Math.toRadians(anchor));
            double b =
                    ci * Math.sin(Math.toRadians(hi))
                            + grey * TINT[i] * Math.sin(Math.toRadians(anchor));
            out[i] = toRgb(li, Math.hypot(a, b), (Math.toDegrees(Math.atan2(b, a)) + 360) % 360);
        }
        return out;
    }

    private static double toward(double h, double anchor, double deg) {
        double d = ((anchor - h + 540) % 360) - 180;
        return (h + Math.copySign(Math.min(deg, Math.abs(d)), d) + 360) % 360;
    }

    private static double lin(int c) {
        double v = c / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static int srgb(double v) {
        v = Math.min(1, Math.max(0, v));
        v = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
        return (int) Math.round(Math.min(255, Math.max(0, v * 255)));
    }

    static double[] oklch(int rgb) {
        double r = lin(rgb >> 16 & 255), g = lin(rgb >> 8 & 255), b = lin(rgb & 255);
        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        double L = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s;
        double A = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s;
        double B = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s;
        return new double[] {L, Math.hypot(A, B), (Math.toDegrees(Math.atan2(B, A)) + 360) % 360};
    }

    private static double[] linear(double L, double C, double H) {
        double a = C * Math.cos(Math.toRadians(H)), b = C * Math.sin(Math.toRadians(H));
        double l = Math.pow(L + 0.3963377774 * a + 0.2158037573 * b, 3);
        double m = Math.pow(L - 0.1055613458 * a - 0.0638541728 * b, 3);
        double s = Math.pow(L - 0.0894841775 * a - 1.2914855480 * b, 3);
        return new double[] {
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
        };
    }

    private static boolean inGamut(double[] c) {
        for (double v : c) if (v < -1e-4 || v > 1 + 1e-4) return false;
        return true;
    }

    /** OKLCH to 0xRRGGBB, lowering chroma until the colour fits sRGB. */
    static int toRgb(double L, double C, double H) {
        L = Math.min(1, Math.max(0, L));
        double c = C;
        if (!inGamut(linear(L, C, H))) {
            double lo = 0, hi = C;
            for (int i = 0; i < 30; i++) {
                double mid = (lo + hi) / 2;
                if (inGamut(linear(L, mid, H))) lo = mid;
                else hi = mid;
            }
            c = lo;
        }
        double[] v = linear(L, c, H);
        return srgb(v[0]) << 16 | srgb(v[1]) << 8 | srgb(v[2]);
    }
}
