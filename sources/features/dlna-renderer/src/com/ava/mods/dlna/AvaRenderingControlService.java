package com.ava.mods.dlna;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

import org.jupnp.model.types.UnsignedIntegerFourBytes;
import org.jupnp.model.types.UnsignedIntegerTwoBytes;
import org.jupnp.support.model.Channel;
import org.jupnp.support.renderingcontrol.AbstractAudioRenderingControl;

/**
 * RenderingControl:1 implementation, ported from DLNA-Cast's
 * AudioRenderServiceImpl + AudioRenderController (Kotlin/Cling) to Java/jUPnP.
 * Maps UPnP volume 0-100 onto STREAM_MUSIC, same as the Sendspin device-volume mode.
 */
public class AvaRenderingControlService extends AbstractAudioRenderingControl {
    private static final String TAG = "DlnaRenderingControl";

    private final AudioManager audioManager;
    private final boolean allowVolumeControl;
    private volatile UnsignedIntegerTwoBytes lastNonZeroVolume = new UnsignedIntegerTwoBytes(50);

    public AvaRenderingControlService(Context context, boolean allowVolumeControl) {
        this.audioManager = (AudioManager) context.getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
        this.allowVolumeControl = allowVolumeControl;
    }

    @Override
    public UnsignedIntegerFourBytes[] getCurrentInstanceIds() {
        return new UnsignedIntegerFourBytes[]{new UnsignedIntegerFourBytes(0)};
    }

    @Override
    public boolean getMute(UnsignedIntegerFourBytes instanceId, String channelName) {
        return getVolume(instanceId, channelName).getValue() == 0L;
    }

    @Override
    public void setMute(UnsignedIntegerFourBytes instanceId, String channelName, boolean desiredMute) {
        Log.i(TAG, "SetMute: " + desiredMute);
        if (desiredMute) {
            UnsignedIntegerTwoBytes current = getVolume(instanceId, channelName);
            if (current.getValue() > 0L) {
                lastNonZeroVolume = current;
            }
            setVolume(instanceId, channelName, new UnsignedIntegerTwoBytes(0));
        } else {
            setVolume(instanceId, channelName, lastNonZeroVolume);
        }
    }

    @Override
    public UnsignedIntegerTwoBytes getVolume(UnsignedIntegerFourBytes instanceId, String channelName) {
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        return new UnsignedIntegerTwoBytes(max > 0 ? volume * 100L / max : 0L);
    }

    @Override
    public void setVolume(UnsignedIntegerFourBytes instanceId, String channelName,
                          UnsignedIntegerTwoBytes desiredVolume) {
        if (!allowVolumeControl) {
            Log.d(TAG, "SetVolume ignored (disabled in mod config)");
            return;
        }
        long percent = Math.max(0L, Math.min(100L, desiredVolume.getValue()));
        Log.i(TAG, "SetVolume: " + percent);
        if (percent > 0L) {
            lastNonZeroVolume = new UnsignedIntegerTwoBytes(percent);
        }
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int target = (int) (percent * max / 100L);
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        } catch (SecurityException e) {
            // Do-not-disturb restrictions on some ROMs.
            Log.w(TAG, "setStreamVolume rejected", e);
        }
    }

    @Override
    protected Channel[] getCurrentChannels() {
        return new Channel[]{Channel.Master};
    }
}
