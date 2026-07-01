package com.ava.mods.edgetts;

final class EdgeTtsVoices {
    static final String DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural";

    private static final String[] VOICES = {
        "zh-CN-XiaoxiaoNeural",
        "zh-CN-YunxiNeural",
        "zh-CN-YunyangNeural",
        "zh-CN-XiaoyiNeural",
        "zh-CN-YunjianNeural",
        "zh-CN-liaoning-XiaobeiNeural",
        "zh-CN-shaanxi-XiaoniNeural",
        "en-US-AriaNeural",
        "en-US-DavisNeural",
        "en-US-GuyNeural",
        "en-US-JennyNeural",
        "en-US-AmberNeural",
        "en-US-AnaNeural",
        "en-US-AndrewNeural",
        "en-US-BrandonNeural",
        "en-US-ChristopherNeural",
        "en-US-CoraNeural",
        "en-US-ElizabethNeural",
        "en-US-MonicaNeural",
        "en-US-SaraNeural",
        "en-GB-SoniaNeural",
        "en-GB-RyanNeural",
        "en-AU-NatashaNeural",
        "en-AU-WilliamNeural",
        "ja-JP-NanamiNeural",
        "ja-JP-KeitaNeural",
        "ko-KR-SunHiNeural",
        "ko-KR-InJoonNeural",
        "fr-FR-DeniseNeural",
        "fr-FR-HenriNeural",
        "de-DE-KatjaNeural",
        "de-DE-ConradNeural",
        "es-ES-ElviraNeural",
        "es-ES-AlvaroNeural",
        "it-IT-ElsaNeural",
        "it-IT-DiegoNeural",
        "pt-BR-FranciscaNeural",
        "pt-BR-AntonioNeural",
        "ru-RU-SvetlanaNeural",
        "ru-RU-DmitryNeural",
        "vi-VN-HoaiMyNeural",
        "vi-VN-NamMinhNeural",
        "th-TH-PremwadeeNeural",
        "th-TH-NiwatNeural",
        "id-ID-GadisNeural",
        "id-ID-ArdiNeural",
        "hi-IN-SwaraNeural",
        "hi-IN-MadhurNeural",
        "ar-SA-ZariyahNeural",
        "ar-SA-HamedNeural",
        "tr-TR-EmelNeural",
        "tr-TR-AhmetNeural",
        "nl-NL-ColetteNeural",
        "nl-NL-FennaNeural",
        "pl-PL-ZofiaNeural",
        "pl-PL-MarekNeural",
        "uk-UA-PolinaNeural",
        "uk-UA-OstapNeural"
    };

    private static final String[] WYOMING_LANGUAGES = {
        "zh-CN", "en-US", "en-GB", "en-AU",
        "ja-JP", "ko-KR", "fr-FR", "de-DE",
        "es-ES", "it-IT", "pt-BR", "ru-RU",
        "vi-VN", "th-TH", "id-ID", "hi-IN",
        "ar-SA", "tr-TR", "nl-NL", "pl-PL", "uk-UA"
    };

    private EdgeTtsVoices() {
    }

    static String[] voices() {
        return VOICES.clone();
    }

    static String[] wyomingLanguages() {
        return WYOMING_LANGUAGES.clone();
    }

    static String languageOf(String voice) {
        if (voice == null || voice.length() < 5) {
            return "zh-CN";
        }
        return voice.substring(0, 5);
    }

    static boolean isValid(String voice) {
        if (voice == null || voice.isEmpty()) {
            return false;
        }
        for (String v : VOICES) {
            if (v.equals(voice)) {
                return true;
            }
        }
        return false;
    }
}
