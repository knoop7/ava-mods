package com.ava.mods.airplay;

public final class DebugInfo {
    public final String videoCodec;
    public final String videoRes;
    public final int videoFps;
    public final long videoBitrate;
    public final long videoFrames;
    public final long droppedFrames;
    public final long framePacingJitterUs;
    public final String audioCodec;
    public final int audioVolume;
    public final int connections;

    public DebugInfo() {
        this("", "", 0, 0L, 0L, 0L, 0L, "", 100, 0);
    }

    public DebugInfo(String videoCodec,
                     String videoRes,
                     int videoFps,
                     long videoBitrate,
                     long videoFrames,
                     long droppedFrames,
                     long framePacingJitterUs,
                     String audioCodec,
                     int audioVolume,
                     int connections) {
        this.videoCodec = videoCodec;
        this.videoRes = videoRes;
        this.videoFps = videoFps;
        this.videoBitrate = videoBitrate;
        this.videoFrames = videoFrames;
        this.droppedFrames = droppedFrames;
        this.framePacingJitterUs = framePacingJitterUs;
        this.audioCodec = audioCodec;
        this.audioVolume = audioVolume;
        this.connections = connections;
    }

    public String getVideoCodec() { return videoCodec; }
    public String getVideoRes() { return videoRes; }
    public int getVideoFps() { return videoFps; }
    public long getVideoBitrate() { return videoBitrate; }
    public long getVideoFrames() { return videoFrames; }
    public long getDroppedFrames() { return droppedFrames; }
    public long getFramePacingJitterUs() { return framePacingJitterUs; }
    public String getAudioCodec() { return audioCodec; }
    public int getAudioVolume() { return audioVolume; }
    public int getConnections() { return connections; }
}
