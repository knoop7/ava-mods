package com.ava.mods.airplay.bridge;

public interface RaopCallbackHandler {
    void onVideoData(byte[] data, long ntpTimeNs, boolean isH265);
    void onAudioData(byte[] data, int ct, long ntpTimeNs, int seqNum);
    void onAudioFormat(int ct, int spf, boolean usingScreen);
    void onVideoSize(float srcW, float srcH, float w, float h);
    void onVolumeChange(float volume);
    void onConnectionInit();
    void onConnectionDestroy();
    void onConnectionReset(int reason);
    void onDisplayPin(String pin);
    void onMetadata(byte[] data);
    void onCoverArt(byte[] data);
    void onProgress(long start, long curr, long end);
    void onDacpId(String dacpId, String activeRemote);
    void onAudioOnly(boolean audioOnly);
    void onVideoPlay(String location, float startPositionSeconds);
    void onVideoScrub(float positionSeconds);
    void onVideoRate(float rate);
    void onVideoStop();
    void onVideoSessionPoll();
}
