package com.ava.mods.camerastream;

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
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Camera2 YUV capture shared by MJPEG HTTP. Optional encoder surface for RTSP/H.264.
 * Uses YUV_420_888 (not JPEG/BLOB) to avoid Qualcomm qdgralloc "Invalid format 0x21" spam.
 */
public final class CameraJpegSource {

    public interface FrameListener {
        void onJpegFrame(byte[] jpeg);
    }

    private static final String TAG = "CameraJpegSource";

    private final Context context;
    private final StreamConfig config;
    private final AtomicReference<byte[]> latestJpeg = new AtomicReference<>();
    private final Object lock = new Object();

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Surface encoderSurface;
    private FrameListener frameListener;
    private volatile boolean running;
    private long lastFrameAt;
    private byte[] nv21Scratch;

    public CameraJpegSource(Context context, StreamConfig config) {
        this.context = context.getApplicationContext();
        this.config = config;
    }

    public byte[] getLatestJpeg() {
        return latestJpeg.get();
    }

    public void setFrameListener(FrameListener listener) {
        this.frameListener = listener;
    }

    public void setEncoderSurface(Surface surface) {
        synchronized (lock) {
            this.encoderSurface = surface;
        }
    }

    public boolean isRunning() {
        return running;
    }

    @SuppressLint("MissingPermission")
    public void start() {
        synchronized (lock) {
            if (running) return;
            running = true;
            cameraThread = new HandlerThread("ava-camera-stream");
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
            Log.e(TAG, "CameraManager unavailable");
            return;
        }
        try {
            String cameraId = pickCameraId(manager, config.useFrontCamera);
            if (cameraId == null) {
                Log.e(TAG, "No camera found");
                return;
            }
            Size captureSize = pickYuvSize(manager, cameraId, config.resolution);
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
                    byte[] jpeg = yuvToJpeg(image, config.jpegQuality);
                    if (jpeg == null) return;
                    latestJpeg.set(jpeg);
                    FrameListener listener = frameListener;
                    if (listener != null) {
                        listener.onJpegFrame(jpeg);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "YUV frame error: " + e.getMessage());
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
                    Log.w(TAG, "Camera disconnected");
                    closeCameraLocked();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    closeCameraLocked();
                }
            }, cameraHandler);
        } catch (SecurityException e) {
            Log.e(TAG, "CAMERA permission missing", e);
        } catch (Exception e) {
            Log.e(TAG, "openCamera failed", e);
        }
    }

    private void createSession() {
        if (!running || cameraDevice == null || imageReader == null) return;
        try {
            Surface yuvSurface = imageReader.getSurface();
            Surface enc = encoderSurface;
            final List<Surface> targets = enc != null
                    ? Arrays.asList(yuvSurface, enc)
                    : Collections.singletonList(yuvSurface);

            cameraDevice.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        CaptureRequest.Builder builder =
                                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        builder.addTarget(yuvSurface);
                        if (enc != null) {
                            builder.addTarget(enc);
                        }
                        builder.set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        session.setRepeatingRequest(builder.build(), null, cameraHandler);
                        Log.i(TAG, "Capture session started targets=" + targets.size()
                                + " format=YUV_420_888");
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "setRepeatingRequest failed", e);
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    Log.e(TAG, "Capture session configure failed");
                }
            }, cameraHandler);
        } catch (Exception e) {
            Log.e(TAG, "createSession failed", e);
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
        if (!yuv.compressToJpeg(new Rect(0, 0, width, height),
                Math.max(40, Math.min(95, quality)), out)) {
            return null;
        }
        return out.toByteArray();
    }

    /** Pack YUV_420_888 into NV21 (Y + interleaved VU). */
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

    private static String pickCameraId(CameraManager manager, boolean front) throws CameraAccessException {
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
        android.hardware.camera2.params.StreamConfigurationMap map =
                c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map != null ? map.getOutputSizes(ImageFormat.YUV_420_888) : null;
        if (sizes == null || sizes.length == 0) {
            return new Size(640, 480);
        }
        int target = Math.max(240, Math.min(shortEdge, 1080));
        Size best = sizes[0];
        int bestScore = Integer.MAX_VALUE;
        for (Size s : sizes) {
            int shortSide = Math.min(s.getWidth(), s.getHeight());
            int score = Math.abs(shortSide - target);
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        Arrays.sort(sizes, new Comparator<Size>() {
            @Override
            public int compare(Size a, Size b) {
                return Integer.compare(
                        Math.min(a.getWidth(), a.getHeight()),
                        Math.min(b.getWidth(), b.getHeight())
                );
            }
        });
        Size lower = null;
        for (Size s : sizes) {
            if (Math.min(s.getWidth(), s.getHeight()) <= target) {
                lower = s;
            }
        }
        return lower != null ? lower : best;
    }
}
