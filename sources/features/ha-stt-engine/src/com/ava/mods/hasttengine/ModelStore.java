package com.ava.mods.hasttengine;

import android.content.Context;

import java.io.File;

final class ModelStore {
    private static final String DIR_NAME = "ha-stt-engine";
    private static final String MODEL_FILE = "model.int8.onnx";
    private static final String TOKENS_FILE = "tokens.txt";
    private static final String DOWNLOADED_MARKER = "downloaded";

    private ModelStore() {
    }

    static File rootDir(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getFilesDir();
        }
        File root = new File(base, DIR_NAME);
        if (!root.exists()) {
            root.mkdirs();
        }
        return root;
    }

    static File modelFile(Context context) {
        return new File(rootDir(context), MODEL_FILE);
    }

    static File tokensFile(Context context) {
        return new File(rootDir(context), TOKENS_FILE);
    }

    static File markerFile(Context context) {
        return new File(rootDir(context), DOWNLOADED_MARKER);
    }

    static boolean isReady(Context context) {
        File model = modelFile(context);
        File tokens = tokensFile(context);
        return model.isFile()
                && model.length() > 0
                && tokens.isFile()
                && tokens.length() > 0
                && markerFile(context).isFile();
    }

    static void clear(Context context) {
        deleteQuietly(modelFile(context));
        deleteQuietly(tokensFile(context));
        deleteQuietly(markerFile(context));
    }

    static String displayPath(Context context) {
        return rootDir(context).getAbsolutePath();
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            file.delete();
        }
    }
}
