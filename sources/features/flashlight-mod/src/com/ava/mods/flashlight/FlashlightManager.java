package com.ava.mods.flashlight;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Torch control backed by {@link CameraManager#setTorchMode}, which drives the flash unit without
 * opening a camera session. The torch therefore works while nothing is capturing and never competes
 * with a camera stream for the device.
 *
 * State is read from the system's own {@link CameraManager.TorchCallback} rather than from the last
 * command sent, so it stays correct when something else turns the torch off — which happens when
 * another app opens that camera, or when the device gets too hot.
 */
public class FlashlightManager {

    private static final String TAG = "FlashlightManager";
    private static final String ENTITY_TORCH = "torch";

    private static volatile FlashlightManager instance;

    private final Context context;
    private final CameraManager cameraManager;
    private final CopyOnWriteArrayList<Object> torchListeners = new CopyOnWriteArrayList<>();

    private String cameraId;
    private boolean hasFlashlight = false;
    private volatile boolean isOn = false;
    private CameraManager.TorchCallback torchCallback;

    private FlashlightManager(Context context) {
        this.context = context.getApplicationContext();
        this.cameraManager = (CameraManager) this.context.getSystemService(Context.CAMERA_SERVICE);
        initCamera();
        registerTorchCallback();
    }

    public static FlashlightManager getInstance(Context context) {
        if (instance == null) {
            synchronized (FlashlightManager.class) {
                if (instance == null) {
                    instance = new FlashlightManager(context);
                }
            }
        }
        return instance;
    }

    /**
     * Picks the camera whose flash to drive: any camera reporting a flash unit, preferring the back
     * one when several qualify. Earlier versions required LENS_FACING_BACK, which left devices whose
     * only flash sits on another lens with no flashlight at all.
     */
    private void initCamera() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(TAG, "setTorchMode needs API 23; no flashlight on this Android version");
            return;
        }
        if (cameraManager == null) {
            Log.w(TAG, "No camera service available");
            return;
        }
        try {
            String fallbackId = null;
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                Boolean hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (hasFlash == null || !hasFlash) {
                    continue;
                }
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    hasFlashlight = true;
                    Log.d(TAG, "Found flashlight camera: " + cameraId);
                    return;
                }
                if (fallbackId == null) {
                    fallbackId = id;
                }
            }
            if (fallbackId != null) {
                cameraId = fallbackId;
                hasFlashlight = true;
                Log.d(TAG, "No back-facing flash; using camera " + cameraId);
            } else {
                Log.w(TAG, "No flashlight available on this device");
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to init camera", e);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to read camera characteristics", e);
        }
    }

    private void registerTorchCallback() {
        if (!hasFlashlight || cameraManager == null) {
            return;
        }
        torchCallback = new CameraManager.TorchCallback() {
            @Override
            public void onTorchModeChanged(String changedId, boolean enabled) {
                if (changedId.equals(cameraId) && enabled != isOn) {
                    isOn = enabled;
                    notifyListeners(enabled);
                }
            }

            @Override
            public void onTorchModeUnavailable(String changedId) {
                // Raised while another app holds the camera. The torch is off in that state.
                if (changedId.equals(cameraId) && isOn) {
                    isOn = false;
                    notifyListeners(false);
                }
            }
        };
        try {
            cameraManager.registerTorchCallback(torchCallback, new Handler(Looper.getMainLooper()));
        } catch (Exception e) {
            Log.w(TAG, "Could not track torch state; falling back to last command sent", e);
            torchCallback = null;
        }
    }

    public boolean hasFlashlight() {
        return hasFlashlight;
    }

    public boolean isOn() {
        return isOn;
    }

    public void turnOn() {
        setTorch(true);
    }

    public void turnOff() {
        setTorch(false);
    }

    public void toggle() {
        setTorch(!isOn);
    }

    private void setTorch(boolean enabled) {
        if (!hasFlashlight || cameraId == null) {
            Log.w(TAG, "No flashlight available");
            return;
        }
        try {
            cameraManager.setTorchMode(cameraId, enabled);
            Log.d(TAG, "Flashlight " + (enabled ? "ON" : "OFF"));
            if (torchCallback == null) {
                // No callback registered, so nothing else will report the change.
                isOn = enabled;
                notifyListeners(enabled);
            }
        } catch (CameraAccessException e) {
            // Typically another app holding the camera, or a thermal block. The state is left
            // alone: the torch callback reports what the hardware is actually doing.
            Log.e(TAG, "Failed to switch flashlight " + (enabled ? "on" : "off"), e);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Camera " + cameraId + " no longer supports a torch", e);
        }
    }

    /**
     * Push-based state updates, so Home Assistant sees the torch going out by itself rather than
     * waiting for the next poll.
     */
    public boolean registerStateListener(String entityId, Object callback) {
        if (callback == null || !ENTITY_TORCH.equals(entityId)) {
            return false;
        }
        torchListeners.addIfAbsent(callback);
        return true;
    }

    private void notifyListeners(boolean enabled) {
        for (Object callback : torchListeners) {
            // Ava core uses two callback shapes: ModStateCallback.onStateChanged(Object) from the
            // entity factory, and onState(Object) from the bridge.
            try {
                Method method;
                try {
                    method = callback.getClass().getMethod("onStateChanged", Object.class);
                } catch (NoSuchMethodException e) {
                    method = callback.getClass().getMethod("onState", Object.class);
                }
                method.invoke(callback, enabled);
            } catch (Exception e) {
                Log.w(TAG, "State callback failed", e);
            }
        }
    }

    /** Turns the torch off, so disabling the mod cannot leave the light on with no way to reach it. */
    public void onDestroy() {
        setTorch(false);
        if (torchCallback != null) {
            try {
                cameraManager.unregisterTorchCallback(torchCallback);
            } catch (Exception e) {
                Log.w(TAG, "Failed to unregister torch callback", e);
            }
            torchCallback = null;
        }
        torchListeners.clear();
    }
}
