package com.limelight.utils;

import android.opengl.GLES20;

/**
 * The lens weave for a film laid over an ordinary screen: the Galaxy S25 Ultra with the
 * SpatialGlass 3D film.
 *
 * Unlike a Leia panel, the film is only a lens -- nothing on the device weaves or tracks.
 * So the app does both: every screen pixel shows the left or right eye (per colour channel,
 * with the curves measured from Spatial Glass's own output, {@link GlassWeaveTable})
 * according to where it sits under the lens as seen from the viewer's eyes, which the glass
 * flavour's PanelDriver tracks with the front camera.
 *
 * Off ({@link #enabled} false) on every other build. The glass PanelDriver switches it on and
 * keeps the lens values and the eye position here up to date; the shaders read them through
 * {@link #GLSL} and {@link #apply}.
 *
 * Lens model (all in screen pixels, gl_FragCoord): phase = fract((q.x + q.y * slant) / pitch
 * + phase0), with q = p + E k - C k0 - p (k - k0), k = gap / eye distance, k0 = gap /
 * reference distance, E the eye position and C the screen centre. The calibration holds for
 * an eye straight ahead at the reference distance; tracking adds only the change from there.
 */
public final class GlassLens {

    /** True on the glass build once its driver is up. */
    public static volatile boolean enabled = false;

    // Lens values, calibrated on the S25 Ultra + SpatialGlass film (2026-11-18). The glass
    // driver loads the saved ones over these.
    public static volatile float pitch = 1.51207f;   // px per lens period
    public static volatile float slant = -0.19057f;  // px shift per row (y up)
    public static volatile float phase = 0.65f;      // 0..1, good range 0.4..0.9
    public static volatile float gap = 2.0f;         // tracking strength, good range 1.5..2.5
    public static volatile float trackY = 1.0f;
    public static volatile boolean swap = false;
    public static volatile boolean tracking = true;

    /** Size of one screen pixel in mm. */
    public static volatile float pixelMm = 0.0507f;

    // Latest eye position from the tracker: mm from the screen centre, x right, y up, z out.
    public static volatile boolean eyeValid = false;
    public static volatile float eyeX = 0f;
    public static volatile float eyeY = 0f;
    public static volatile float eyeZ = 400f;

    /** Distance the calibration stands for, as Spatial Glass asks: straight ahead, 400 mm. */
    public static final float REFERENCE_DISTANCE_MM = 400f;

    /**
     * Uniforms and functions to paste into a fragment shader. {@code glassWeave(l, r)} returns
     * the colour this screen pixel must show for left colour l and right colour r.
     */
    public static final String GLSL =
            "uniform float u_glPitch;\n" +
            "uniform float u_glSlant;\n" +
            "uniform float u_glPhase;\n" +
            "uniform vec2 u_glEye;\n" +
            "uniform float u_glK;\n" +
            "uniform vec2 u_glRef;\n" +
            "uniform float u_glK0;\n" +
            "uniform float u_glSwap;\n" +
            "uniform sampler2D s_glWeave;\n" +
            "vec3 glassWeave(vec3 l, vec3 r) {\n" +
            "  vec2 p = gl_FragCoord.xy;\n" +
            "  vec2 q = p + u_glEye * u_glK - u_glRef * u_glK0 - p * (u_glK - u_glK0);\n" +
            "  float ph = fract((q.x + q.y * u_glSlant) / u_glPitch + u_glPhase);\n" +
            "  vec3 wl = texture2D(s_glWeave, vec2(ph, 0.25)).rgb;\n" +
            "  vec3 wr = texture2D(s_glWeave, vec2(ph, 0.75)).rgb;\n" +
            "  if (u_glSwap > 0.5) { vec3 t = wl; wl = wr; wr = t; }\n" +
            "  return wl * l + wr * r;\n" +
            "}\n";

    /**
     * Creates the weave table texture in the current GL context. Call once per context.
     *
     * @return the texture name, to hand to {@link #apply}
     */
    public static int createWeaveTexture() {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        // Power-of-two width so the phase can wrap with GL_REPEAT.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, GlassWeaveTable.SIZE, 2, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, GlassWeaveTable.texture());
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        return t[0];
    }

    /**
     * Sets the weave uniforms on {@code program}, which must be current, for a screen of
     * {@code width} x {@code height} pixels, and binds the weave table to {@code unit}.
     */
    public static void apply(int program, int weaveTexture, int unit, int width, int height) {
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "u_glPitch"), pitch);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "u_glSlant"), slant);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "u_glPhase"), phase);
        float k = 0f;
        float k0 = 0f;
        float ex = 0f;
        float ey = 0f;
        float z = eyeZ;
        if (tracking && eyeValid && z > 50f) {
            float mm = pixelMm;
            k = gap / (z / mm);
            k0 = gap / (REFERENCE_DISTANCE_MM / mm);
            ex = width / 2f + eyeX / mm;
            ey = height / 2f + eyeY * trackY / mm;
        }
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "u_glEye"), ex, ey);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "u_glK"), k);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "u_glRef"), width / 2f, height / 2f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "u_glK0"), k0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "u_glSwap"), swap ? 1f : 0f);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, weaveTexture);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "s_glWeave"), unit);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    private GlassLens() {
    }
}
