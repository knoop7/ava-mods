package com.ava.mods.hasttengine;

final class RecognitionResult {
    final String text;
    final String emotion;
    final String audioEvent;
    final String language;

    RecognitionResult(String text, String emotion, String audioEvent, String language) {
        this.text = text == null ? "" : text;
        this.emotion = emotion == null ? "" : emotion;
        this.audioEvent = audioEvent == null ? "" : audioEvent;
        this.language = language == null ? "" : language;
    }

    static RecognitionResult empty() {
        return new RecognitionResult("", "", "", "");
    }
}
