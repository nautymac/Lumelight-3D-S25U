package com.limelight.utils;

import android.content.Context;
import android.content.SharedPreferences;

/** Viewer GlassSettings and film calibration, persisted across launches. */
final class GlassSettings {
    static final int MODE_3D = 0;
    static final int MODE_2D = 1;
    static final int MODE_TEST = 2;
    /** Calibration pattern: left eye red, right eye black. */
    static final int MODE_CAL = 3;

    static final int LAYOUT_SBS = 0;       // full side-by-side: each eye is half the frame width
    static final int LAYOUT_HALF_SBS = 1;  // half side-by-side: each eye squeezed to half width

    /*
     * Defaults measured from the Spatial Glass app's own output on this phone (S25 Ultra
     * with the SpatialGlass film, after its manual calibration): a subpixel weave with a
     * lens period of 4.5365 subpixels (1.51217 px) whose stripes move 0.18973 px left per
     * row going down, i.e. -0.18973 px per row in our y-up coordinates. The phase makes
     * our left eye the view Glass shows its calibration box in.
     */
    // Refined against Glass showing the SBS game picture in 3D: P = 4.5377 subpixels,
    // s = 0.5723 subpixels per row (downward). Our phase that puts the shader's pixel phase
    // exactly on Glass's (and so on WeaveTable's) is -(1.5 - s*(H - 0.5))/P mod 1 = 0.222;
    // measuring our own output the same way showed the curves 0.031 of a period late
    // (table resampling), so 0.253.
    /*
     * History: an early fit from face-box taps gave a 10.4 px tracking gap; the lens
     * geometry suggests 5.4 px; with iris tracking (MediaPipe, ~46 fps) the user dragged it
     * down to ~2 and found that best, with the phase in the middle (0.50). These are the
     * values the user settled on by eye on this phone (S25 Ultra + SpatialGlass film).
     */
    static final float DEFAULT_PITCH = 1.51207f;
    static final float DEFAULT_SLANT = -0.19057f;
    // The user found phase 0.4-0.9 and strength 1.5-2.5 look right at 30-40 cm
    // (2026-11-18); the middle of each leaves the most room for head movement before
    // 3D breaks. The earlier 0.4982 sat at the lower edge (CALIBRATION.md).
    static final float DEFAULT_PHASE = 0.65f;
    static final float DEFAULT_GAP = 2.0f;
    static final float DEFAULT_TRACK_Y = 1.0f;
    /** 3D 는 각 눈에 화소의 일부만 가서 어둡다 (화면 밝기는 이미 최대). 그림을 기본으로 올린다. */
    static final float DEFAULT_BOOST = 1.4f;
    static final float DEFAULT_CONTRAST = 1.15f;
    /** Tracking-strength range that looks right on this film at 30-40 cm (user, 2026-11-18). */
    static final float MIN_GAP = 1.5f;
    static final float MAX_GAP = 2.5f;
    private static final int PREFS_VERSION = 9;

    /** Lens period across the screen, in physical pixels. */
    volatile float pitch;
    /** Horizontal lens shift per pixel row (tangent of the lens slant). */
    volatile float slant;
    /** Lens phase, 0..1. */
    volatile float phase;
    /** Eye-tracking strength: the pixel-to-lens gap in pixels. Negative flips the direction. */
    volatile float gap;
    /** Factor on the tracked vertical eye offset (measured: reversed, about half). */
    volatile float trackY;
    /** 3D 밝기 (1 = 그대로) 와 대비 (1 = 그대로). */
    volatile float boost;
    volatile float contrast;
    volatile int mode;
    volatile int layout;
    volatile boolean swap;
    volatile boolean subpixel;
    volatile boolean tracking;
    volatile boolean showTrack;
    volatile boolean flipX;
    volatile boolean flipY;
    /** Whether the user has gone through calibration once. */
    volatile boolean calibrated;
    /** Calibration step on screen: -1 none, 0 horizontal, 1 vertical, 2 image. Not persisted. */
    volatile int calStep = -1;

    private final SharedPreferences prefs;

    GlassSettings(Context context) {
        prefs = context.getSharedPreferences("glass_lens", Context.MODE_PRIVATE);
        pitch = prefs.getFloat("pitch", DEFAULT_PITCH);
        slant = prefs.getFloat("slant", DEFAULT_SLANT);
        phase = prefs.getFloat("phase", DEFAULT_PHASE);
        gap = prefs.getFloat("gap", DEFAULT_GAP);
        trackY = prefs.getFloat("trackY", DEFAULT_TRACK_Y);
        boost = prefs.getFloat("boost", DEFAULT_BOOST);
        contrast = prefs.getFloat("contrast", DEFAULT_CONTRAST);
        mode = prefs.getInt("mode", MODE_3D);
        layout = prefs.getInt("layout", LAYOUT_SBS);
        swap = prefs.getBoolean("swap", false);
        subpixel = prefs.getBoolean("subpixel", true);
        tracking = prefs.getBoolean("tracking", true);
        showTrack = prefs.getBoolean("showTrack", false);
        flipX = prefs.getBoolean("flipX", false);
        flipY = prefs.getBoolean("flipY", false);
        calibrated = prefs.getBoolean("calibrated", false);
        int version = prefs.getInt("version", 1);
        if (version < 5) {
            // Values from before the Glass measurement were guesses; start from the measured ones.
            resetCalibration();
            calibrated = true;
        } else if (version < 7) {
            // Keep the user's pitch, slant and phase; restart tracking from the tuned default.
            gap = DEFAULT_GAP;
            trackY = DEFAULT_TRACK_Y;
        }
        // Version 7 -> 8 only changed the defaults to what this user tuned; their saved
        // values are kept as they are.
        if (version < 9) {
            // Centre of the ranges the user judged good (phase 0.4-0.9, strength 1.5-2.5).
            phase = DEFAULT_PHASE;
            gap = DEFAULT_GAP;
        }
        gap = Math.max(MIN_GAP, Math.min(MAX_GAP, gap));
        if (version < PREFS_VERSION) {
            save();
        }
    }

    void resetCalibration() {
        pitch = DEFAULT_PITCH;
        slant = DEFAULT_SLANT;
        phase = DEFAULT_PHASE;
        gap = DEFAULT_GAP;
        trackY = DEFAULT_TRACK_Y;
        boost = DEFAULT_BOOST;
        contrast = DEFAULT_CONTRAST;
        swap = false;
        subpixel = true;
        flipX = false;
        flipY = false;
    }

    void save() {
        prefs.edit()
                .putFloat("pitch", pitch)
                .putFloat("slant", slant)
                .putFloat("phase", phase)
                .putFloat("gap", gap)
                .putFloat("trackY", trackY)
                .putFloat("boost", boost)
                .putFloat("contrast", contrast)
                .putInt("mode", mode)
                .putInt("layout", layout)
                .putBoolean("swap", swap)
                .putBoolean("subpixel", subpixel)
                .putBoolean("tracking", tracking)
                .putBoolean("showTrack", showTrack)
                .putBoolean("flipX", flipX)
                .putBoolean("flipY", flipY)
                .putBoolean("calibrated", calibrated)
                .putInt("version", PREFS_VERSION)
                .apply();
    }
}
