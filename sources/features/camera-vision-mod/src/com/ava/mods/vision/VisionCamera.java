package com.ava.mods.vision;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

public final class VisionCamera {

    public interface FrameListener {
        void onJpegFrame(byte[] jpeg);
    }

    private static final String TAG = "VisionCamera";

    private final Context context;
    private final VisionConfig config;
    private final AtomicReference<byte[]> latestJpeg = new AtomicReference<>();
    private final Object lock = new Object();

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private FrameListener frameListener;
    private volatile boolean running;
    private volatile String lastError = "";
    private volatile int measuredFps = 0;
    private long lastFrameAt;
    private long fpsWindowStart;
    private int fpsWindowCount;
    private byte[] nv21Scratch;

    public VisionCamera(Context context, VisionConfig config) {
        this.context = context.getApplicationContext();
        this.config = config;
    }

    public byte[] getLatestJpeg() {
        return latestJpeg.get();
    }

    public void setFrameListener(FrameListener listener) {
        this.frameListener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    public int getMeasuredFps() {
        return measuredFps;
    }

    public String getLastError() {
        return lastError;
    }

    @SuppressLint("MissingPermission")
    public void start() {
        synchronized (lock) {
            if (running) return;
            running = true;
            lastError = "";
            fpsWindowStart = System.currentTimeMillis();
            fpsWindowCount = 0;
            cameraThread = new HandlerThread("ava-vision-camera");
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
            cameraHandler.post(this::openCamera);
        }
    }

    public void stop() {
        synchronized (lock) {
            running = false;
            if (cameraHandler != null) {
                cameraHandler.post(this::closeCameraLocked);
            } else {
                closeCameraLocked();
            }
            if (cameraThread != null) {
                cameraThread.quitSafely();
                try {
                    cameraThread.join(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                cameraThread = null;
                cameraHandler = null;
            }
            latestJpeg.set(null);
            nv21Scratch = null;
            measuredFps = 0;
        }
    }

    public void restart() {
        stop();
        start();
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        if (!running) return;
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            lastError = "CameraManager unavailable";
            Log.e(TAG, lastError);
            return;
        }
        try {
            String cameraId = pickCameraId(manager, config.useFrontCamera);
            if (cameraId == null) {
                lastError = "No camera found";
                Log.e(TAG, lastError);
                return;
            }
            Size captureSize = pickYuvSize(manager, cameraId, config.safeResolution());
            imageReader = ImageReader.newInstance(
                    captureSize.getWidth(),
                    captureSize.getHeight(),
                    ImageFormat.YUV_420_888,
                    2
            );
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image == null) return;
                    long now = System.currentTimeMillis();
                    if (now - lastFrameAt < config.frameIntervalMs()) {
                        return;
                    }
                    lastFrameAt = now;
                    byte[] jpeg = yuvToJpeg(image, config.safeQuality());
                    if (jpeg == null) return;
                    latestJpeg.set(jpeg);
                    trackFps(now);
                    FrameListener listener = frameListener;
                    if (listener != null) {
                        listener.onJpegFrame(jpeg);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Frame error: " + e.getMessage());
                } finally {
                    if (image != null) image.close();
                }
            }, cameraHandler);

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    createSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    lastError = "Camera disconnected";
                    Log.w(TAG, lastError);
                    closeCameraLocked();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    lastError = "Camera error " + error;
                    Log.e(TAG, lastError);
                    closeCameraLocked();
                }
            }, cameraHandler);
        } catch (SecurityException e) {
            lastError = "CAMERA permission missing";
            Log.e(TAG, lastError, e);
        } catch (Exception e) {
            lastError = String.valueOf(e.getMessage());
            Log.e(TAG, "openCamera failed", e);
        }
    }

    private void createSession() {
        if (!running || cameraDevice == null || imageReader == null) return;
        try {
            final Surface yuvSurface = imageReader.getSurface();
            cameraDevice.createCaptureSession(
                    Collections.singletonList(yuvSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                CaptureRequest.Builder builder = cameraDevice
                                        .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                builder.addTarget(yuvSurface);
                                builder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                session.setRepeatingRequest(builder.build(), null, cameraHandler);
                                Log.i(TAG, "Vision camera started "
                                        + (config.useFrontCamera ? "front" : "back")
                                        + " @" + config.fps + "fps");
                            } catch (CameraAccessException e) {
                                lastError = "setRepeatingRequest failed";
                                Log.e(TAG, lastError, e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            lastError = "Capture session configure failed";
                            Log.e(TAG, lastError);
                        }
                    }, cameraHandler);
        } catch (Exception e) {
            lastError = String.valueOf(e.getMessage());
            Log.e(TAG, "createSession failed", e);
        }
    }

    private void trackFps(long now) {
        fpsWindowCount++;
        long elapsed = now - fpsWindowStart;
        if (elapsed >= 2000) {
            measuredFps = (int) Math.round(fpsWindowCount * 1000.0 / elapsed);
            fpsWindowStart = now;
            fpsWindowCount = 0;
        }
    }

    private byte[] yuvToJpeg(Image image, int quality) {
        int width = image.getWidth();
        int height = image.getHeight();
        int needed = width * height * 3 / 2;
        if (nv21Scratch == null || nv21Scratch.length < needed) {
            nv21Scratch = new byte[needed];
        }
        if (!imageToNv21(image, nv21Scratch)) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(needed / 2);
        YuvImage yuv = new YuvImage(nv21Scratch, ImageFormat.NV21, width, height, null);
        if (!yuv.compressToJpeg(new Rect(0, 0, width, height), quality, out)) {
            return null;
        }
        return out.toByteArray();
    }

    private static boolean imageToNv21(Image image, byte[] nv21) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 3) return false;

        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane yPlane = planes[0];
        Image.Plane uPlane = planes[1];
        Image.Plane vPlane = planes[2];

        ByteBuffer yBuffer = yPlane.getBuffer().duplicate();
        ByteBuffer uBuffer = uPlane.getBuffer().duplicate();
        ByteBuffer vBuffer = vPlane.getBuffer().duplicate();

        int yRowStride = yPlane.getRowStride();
        int uvRowStride = uPlane.getRowStride();
        int uvPixelStride = uPlane.getPixelStride();

        int pos = 0;
        for (int row = 0; row < height; row++) {
            int yOffset = row * yRowStride;
            if (yOffset + width > yBuffer.capacity()) return false;
            yBuffer.position(yOffset);
            yBuffer.get(nv21, pos, width);
            pos += width;
        }

        int uvHeight = height / 2;
        int uvWidth = width / 2;
        for (int row = 0; row < uvHeight; row++) {
            for (int col = 0; col < uvWidth; col++) {
                int uvOffset = row * uvRowStride + col * uvPixelStride;
                if (uvOffset < vBuffer.capacity() && uvOffset < uBuffer.capacity()) {
                    nv21[pos++] = vBuffer.get(uvOffset);
                    nv21[pos++] = uBuffer.get(uvOffset);
                } else {
                    nv21[pos++] = (byte) 128;
                    nv21[pos++] = (byte) 128;
                }
            }
        }
        return true;
    }

    private void closeCameraLocked() {
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        } catch (Exception ignored) {
        }
    }

    private static String pickCameraId(CameraManager manager, boolean front)
            throws CameraAccessException {
        String fallback = null;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing == null) continue;
            boolean isFront = facing == CameraCharacteristics.LENS_FACING_FRONT;
            if (front == isFront) return id;
            if (fallback == null) fallback = id;
        }
        return fallback;
    }

    private static Size pickYuvSize(CameraManager manager, String cameraId, int shortEdge)
            throws CameraAccessException {
        CameraCharacteristics c = manager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map != null ? map.getOutputSizes(ImageFormat.YUV_420_888) : null;
        if (sizes == null || sizes.length == 0) {
            return new Size(640, 480);
        }
        Size best = sizes[0];
        int bestScore = Integer.MAX_VALUE;
        for (Size s : sizes) {
            int shortSide = Math.min(s.getWidth(), s.getHeight());
            int score = Math.abs(shortSide - shortEdge);
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }
}
