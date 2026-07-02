package com.ava.mods.dlna;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import org.jupnp.UpnpService;
import org.jupnp.UpnpServiceConfiguration;
import org.jupnp.UpnpServiceImpl;
import org.jupnp.android.AndroidRouter;
import org.jupnp.android.AndroidUpnpServiceConfiguration;
import org.jupnp.binding.annotations.AnnotationLocalServiceBinder;
import org.jupnp.model.DefaultServiceManager;
import org.jupnp.model.meta.DeviceDetails;
import org.jupnp.model.meta.DeviceIdentity;
import org.jupnp.model.meta.LocalDevice;
import org.jupnp.model.meta.LocalService;
import org.jupnp.model.meta.ManufacturerDetails;
import org.jupnp.model.meta.ModelDetails;
import org.jupnp.model.types.UDADeviceType;
import org.jupnp.model.types.UDN;
import org.jupnp.protocol.ProtocolFactory;
import org.jupnp.registry.Registry;
import org.jupnp.support.avtransport.lastchange.AVTransportLastChangeParser;
import org.jupnp.support.avtransport.lastchange.AVTransportVariable;
import org.jupnp.support.connectionmanager.ConnectionManagerService;
import org.jupnp.support.lastchange.LastChangeAwareServiceManager;
import org.jupnp.support.model.Channel;
import org.jupnp.support.model.ProtocolInfos;
import org.jupnp.support.model.TransportState;
import org.jupnp.support.renderingcontrol.lastchange.ChannelVolume;
import org.jupnp.support.renderingcontrol.lastchange.RenderingControlLastChangeParser;
import org.jupnp.support.renderingcontrol.lastchange.RenderingControlVariable;
import org.jupnp.transport.Router;

import java.util.UUID;

/**
 * Hosts the jUPnP stack and the MediaRenderer:1 LocalDevice.
 *
 * Port of DLNA-Cast's DLNARendererService (Kotlin/Cling), with two structural
 * changes: no Android Service wrapper (Ava mods cannot add manifest entries,
 * so the UpnpService runs inside the mod manager), and jUPnP 3.x instead of
 * the unobtainable Cling 2.1.1.
 */
public class DlnaUpnpEngine {
    private static final String TAG = "DlnaUpnpEngine";

    /** Process-wide guard: only one UPnP stack / SSDP advertiser at a time. */
    static final Object LIFECYCLE_LOCK = new Object();
    private static volatile DlnaUpnpEngine activeEngine;

    /** Audio/video formats we announce as sink; playback uses platform MediaPlayer codecs. */
    private static final String[] SINK_PROTOCOLS = {
            "http-get:*:audio/mpeg:*",
            "http-get:*:audio/mp4:*",
            "http-get:*:audio/x-m4a:*",
            "http-get:*:audio/aac:*",
            "http-get:*:audio/flac:*",
            "http-get:*:audio/x-flac:*",
            "http-get:*:audio/wav:*",
            "http-get:*:audio/x-wav:*",
            "http-get:*:audio/ogg:*",
            "http-get:*:audio/L16:*",
            "http-get:*:audio/*:*",
            "http-get:*:video/mp4:*",
            "http-get:*:video/x-matroska:*",
            "http-get:*:video/webm:*",
            "http-get:*:video/quicktime:*",
            "http-get:*:video/x-msvideo:*",
            "http-get:*:video/mpeg:*",
            "http-get:*:video/*:*",
    };

    /**
     * Fixed HTTP port for the device-description/control server. jUPnP's default
     * configuration binds an OS-assigned ephemeral port (0), which changes on
     * every restart and invalidates the device-description URL that controllers
     * (BubbleUPnP, Windows "Cast to Device", etc.) cached from a previous session
     * - they then need a fresh SSDP round-trip before they can talk to us again.
     * Pinning a fixed port keeps our advertised URL stable across app restarts as
     * long as the device keeps the same LAN IP. Falls back to an ephemeral port
     * if this one is unavailable (e.g. another process is squatting on it).
     */
    private static final int FIXED_STREAM_PORT = 49493;

    private final Context context;
    private final PlaybackEngine playbackEngine;
    private final DlnaRendererManager manager;

    private UpnpService upnpService;
    private LocalDevice localDevice;
    private LocalService<AvaAVTransportService> avTransportService;
    private LocalService<AvaRenderingControlService> renderingControlService;
    private AvaAVTransportService avTransportInstance;
    private WifiManager.MulticastLock multicastLock;

    private volatile boolean running = false;

    public DlnaUpnpEngine(Context context, PlaybackEngine playbackEngine, DlnaRendererManager manager) {
        this.context = context.getApplicationContext();
        this.playbackEngine = playbackEngine;
        this.manager = manager;
    }

    public boolean isRunning() {
        return running;
    }

    public AvaAVTransportService getAvTransport() {
        return avTransportInstance;
    }

    /** Blocking; call from a background thread while holding {@link #LIFECYCLE_LOCK}. */
    public void start(String friendlyName, boolean allowVolumeControl) {
        synchronized (LIFECYCLE_LOCK) {
            if (running) {
                return;
            }
            DlnaUpnpEngine previous = activeEngine;
            if (previous != null && previous != this) {
                Log.w(TAG, "Stopping stale UPnP engine before start");
                previous.stopLocked();
            }
            try {
                acquireMulticastLock();
                upnpService = startUpnpService(FIXED_STREAM_PORT);
                if (upnpService == null) {
                    // Fixed port unavailable (in use by another process, etc.) - fall
                    // back to an OS-assigned ephemeral port so the renderer still
                    // comes up; discovery just won't survive a restart as cleanly.
                    Log.w(TAG, "Fixed port " + FIXED_STREAM_PORT + " unavailable, falling back to ephemeral port");
                    upnpService = startUpnpService(0);
                }
                localDevice = createRendererDevice(friendlyName, allowVolumeControl);
                upnpService.getRegistry().addDevice(localDevice);
                running = true;
                activeEngine = this;
                Log.i(TAG, "DMR online: " + friendlyName);
            } catch (Throwable e) {
                // Catch Throwable: jUPnP/Jetty class initialization failures surface as
                // Errors and must not take down the Ava process.
                Log.e(TAG, "Failed to start UPnP stack", e);
                stopLocked();
            }
        }
    }

    public void stop() {
        synchronized (LIFECYCLE_LOCK) {
            stopLocked();
        }
    }

    private void stopLocked() {
        running = false;
        if (activeEngine == this) {
            activeEngine = null;
        }
        LocalDevice device = localDevice;
        UpnpService service = upnpService;
        localDevice = null;
        upnpService = null;
        avTransportService = null;
        renderingControlService = null;
        avTransportInstance = null;
        try {
            if (service != null) {
                if (device != null) {
                    try {
                        service.getRegistry().removeDevice(device);
                    } catch (Throwable ignored) {
                    }
                }
                service.shutdown();
            }
        } catch (Throwable e) {
            Log.w(TAG, "UPnP shutdown error", e);
        }
        releaseMulticastLock();
        Log.i(TAG, "DMR offline");
    }

    /**
     * Builds and starts the jUPnP stack bound to {@code streamPort}. Returns
     * null (instead of throwing) on a bind failure so the caller can retry
     * with a different port rather than aborting startup entirely.
     */
    private UpnpService startUpnpService(int streamPort) {
        try {
            UpnpServiceConfiguration configuration =
                    new AndroidUpnpServiceConfiguration(streamPort, 0);
            UpnpService service = new UpnpServiceImpl(configuration) {
                @Override
                protected Router createRouter(ProtocolFactory protocolFactory, Registry registry) {
                    return new AndroidRouter(getConfiguration(), protocolFactory, context);
                }
            };
            service.startup();
            return service;
        } catch (Throwable e) {
            Log.w(TAG, "UPnP startup failed on port " + streamPort, e);
            return null;
        }
    }

    /**
     * Android drops inbound multicast datagrams for an app's sockets unless it
     * explicitly holds a MulticastLock. Without this, our SSDP listener never
     * sees M-SEARCH requests from controllers doing an on-demand scan - we'd
     * only be found via our own periodic NOTIFY broadcasts, which is exactly
     * why some devices "can't discover it in time".
     */
    private void acquireMulticastLock() {
        try {
            WifiManager wifiManager =
                    (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                return;
            }
            WifiManager.MulticastLock lock = wifiManager.createMulticastLock("AvaDlnaRendererSsdp");
            lock.setReferenceCounted(false);
            lock.acquire();
            multicastLock = lock;
        } catch (Throwable e) {
            Log.w(TAG, "Failed to acquire multicast lock; SSDP discovery may be unreliable", e);
        }
    }

    private void releaseMulticastLock() {
        WifiManager.MulticastLock lock = multicastLock;
        multicastLock = null;
        if (lock != null) {
            try {
                if (lock.isHeld()) {
                    lock.release();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // LastChange eventing (GENA) - keeps BubbleUPnP & co. in sync
    // ------------------------------------------------------------------

    public void notifyTransportState(TransportState state) {
        LocalService<AvaAVTransportService> service = avTransportService;
        if (service == null) {
            return;
        }
        try {
            AvaAVTransportService impl =
                    (AvaAVTransportService) service.getManager().getImplementation();
            impl.getLastChange().setEventedValue(0, new AVTransportVariable.TransportState(state));
            ((LastChangeAwareServiceManager<?>) service.getManager()).fireLastChange();
        } catch (Exception e) {
            Log.w(TAG, "notifyTransportState failed", e);
        }
    }

    public void notifyVolume(int volumePercent) {
        LocalService<AvaRenderingControlService> service = renderingControlService;
        if (service == null) {
            return;
        }
        try {
            AvaRenderingControlService impl =
                    (AvaRenderingControlService) service.getManager().getImplementation();
            impl.getLastChange().setEventedValue(0,
                    new RenderingControlVariable.Volume(new ChannelVolume(Channel.Master, volumePercent)));
            ((LastChangeAwareServiceManager<?>) service.getManager()).fireLastChange();
        } catch (Exception e) {
            Log.w(TAG, "notifyVolume failed", e);
        }
    }

    // ------------------------------------------------------------------
    // Device construction (port of DLNARendererService.createRendererDevice)
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private LocalDevice createRendererDevice(String friendlyName, boolean allowVolumeControl)
            throws Exception {
        String seed = "AvaDlnaRenderer-" + friendlyName + "-" + Build.MODEL + "-" + Build.MANUFACTURER;
        UDN udn;
        try {
            udn = new UDN(UUID.nameUUIDFromBytes(seed.getBytes()));
        } catch (Exception e) {
            udn = new UDN(UUID.randomUUID());
        }

        AnnotationLocalServiceBinder binder = new AnnotationLocalServiceBinder();

        // AVTransport
        avTransportService = (LocalService<AvaAVTransportService>)
                binder.read(AvaAVTransportService.class);
        avTransportInstance = new AvaAVTransportService(playbackEngine, manager);
        final AvaAVTransportService avInstance = avTransportInstance;
        avTransportService.setManager(new LastChangeAwareServiceManager<AvaAVTransportService>(
                avTransportService, new AVTransportLastChangeParser()) {
            @Override
            protected AvaAVTransportService createServiceInstance() {
                return avInstance;
            }
        });

        // RenderingControl
        renderingControlService = (LocalService<AvaRenderingControlService>)
                binder.read(AvaRenderingControlService.class);
        final AvaRenderingControlService rcInstance =
                new AvaRenderingControlService(context, allowVolumeControl);
        renderingControlService.setManager(new LastChangeAwareServiceManager<AvaRenderingControlService>(
                renderingControlService, new RenderingControlLastChangeParser()) {
            @Override
            protected AvaRenderingControlService createServiceInstance() {
                return rcInstance;
            }
        });

        // ConnectionManager (some controllers refuse renderers without it)
        LocalService<ConnectionManagerService> connectionManagerService =
                (LocalService<ConnectionManagerService>) binder.read(ConnectionManagerService.class);
        StringBuilder csv = new StringBuilder();
        for (String protocol : SINK_PROTOCOLS) {
            if (csv.length() > 0) {
                csv.append(',');
            }
            csv.append(protocol);
        }
        final ProtocolInfos sinkProtocols = new ProtocolInfos(csv.toString());
        connectionManagerService.setManager(new DefaultServiceManager<ConnectionManagerService>(
                connectionManagerService, ConnectionManagerService.class) {
            @Override
            protected ConnectionManagerService createServiceInstance() {
                return new ConnectionManagerService(new ProtocolInfos(), sinkProtocols);
            }
        });

        return new LocalDevice(
                new DeviceIdentity(udn),
                new UDADeviceType("MediaRenderer", 1),
                new DeviceDetails(
                        friendlyName,
                        new ManufacturerDetails("Ava", "https://github.com/ava-voice"),
                        new ModelDetails("Ava DLNA Renderer", "Ava voice satellite DLNA media renderer", "1.1")),
                new LocalService[]{avTransportService, renderingControlService, connectionManagerService});
    }
}
