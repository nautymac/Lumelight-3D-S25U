package com.limelight.utils;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * Glass build of the panel driver: Galaxy S25 Ultra with the SpatialGlass 3D film.
 *
 * The film is only a lens over an ordinary screen, so there is no SDK or 3D service to hand
 * frames to. The weave is drawn by this app's own shaders ({@link GlassLens}); what this
 * class adds is the viewer's eye position, from the front camera (MediaPipe irises, see
 * {@link EyeTracker}), which moves the weave so 3D holds as the head moves.
 *
 * Same shape as the Leia driver, because the shared code is compiled against whichever one
 * the flavour selects. {@link #ownsInterlacing()} is false: the shader weaves.
 *
 * The lens values are the ones calibrated on this phone (2026-11-18: pitch 1.51207, slant
 * -0.19057, phase 0.65, strength 2.0), stored in {@link GlassSettings}.
 */
public final class PanelDriver {

    private static final String TAG = "GlassPanel";
    private static final int REQ_CAMERA = 7302;

    /** Receives the surface the stream should be decoded into, once the panel has one. */
    public interface SurfaceListener {
        void onPanelSurfaceReady(Surface surface);
    }

    /** Declared to match the Leia driver; nothing on this side converts anything. */
    public interface ConversionListener {
        void onConversionInputReady(Surface input, int width, int height);
    }

    private static PanelDriver instance;

    private final Context appContext;
    private WeakReference<Activity> activityRef = new WeakReference<>(null);
    private final GlassSettings settings;
    // The phase / strength bar shown while 3D is on, and the activity it was added to.
    private GlassTuner tuner;
    private Activity tunerActivity;
    private final EyeTracker tracker;
    private float pixelMm;
    private boolean resumed = true;
    private boolean tracking;
    private boolean askedCamera;

    public static synchronized PanelDriver get(Context context) {
        if (instance == null) {
            instance = new PanelDriver(context);
        }
        if (context instanceof Activity) {
            instance.activityRef = new WeakReference<>((Activity) context);
        }
        return instance;
    }

    private PanelDriver(Context context) {
        appContext = context.getApplicationContext();
        settings = loadLens(context);

        DisplayMetrics m = new DisplayMetrics();
        WindowManager wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(m);
        pixelMm = 25.4f / m.xdpi;
        GlassLens.pixelMm = pixelMm;
        // Held in landscape for 3D, so the panel is the long side across.
        GlassLens.screenW = Math.max(m.widthPixels, m.heightPixels);
        GlassLens.screenH = Math.min(m.widthPixels, m.heightPixels);
        Log.i(TAG, String.format(Locale.US, "S25 film: pixel %.4f mm, pitch %.5f slant %.5f phase %.3f gap %.2f",
                pixelMm, GlassLens.pitch, GlassLens.slant, GlassLens.phase, GlassLens.gap));

        tracker = new EyeTracker(appContext, settings, s -> {
            if (s.valid) {
                GlassLens.eyeX = s.xMm;
                GlassLens.eyeY = s.yMm;
                GlassLens.eyeZ = s.zMm;
                GlassLens.eyeValid = true;
            }
        });
        GlassLens.enabled = true;
    }

    /** Lens values from storage into {@link GlassLens}. */
    private static GlassSettings loadLens(Context context) {
        GlassSettings s = new GlassSettings(context);
        GlassLens.pitch = s.pitch;
        GlassLens.slant = s.slant;
        GlassLens.phase = s.phase;
        GlassLens.gap = s.gap;
        GlassLens.trackY = s.trackY;
        GlassLens.swap = s.swap;
        GlassLens.tracking = s.tracking;
        return s;
    }

    /** Always false: the shader weaves, from the tracked eyes. */
    public boolean ownsInterlacing() {
        return false;
    }

    /** @return null, so the caller builds its own view and weaves in the shader. */
    public View createStreamView(Context context, int width, int height, SurfaceListener listener) {
        return null;
    }

    public boolean startConversion(Context context, Surface out, int eyeWidth, int eyeHeight,
                                   float strength, ConversionListener listener) {
        return false;
    }

    public void stopConversion() {
    }

    public void setStereoContent(boolean sideBySide) {
    }

    public void setSourceSize(int width, int height) {
    }

    /** Game sets the shader's 3D flag before telling the driver, so that flag is read here. */
    public void set3DMode(boolean enabled) {
        update();
    }

    public void onResume() {
        resumed = true;
        update();
    }

    public void onPause() {
        resumed = false;
        update();
    }

    public void onWindowFocusChanged(boolean hasFocus) {
    }

    public static synchronized void shutdown() {
        if (instance != null) {
            instance.resumed = false;
            instance.update();
            // The stream's activity is going; the next one gets a bar of its own.
            instance.tuner = null;
            instance.tunerActivity = null;
        }
        GlassLens.eyeValid = false;
    }

    /**
     * The small "3D 조절" button (top right) and its bar, on the stream's activity while 3D
     * is on: phase and tracking strength can be tuned while watching, and are kept.
     */
    private void showTuner(boolean show) {
        Activity a = activityRef.get();
        if (show && a != null && tunerActivity != a) {
            tuner = new GlassTuner(a, GlassLens.phase, GlassLens.gap,
                    GlassSettings.MIN_GAP, GlassSettings.MAX_GAP, (phase, gap) -> {
                        GlassLens.phase = phase;
                        GlassLens.gap = gap;
                        settings.phase = phase;
                        settings.gap = gap;
                        settings.save();
                    });
            a.addContentView(tuner, new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            tunerActivity = a;
        }
        if (tuner != null) {
            tuner.setVisibility(show ? View.VISIBLE : View.GONE);
            if (!show) {
                tuner.setOpen(false);
            }
        }
    }

    /** The camera runs only while 3D is on screen. */
    private void update() {
        boolean want = resumed && Stereo3DRenderer.isInterlaced;
        showTuner(want);
        if (!want) {
            if (tracking) {
                tracker.stop();
                tracking = false;
                GlassLens.eyeValid = false;
            }
            return;
        }
        if (tracking) {
            return;
        }
        Activity a = activityRef.get();
        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // When the prompt closes the activity resumes, and onResume tries again.
            if (a != null && !askedCamera) {
                askedCamera = true;
                a.requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            }
            return;
        }
        // The film was calibrated with the front camera on the left (display rotation 90).
        // Turned the other way the lens runs backwards and the picture looks like an
        // anaglyph, so 3D holds the screen that way round.
        if (a != null) {
            a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }
        WindowManager wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        Display d = wm.getDefaultDisplay();
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        int rot;
        switch (d.getRotation()) {
            case Surface.ROTATION_90: rot = 90; break;
            case Surface.ROTATION_180: rot = 180; break;
            case Surface.ROTATION_270: rot = 270; break;
            default: rot = 0; break;
        }
        tracker.start(rot, m.widthPixels * pixelMm, m.heightPixels * pixelMm);
        tracking = true;
    }
}
