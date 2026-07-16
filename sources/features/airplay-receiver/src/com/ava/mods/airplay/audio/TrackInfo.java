package com.ava.mods.airplay.audio;

import android.graphics.Bitmap;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TrackInfo {
    public final String title;
    public final String artist;
    public final String album;
    public final String genre;
    public final long durationMs;
    public final Bitmap coverArt;

    public TrackInfo() {
        this("", "", "", "", 0L, null);
    }

    public TrackInfo(String title,
                     String artist,
                     String album,
                     String genre,
                     long durationMs,
                     Bitmap coverArt) {
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.genre = genre;
        this.durationMs = durationMs;
        this.coverArt = coverArt;
    }

    public TrackInfo withCoverArt(Bitmap art) {
        return new TrackInfo(title, artist, album, genre, durationMs, art);
    }

    public static TrackInfo fromDmap(Map<String, Object> map, Bitmap existingArt) {
        Map<String, Object> flat = flatten(map);
        String title = strOrEmpty(flat.get("minm"));
        String artist = strOrEmpty(flat.get("asar"));
        String album = strOrEmpty(flat.get("asal"));
        String genre = strOrEmpty(flat.get("asgn"));
        long durationMs = 0L;
        Object dur = flat.get("astm");
        if (dur instanceof Long) durationMs = (Long) dur;
        else if (dur instanceof Number) durationMs = ((Number) dur).longValue();
        return new TrackInfo(title, artist, album, genre, durationMs, existingArt);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> flatten(Map<String, Object> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map) {
                result.putAll(flatten((Map<String, Object>) v));
            } else {
                result.put(e.getKey(), v);
            }
        }
        return result;
    }

    private static String strOrEmpty(Object o) {
        return o instanceof String ? (String) o : "";
    }
}
