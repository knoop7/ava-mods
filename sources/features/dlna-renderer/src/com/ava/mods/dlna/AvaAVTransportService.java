package com.ava.mods.dlna;

import android.util.Log;

import org.jupnp.model.ModelUtil;
import org.jupnp.model.types.ErrorCode;
import org.jupnp.model.types.UnsignedIntegerFourBytes;
import org.jupnp.support.avtransport.AVTransportException;
import org.jupnp.support.avtransport.AbstractAVTransportService;
import org.jupnp.support.model.DeviceCapabilities;
import org.jupnp.support.model.MediaInfo;
import org.jupnp.support.model.PositionInfo;
import org.jupnp.support.model.StorageMedium;
import org.jupnp.support.model.TransportAction;
import org.jupnp.support.model.TransportInfo;
import org.jupnp.support.model.TransportSettings;
import org.jupnp.support.model.TransportState;
import org.jupnp.support.model.TransportStatus;

import java.net.URI;

/**
 * AVTransport:1 implementation, ported from DLNA-Cast's AVTransportServiceImpl +
 * AVTransportController (Kotlin/Cling) to Java/jUPnP, made headless.
 *
 * Preemption: SetAVTransportURI always replaces the current stream, matching how
 * the Sendspin pipeline treats a new stream/start. No local queue is kept;
 * SetNextAVTransportURI is stored only so Next can honor a controller-provided
 * gapless hint.
 */
public class AvaAVTransportService extends AbstractAVTransportService {
    private static final String TAG = "DlnaAVTransport";

    private static final TransportAction[] ACTIONS_STOPPED =
            {TransportAction.Play};
    private static final TransportAction[] ACTIONS_PLAYING =
            {TransportAction.Stop, TransportAction.Pause, TransportAction.Seek};
    private static final TransportAction[] ACTIONS_PAUSED =
            {TransportAction.Play, TransportAction.Seek, TransportAction.Stop};

    private final PlaybackEngine engine;
    private final DlnaRendererManager manager;

    private volatile String nextUri;
    private volatile String nextUriMetaData;

    public AvaAVTransportService(PlaybackEngine engine, DlnaRendererManager manager) {
        this.engine = engine;
        this.manager = manager;
    }

    @Override
    public UnsignedIntegerFourBytes[] getCurrentInstanceIds() {
        return new UnsignedIntegerFourBytes[]{new UnsignedIntegerFourBytes(0)};
    }

    @Override
    public void setAVTransportURI(UnsignedIntegerFourBytes instanceId,
                                  String currentURI,
                                  String currentURIMetaData) throws AVTransportException {
        Log.i(TAG, "SetAVTransportURI: " + currentURI);
        try {
            new URI(currentURI);
        } catch (Exception e) {
            throw new AVTransportException(ErrorCode.INVALID_ARGS, "CurrentURI can not be null or malformed");
        }
        DidlMetadata didl = DidlMetadata.parse(currentURIMetaData);
        // Preempt: replace whatever is playing. Auto-play only if we were already
        // playing; a following Play action starts cold loads (spec-compliant, and
        // what BubbleUPnP/foobar2000 expect).
        boolean autoPlay = engine.getState() == TransportState.PLAYING
                || engine.getState() == TransportState.TRANSITIONING;
        engine.setUri(currentURI, didl, autoPlay);
        manager.onTrackChanged(didl, currentURI);
    }

    @Override
    public void setNextAVTransportURI(UnsignedIntegerFourBytes instanceId,
                                      String nextURI,
                                      String nextURIMetaData) {
        Log.i(TAG, "SetNextAVTransportURI: " + nextURI);
        this.nextUri = nextURI;
        this.nextUriMetaData = nextURIMetaData;
    }

    /** Called by the manager when a track finishes and a next URI was provided. */
    boolean advanceToNextIfAvailable() {
        String uri = nextUri;
        String meta = nextUriMetaData;
        nextUri = null;
        nextUriMetaData = null;
        if (uri == null || uri.isEmpty()) {
            return false;
        }
        DidlMetadata didl = DidlMetadata.parse(meta);
        engine.setUri(uri, didl, true);
        manager.onTrackChanged(didl, uri);
        return true;
    }

    @Override
    public MediaInfo getMediaInfo(UnsignedIntegerFourBytes instanceId) {
        String uri = engine.getCurrentUri();
        String meta = engine.getMetadata().rawXml;
        long durationMs = engine.getDurationMs();
        if (uri.isEmpty()) {
            return new MediaInfo();
        }
        return new MediaInfo(uri, meta, new UnsignedIntegerFourBytes(1),
                ModelUtil.toTimeString(durationMs / 1000), StorageMedium.NETWORK);
    }

    @Override
    public TransportInfo getTransportInfo(UnsignedIntegerFourBytes instanceId) {
        return new TransportInfo(engine.getState(), TransportStatus.OK, "1");
    }

    @Override
    public PositionInfo getPositionInfo(UnsignedIntegerFourBytes instanceId) {
        String uri = engine.getCurrentUri();
        if (uri.isEmpty()) {
            return new PositionInfo();
        }
        String duration = ModelUtil.toTimeString(engine.getDurationMs() / 1000);
        String position = ModelUtil.toTimeString(engine.getPositionMs() / 1000);
        return new PositionInfo(1, duration, uri, position, position);
    }

    @Override
    public DeviceCapabilities getDeviceCapabilities(UnsignedIntegerFourBytes instanceId) {
        return new DeviceCapabilities(new StorageMedium[]{StorageMedium.NETWORK});
    }

    @Override
    public TransportSettings getTransportSettings(UnsignedIntegerFourBytes instanceId) {
        return new TransportSettings();
    }

    @Override
    public void stop(UnsignedIntegerFourBytes instanceId) {
        Log.i(TAG, "Stop");
        engine.stop();
    }

    @Override
    public void play(UnsignedIntegerFourBytes instanceId, String speed) {
        Log.i(TAG, "Play");
        engine.play();
    }

    @Override
    public void pause(UnsignedIntegerFourBytes instanceId) {
        Log.i(TAG, "Pause");
        engine.pause();
    }

    @Override
    public void seek(UnsignedIntegerFourBytes instanceId, String unit, String target)
            throws AVTransportException {
        Log.i(TAG, "Seek unit=" + unit + " target=" + target);
        if (target == null || target.trim().isEmpty()) {
            throw new AVTransportException(ErrorCode.INVALID_ARGS, "Seek target missing");
        }
        String normalizedUnit = unit != null ? unit.trim().toUpperCase(java.util.Locale.US) : "REL_TIME";
        if ("TRACK_NR".equals(normalizedUnit)) {
            throw new AVTransportException(ErrorCode.INVALID_ARGS, "TRACK_NR seek not supported");
        }
        try {
            // DLNA AVTransport Seek(REL_TIME) target is H+:MM:SS[.F] — same format
            // as GetPositionInfo / DIDL duration. ABS_TIME is rare on renderers; treat
            // the target string the same way (absolute offset from track start).
            long seconds = ModelUtil.fromTimeString(target.trim());
            if (seconds < 0) {
                throw new AVTransportException(ErrorCode.INVALID_ARGS, "Seek target unparsable: " + target);
            }
            engine.seekTo(seconds * 1000L);
        } catch (AVTransportException e) {
            throw e;
        } catch (Exception e) {
            Log.w(TAG, "Seek failed for target=" + target, e);
            throw new AVTransportException(ErrorCode.INVALID_ARGS, "Seek target unparsable: " + target);
        }
    }

    @Override
    public void next(UnsignedIntegerFourBytes instanceId) {
        Log.i(TAG, "Next");
        manager.skipToNext();
    }

    @Override
    public void previous(UnsignedIntegerFourBytes instanceId) {
        Log.i(TAG, "Previous");
        manager.skipToPrevious();
    }

    @Override
    public void setPlayMode(UnsignedIntegerFourBytes instanceId, String newPlayMode) {
        Log.d(TAG, "SetPlayMode ignored: " + newPlayMode);
    }

    @Override
    public void record(UnsignedIntegerFourBytes instanceId) {
        // Not supported.
    }

    @Override
    public void setRecordQualityMode(UnsignedIntegerFourBytes instanceId, String newRecordQualityMode) {
        // Not supported.
    }

    @Override
    protected TransportAction[] getCurrentTransportActions(UnsignedIntegerFourBytes instanceId) {
        TransportState state = engine.getState();
        if (state == TransportState.PLAYING || state == TransportState.TRANSITIONING) {
            return ACTIONS_PLAYING;
        }
        if (state == TransportState.PAUSED_PLAYBACK) {
            return ACTIONS_PAUSED;
        }
        return ACTIONS_STOPPED;
    }
}
