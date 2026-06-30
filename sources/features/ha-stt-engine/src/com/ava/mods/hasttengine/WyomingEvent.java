package com.ava.mods.hasttengine;

import org.json.JSONObject;

final class WyomingEvent {
    final String type;
    final JSONObject data;
    final byte[] payload;

    WyomingEvent(String type, JSONObject data, byte[] payload) {
        this.type = type;
        this.data = data == null ? new JSONObject() : data;
        this.payload = payload;
    }
}
