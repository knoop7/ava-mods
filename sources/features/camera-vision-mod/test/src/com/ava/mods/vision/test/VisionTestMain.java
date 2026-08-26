package com.ava.mods.vision.test;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import com.ava.mods.vision.ChildFirstLoader;
import com.ava.mods.vision.api.VisionApi;
import com.ava.mods.vision.core.AdaptiveQuality;
import com.ava.mods.vision.core.FaceBackend;
import com.ava.mods.vision.core.FaceEngine;
import com.ava.mods.vision.core.FaceResult;
import com.ava.mods.vision.core.GestureEngine;
import com.ava.mods.vision.core.ModelStore;
import com.ava.mods.vision.core.QrScanner;
import com.ava.mods.vision.core.YuNetEngine;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.File;

/**
 * On-device test entry, run as the shell user via app_process — no host app,
 * no store download. Exercises model extraction, the bundled TFLite JNI load,
 * both face models, the hand pipeline and a ZXing encode/decode round trip
 * against real hardware.
 *
 *   CLASSPATH=/data/local/tmp/cv-test/test.jar \
 *       app_process /data/local/tmp/cv-test com.ava.mods.vision.test.VisionTestMain
 */
public final class VisionTestMain {

    private static final File BASE = new File("/data/local/tmp/cv-test");

    /** Enough frames to satisfy both the appear hysteresis and the majority vote. */
    private static final int SETTLE_FRAMES = 6;

    private static int failures;

    public static void main(String[] args) {
        ModelStore.setBaseDir(BASE);
        long start = System.currentTimeMillis();

        testChildFirstLoader();
        testDualLoaderJniLoad();
        testAdaptiveQuality();
        testQrRoundTrip();
        testSmallQr();
        testFace("yunet");
        testFace("sparse");
        testFace("short");
        testFarFace();
        testGesture();
        testCrossFalsePositives();

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("----------------------------------------");
        if (failures == 0) {
            System.out.println("ALL TESTS PASSED (" + elapsed + " ms)");
        } else {
            System.out.println(failures + " TEST(S) FAILED (" + elapsed + " ms)");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * Verifies the loader topology the facade relies on in production: the core
     * class resolves from the child loader yet still implements the parent-loaded
     * VisionApi, while TFLite classes stay isolated from the parent's copies.
     */
    private static void testChildFirstLoader() {
        try {
            File jar = new File(BASE, "test.jar");
            File cache = new File(BASE, "dex-cache");
            cache.mkdirs();
            ClassLoader parent = VisionTestMain.class.getClassLoader();
            ChildFirstLoader child = new ChildFirstLoader(
                    jar.getAbsolutePath(), cache.getAbsolutePath(), parent);

            Class<?> core = child.loadClass("com.ava.mods.vision.core.VisionCore");
            check("loader: core resolves child-first", core.getClassLoader() == child,
                    "loader=" + core.getClassLoader());
            check("loader: core implements shared VisionApi",
                    VisionApi.class.isAssignableFrom(core),
                    "interfaces=" + java.util.Arrays.toString(core.getInterfaces()));

            Class<?> childTflite = child.loadClass("org.tensorflow.lite.Interpreter");
            Class<?> parentTflite = parent.loadClass("org.tensorflow.lite.Interpreter");
            check("loader: TFLite isolated from parent", childTflite != parentTflite,
                    "child=" + childTflite.getClassLoader());
        } catch (Throwable t) {
            fail("loader: crashed", t);
        }
    }

    /**
     * The host rebuilds the mod classloader on registry changes, so successive
     * ChildFirstLoader generations must each be able to bind the TFLite JNI
     * library. ART allows one .so file per classloader, hence the uniquely
     * named per-generation copies in TfLiteRuntime — this guards that fix.
     */
    private static void testDualLoaderJniLoad() {
        try {
            File jar = new File(BASE, "test.jar");
            ClassLoader parent = VisionTestMain.class.getClassLoader();
            for (int gen = 1; gen <= 2; gen++) {
                File cache = new File(BASE, "dex-cache-gen" + gen);
                cache.mkdirs();
                ChildFirstLoader child = new ChildFirstLoader(
                        jar.getAbsolutePath(), cache.getAbsolutePath(), parent);
                Class<?> store = child.loadClass("com.ava.mods.vision.core.ModelStore");
                store.getMethod("setBaseDir", File.class).invoke(null, BASE);
                Class<?> faceCls = child.loadClass("com.ava.mods.vision.core.FaceEngine");
                Object engine = faceCls.getConstructor(Context.class, String.class)
                        .newInstance(null, "short");
                boolean ready = (Boolean) faceCls.getMethod("isReady").invoke(engine);
                String err = (String) faceCls.getMethod("getError").invoke(engine);
                faceCls.getMethod("close").invoke(engine);
                check("jni gen" + gen + ": TFLite binds in fresh loader", ready, err);
            }
        } catch (Throwable t) {
            fail("jni dual-loader: crashed", t);
        }
    }

    /**
     * Walks the stutter ladder end to end with a simulated clock: sustained low
     * FPS steps 720p down to 480p, a stable stream needs the full observation
     * window before climbing back, a stutter right after the climb re-degrades
     * and doubles the next window, and the 240p floor never steps below itself.
     */
    private static void testAdaptiveQuality() {
        try {
            AdaptiveQuality aq = new AdaptiveQuality();
            long t = 100_000;
            int user = 720;

            check("adaptive: starts at user resolution",
                    aq.appliedResolution(user) == 720 && !aq.isDegraded(),
                    "applied=" + aq.appliedResolution(user));

            AdaptiveQuality.Action a1 = aq.onFpsSample(2, 10, user, t);
            t += 2000;
            AdaptiveQuality.Action a2 = aq.onFpsSample(2, 10, user, t);
            t += 2000;
            AdaptiveQuality.Action a3 = aq.onFpsSample(2, 10, user, t);
            check("adaptive: degrades on 3rd bad sample, not sooner",
                    a1 == AdaptiveQuality.Action.NONE
                            && a2 == AdaptiveQuality.Action.NONE
                            && a3 == AdaptiveQuality.Action.STEP_DOWN,
                    a1 + "," + a2 + "," + a3);
            check("adaptive: 720 degrades to 480",
                    aq.appliedResolution(user) == 480 && aq.isDegraded(),
                    "applied=" + aq.appliedResolution(user));

            int stepUpAtSample = -1;
            for (int i = 1; i <= 20; i++) {
                t += 2000;
                if (aq.onFpsSample(10, 10, user, t) == AdaptiveQuality.Action.STEP_UP) {
                    stepUpAtSample = i;
                    break;
                }
            }
            check("adaptive: recovers after 15 good samples", stepUpAtSample == 15,
                    "stepUpAtSample=" + stepUpAtSample);
            check("adaptive: back at user resolution",
                    aq.appliedResolution(user) == 720 && !aq.isDegraded(),
                    "applied=" + aq.appliedResolution(user));

            // Stutter right after the climb: flap detected, window doubles.
            t += 12_000;
            for (int i = 0; i < 3; i++) {
                t += 2000;
                aq.onFpsSample(2, 10, user, t);
            }
            check("adaptive: flap re-degrades", aq.isDegraded(),
                    "applied=" + aq.appliedResolution(user));
            stepUpAtSample = -1;
            for (int i = 1; i <= 60; i++) {
                t += 2000;
                if (aq.onFpsSample(10, 10, user, t) == AdaptiveQuality.Action.STEP_UP) {
                    stepUpAtSample = i;
                    break;
                }
            }
            check("adaptive: doubled window after flap (30 samples)", stepUpAtSample == 30,
                    "stepUpAtSample=" + stepUpAtSample);

            AdaptiveQuality floor = new AdaptiveQuality();
            long ft = 500_000;
            boolean stepped = false;
            for (int i = 0; i < 10; i++) {
                ft += 2000;
                if (floor.onFpsSample(1, 10, 240, ft) != AdaptiveQuality.Action.NONE) {
                    stepped = true;
                }
            }
            check("adaptive: 240p floor never steps down", !stepped && floor.appliedResolution(240) == 240,
                    "applied=" + floor.appliedResolution(240));

            AdaptiveQuality off = new AdaptiveQuality();
            off.setEnabled(false);
            boolean acted = false;
            long ot = 900_000;
            for (int i = 0; i < 6; i++) {
                ot += 2000;
                if (off.onFpsSample(1, 10, 720, ot) != AdaptiveQuality.Action.NONE) {
                    acted = true;
                }
            }
            check("adaptive: disabled switch never acts", !acted, "acted=" + acted);
        } catch (Throwable t) {
            fail("adaptive: crashed", t);
        }
    }

    private static void testQrRoundTrip() {
        try {
            QrScanner scanner = new QrScanner();
            String payload = "https://www.home-assistant.io/tag/test-1234-abcd";

            Bitmap frame = frameWithQr(payload);
            long t0 = System.currentTimeMillis();
            String decoded = scanner.decode(frame);
            long ms = System.currentTimeMillis() - t0;
            frame.recycle();

            check("qr: decode own encoding (" + ms + " ms)", payload.equals(decoded),
                    "decoded=" + decoded);

            Bitmap blank = solid(640, 480, Color.DKGRAY);
            String none = scanner.decode(blank);
            blank.recycle();
            check("qr: no false positive on blank", none == null, "decoded=" + none);
        } catch (Throwable t) {
            fail("qr: crashed", t);
        }
    }

    private static FaceBackend newFace(String range) {
        return "yunet".equals(range) ? new YuNetEngine(null) : new FaceEngine(null, range);
    }

    private static void testFace(String range) {
        FaceBackend engine = null;
        try {
            engine = newFace(range);
            if (!check("face[" + range + "]: engine ready", engine.isReady(), engine.getError())) {
                return;
            }

            Bitmap portrait = load("face.jpg");
            if (portrait == null) return;
            FaceResult result = null;
            long t0 = System.currentTimeMillis();
            for (int i = 0; i < SETTLE_FRAMES; i++) {
                result = engine.detect(portrait);
            }
            long ms = (System.currentTimeMillis() - t0) / SETTLE_FRAMES;
            portrait.recycle();
            check("face[" + range + "]: portrait detected (" + ms + " ms/frame)",
                    result.present && result.count >= 1,
                    "present=" + result.present + " count=" + result.count);

            Bitmap blank = solid(640, 480, Color.BLACK);
            for (int i = 0; i < SETTLE_FRAMES; i++) {
                result = engine.detect(blank);
            }
            blank.recycle();
            check("face[" + range + "]: blank clears presence", !result.present,
                    "present=" + result.present + " count=" + result.count);
        } catch (Throwable t) {
            fail("face[" + range + "]: crashed", t);
        } finally {
            if (engine != null) engine.close();
        }
    }

    private static void testGesture() {
        GestureEngine engine = null;
        try {
            engine = new GestureEngine(null);
            if (!check("gesture: engine ready", engine.isReady(), engine.getError())) {
                return;
            }

            Bitmap palm = load("palm.jpg");
            if (palm == null) return;
            GestureEngine.Result result = null;
            long t0 = System.currentTimeMillis();
            for (int i = 0; i < SETTLE_FRAMES; i++) {
                result = engine.detect(palm);
            }
            long ms = (System.currentTimeMillis() - t0) / SETTLE_FRAMES;
            palm.recycle();
            check("gesture: open palm recognised (" + ms + " ms/frame)",
                    result.openPalm && result.fingerCount == 5,
                    "gesture=" + result.gesture + " fingers=" + result.fingerCount);

            Bitmap blank = solid(640, 480, Color.BLACK);
            for (int i = 0; i < SETTLE_FRAMES; i++) {
                result = engine.detect(blank);
            }
            blank.recycle();
            check("gesture: blank reads none", "none".equals(result.gesture),
                    "gesture=" + result.gesture + " fingers=" + result.fingerCount);
        } catch (Throwable t) {
            fail("gesture: crashed", t);
        } finally {
            if (engine != null) engine.close();
        }
    }

    /** A distant tag is a small code in a mostly empty frame; verify the size floor. */
    private static void testSmallQr() {
        try {
            QrScanner scanner = new QrScanner();
            String payload = "https://www.home-assistant.io/tag/far-away";
            check("qr: 96px code in 640x480 decodes",
                    payload.equals(decodeQrAt(scanner, payload, 96)), "96px failed");
            // Informational only: the realistic floor for camera frames.
            String at64 = decodeQrAt(scanner, payload, 64);
            System.out.println("INFO  qr: 64px code decodes=" + payload.equals(at64));
        } catch (Throwable t) {
            fail("qr small: crashed", t);
        }
    }

    /**
     * The same portrait scaled down mimics a person standing further away. YuNet
     * must hold detection well past where BlazeFace goes blind — that gap is the
     * whole reason it ships as the default backend.
     */
    private static void testFarFace() {
        Bitmap portrait = load("face.jpg");
        if (portrait == null) return;
        try {
            FaceBackend yunet = newFace("yunet");
            if (check("face far[yunet]: engine ready", yunet.isReady(), yunet.getError())) {
                check("face far[yunet]: detected at 33%",
                        freshDetectScaled("yunet", portrait, 0.33f).present, "missed at 33%");
                check("face far[yunet]: detected at 20%",
                        freshDetectScaled("yunet", portrait, 0.20f).present, "missed at 20%");
                FaceResult tiny = freshDetectScaled("yunet", portrait, 0.12f);
                System.out.println("INFO  face far[yunet]: detected at 12%=" + tiny.present);
            }
            yunet.close();

            FaceResult sparseThird = freshDetectScaled("sparse", portrait, 0.33f);
            System.out.println("INFO  face far[sparse]: detected at 33%=" + sparseThird.present
                    + " (BlazeFace reference)");
        } catch (Throwable t) {
            fail("face far: crashed", t);
        } finally {
            portrait.recycle();
        }
    }

    /** A fresh engine per scale so hysteresis from one scale cannot leak into the next. */
    private static FaceResult freshDetectScaled(String range, Bitmap src, float scale) {
        FaceBackend engine = newFace(range);
        try {
            return detectFaceScaled(engine, src, scale);
        } finally {
            engine.close();
        }
    }

    /** A face must not read as a hand, and a palm must not read as a face. */
    private static void testCrossFalsePositives() {
        try {
            Bitmap portrait = load("face.jpg");
            Bitmap palm = load("palm.jpg");
            if (portrait == null || palm == null) return;

            GestureEngine gesture = new GestureEngine(null);
            GestureEngine.Result g = null;
            for (int i = 0; i < SETTLE_FRAMES; i++) {
                g = gesture.detect(portrait);
            }
            gesture.close();
            check("cross: portrait yields no gesture", "none".equals(g.gesture),
                    "gesture=" + g.gesture + " fingers=" + g.fingerCount);

            for (String range : new String[] {"yunet", "sparse"}) {
                FaceBackend face = newFace(range);
                FaceResult f = null;
                for (int i = 0; i < SETTLE_FRAMES; i++) {
                    f = face.detect(palm);
                }
                face.close();
                check("cross: palm yields no face [" + range + "]", !f.present,
                        "present=" + f.present + " count=" + f.count);
            }

            portrait.recycle();
            palm.recycle();
        } catch (Throwable t) {
            fail("cross: crashed", t);
        }
    }

    private static FaceResult detectFaceScaled(FaceBackend engine, Bitmap src, float scale) {
        Bitmap frame = solid(640, 480, Color.DKGRAY);
        Canvas canvas = new Canvas(frame);
        float w = 640 * scale;
        float h = 480 * scale;
        float left = (640 - w) / 2f;
        float top = (480 - h) / 2f;
        canvas.drawBitmap(src, null, new RectF(left, top, left + w, top + h),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        FaceResult result = null;
        for (int i = 0; i < SETTLE_FRAMES; i++) {
            result = engine.detect(frame);
        }
        frame.recycle();
        return result;
    }

    private static String decodeQrAt(QrScanner scanner, String payload, int sizePx)
            throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx);
        Bitmap qr = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < sizePx; y++) {
            for (int x = 0; x < sizePx; x++) {
                qr.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        Bitmap frame = solid(640, 480, Color.WHITE);
        float left = (640 - sizePx) / 2f;
        float top = (480 - sizePx) / 2f;
        new Canvas(frame).drawBitmap(qr, left, top, null);
        qr.recycle();
        String decoded = scanner.decode(frame);
        frame.recycle();
        return decoded;
    }

    /** White 640x480 frame with the QR centered, roughly what the camera would hand over. */
    private static Bitmap frameWithQr(String payload) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 320, 320);
        Bitmap qr = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 320; y++) {
            for (int x = 0; x < 320; x++) {
                qr.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        Bitmap frame = solid(640, 480, Color.WHITE);
        new Canvas(frame).drawBitmap(qr, null, new RectF(160, 80, 480, 400),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        qr.recycle();
        return frame;
    }

    private static Bitmap solid(int w, int h, int color) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.eraseColor(color);
        return bmp;
    }

    private static Bitmap load(String name) {
        File f = new File(BASE, name);
        Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
        check("asset: " + name + " loads", bmp != null,
                f.getAbsolutePath() + " exists=" + f.isFile());
        return bmp;
    }

    private static boolean check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name + "  [" + detail + "]");
        }
        return ok;
    }

    private static void fail(String name, Throwable t) {
        failures++;
        System.out.println("FAIL  " + name + "  [" + t + "]");
        t.printStackTrace(System.out);
    }
}
