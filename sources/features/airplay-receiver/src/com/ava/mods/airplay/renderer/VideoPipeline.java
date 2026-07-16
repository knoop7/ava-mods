package com.ava.mods.airplay.renderer;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public final class VideoPipeline {

    private static final String TAG = "VideoPipeline";

    private static final float[] POS_ARR = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
    private static final float[] TEX_ARR = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};

    private static final FloatBuffer POS = makeBuffer(POS_ARR);
    private static final FloatBuffer TEX = makeBuffer(TEX_ARR);

    private static final String VERT =
            "attribute vec2 aPos;\n" +
            "attribute vec2 aTex;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "  vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;\n" +
            "}\n";

    private static final String FRAG =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTex;\n" +
            "uniform samplerExternalOES sTex;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTex, vTex);\n" +
            "}\n";

    private final Object lock = new Object();
    private Thread thread;
    private volatile boolean running;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig eglConfig;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private int winW = 0;
    private int winH = 0;

    private int oesTex = 0;
    private int program = 0;
    private int aPos = 0;
    private int aTex = 0;
    private int uTexMatrix = 0;
    private final float[] texMatrix = new float[16];
    private boolean hasFrame = false;

    private SurfaceTexture surfaceTexture;
    private Surface inputSurface;

    private volatile boolean frameAvailable = false;
    private Surface pendingDisplay;
    private boolean displayDirty = false;
    private volatile int videoW = 0;
    private volatile int videoH = 0;

    public Surface getInputSurface() { return inputSurface; }

    public void start() {
        synchronized (lock) {
            if (running) return;
            running = true;
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "VideoPipeline");
            thread.start();
            while (inputSurface == null && running) {
                try { lock.wait(); } catch (InterruptedException e) { break; }
            }
        }
    }

    public void setDisplaySurface(Surface surface) {
        synchronized (lock) {
            pendingDisplay = surface;
            displayDirty = true;
            lock.notifyAll();
        }
    }

    public void setVideoSize(int w, int h) {
        videoW = w;
        videoH = h;
        if (surfaceTexture != null) surfaceTexture.setDefaultBufferSize(w, h);
    }

    public void release() {
        Thread t;
        synchronized (lock) {
            if (!running) return;
            running = false;
            lock.notifyAll();
            t = thread;
        }
        if (t != null) {
            try { t.join(); } catch (InterruptedException ignored) {}
        }
        thread = null;
    }

    private void loop() {
        try {
            initEgl();
            initGl();
        } catch (Exception e) {
            Log.e(TAG, "GL init failed", e);
            synchronized (lock) { running = false; lock.notifyAll(); }
            return;
        }
        Matrix.setIdentityM(texMatrix, 0);
        synchronized (lock) {
            inputSurface = new Surface(surfaceTexture);
            lock.notifyAll();
        }
        while (true) {
            Surface newDisplay = null;
            boolean displayChanged = false;
            boolean doFrame = false;
            synchronized (lock) {
                while (running && !frameAvailable && !displayDirty) {
                    try { lock.wait(); } catch (InterruptedException e) { break; }
                }
                if (running && displayDirty) {
                    newDisplay = pendingDisplay;
                    displayChanged = true;
                    displayDirty = false;
                }
                if (running && frameAvailable) {
                    frameAvailable = false;
                    doFrame = true;
                }
            }
            if (!running) break;
            if (displayChanged) bindDisplay(newDisplay);
            if (doFrame) consumeAndDraw();
        }
        releaseGl();
    }

    private void bindDisplay(Surface surface) {
        if (window != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext);
            EGL14.eglDestroySurface(eglDisplay, window);
            window = EGL14.EGL_NO_SURFACE;
        }
        if (surface == null || !surface.isValid()) return;
        window = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface,
                new int[]{EGL14.EGL_NONE}, 0);
        if (window == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "eglCreateWindowSurface failed: " + EGL14.eglGetError());
            return;
        }
        EGL14.eglMakeCurrent(eglDisplay, window, window, eglContext);
        winW = query(EGL14.EGL_WIDTH);
        winH = query(EGL14.EGL_HEIGHT);
        if (hasFrame) render();
    }

    private void consumeAndDraw() {
        SurfaceTexture st = surfaceTexture;
        if (st == null) return;
        if (window == EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext);
            st.updateTexImage();
            hasFrame = true;
            return;
        }
        EGL14.eglMakeCurrent(eglDisplay, window, window, eglContext);
        st.updateTexImage();
        st.getTransformMatrix(texMatrix);
        hasFrame = true;
        render();
    }

    private void render() {
        GLES20.glViewport(0, 0, winW, winH);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, POS);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, TEX);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aTex);
        EGL14.eglSwapBuffers(eglDisplay, window);
    }

    private int query(int what) {
        int[] v = new int[1];
        EGL14.eglQuerySurface(eglDisplay, window, what, v, 0);
        return v[0];
    }

    private void initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        EGL14.eglInitialize(eglDisplay, new int[2], 0, new int[2], 1);
        int[] attribs = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, new int[1], 0);
        eglConfig = configs[0];
        eglContext = EGL14.eglCreateContext(
                eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        pbuffer = EGL14.eglCreatePbufferSurface(
                eglDisplay, eglConfig,
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext);
    }

    private void initGl() {
        program = buildProgram();
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aTex = GLES20.glGetAttribLocation(program, "aTex");
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix");
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        oesTex = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        surfaceTexture = new SurfaceTexture(oesTex);
        if (videoW > 0 && videoH > 0) surfaceTexture.setDefaultBufferSize(videoW, videoH);
        surfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                synchronized (lock) {
                    frameAvailable = true;
                    lock.notifyAll();
                }
            }
        });
    }

    private int buildProgram() {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, VERT);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAG);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return p;
    }

    private int compileShader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) Log.e(TAG, "shader compile failed: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private void releaseGl() {
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, window);
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbuffer);
            EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        window = EGL14.EGL_NO_SURFACE;
        pbuffer = EGL14.EGL_NO_SURFACE;
    }

    private static FloatBuffer makeBuffer(float[] a) {
        FloatBuffer b = ByteBuffer.allocateDirect(a.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        b.put(a);
        b.position(0);
        return b;
    }
}
