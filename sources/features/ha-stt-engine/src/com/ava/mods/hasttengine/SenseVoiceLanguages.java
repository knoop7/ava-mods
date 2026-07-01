package com.ava.mods.hasttengine;

/**
 * Language codes for the bundled SenseVoice small model
 * (sherpa-onnx-sense-voice-zh-en-ja-ko-yue).
 *
 * @see <a href="https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html">sherpa SenseVoice docs</a>
 */
final class SenseVoiceLanguages {
    static final String DEFAULT = "auto";

    private static final String[] DECODE_CODES = {"auto", "zh", "en", "ja", "ko", "yue"};
    private static final String[] WYOMING_CODES = {"zh", "zh-CN", "en", "ja", "ko", "yue"};

    private SenseVoiceLanguages() {
    }

    static String normalize(String value) {
        if (value == null) {
            return DEFAULT;
        }
        String trimmed = value.trim().toLowerCase();
        if (trimmed.isEmpty()) {
            return DEFAULT;
        }
        for (String code : DECODE_CODES) {
            if (code.equals(trimmed)) {
                return code;
            }
        }
        return DEFAULT;
    }

    /** Value passed to sherpa OfflineSenseVoiceModelConfig.language. */
    static String toSherpaLanguage(String configured) {
        String normalized = normalize(configured);
        if ("auto".equals(normalized)) {
            return "auto";
        }
        return normalized;
    }

    static String[] decodeOptions() {
        return DECODE_CODES.clone();
    }

    static String[] wyomingLanguageCodes() {
        return WYOMING_CODES.clone();
    }

    static String modelDescription() {
        return "Multilingual offline ASR (zh, en, ja, ko, Cantonese) with emotion and event detection";
    }
}
