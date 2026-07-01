package com.ava.mods.hasttengine;

import android.content.Context;

import java.io.File;

final class ModelStore {
    private static final String DIR_NAME = "ha-stt-engine";
    private static final String MODEL_FILE = "model.int8.onnx";
    private static final String TOKENS_FILE = "tokens.txt";
    private static final String DOWNLOADED_MARKER = "downloaded";
    private static final String LANGUAGE_FILE = "recognition_language.txt";

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

    static void setBundledLanguage(Context context, String language) {
        try {
            File file = languageFile(context);
            java.io.FileWriter writer = new java.io.FileWriter(file, false);
            try {
                writer.write(SenseVoiceLanguages.normalize(language));
            } finally {
                writer.close();
            }
        } catch (Exception ignored) {
        }
    }

    static String getBundledLanguage(Context context) {
        File file = languageFile(context);
        if (!file.isFile() || file.length() <= 0L) {
            return "";
        }
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file));
            try {
                String line = reader.readLine();
                return line == null ? "" : SenseVoiceLanguages.normalize(line);
            } finally {
                reader.close();
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** True when model files exist and were installed for this decode language. */
    static boolean matchesBundledLanguage(Context context, String language) {
        if (!isReady(context)) {
            return false;
        }
        String bundled = getBundledLanguage(context);
        if (bundled.isEmpty()) {
            return true;
        }
        return bundled.equals(SenseVoiceLanguages.normalize(language));
    }

    static File languageFile(Context context) {
        return new File(rootDir(context), LANGUAGE_FILE);
    }

    static void clear(Context context) {
        deleteQuietly(modelFile(context));
        deleteQuietly(tokensFile(context));
        deleteQuietly(markerFile(context));
        deleteQuietly(languageFile(context));
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
