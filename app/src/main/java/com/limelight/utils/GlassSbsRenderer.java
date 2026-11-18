package com.limelight.utils;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.view.Surface;

import com.limelight.LimeLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Shows a side-by-side stream from the host on a lens film ({@link GlassLens}): the decoder
 * draws into this renderer's texture and every screen pixel takes the left or right half by
 * the film's weave, following the tracked eyes.
 *
 * The film has no 3D service of its own to hand a side-by-side frame to (unlike the Leia
 * panels), so with the host already sending both eyes this is the whole 3D path. With 3D
 * toggled off ({@link Stereo3DRenderer#isInterlaced} false) the frame is shown as it arrives.
 */
public class GlassSbsRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    public interface SurfaceListener {
        /** The surface to decode into. Called on the GL thread. */
        void onSurfaceReady(Surface surface);
    }

    private static final float[] QUAD = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
    // Same orientation as Stereo3DRenderer: the decoder's frame arrives upside down to GL.
    private static final float[] TEX = {0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f};

    private final SurfaceListener listener;
    private final int streamWidth;
    private final int streamHeight;
    private final FloatBuffer quad;
    private final FloatBuffer tex;

    private int program;
    private int videoTexture;
    private int weaveTexture;
    private SurfaceTexture surfaceTexture;
    private Surface surface;
    private int width;
    private int height;

    public GlassSbsRenderer(int streamWidth, int streamHeight, SurfaceListener listener) {
        this.streamWidth = streamWidth;
        this.streamHeight = streamHeight;
        this.listener = listener;
        quad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(QUAD).position(0);
        tex = ByteBuffer.allocateDirect(TEX.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        tex.put(TEX).position(0);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        program = build(ShaderUtils.VERTEX_SHADER, ShaderUtils.FRAGMENT_SHADER_SBS_GLASS);
        weaveTexture = GlassLens.createWeaveTexture();

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        videoTexture = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        surfaceTexture = new SurfaceTexture(videoTexture);
        surfaceTexture.setOnFrameAvailableListener(this);
        surface = new Surface(surfaceTexture);
        listener.onSurfaceReady(surface);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        width = w;
        height = h;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        // The view renders continuously (the eyes move even when the picture does not), so
        // there is nothing to request here.
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        try {
            surfaceTexture.updateTexImage();
        } catch (Exception e) {
            return;
        }

        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // Box one eye's picture into the screen at its own shape. A full side-by-side frame
        // (two whole pictures, much wider than one) carries each eye at half its width; a
        // half one squeezes each eye to half width and is shown at the frame's own shape.
        // With 3D off the frame is shown whole, at its own shape.
        boolean threeD = Stereo3DRenderer.isInterlaced;
        float eyeAspect = 0f;
        if (streamWidth > 0 && streamHeight > 0) {
            float a = (float) streamWidth / streamHeight;
            eyeAspect = threeD && a > 2.2f ? a * 0.5f : a;
        }
        int dw = width;
        int dh = height;
        if (eyeAspect > 0f) {
            boolean wider = width > height * eyeAspect;
            if (wider != Stereo3DRenderer.fillDisplay) {
                dh = height;
                dw = Math.round(height * eyeAspect);
            } else {
                dw = width;
                dh = Math.round(width / eyeAspect);
            }
        }
        GLES20.glViewport((width - dw) / 2, (height - dh) / 2, dw, dh);

        GLES20.glUseProgram(program);
        GlassLens.apply(program, weaveTexture, 1, width, height);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "u_mode"), threeD ? 1f : 0f);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexture);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "s_ColorTexture"), 0);

        int pos = GLES20.glGetAttribLocation(program, "a_Position");
        int uv = GLES20.glGetAttribLocation(program, "a_TexCoord");
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, tex);
        GLES20.glEnableVertexAttribArray(pos);
        GLES20.glEnableVertexAttribArray(uv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(pos);
        GLES20.glDisableVertexAttribArray(uv);
    }

    /** The decoder is gone; let the texture's buffers go with it. */
    public void release() {
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
    }

    private static int build(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            LimeLog.severe("Glass SBS link failed: " + GLES20.glGetProgramInfoLog(p));
        }
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            LimeLog.severe("Glass SBS shader failed: " + GLES20.glGetShaderInfoLog(s));
        }
        return s;
    }
}
