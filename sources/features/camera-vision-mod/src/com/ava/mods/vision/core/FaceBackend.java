package com.ava.mods.vision.core;

import android.graphics.Bitmap;

/** Common surface of the interchangeable face detectors (BlazeFace, YuNet). */
public interface FaceBackend {

    boolean isReady();

    String getError();

    FaceResult detect(Bitmap bitmap);

    void close();
}
