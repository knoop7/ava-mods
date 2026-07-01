package com.ava.mods.edgetts;

import org.json.JSONArray;
import org.json.JSONObject;

final class WyomingInfoBuilder {
    private static final String VERSION = "1.0.4";

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
        attribution.put("name", "Ava");
        attribution.put("url", "https://github.com/knoop7/ava-mods");

        JSONArray voices = new JSONArray();
        for (String voiceId : EdgeTtsVoices.voices()) {
            JSONObject voice = new JSONObject();
            voice.put("name", voiceId);
            voice.put("attribution", attribution);
            voice.put("installed", true);
            voice.put("description", "");
            voice.put("version", VERSION);
            JSONArray voiceLangs = new JSONArray();
            voiceLangs.put(EdgeTtsVoices.languageOf(voiceId));
            voice.put("languages", voiceLangs);
            voices.put(voice);
        }

        JSONObject program = new JSONObject();
        program.put("name", "HA Edge TTS");
        program.put("description", "Edge TTS Wyoming server on Ava");
        program.put("attribution", attribution);
        program.put("version", VERSION);
        program.put("installed", true);
        program.put("supports_synthesize_streaming", false);
        program.put("voices", voices);

        JSONArray tts = new JSONArray();
        tts.put(program);
        root.put("tts", tts);
        root.put("asr", new JSONArray());
        root.put("handle", new JSONArray());
        root.put("intent", new JSONArray());
        root.put("wake", new JSONArray());
        root.put("mic", new JSONArray());
        root.put("snd", new JSONArray());
        return root;
    }
}
