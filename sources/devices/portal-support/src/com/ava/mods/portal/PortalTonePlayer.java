package com.ava.mods.portal;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

class PortalTonePlayer {

    private static final String TAG = "PortalSupport";
    private static final int SAMPLE_RATE = 44100;

    static void play(String name) {
        String normalized = name == null ? "" : name.trim().toLowerCase();
        final short[] pcm;
        if ("doorbell".equals(normalized)) {
            pcm = doorbell();
        } else if ("alert".equals(normalized)) {
            pcm = alert();
        } else {
            Log.w(TAG, "unknown tone '" + name + "'");
            return;
        }

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                AudioTrack track = null;
                try {
                    track = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build())
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(SAMPLE_RATE)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .build())
                            .setTransferMode(AudioTrack.MODE_STATIC)
                            .setBufferSizeInBytes(pcm.length * 2)
                            .build();
                    track.write(pcm, 0, pcm.length);
                    track.play();
                    Thread.sleep(pcm.length * 1000L / SAMPLE_RATE + 200L);
                } catch (Exception e) {
                    Log.w(TAG, "tone playback failed: " + e.getMessage());
                } finally {
                    if (track != null) {
                        try {
                            track.release();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }, "portal-tone");
        thread.setDaemon(true);
        thread.start();
    }

    private static short[] doorbell() {
        return concat(tone(659.25, 0.45, 4.0), gap(0.06), tone(523.25, 0.65, 3.0));
    }

    private static short[] alert() {
        short[] beep = tone(880.0, 0.16, 1.5);
        short[] gap = gap(0.12);
        return concat(beep, gap, beep, gap, beep);
    }

    private static short[] tone(double freq, double seconds, double decay) {
        int n = (int) (seconds * SAMPLE_RATE);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) SAMPLE_RATE;
            double env = Math.exp(-decay * t / seconds);
            double s = 0.8 * Math.sin(2 * Math.PI * freq * t) + 0.2 * Math.sin(4 * Math.PI * freq * t);
            out[i] = (short) (s * env * 0.85 * Short.MAX_VALUE);
        }
        return out;
    }

    private static short[] gap(double seconds) {
        return new short[(int) (seconds * SAMPLE_RATE)];
    }

    private static short[] concat(short[]... parts) {
        int total = 0;
        for (short[] part : parts) {
            total += part.length;
        }
        short[] out = new short[total];
        int offset = 0;
        for (short[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }
}
