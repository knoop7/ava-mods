package com.ava.mods.irblaster;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;

/**
 * Handles one Home Assistant connection over the ESPHome plaintext native API.
 *
 * Message IDs (esphome/components/api/api.proto):
 *   1 HelloRequest / 2 HelloResponse
 *   3 (Connect/Auth)Request / 4 (Connect/Auth)Response  [password auth deprecated -> ack empty]
 *   5 DisconnectRequest / 6 DisconnectResponse
 *   7 PingRequest / 8 PingResponse
 *   9 DeviceInfoRequest / 10 DeviceInfoResponse
 *   11 ListEntitiesRequest / 19 ListEntitiesDoneResponse
 *   20 SubscribeStatesRequest (infrared is stateless -> nothing to send)
 *   135 ListEntitiesInfraredResponse
 *   136 InfraredRFTransmitRawTimingsRequest (rpc returns void -> no response)
 */
final class EsphomeConnection implements Runnable {

    private static final String TAG = "IrBlaster";

    private static final int API_VERSION_MAJOR = 1;
    private static final int API_VERSION_MINOR = 14;

    private static final int MSG_HELLO_REQUEST = 1;
    private static final int MSG_HELLO_RESPONSE = 2;
    private static final int MSG_CONNECT_REQUEST = 3;
    private static final int MSG_CONNECT_RESPONSE = 4;
    private static final int MSG_DISCONNECT_REQUEST = 5;
    private static final int MSG_DISCONNECT_RESPONSE = 6;
    private static final int MSG_PING_REQUEST = 7;
    private static final int MSG_PING_RESPONSE = 8;
    private static final int MSG_DEVICE_INFO_REQUEST = 9;
    private static final int MSG_DEVICE_INFO_RESPONSE = 10;
    private static final int MSG_LIST_ENTITIES_REQUEST = 11;
    private static final int MSG_LIST_ENTITIES_DONE_RESPONSE = 19;
    private static final int MSG_LIST_ENTITIES_INFRARED_RESPONSE = 135;
    private static final int MSG_INFRARED_TRANSMIT_REQUEST = 136;

    private static final int INFRARED_CAPABILITY_TRANSMITTER = 1;

    private static final byte[] EMPTY = new byte[0];

    private final Socket socket;
    private final EsphomeApiServer server;

    EsphomeConnection(Socket socket, EsphomeApiServer server) {
        this.socket = socket;
        this.server = server;
    }

    @Override
    public void run() {
        String peer = socket.getRemoteSocketAddress() != null
                ? socket.getRemoteSocketAddress().toString() : "?";
        try {
            socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            Log.i(TAG, "HA connected: " + peer);

            while (!socket.isClosed()) {
                ProtoIO.Frame frame = ProtoIO.readFrame(in);
                if (frame == null) {
                    break;
                }
                if (!handle(frame, out)) {
                    break;
                }
            }
        } catch (IOException e) {
            Log.d(TAG, "connection closed (" + peer + "): " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "connection error (" + peer + ")", e);
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
            Log.i(TAG, "HA disconnected: " + peer);
        }
    }

    /** Returns false when the connection should close. */
    private boolean handle(ProtoIO.Frame frame, OutputStream out) throws IOException {
        switch (frame.type) {
            case MSG_HELLO_REQUEST:
                ProtoIO.writeFrame(out, MSG_HELLO_RESPONSE, buildHelloResponse());
                return true;
            case MSG_CONNECT_REQUEST:
                ProtoIO.writeFrame(out, MSG_CONNECT_RESPONSE, EMPTY);
                return true;
            case MSG_PING_REQUEST:
                ProtoIO.writeFrame(out, MSG_PING_RESPONSE, EMPTY);
                return true;
            case MSG_DEVICE_INFO_REQUEST:
                ProtoIO.writeFrame(out, MSG_DEVICE_INFO_RESPONSE, buildDeviceInfoResponse());
                return true;
            case MSG_LIST_ENTITIES_REQUEST:
                ProtoIO.writeFrame(out, MSG_LIST_ENTITIES_INFRARED_RESPONSE, buildInfraredEntity());
                ProtoIO.writeFrame(out, MSG_LIST_ENTITIES_DONE_RESPONSE, EMPTY);
                return true;
            case MSG_INFRARED_TRANSMIT_REQUEST:
                handleTransmit(frame.payload);
                return true;
            case MSG_DISCONNECT_REQUEST:
                ProtoIO.writeFrame(out, MSG_DISCONNECT_RESPONSE, EMPTY);
                return false;
            default:
                // Unknown / unhandled request (logs, time, subscribe, ...): ignore.
                return true;
        }
    }

    private byte[] buildHelloResponse() {
        ByteArrayOutputStream b = new ByteArrayOutputStream(48);
        ProtoIO.writeVarintField(b, 1, API_VERSION_MAJOR);
        ProtoIO.writeVarintField(b, 2, API_VERSION_MINOR);
        ProtoIO.writeStringField(b, 3, "ava-ir " + server.esphomeVersion);
        ProtoIO.writeStringField(b, 4, server.nodeName);
        return b.toByteArray();
    }

    private byte[] buildDeviceInfoResponse() {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        ProtoIO.writeStringField(b, 2, server.nodeName);
        ProtoIO.writeStringField(b, 3, server.macAddress);
        ProtoIO.writeStringField(b, 4, server.esphomeVersion);
        ProtoIO.writeStringField(b, 6, server.model);
        ProtoIO.writeStringField(b, 12, server.manufacturer);
        ProtoIO.writeStringField(b, 13, server.friendlyName);
        return b.toByteArray();
    }

    private byte[] buildInfraredEntity() {
        ByteArrayOutputStream b = new ByteArrayOutputStream(96);
        ProtoIO.writeStringField(b, 1, server.entityObjectId);
        ProtoIO.writeFixed32Field(b, 2, server.entityKey);
        ProtoIO.writeStringField(b, 3, server.entityName);
        if (server.entityIcon != null && !server.entityIcon.isEmpty()) {
            ProtoIO.writeStringField(b, 4, server.entityIcon);
        }
        ProtoIO.writeVarintField(b, 8, INFRARED_CAPABILITY_TRANSMITTER);
        return b.toByteArray();
    }

    private void handleTransmit(byte[] payload) {
        int carrierFrequency = 0;
        int repeatCount = 1;
        int[] timings = null;

        ProtoIO.Reader r = new ProtoIO.Reader(payload);
        while (r.hasMore()) {
            long tag = r.readVarint();
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            switch (field) {
                case 3:
                    if (wire == 0) {
                        carrierFrequency = (int) r.readVarint();
                    } else {
                        r.skip(wire);
                    }
                    break;
                case 4:
                    if (wire == 0) {
                        repeatCount = (int) r.readVarint();
                    } else {
                        r.skip(wire);
                    }
                    break;
                case 5:
                    if (wire == 2) {
                        timings = toIntArray(r.readPackedSint32());
                    } else if (wire == 0) {
                        timings = append(timings, ProtoIO.zigzagDecode(r.readVarint()));
                    } else {
                        r.skip(wire);
                    }
                    break;
                default:
                    r.skip(wire);
                    break;
            }
        }

        if (timings == null || timings.length == 0) {
            Log.w(TAG, "transmit request had no timings");
            return;
        }
        server.onTransmit(carrierFrequency, timings, repeatCount);
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private static int[] append(int[] arr, int value) {
        if (arr == null) {
            return new int[]{value};
        }
        int[] out = new int[arr.length + 1];
        System.arraycopy(arr, 0, out, 0, arr.length);
        out[arr.length] = value;
        return out;
    }
}
