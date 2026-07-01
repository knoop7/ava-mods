package com.ava.mods.hasttengine;

import org.json.JSONArray;
import org.json.JSONObject;

final class WyomingInfoBuilder {
    private static final String VERSION = "1.0.0";

    private WyomingInfoBuilder() {
    }

    static JSONObject build() {
        try {
            return buildInternal();
        } catch (org.json.JSONException e) {
            return new JSONObject();
        }
    }

    private static JSONObject buildInternal() throws org.json.JSONException {
        JSONObject root = new JSONObject();

        JSONObject attribution = new JSONObject();
        attribution.put("name", "FunAudioLLM");
        attribution.put("url", "https://github.com/FunAudioLLM/SenseVoice");

        JSONObject model = new JSONObject();
        model.put("name", "SenseVoice Small");
        model.put("description", SenseVoiceLanguages.modelDescription());
        model.put("attribution", attribution);
        model.put("version", VERSION);
        model.put("installed", true);
        JSONArray languages = new JSONArray();
        for (String language : SenseVoiceLanguages.wyomingLanguageCodes()) {
            languages.put(language);
        }
        model.put("languages", languages);

        JSONObject program = new JSONObject();
        program.put("name", "HA STT Engine");
        program.put("description", "Local Wyoming ASR/STT server on Ava (SenseVoice int8)");
        program.put("attribution", attribution);
        program.put("version", VERSION);
        program.put("installed", true);
        program.put("supports_transcript_streaming", false);
        program.put("requires_external_vad", true);
        program.put("prefers_auto_gain_enabled", true);
        program.put("prefers_noise_reduction_enabled", true);
        JSONArray models = new JSONArray();
        models.put(model);
        program.put("models", models);

        JSONArray asr = new JSONArray();
        asr.put(program);
        root.put("asr", asr);
        root.put("tts", new JSONArray());
        root.put("handle", new JSONArray());
        root.put("intent", new JSONArray());
        root.put("wake", new JSONArray());
        root.put("mic", new JSONArray());
        root.put("snd", new JSONArray());
        return root;
    }
}
