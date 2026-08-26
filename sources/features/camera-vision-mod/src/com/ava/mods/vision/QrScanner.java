package com.ava.mods.vision;

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

    private final MultiFormatReader reader;

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
        if (width > 640) {
            float ratio = 640f / width;
            scaledW = 640;
            scaledH = (int) (height * ratio);
            scaled = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, false);
        }

        int[] pixels = new int[scaledW * scaledH];
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
