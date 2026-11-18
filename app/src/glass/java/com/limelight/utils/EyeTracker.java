package com.limelight.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.Face;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Finds the viewer's eyes with the front camera.
 *
 * Primary: Google MediaPipe Face Landmarker (the same family of model Spatial Glass ships):
 * the two iris centres every frame. Distance comes from the iris spacing, corrected for
 * how far the head is turned (from the landmarker's head pose). Fallback, if the model
 * cannot start: the camera's own face box.
 *
 * Positions are reported in millimetres relative to the centre of the screen:
 * x to the viewer's right, y up, z out of the screen towards the viewer.
 *
 * Camera-to-screen mapping: the front image is treated as a mirror, the way a selfie
 * preview is shown. In a mirror a face appears straight in front of where it really is,
 * so the mirrored, display-rotated image point directly gives the direction of the face
 * from the camera.
 */
final class EyeTracker {
    private static final String TAG = "EyeTracker";
    private static final float IPD_MM = 63f;
    private static final float FACE_WIDTH_MM = 150f;
    /** Distance of the camera centre from the screen edge it sits on, in mm. */
    private static final float CAMERA_INSET_MM = 5f;
    private static final int FRAME_W = 640;
    private static final int FRAME_H = 480;
    /** MediaPipe face mesh with irises: centre points of the two irises. */
    private static final int IRIS_A = 468;
    private static final int IRIS_B = 473;

    static final class State {
        boolean cameraOk;
        String error;
        int faceMode;        // 0 off, 1 simple (box), 2 full (eyes), 3 landmarks (irises)
        float fps;
        boolean found;       // a face in the latest frame
        boolean valid;       // a face has been seen at least once; x/y/z hold the last one
        float xMm;
        float yMm;
        float zMm;
        RectF face;          // mirror-view normalised 0..1
        PointF eye;          // mirror-view normalised 0..1
        Bitmap thumb;        // mirror-view greyscale camera image, for the debug overlay
        String info = "";
    }

    interface Listener {
        void onUpdate(State state);
    }

    /** One Euro filter: smooth when still, quick when moving. */
    private static final class OneEuro {
        private final double minCutoff;
        private final double beta;
        private double x;
        private double dx;
        private long t;
        private boolean init;

        OneEuro(double minCutoff, double beta) {
            this.minCutoff = minCutoff;
            this.beta = beta;
        }

        double filter(double v, long tMs) {
            if (!init) {
                init = true;
                x = v;
                dx = 0;
                t = tMs;
                return v;
            }
            double dt = Math.max(1e-3, (tMs - t) / 1000.0);
            t = tMs;
            double rate = (v - x) / dt;
            dx += alpha(dt, 1.0) * (rate - dx);
            double cutoff = minCutoff + beta * Math.abs(dx);
            x += alpha(dt, cutoff) * (v - x);
            return x;
        }

        private static double alpha(double dt, double cutoff) {
            double tau = 1.0 / (2 * Math.PI * cutoff);
            return 1.0 / (1.0 + tau / dt);
        }
    }

    private final Context context;
    private final GlassSettings GlassSettings;
    private final Listener listener;

    private HandlerThread thread;
    private Handler handler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private FaceLandmarker landmarker;
    private String landmarkerInfo = "";

    private Rect active;
    private int rotation;        // clockwise rotation applied after the mirror
    private float tanX;          // tan of half the field of view along the mirror view's x
    private float tanY;
    private float camX;          // camera position relative to screen centre, mm
    private float camY;
    private int faceMode;
    private String info = "";

    private boolean valid;
    private float sx;
    private float sy;
    private float sz;
    private final OneEuro fx = new OneEuro(1.5, 0.05);
    private final OneEuro fy = new OneEuro(1.5, 0.05);
    // Distance sets the whole-screen lens period, so its noise shows up as ripples running
    // across the picture. Real viewing distance changes slowly: smooth it much harder than
    // x/y (0.15 Hz floor, opening up only for deliberate moves).
    private final OneEuro fz = new OneEuro(0.15, 0.004);
    private volatile Bitmap thumb;
    private long lastThumb;
    private int frames;
    private long fpsStart;
    private float fps;
    private long lastTs;
    private int[] gray;
    private byte[] yBytes;
    private Bitmap grayBitmap;
    private long detectMs;
    private long convMs;
    private int timed;
    private float ageSum;
    /** About one display frame from handing the position to the renderer to light on screen. */
    private static final float RENDER_MS = 20f;
    /** Clamp for the predicted head speed; faster than this is treated as a tracking jump. */
    private static final float MAX_SPEED = 800f;
    private float leadS = 0.08f;
    private float vx;
    private float vy;
    private float px;
    private float py;
    private float lastSx;
    private float lastSy;
    private long lastPredT;

    EyeTracker(Context context, GlassSettings GlassSettings, Listener listener) {
        this.context = context;
        this.GlassSettings = GlassSettings;
        this.listener = listener;
    }

    /**
     * @param displayRotation display rotation in degrees (0, 90, 180, 270)
     * @param screenWmm       current screen width in mm (in the current orientation)
     * @param screenHmm       current screen height in mm
     */
    void start(int displayRotation, float screenWmm, float screenHmm) {
        if (thread != null) {
            return;
        }
        thread = new HandlerThread("eye-tracker");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(() -> {
            initLandmarker();
            openCamera(displayRotation, screenWmm, screenHmm);
        });
    }

    private void initLandmarker() {
        if (landmarker != null) {
            return;
        }
        // GPU measured faster on this phone (detect ~23 ms vs ~29 ms on CPU).
        for (Delegate delegate : new Delegate[]{Delegate.GPU, Delegate.CPU}) {
            try {
                FaceLandmarker.FaceLandmarkerOptions options = FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder()
                                .setModelAssetPath("face_landmarker.task")
                                .setDelegate(delegate)
                                .build())
                        .setRunningMode(RunningMode.VIDEO)
                        .setNumFaces(1)
                        .setMinFaceDetectionConfidence(0.5f)
                        .setMinFacePresenceConfidence(0.5f)
                        .setMinTrackingConfidence(0.5f)
                        .setOutputFacialTransformationMatrixes(true)
                        .build();
                landmarker = FaceLandmarker.createFromOptions(context, options);
                landmarkerInfo = "landmarks " + delegate;
                Log.i(TAG, "Face Landmarker ready on " + delegate);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "Face Landmarker on " + delegate + " failed: " + t);
            }
        }
        landmarkerInfo = "landmarks unavailable";
    }

    @SuppressLint("MissingPermission")
    private void openCamera(int displayRotation, float screenWmm, float screenHmm) {
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String id = null;
            CameraCharacteristics ch = null;
            for (String c : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(c);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    id = c;
                    ch = cc;
                    break;
                }
            }
            if (id == null) {
                fail(context.getString(com.limelight.R.string.glass_err_no_front));
                return;
            }

            int[] modes = ch.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
            int hwFace = 0;
            if (modes != null) {
                for (int m : modes) {
                    hwFace = Math.max(hwFace, m);
                }
            }
            faceMode = landmarker != null ? 3 : hwFace;
            if (faceMode == 0) {
                fail(context.getString(com.limelight.R.string.glass_err_no_face));
                return;
            }

            int sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Size pixels = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            SizeF phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            float focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)[0];
            float activeW = phys.getWidth() * active.width() / pixels.getWidth();
            float activeH = phys.getHeight() * active.height() / pixels.getHeight();
            float tanU = activeW / 2f / focal;
            float tanV = activeH / 2f / focal;

            // Front camera: the image is mirrored, then rotated clockwise by this much
            // (the classic Camera.setDisplayOrientation rule for a front camera).
            rotation = (360 - (sensorOrientation + displayRotation) % 360) % 360;
            boolean swapped = rotation == 90 || rotation == 270;
            tanX = swapped ? tanV : tanU;
            tanY = swapped ? tanU : tanV;

            // In the natural (portrait) orientation the camera sits at the top centre.
            switch (displayRotation) {
                case 90:  camX = -screenWmm / 2f + CAMERA_INSET_MM; camY = 0f; break;
                case 270: camX = screenWmm / 2f - CAMERA_INSET_MM;  camY = 0f; break;
                case 180: camX = 0f; camY = -screenHmm / 2f + CAMERA_INSET_MM; break;
                default:  camX = 0f; camY = screenHmm / 2f - CAMERA_INSET_MM; break;
            }

            // Fastest steady frame rate the front camera offers.
            Range<Integer>[] ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            Range<Integer> best = null;
            if (ranges != null) {
                for (Range<Integer> r : ranges) {
                    if (best == null || r.getUpper() > best.getUpper()
                            || (r.getUpper().equals(best.getUpper()) && r.getLower() > best.getLower())) {
                        best = r;
                    }
                }
            }
            Range<Integer> fpsRange = best;

            info = String.format(Locale.US, "cam %s · %s · sensor %d° · display %d° → rot %d° · fov %.0f°x%.0f° · fps %s",
                    id, landmarker != null ? landmarkerInfo : "face box mode " + hwFace, sensorOrientation,
                    displayRotation, rotation, Math.toDegrees(2 * Math.atan(tanX)), Math.toDegrees(2 * Math.atan(tanY)),
                    fpsRange);
            Log.i(TAG, info + String.format(Locale.US, " · camera at (%.1f, %.1f) mm", camX, camY));

            int hwFaceMode = hwFace;
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    device = camera;
                    startSession(fpsRange, landmarker != null ? 0 : hwFaceMode);
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    device = null;
                    fail(context.getString(com.limelight.R.string.glass_err_disconnected));
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    device = null;
                    fail(context.getString(com.limelight.R.string.glass_err_camera, error));
                }
            }, handler);
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            fail(context.getString(com.limelight.R.string.glass_err_start, e.getMessage()));
        }
    }

    void stop() {
        if (thread == null) {
            return;
        }
        Handler h = handler;
        HandlerThread t = thread;
        h.post(() -> {
            try {
                if (session != null) {
                    session.close();
                }
                if (device != null) {
                    device.close();
                }
                if (reader != null) {
                    reader.close();
                }
            } catch (Exception e) {
                Log.w(TAG, "stop", e);
            }
            session = null;
            device = null;
            reader = null;
        });
        t.quitSafely();
        thread = null;
        handler = null;
    }

    @SuppressWarnings("deprecation")
    private void startSession(Range<Integer> fpsRange, int hwFaceMode) {
        try {
            reader = ImageReader.newInstance(FRAME_W, FRAME_H, ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::onImage, handler);
            device.createCaptureSession(Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                b.addTarget(reader.getSurface());
                                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, hwFaceMode);
                                if (fpsRange != null) {
                                    b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
                                }
                                s.setRepeatingRequest(b.build(), new CameraCaptureSession.CaptureCallback() {
                                    @Override
                                    public void onCaptureCompleted(CameraCaptureSession cs, CaptureRequest r,
                                                                   TotalCaptureResult result) {
                                        if (landmarker == null) {
                                            onFaceBoxResult(result);
                                        }
                                    }
                                }, handler);
                                fpsStart = SystemClock.uptimeMillis();
                            } catch (Exception e) {
                                Log.e(TAG, "request failed", e);
                                fail(context.getString(com.limelight.R.string.glass_err_request, e.getMessage()));
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            fail(context.getString(com.limelight.R.string.glass_err_session_cfg));
                        }
                    }, handler);
        } catch (Exception e) {
            Log.e(TAG, "session failed", e);
            fail(context.getString(com.limelight.R.string.glass_err_session, e.getMessage()));
        }
    }

    private void countFrame() {
        frames++;
        long now = SystemClock.uptimeMillis();
        if (now - fpsStart >= 1000) {
            fps = frames * 1000f / (now - fpsStart);
            frames = 0;
            fpsStart = now;
        }
    }

    // ---- landmarks (primary) ----

    private void onImage(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        long captureNs = img.getTimestamp();
        try {
            Image.Plane yPlane = img.getPlanes()[0];
            ByteBuffer buf = yPlane.getBuffer();
            int rowStride = yPlane.getRowStride();
            int pixStride = yPlane.getPixelStride();
            int w = img.getWidth();
            int h = img.getHeight();
            if (GlassSettings.showTrack && now - lastThumb >= 200) {
                lastThumb = now;
                makeThumb(buf, rowStride, pixStride, w, h);
            }
            if (landmarker == null) {
                return;
            }
            if (gray == null || gray.length != w * h) {
                gray = new int[w * h];
                grayBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            // One bulk copy of the luma plane, then plain array work (per-pixel
            // ByteBuffer.get was the bottleneck: ~16 fps).
            int need = (h - 1) * rowStride + (w - 1) * pixStride + 1;
            if (yBytes == null || yBytes.length < need) {
                yBytes = new byte[need];
            }
            buf.position(0);
            buf.get(yBytes, 0, Math.min(need, buf.remaining()));
            for (int j = 0; j < h; j++) {
                int row = j * rowStride;
                int o = j * w;
                for (int i = 0; i < w; i++) {
                    int l = yBytes[row + i * pixStride] & 0xff;
                    gray[o + i] = 0xff000000 | (l * 0x010101);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "frame", e);
            return;
        } finally {
            img.close();
        }

        countFrame();
        grayBitmap.setPixels(gray, 0, grayBitmap.getWidth(), 0, 0, grayBitmap.getWidth(), grayBitmap.getHeight());
        MPImage mp = new BitmapImageBuilder(grayBitmap).build();
        long ts = Math.max(now, lastTs + 1);
        lastTs = ts;
        FaceLandmarkerResult res;
        long t0 = SystemClock.uptimeMillis();
        try {
            res = landmarker.detectForVideo(mp, ts);
        } catch (Throwable t) {
            Log.w(TAG, "detect", t);
            return;
        }
        long t1 = SystemClock.uptimeMillis();
        detectMs += t1 - t0;
        convMs += t0 - now;
        // Age of this frame's eye position: from the sensor exposure to now (the camera
        // stamps frames on the realtime clock). Anything implausible falls back to 60 ms.
        float ageMs = (SystemClock.elapsedRealtimeNanos() - captureNs) / 1e6f;
        if (!(ageMs > 0f && ageMs < 300f)) {
            ageMs = 60f;
        }
        ageSum += ageMs;
        // Draw where the eyes will be when this frame reaches the screen.
        leadS = (ageMs + RENDER_MS) / 1000f;
        if (++timed >= 120) {
            Log.i(TAG, String.format(Locale.US,
                    "per frame: convert %.1f ms, detect %.1f ms, frame age %.0f ms, lead %.0f ms, %.1f fps, speed x %.0f y %.0f mm/s",
                    convMs / (float) timed, detectMs / (float) timed, ageSum / timed, leadS * 1000f, fps, vx, vy));
            timed = 0;
            detectMs = 0;
            convMs = 0;
            ageSum = 0;
        }
        onLandmarks(res, ts);
    }

    private void onLandmarks(FaceLandmarkerResult res, long ts) {
        State s = newState();
        List<List<NormalizedLandmark>> faces = res.faceLandmarks();
        if (faces != null && !faces.isEmpty() && faces.get(0).size() > IRIS_B) {
            List<NormalizedLandmark> lm = faces.get(0);
            PointF a = toViewNorm(lm.get(IRIS_A).x(), lm.get(IRIS_A).y());
            PointF b = toViewNorm(lm.get(IRIS_B).x(), lm.get(IRIS_B).y());
            PointF eye = new PointF((a.x + b.x) / 2f, (a.y + b.y) / 2f);

            // Head turn shortens the eye spacing seen by the camera; undo it with the head
            // pose so distance does not jump when the head turns.
            float cosYaw = 1f;
            if (res.facialTransformationMatrixes().isPresent()
                    && !res.facialTransformationMatrixes().get().isEmpty()) {
                float[] m = res.facialTransformationMatrixes().get().get(0);
                cosYaw = (float) Math.sqrt(m[0] * m[0] + m[1] * m[1]);
                cosYaw = Math.max(0.5f, Math.min(1f, cosYaw));
            }
            float span = (float) Math.hypot((a.x - b.x) * 2f * tanX, (a.y - b.y) * 2f * tanY);
            float zRaw = IPD_MM * cosYaw / Math.max(span, 1e-3f);
            sz = (float) fz.filter(Math.max(200f, Math.min(900f, zRaw)), ts);
            // Position from the direction to the eyes at the smoothed distance, so distance
            // noise does not leak into x/y either.
            float x = camX + (eye.x - 0.5f) * 2f * tanX * sz;
            float y = camY - (eye.y - 0.5f) * 2f * tanY * sz;

            sx = (float) fx.filter(x, ts);
            sy = (float) fy.filter(y, ts);
            predict(ts);
            valid = true;

            float minX = 1f;
            float minY = 1f;
            float maxX = 0f;
            float maxY = 0f;
            for (int i = 0; i < lm.size(); i += 4) {
                PointF p = toViewNorm(lm.get(i).x(), lm.get(i).y());
                minX = Math.min(minX, p.x);
                maxX = Math.max(maxX, p.x);
                minY = Math.min(minY, p.y);
                maxY = Math.max(maxY, p.y);
            }
            s.found = true;
            s.face = new RectF(minX, minY, maxX, maxY);
            s.eye = eye;
        } else {
            // Face lost: drop the motion estimate. Otherwise the last speed stays and keeps
            // pushing the predicted position (166 mm/s x 0.11 s = 18 mm seen standing still),
            // and the first frame after the face comes back would count the gap as motion.
            vx = 0f;
            vy = 0f;
            lastPredT = 0;
            px = sx;
            py = sy;
        }
        publish(s);
    }

    // ---- camera face box (fallback) ----

    private void onFaceBoxResult(TotalCaptureResult result) {
        countFrame();
        Face[] faces = result.get(CaptureResult.STATISTICS_FACES);
        Face best = null;
        if (faces != null) {
            for (Face f : faces) {
                if (f.getScore() < 20) {
                    continue;
                }
                if (best == null || area(f.getBounds()) > area(best.getBounds())) {
                    best = f;
                }
            }
        }
        State s = newState();
        if (best != null) {
            Rect bb = best.getBounds();
            PointF a = toView(bb.left, bb.top);
            PointF c = toView(bb.right, bb.bottom);
            RectF face = new RectF(Math.min(a.x, c.x), Math.min(a.y, c.y), Math.max(a.x, c.x), Math.max(a.y, c.y));
            PointF eye;
            float z;
            Point le = best.getLeftEyePosition();
            Point re = best.getRightEyePosition();
            if (le != null && re != null) {
                PointF l = toView(le.x, le.y);
                PointF r = toView(re.x, re.y);
                eye = new PointF((l.x + r.x) / 2f, (l.y + r.y) / 2f);
                float span = (float) Math.hypot((l.x - r.x) * 2f * tanX, (l.y - r.y) * 2f * tanY);
                z = IPD_MM / Math.max(span, 1e-3f);
            } else {
                // Eyes sit a little above the middle of the face box.
                eye = new PointF(face.centerX(), face.top + 0.42f * face.height());
                z = FACE_WIDTH_MM / Math.max(face.width() * 2f * tanX, 1e-3f);
            }
            long ts = SystemClock.uptimeMillis();
            sx = (float) fx.filter(camX + (eye.x - 0.5f) * 2f * tanX * z, ts);
            sy = (float) fy.filter(camY - (eye.y - 0.5f) * 2f * tanY * z, ts);
            sz = (float) fz.filter(z, ts);
            valid = true;
            s.found = true;
            s.face = face;
            s.eye = eye;
        }
        publish(s);
    }

    private State newState() {
        State s = new State();
        s.cameraOk = true;
        s.faceMode = faceMode;
        s.fps = fps;
        s.thumb = thumb;
        s.info = info;
        return s;
    }

    /**
     * Velocity of the smoothed eye position (its own quick 6 Hz smoothing, so it does not
     * lag like the One Euro derivative) and the predicted position {@code leadS} ahead.
     * Standing still gives zero velocity, so nothing changes then.
     */
    private void predict(long tsMs) {
        if (lastPredT > 0) {
            float dt = Math.max(1e-3f, (tsMs - lastPredT) / 1000f);
            float a = (float) (1.0 / (1.0 + 1.0 / (2 * Math.PI * 6.0 * dt)));
            vx += a * ((sx - lastSx) / dt - vx);
            vy += a * ((sy - lastSy) / dt - vy);
            vx = Math.max(-MAX_SPEED, Math.min(MAX_SPEED, vx));
            vy = Math.max(-MAX_SPEED, Math.min(MAX_SPEED, vy));
        }
        lastPredT = tsMs;
        lastSx = sx;
        lastSy = sy;
        px = sx + vx * leadS;
        py = sy + vy * leadS;
    }

    private void publish(State s) {
        s.valid = valid;
        s.xMm = landmarker != null ? px : sx;
        s.yMm = landmarker != null ? py : sy;
        s.zMm = sz;
        listener.onUpdate(s);
    }

    /** Sensor active-array coordinates → mirror-view normalised coordinates (y down). */
    private PointF toView(float sxp, float syp) {
        float u = (sxp - active.left) / active.width();
        float v = (syp - active.top) / active.height();
        return toViewNorm(u, v);
    }

    /** Sensor-image normalised coordinates (as the camera frame) → mirror view. */
    private PointF toViewNorm(float u, float v) {
        u = 1f - u; // mirror
        float x;
        float y;
        switch (rotation) {
            case 90:  x = 1f - v; y = u;      break;
            case 180: x = 1f - u; y = 1f - v; break;
            case 270: x = v;      y = 1f - u; break;
            default:  x = u;      y = v;      break;
        }
        if (GlassSettings.flipX) {
            x = 1f - x;
        }
        if (GlassSettings.flipY) {
            y = 1f - y;
        }
        return new PointF(x, y);
    }

    private void makeThumb(ByteBuffer buf, int rowStride, int pixStride, int w, int h) {
        int sw = 160;
        int sh = 120;
        int stepX = w / sw;
        int stepY = h / sh;
        boolean swapped = rotation == 90 || rotation == 270;
        int ow = swapped ? sh : sw;
        int oh = swapped ? sw : sh;
        int[] out = new int[ow * oh];
        for (int j = 0; j < sh; j++) {
            for (int i = 0; i < sw; i++) {
                int lum = buf.get(j * stepY * rowStride + i * stepX * pixStride) & 0xff;
                PointF p = toViewNorm((i + 0.5f) / sw, (j + 0.5f) / sh);
                int ox = Math.min(ow - 1, (int) (p.x * ow));
                int oy = Math.min(oh - 1, (int) (p.y * oh));
                out[oy * ow + ox] = 0xff000000 | (lum << 16) | (lum << 8) | lum;
            }
        }
        thumb = Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888);
    }

    private void fail(String message) {
        Log.e(TAG, message);
        State s = new State();
        s.error = message;
        s.info = info;
        listener.onUpdate(s);
    }

    private static int area(Rect r) {
        return r.width() * r.height();
    }
}
