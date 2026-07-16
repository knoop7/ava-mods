package com.ava.mods.airplay.audio;

/**
 * Thin DACP playback helper used by AirPlayEngine.
 * MediaSessionCompat in the engine owns transport UI; this class only
 * refreshes sender-side play/pause/next/prev via {@link DacpController}.
 */
public final class DacpPlayer {

    public interface DacpSupplier {
        DacpController get();
    }

    public interface SnapshotSupplier {
        Snapshot get();
    }

    public interface LongSupplier {
        long get();
    }

    public interface PlayingSetter {
        void set(boolean playing);
    }

    public static final class Snapshot {
        public final TrackInfo track;
        public final byte[] artworkData;
        public final long durationMs;
        public final boolean playing;
        public final boolean active;

        public Snapshot(TrackInfo track,
                        byte[] artworkData,
                        long durationMs,
                        boolean playing,
                        boolean active) {
            this.track = track;
            this.artworkData = artworkData;
            this.durationMs = durationMs;
            this.playing = playing;
            this.active = active;
        }
    }

    private final DacpSupplier dacp;
    private final SnapshotSupplier snapshot;
    private final PlayingSetter setPlaying;

    public DacpPlayer(Object ignoredLooper,
                      DacpSupplier dacp,
                      SnapshotSupplier snapshot,
                      LongSupplier ignoredPositionMs,
                      PlayingSetter setPlaying) {
        this.dacp = dacp;
        this.snapshot = snapshot;
        this.setPlaying = setPlaying;
    }

    public void refresh() {
        // Engine pushes MediaSession metadata separately; nothing to invalidate here.
    }

    public void setPlayingRemote(boolean playing) {
        DacpController c = dacp != null ? dacp.get() : null;
        if (c == null) return;
        if (playing) c.play(); else c.pause();
        if (setPlaying != null) setPlaying.set(playing);
    }

    public void next() {
        DacpController c = dacp != null ? dacp.get() : null;
        if (c != null) c.nextItem();
    }

    public void previous() {
        DacpController c = dacp != null ? dacp.get() : null;
        if (c != null) c.prevItem();
    }

    public Snapshot currentSnapshot() {
        return snapshot != null ? snapshot.get() : null;
    }

    public void release() {
        // no resources
    }
}
