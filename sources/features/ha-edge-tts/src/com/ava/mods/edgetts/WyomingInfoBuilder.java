package com.ava.mods.edgetts;

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
        attribution.put("name", "Microsoft");
        attribution.put("url", "https://github.com/ha-china/ai_hub");

        JSONObject model = new JSONObject();
        model.put("name", "Edge TTS");
        model.put("description", "Microsoft Edge TTS voices via WebSocket");
        model.put("attribution", attribution);
        model.put("version", VERSION);
        model.put("installed", true);
        JSONArray languages = new JSONArray();
        for (String lang : EdgeTtsVoices.wyomingLanguages()) {
            languages.put(lang);
        }
        model.put("languages", languages);

        JSONArray speakers = new JSONArray();
        for (String voice : EdgeTtsVoices.voices()) {
            JSONObject speaker = new JSONObject();
            speaker.put("name", voice);
            speakers.put(speaker);
        }
        model.put("speakers", speakers);

        JSONObject program = new JSONObject();
        program.put("name", "HA Edge TTS");
        program.put("description", "Edge TTS Wyoming server on Ava");
        program.put("attribution", attribution);
        program.put("version", VERSION);
        program.put("installed", true);
        program.put("supports_synthesize_streaming", false);
        JSONArray models = new JSONArray();
        models.put(model);
        program.put("models", models);

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
