package com.ava.mods.hasttengine;

import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.QnnConfig;

import java.util.regex.Pattern;

final class SenseVoiceEngine {
    private static final String TAG = "HaSttSenseVoice";
    private static final Pattern MARKER_PATTERN = Pattern.compile("<\\||\\|>");

    private final Object lock = new Object();
    private OfflineRecognizer recognizer;
    private volatile boolean loaded;

    boolean isLoaded() {
        return loaded;
    }

    void load(String modelPath, String tokensPath, int numThreads) throws Exception {
        synchronized (lock) {
            releaseLocked();
            OfflineSenseVoiceModelConfig senseVoice = new OfflineSenseVoiceModelConfig(
                    modelPath,
                    "",
                    true,
                    new QnnConfig()
            );
            OfflineModelConfig modelConfig = new OfflineModelConfig();
            modelConfig.setSenseVoice(senseVoice);
            modelConfig.setTokens(tokensPath);
            modelConfig.setNumThreads(Math.max(1, numThreads));
            modelConfig.setProvider("cpu");
            modelConfig.setDebug(false);

            OfflineRecognizerConfig config = new OfflineRecognizerConfig();
            config.setFeatConfig(new FeatureConfig(16000, 80, 0.0f));
            config.setModelConfig(modelConfig);
            config.setHr(new HomophoneReplacerConfig("", "", ""));

            recognizer = new OfflineRecognizer(null, config);
            loaded = true;
            Log.i(TAG, "SenseVoice model loaded from " + modelPath);
        }
    }

    RecognitionResult transcribe(byte[] pcm16, int sampleRate) {
        synchronized (lock) {
            if (!loaded || recognizer == null || pcm16 == null || pcm16.length == 0) {
                return RecognitionResult.empty();
            }

            float[] samples = pcm16ToFloat(pcm16);
            if (samples.length == 0) {
                return RecognitionResult.empty();
            }

            OfflineStream stream = recognizer.createStream();
            try {
                stream.acceptWaveform(samples, sampleRate);
                recognizer.decode(stream);
                OfflineRecognizerResult result = recognizer.getResult(stream);
                return fromSherpa(result);
            } finally {
                stream.release();
            }
        }
    }

    void release() {
        synchronized (lock) {
            releaseLocked();
        }
    }

    private void releaseLocked() {
        if (recognizer != null) {
            try {
                recognizer.release();
            } catch (Exception e) {
                Log.w(TAG, "Failed to release recognizer", e);
            }
            recognizer = null;
        }
        loaded = false;
    }

    private static RecognitionResult fromSherpa(OfflineRecognizerResult result) {
        if (result == null) {
            return RecognitionResult.empty();
        }

        String text = result.getText() == null ? "" : result.getText().trim();
        String emotion = cleanTag(result.getEmotion());
        String audioEvent = cleanTag(result.getEvent());
        String language = cleanTag(result.getLang());

        if (isPunctuationOnly(text)) {
            text = "";
        }

        return new RecognitionResult(text, emotion, audioEvent, language);
    }

    private static String cleanTag(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        return MARKER_PATTERN.matcher(value).replaceAll("").trim().toLowerCase();
    }

    private static boolean isPunctuationOnly(String text) {
        if (text == null || text.trim().isEmpty()) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                return false;
            }
        }
        return true;
    }

    private static float[] pcm16ToFloat(byte[] pcm16) {
        int sampleCount = pcm16.length / 2;
        float[] samples = new float[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            int low = pcm16[i * 2] & 0xff;
            int high = pcm16[i * 2 + 1];
            short sample = (short) ((high << 8) | low);
            samples[i] = sample / 32768.0f;
        }
        return samples;
    }
}
