package com.ava.mods.phicomm;

import android.content.Context;
import android.util.Log;

/**
 * Top-cover key sensor — mirrors stock {@code PhicommKeyEventProcessor} (msg 256 only).
 * Gesture side-effects (dormant enter/exit) are delegated to the manager; no Unisound engine.
 */
final class PhicommKeyEventListener {
    private static final String TAG = "PhicommKeyEvent";
    private static final int MSG_TYPE_INPUT_KEY = 256;

    static final int TYPE_CLICK = 1;
    static final int TYPE_DOUBLE_CLICK = 2;
    static final int TYPE_TRIPLE_CLICK = 3;
    static final int TYPE_LONG_CLICK = 5;

    interface Handler {
        void onTopKeyGesture(int type, String label);

        void onSingleClick();

        void onDoubleClick();

        void onTripleClick();

        void onLongClick();
    }

    private final MsgCenterBridge bridge;
    private volatile Handler handler;
    private volatile String lastKeyLabel = "idle";
    private volatile boolean started;

    PhicommKeyEventListener(Context context) {
        bridge = new MsgCenterBridge(context);
    }

    boolean isAvailable() {
        return bridge.isAvailable();
    }

    void setHandler(Handler handler) {
        this.handler = handler;
    }

    String getLastKeyLabel() {
        return lastKeyLabel;
    }

    void start() {
        if (started || !bridge.isAvailable()) {
            return;
        }
        boolean registered = bridge.registerMessageReceiver(MSG_TYPE_INPUT_KEY, new MsgCenterBridge.MessageCallback() {
            @Override
            public void onMessage(int what, int arg1, int arg2, Object payload) {
                if (what != MSG_TYPE_INPUT_KEY) {
                    return;
                }
                dispatchKeyType(arg1);
            }
        });
        started = registered;
        Log.i(TAG, "top key listener started=" + registered);
    }

    void stop() {
        bridge.unregisterAll();
        started = false;
    }

    private void dispatchKeyType(int type) {
        String label;
        Handler current = handler;
        switch (type) {
            case TYPE_CLICK:
                label = "single";
                if (current != null) {
                    current.onSingleClick();
                }
                break;
            case TYPE_DOUBLE_CLICK:
                label = "double";
                if (current != null) {
                    current.onDoubleClick();
                }
                break;
            case TYPE_TRIPLE_CLICK:
                label = "triple";
                if (current != null) {
                    current.onTripleClick();
                }
                break;
            case TYPE_LONG_CLICK:
                label = "long";
                if (current != null) {
                    current.onLongClick();
                }
                break;
            default:
                label = "unknown_" + type;
                break;
        }
        lastKeyLabel = label;
        Log.d(TAG, "top key type=" + type + " label=" + label);
        if (current != null) {
            current.onTopKeyGesture(type, label);
        }
    }
}
