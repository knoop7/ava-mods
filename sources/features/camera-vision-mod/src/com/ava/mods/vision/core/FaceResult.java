package com.ava.mods.vision.core;

public final class FaceResult {

    public final int count;
    public final boolean present;

    public FaceResult(int count, boolean present) {
        this.count = count;
        this.present = present;
    }
}
