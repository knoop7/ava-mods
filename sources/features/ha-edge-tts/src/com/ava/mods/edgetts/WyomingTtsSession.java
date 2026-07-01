package com.ava.mods.edgetts;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.net.Socket;

final class WyomingTtsSession implements Runnable {
    private static final String TAG = "EdgeTtsSession";

    interface Callback {
        void onSynthesized(String text);
    }

    private final Socket socket;
    private final String defaultVoice;
    private final String defaultRate;
    private final String defaultVolume;
    private final String defaultPitch;
    private final File cacheDir;
    private final Callback callback;

    WyomingTtsSession(Socket socket, String voice, String rate, String volume, String pitch,
                      File cacheDir, Callback callback) {
        this.socket = socket;
        this.defaultVoice = voice;
        this.defaultRate = rate;
        this.defaultVolume = volume;
        this.defaultPitch = pitch;
        this.cacheDir = cacheDir;
        this.callback = callback;
    }

    @Override
    public void run() {
        BufferedInputStream input = null;
        BufferedOutputStream output = null;
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(60000);
            input = new BufferedInputStream(socket.getInputStream());
            output = new BufferedOutputStream(socket.getOutputStream());

            while (!socket.isClosed()) {
                WyomingEvent event = WyomingWire.readEvent(input);
                if (event == null) {
                    break;
                }

                if ("describe".equals(event.type)) {
                    WyomingWire.writeEvent(output, WyomingWire.infoEvent());
                    continue;
                }

                if ("synthesize".equals(event.type)) {
                    handleSynthesize(output, event);
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Session ended: " + e.getMessage());
        } finally {
            closeQuietly(output, input);
        }
    }

    private void handleSynthesize(BufferedOutputStream output, WyomingEvent event) {
        String text = event.data.optString("text", "");

        String voice = null;
        String language = null;
        if (event.data.has("voice")) {
            try {
                JSONObject voiceObj = event.data.getJSONObject("voice");
                voice = voiceObj.optString("name", null);
                language = voiceObj.optString("language", null);
            } catch (Exception e) {
                voice = event.data.optString("voice", null);
            }
        }
        if (language == null) {
            language = event.data.optString("language", null);
        }

        String rate = event.data.optString("rate", defaultRate);
        String volume = event.data.optString("volume", defaultVolume);
        String pitch = event.data.optString("pitch", defaultPitch);

        if (voice == null || voice.isEmpty() || !EdgeTtsVoices.isValid(voice)) {
            if (language != null && !language.isEmpty()) {
                voice = voiceForLanguage(language, defaultVoice);
            } else {
                voice = defaultVoice;
            }
        }

        if (text == null || text.trim().isEmpty()) {
            Log.w(TAG, "Synthesize with empty text");
            return;
        }

        try {
            byte[] mp3Data = EdgeTtsEngine.synthesize(text, voice, rate, volume, pitch);
            byte[] pcmData = Mp3Decoder.decodeToPcm16(mp3Data, cacheDir);
            pcmData = normalizePcm16(pcmData);

            int sampleRate = 24000;
            int sampleWidth = 2;
            int channels = 1;

            WyomingWire.writeEvent(output, WyomingWire.audioStartEvent(sampleRate, sampleWidth, channels, null));

            int chunkSize = 8192;
            int offset = 0;
            while (offset < pcmData.length) {
                int end = Math.min(offset + chunkSize, pcmData.length);
                byte[] chunk = new byte[end - offset];
                System.arraycopy(pcmData, offset, chunk, 0, chunk.length);
                WyomingWire.writeEvent(output, WyomingWire.audioChunkEvent(chunk, sampleRate, sampleWidth, channels));
                offset = end;
            }

            WyomingWire.writeEvent(output, WyomingWire.audioStopEvent());

            if (callback != null) {
                callback.onSynthesized(text);
            }

            Log.d(TAG, "Synthesized " + text.length() + " chars, " + mp3Data.length + " bytes MP3, " + pcmData.length + " bytes PCM");
        } catch (Exception e) {
            Log.e(TAG, "Synthesis failed", e);
            try {
                JSONObject errData = new JSONObject();
                errData.put("error", e.getMessage() == null ? "Synthesis failed" : e.getMessage());
                WyomingWire.writeEvent(output, new WyomingEvent("error", errData, null));
            } catch (Exception ignored) {
            }
        }
    }

    private static String voiceForLanguage(String language, String fallback) {
        if (language == null || language.isEmpty()) {
            return fallback;
        }
        String langLower = language.toLowerCase();
        for (String voice : EdgeTtsVoices.voices()) {
            if (voice.toLowerCase().startsWith(langLower)) {
                return voice;
            }
        }
        if (langLower.startsWith("zh")) return "zh-CN-XiaoxiaoNeural";
        if (langLower.startsWith("en")) return "en-US-AriaNeural";
        if (langLower.startsWith("ja")) return "ja-JP-NanamiNeural";
        if (langLower.startsWith("ko")) return "ko-KR-SunHiNeural";
        return fallback;
    }

    private static byte[] normalizePcm16(byte[] pcm) {
        if (pcm == null || pcm.length < 2) return pcm;

        int peak = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            int abs = Math.abs(sample);
            if (abs > peak) peak = abs;
        }

        if (peak < 100) return pcm;

        final int TARGET = (int) (32767 * 0.9);
        float gain = (float) TARGET / peak;
        gain = Math.min(gain, 5.0f);

        byte[] out = new byte[pcm.length];
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            float scaled = sample * gain;
            if (scaled > 32767) scaled = 32767;
            if (scaled < -32768) scaled = -32768;
            int val = (int) scaled;
            out[i] = (byte) (val & 0xFF);
            out[i + 1] = (byte) (val >> 8);
        }
        return out;
    }

    private void closeQuietly(BufferedOutputStream output, BufferedInputStream input) {
        try {
            if (output != null) output.flush();
        } catch (Exception ignored) {
        }
        try {
            socket.shutdownOutput();
        } catch (Exception ignored) {
        }
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }
}
