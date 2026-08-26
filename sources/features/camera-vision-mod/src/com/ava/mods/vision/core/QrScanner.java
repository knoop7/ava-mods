package com.ava.mods.vision.core;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public final class QrScanner {

    /**
     * Decode at native resolution up to this width. The old 640 cap silently
     * downscaled higher-resolution frames, throwing away exactly the detail a
     * user raising the resolution setting was trying to gain for distant codes.
     */
    private static final int MAX_DECODE_WIDTH = 1280;

    private final MultiFormatReader reader;
    private int[] pixelScratch = new int[0];

    /**
     * QR only. Face, gesture and QR all share one detection thread, and running
     * TRY_HARDER across Data Matrix and Aztec as well cost more than the whole
     * face pass while decoding nothing Home Assistant tags actually use.
     */
    public QrScanner() {
        reader = new MultiFormatReader();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS,
                Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);
    }

    public String decode(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        int scaledW = width;
        int scaledH = height;
        Bitmap scaled = bitmap;
        if (width > MAX_DECODE_WIDTH) {
            float ratio = (float) MAX_DECODE_WIDTH / width;
            scaledW = MAX_DECODE_WIDTH;
            scaledH = (int) (height * ratio);
            scaled = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, false);
        }

        if (pixelScratch.length < scaledW * scaledH) {
            pixelScratch = new int[scaledW * scaledH];
        }
        int[] pixels = pixelScratch;
        scaled.getPixels(pixels, 0, scaledW, 0, 0, scaledW, scaledH);
        if (scaled != bitmap) scaled.recycle();

        RGBLuminanceSource source = new RGBLuminanceSource(scaledW, scaledH, pixels);
        BinaryBitmap binary = new BinaryBitmap(new HybridBinarizer(source));

        try {
            Result result = reader.decodeWithState(binary);
            return result != null ? result.getText() : null;
        } catch (NotFoundException e) {
            return null;
        } finally {
            reader.reset();
        }
    }
}
