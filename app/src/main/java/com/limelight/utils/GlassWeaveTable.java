package com.limelight.utils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * How much of the left and right view each colour channel shows, over one lens period.
 *
 * Measured, not modelled: the same SBS game picture was shown in 3D by Spatial Glass on
 * this phone (S25 Ultra + SpatialGlass film, after its manual calibration) and by us; for
 * every channel and every 1/48 of the lens phase, the Glass screenshot was fitted as
 * aL * left + aR * right by least squares against the source picture. Negative fits are
 * clamped to 0 and over-unity ones to 1.
 *
 * The phase here is the PIXEL phase frac((3x + s*y)/P) with y counted downward and
 * P = 4.5377 subpixels, s = 0.5723 subpixels per row; channel offsets are already in the
 * curves (red and blue weave identically, green is shifted and squarer). Between the two
 * views every channel has a dark gap, which is what keeps the eyes apart.
 */
public final class GlassWeaveTable {
    public static final int SIZE = 64;

    private static final float[] LEFT_R = {0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.01f, 0.03f, 0.07f, 0.11f, 0.18f, 0.27f, 0.38f, 0.48f, 0.56f, 0.60f, 0.68f, 0.78f, 0.89f, 0.99f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 0.94f, 0.83f, 0.72f, 0.62f, 0.56f, 0.50f, 0.41f, 0.31f, 0.16f, 0.05f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f};
    private static final float[] LEFT_G = {0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.05f, 0.07f, 0.06f, 0.04f, 0.02f, 0.01f, 0.02f, 0.05f, 0.13f, 0.27f, 0.47f, 0.70f, 0.91f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 0.98f, 0.87f, 0.66f, 0.41f, 0.22f, 0.10f, 0.03f, 0.00f};
    private static final float[] LEFT_B = {0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.01f, 0.00f, 0.00f, 0.00f, 0.00f, 0.03f, 0.05f, 0.09f, 0.15f, 0.24f, 0.36f, 0.48f, 0.52f, 0.59f, 0.64f, 0.75f, 0.87f, 0.99f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 0.96f, 0.85f, 0.71f, 0.63f, 0.56f, 0.50f, 0.46f, 0.31f, 0.19f, 0.06f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f};
    private static final float[] RIGHT_R = {1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 0.91f, 0.79f, 0.68f, 0.60f, 0.55f, 0.48f, 0.39f, 0.26f, 0.14f, 0.05f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.02f, 0.03f, 0.09f, 0.17f, 0.30f, 0.41f, 0.51f, 0.55f, 0.62f, 0.70f, 0.82f, 0.93f};
    private static final float[] RIGHT_G = {0.07f, 0.17f, 0.34f, 0.59f, 0.83f, 0.98f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 0.97f, 0.78f, 0.53f, 0.30f, 0.15f, 0.06f, 0.01f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.02f};
    private static final float[] RIGHT_B = {1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 0.99f, 0.89f, 0.76f, 0.66f, 0.59f, 0.54f, 0.49f, 0.37f, 0.26f, 0.14f, 0.08f, 0.04f, 0.03f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.05f, 0.17f, 0.29f, 0.44f, 0.49f, 0.56f, 0.60f, 0.70f, 0.82f, 0.94f};

    /**
     * A SIZE x 2 RGBA texture: row 0 holds the left-view weights of R, G, B, row 1 the
     * right-view weights. Texel i stands for phase (i + 0.5) / SIZE, resampled circularly
     * from the 48 measured bins (centred at (k + 0.5) / 48).
     */
    public static ByteBuffer texture() {
        ByteBuffer buf = ByteBuffer.allocateDirect(SIZE * 2 * 4).order(ByteOrder.nativeOrder());
        float[][][] rows = {{LEFT_R, LEFT_G, LEFT_B}, {RIGHT_R, RIGHT_G, RIGHT_B}};
        for (float[][] row : rows) {
            for (int i = 0; i < SIZE; i++) {
                float phase = (i + 0.5f) / SIZE;
                for (float[] curve : row) {
                    buf.put((byte) Math.round(255f * sample(curve, phase)));
                }
                buf.put((byte) 255);
            }
        }
        buf.position(0);
        return buf;
    }

    private static float sample(float[] curve, float phase) {
        int n = curve.length;
        float pos = phase * n - 0.5f;
        int i0 = (int) Math.floor(pos);
        float f = pos - i0;
        float a = curve[((i0 % n) + n) % n];
        float b = curve[(((i0 + 1) % n) + n) % n];
        return a + (b - a) * f;
    }

    private GlassWeaveTable() {
    }
}
