package com.ava.mods.phicomm;

import android.content.Context;

/**
 * Stock device status sync via msgcenter — ported from {@code PhicommXController.syncDeviceStatus}.
 */
final class PhicommStatusBridge {
    static final int STATUS_SPEECH = 0;
    static final int STATUS_MUSIC = 1;
    static final int STATUS_CONNECT_NET = 2;
    static final int STATUS_BLUETOOTH = 3;
    static final int STATUS_DORMANT = 5;

    private static final int MSG_TYPE_HIVOICE = 16384;

    private final MsgCenterBridge bridge;

    PhicommStatusBridge(Context context) {
        bridge = new MsgCenterBridge(context);
    }

    boolean isAvailable() {
        return bridge.isAvailable();
    }

    void syncDeviceStatus(int status) {
        bridge.sendMessage(MSG_TYPE_HIVOICE, 8, -1, PhicommParcelableHelper.obtainInt(status));
    }
}
