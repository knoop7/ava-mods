package com.ava.mods.flashlight;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.Log;

public class FlashlightManager {

    private static final String TAG = "FlashlightManager";
    private static volatile FlashlightManager instance;

    private final Context context;
    private final CameraManager cameraManager;
    private String cameraId;
    private boolean isOn = false;
    private boolean hasFlashlight = false;

    private FlashlightManager(Context context) {
        this.context = context.getApplicationContext();
        this.cameraManager = (CameraManager) this.context.getSystemService(Context.CAMERA_SERVICE);
        initCamera();
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

    private void initCamera() {
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            for (String id : cameraIds) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                Boolean hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (hasFlash != null && hasFlash && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    hasFlashlight = true;
                    Log.d(TAG, "Found flashlight camera: " + cameraId);
                    break;
                }
            }
            if (!hasFlashlight) {
                Log.w(TAG, "No flashlight available on this device");
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to init camera", e);
        }
    }

    public void toggle() {
        if (!hasFlashlight || cameraId == null) {
            Log.w(TAG, "No flashlight available");
            return;
        }
        try {
            isOn = !isOn;
            cameraManager.setTorchMode(cameraId, isOn);
            Log.d(TAG, "Flashlight " + (isOn ? "ON" : "OFF"));
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to toggle flashlight", e);
        }
    }

}
