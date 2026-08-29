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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * QR plus retail product barcodes (EAN-13/EAN-8/UPC-A/UPC-E), each behind its
 * own switch. The format list stays this tight deliberately: face, gesture and
 * scanning share one detection thread, and running TRY_HARDER across Data
 * Matrix, Aztec and the industrial 1D symbologies cost more than the whole
 * face pass while decoding nothing anyone points at a wall panel.
 */
public final class QrScanner {

    /**
     * Decode at native resolution up to this width. The old 640 cap silently
     * downscaled higher-resolution frames, throwing away exactly the detail a
     * user raising the resolution setting was trying to gain for distant codes.
     */
    private static final int MAX_DECODE_WIDTH = 1280;

    public static final class Scan {
        public final String text;
        public final boolean isQr;

        Scan(String text, boolean isQr) {
            this.text = text;
            this.isQr = isQr;
        }
    }

    private final MultiFormatReader reader = new MultiFormatReader();
    private int[] pixelScratch = new int[0];
    private boolean hintsQr;
    private boolean hintsBarcode;
    private boolean hintsSet;

    private void applyFormats(boolean qr, boolean barcode) {
        if (hintsSet && qr == hintsQr && barcode == hintsBarcode) return;
        List<BarcodeFormat> formats = new ArrayList<>();
        if (qr) {
            formats.add(BarcodeFormat.QR_CODE);
        }
        if (barcode) {
            formats.add(BarcodeFormat.EAN_13);
            formats.add(BarcodeFormat.EAN_8);
            formats.add(BarcodeFormat.UPC_A);
            formats.add(BarcodeFormat.UPC_E);
        }
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, formats);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);
        hintsQr = qr;
        hintsBarcode = barcode;
        hintsSet = true;
    }

    public Scan decode(Bitmap bitmap, boolean qr, boolean barcode) {
        if (!qr && !barcode) return null;
        applyFormats(qr, barcode);

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
            if (result == null || result.getText() == null || result.getText().isEmpty()) {
                return null;
            }
            return new Scan(result.getText(), result.getBarcodeFormat() == BarcodeFormat.QR_CODE);
        } catch (NotFoundException e) {
            return null;
        } finally {
            reader.reset();
        }
    }
}
