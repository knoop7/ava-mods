package com.ava.mods.airplay;

import android.media.MediaFormat;

public final class Prefs {
    private Prefs() {}

    public static final String NAME = "ava_airplay_prefs";

    public static final String SERVER_NAME = "server_name";
    public static final String DEF_SERVER_NAME = "Ava";

    public static final String SERVER_PORT = "server_port";
    public static final int DEF_SERVER_PORT = 7000;

    public static final String AUTO_START = "auto_start";
    public static final boolean DEF_AUTO_START = true;

    public static final String BOOT_AUTO_START = "boot_auto_start";
    public static final boolean DEF_BOOT_AUTO_START = true;

    public static final String RUN_IN_BACKGROUND = "run_in_background";
    public static final boolean DEF_RUN_IN_BACKGROUND = true;

    public static final String H265_ENABLED = "h265_enabled";
    public static final boolean DEF_H265_ENABLED = true;

    public static final String ENFORCE_SDR = "enforce_sdr";
    public static final boolean DEF_ENFORCE_SDR = true;

    public static final String KEY_ALLOW_FRAME_DROP = MediaFormat.KEY_ALLOW_FRAME_DROP;
    public static final boolean DEF_KEY_ALLOW_FRAME_DROP = true;

    public static final String KEY_PRIORITY = MediaFormat.KEY_PRIORITY;
    public static final boolean DEF_KEY_PRIORITY = true;

    public static final String KEY_OPERATING_RATE = MediaFormat.KEY_OPERATING_RATE;
    public static final boolean DEF_KEY_OPERATING_RATE = true;

    public static final String SCHEDULED_OUTPUT_BUFFER_RELEASE = "scheduled_output_buffer_release";
    public static final boolean DEF_SCHEDULED_OUTPUT_BUFFER_RELEASE = false;

    public static final String AUDIO_BUFFER_MULTIPLIER = "audio_buffer_multiplier";
    public static final int DEF_AUDIO_BUFFER_MULTIPLIER = 4;

    public static final String ALAC_ENABLED = "alac_enabled";
    public static final boolean DEF_ALAC_ENABLED = false;

    public static final String SW_ALAC_ENABLED = "sw_alac_enabled";
    public static final boolean DEF_SW_ALAC_ENABLED = true;

    public static final String AAC_ENABLED = "aac_enabled";
    public static final boolean DEF_AAC_ENABLED = true;

    public static final String RESOLUTION = "resolution";
    public static final String DEF_RESOLUTION = "auto";

    public static final String MAX_FPS = "max_fps";
    public static final int DEF_MAX_FPS = 60;

    public static final String OVERSCANNED = "overscanned";
    public static final boolean DEF_OVERSCANNED = false;

    public static final String REQUIRE_PIN = "require_pin";
    public static final boolean DEF_REQUIRE_PIN = false;

    public static final String ALLOW_NEW_CONN = "allow_new_conn";
    public static final boolean DEF_ALLOW_NEW_CONN = true;

    public static final String AUDIO_LATENCY_MS = "audio_latency_ms";
    public static final int DEF_AUDIO_LATENCY_MS = -1;

    public static final String DEBUG_ENABLED = "debug_enabled";
    public static final boolean DEF_DEBUG_ENABLED = false;

    public static final String DEVELOPER_OPTIONS = "developer_options";
    public static final boolean DEF_DEVELOPER_OPTIONS = false;

    public static final String BENCHMARK_LOG = "benchmark_log";
    public static final boolean DEF_BENCHMARK_LOG = false;

    public static final String IDLE_PREVIEW = "idle_preview";
    public static final boolean DEF_IDLE_PREVIEW = false;

    public static final String AUTO_FULLSCREEN = "auto_fullscreen";
    public static final boolean DEF_AUTO_FULLSCREEN = true;

    public static final String KEEP_SCREEN_ON = "keep_screen_on";
    public static final boolean DEF_KEEP_SCREEN_ON = true;

    public static final String ADVERTISE_VIDEO = "advertise_video";
    public static final boolean DEF_ADVERTISE_VIDEO = true;

    public static final String ADVERTISE_AUDIO = "advertise_audio";
    public static final boolean DEF_ADVERTISE_AUDIO = true;

    public static final String LAUNCH_ON_CONNECT = "launch_on_connect";
    public static final boolean DEF_LAUNCH_ON_CONNECT = true;

    /** Normalized floating mini-window position (0..1). Negative = unset → bottom-right. */
    public static final String VIDEO_FLOAT_NORM_X = "video_float_norm_x";
    public static final String VIDEO_FLOAT_NORM_Y = "video_float_norm_y";
    public static final float DEF_VIDEO_FLOAT_NORM = -1f;

    /** Last HLS surface framing mode ({@code VideoContentScale} name). */
    public static final String VIDEO_CONTENT_SCALE = "video_content_scale";
    public static final String DEF_VIDEO_CONTENT_SCALE = "BEST_FIT";

    /** Remember video chrome lock across sessions. */
    public static final String VIDEO_CONTROLS_LOCKED = "video_controls_locked";
    public static final boolean DEF_VIDEO_CONTROLS_LOCKED = false;
}
