package com.ava.mods.hasttengine;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

final class WyomingSessionHandler implements Runnable {
    private static final String TAG = "HaSttWyomingSession";
    private static final int MAX_BUFFER_BYTES = 10 * 1024 * 1024;

    interface Callback {
        void onTranscript(RecognitionResult result);
    }

    private final Socket socket;
    private final SenseVoiceEngine engine;
    private final Callback callback;

    private final ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
    private int sampleRate = 16000;
    private int sampleWidth = 2;
    private int channels = 1;

    WyomingSessionHandler(Socket socket, SenseVoiceEngine engine, Callback callback) {
        this.socket = socket;
        this.engine = engine;
        this.callback = callback;
    }

    @Override
    public void run() {
        try {
            socket.setSoTimeout(0);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();

            while (!socket.isClosed()) {
                WyomingEvent event = WyomingWire.readEvent(input);
                if (event == null) {
                    break;
                }

                if ("describe".equals(event.type)) {
                    WyomingWire.writeEvent(output, WyomingWire.infoEvent());
                    continue;
                }

                if ("transcribe".equals(event.type)) {
                    continue;
                }

                if ("audio-start".equals(event.type)) {
                    sampleRate = event.data.optInt("rate", 16000);
                    sampleWidth = event.data.optInt("width", 2);
                    channels = event.data.optInt("channels", 1);
                    audioBuffer.reset();
                    continue;
                }

                if ("audio-chunk".equals(event.type)) {
                    if (event.payload != null && event.payload.length > 0) {
                        if (audioBuffer.size() + event.payload.length > MAX_BUFFER_BYTES) {
                            Log.w(TAG, "Audio buffer overflow, clearing buffer");
                            audioBuffer.reset();
                        }
                        audioBuffer.write(event.payload);
                    }
                    continue;
                }

                if ("audio-stop".equals(event.type)) {
                    RecognitionResult result = engine.transcribe(audioBuffer.toByteArray(), sampleRate);
                    audioBuffer.reset();
                    callback.onTranscript(result);
                    WyomingWire.writeEvent(
                            output,
                            WyomingWire.transcriptEvent(
                                    result.text,
                                    result.emotion,
                                    result.audioEvent,
                                    result.language
                            )
                    );
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Wyoming session ended with error", e);
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }
}
