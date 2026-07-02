package com.ava.mods.dlna;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.util.Locale;

/**
 * Minimal DIDL-Lite parser. Extracts display metadata and media kind from
 * SetAVTransportURI CurrentURIMetaData (best-effort; controllers vary widely).
 */
public final class DidlMetadata {
    public final String title;
    public final String artist;
    public final String album;
    public final String albumArtUri;
    public final String upnpClass;
    public final String mime;
    public final String rawXml;
    /** Duration declared by the controller's <res duration="H:MM:SS.mmm"> attribute, -1 if unknown. */
    public final long durationMs;

    private DidlMetadata(String title, String artist, String album, String albumArtUri,
                         String upnpClass, String mime, String rawXml, long durationMs) {
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.albumArtUri = albumArtUri;
        this.upnpClass = upnpClass;
        this.mime = mime;
        this.rawXml = rawXml;
        this.durationMs = durationMs;
    }

    public static final DidlMetadata EMPTY = new DidlMetadata("", "", "", "", "", "", "", -1);

    public static DidlMetadata parse(String didlXml) {
        if (didlXml == null || didlXml.trim().isEmpty()) {
            return EMPTY;
        }
        String title = "";
        String artist = "";
        String album = "";
        String art = "";
        String upnpClass = "";
        String mime = "";
        long durationMs = -1;
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(didlXml));
            int event = parser.getEventType();
            String currentTag = null;
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    currentTag = parser.getName();
                    if ("res".equals(currentTag)) {
                        String protocolInfo = parser.getAttributeValue(null, "protocolInfo");
                        if (protocolInfo != null && protocolInfo.contains("video/")) {
                            mime = "video/*";
                        } else if (protocolInfo != null && protocolInfo.contains("audio/")) {
                            mime = "audio/*";
                        }
                        if (durationMs < 0) {
                            long parsed = parseDidlDuration(parser.getAttributeValue(null, "duration"));
                            if (parsed >= 0) {
                                durationMs = parsed;
                            }
                        }
                    }
                    if ("class".equals(currentTag) || "upnp:class".equals(currentTag)) {
                        // value read on TEXT event
                    }
                } else if (event == XmlPullParser.TEXT && currentTag != null) {
                    String text = parser.getText();
                    if (text != null && !text.trim().isEmpty()) {
                        String value = text.trim();
                        if ("title".equals(currentTag) && title.isEmpty()) {
                            title = value;
                        } else if (("artist".equals(currentTag) || "creator".equals(currentTag)) && artist.isEmpty()) {
                            artist = value;
                        } else if ("album".equals(currentTag) && album.isEmpty()) {
                            album = value;
                        } else if ("albumArtURI".equals(currentTag) && art.isEmpty()) {
                            art = value;
                        } else if (("class".equals(currentTag) || "upnp:class".equals(currentTag)) && upnpClass.isEmpty()) {
                            upnpClass = value;
                        }
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    currentTag = null;
                }
                event = parser.next();
            }
        } catch (Exception ignored) {
        }
        return new DidlMetadata(title, artist, album, art, upnpClass, mime, didlXml, durationMs);
    }

    /**
     * Parses the UPnP ContentDirectory duration format H+:MM:SS[.F+] (e.g.
     * "0:03:24" or "1:02:03.500") into milliseconds. Returns -1 if the value is
     * missing or malformed.
     */
    private static long parseDidlDuration(String value) {
        if (value == null) {
            return -1;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return -1;
        }
        try {
            String hms = v;
            String fraction = "";
            int dot = v.indexOf('.');
            if (dot >= 0) {
                hms = v.substring(0, dot);
                fraction = v.substring(dot + 1);
            }
            String[] parts = hms.split(":");
            if (parts.length != 3) {
                return -1;
            }
            long hours = Long.parseLong(parts[0]);
            long minutes = Long.parseLong(parts[1]);
            long seconds = Long.parseLong(parts[2]);
            long millis = (hours * 3600 + minutes * 60 + seconds) * 1000;
            if (!fraction.isEmpty()) {
                String fracDigits = (fraction + "000").substring(0, 3);
                millis += Long.parseLong(fracDigits);
            }
            return millis;
        } catch (Exception e) {
            return -1;
        }
    }

    public boolean isVideo() {
        String cls = upnpClass.toLowerCase(Locale.US);
        if (cls.contains("videoitem") || cls.contains("movie")) {
            return true;
        }
        String m = mime.toLowerCase(Locale.US);
        return m.startsWith("video/");
    }

    public boolean isAudio() {
        String cls = upnpClass.toLowerCase(Locale.US);
        if (cls.contains("audioitem") || cls.contains("musictrack")) {
            return true;
        }
        String m = mime.toLowerCase(Locale.US);
        return m.startsWith("audio/");
    }

    public MediaKind mediaKind(String uri) {
        if (isVideo()) {
            return MediaKind.VIDEO;
        }
        if (isAudio()) {
            return MediaKind.AUDIO;
        }
        return guessFromUri(uri);
    }

    public static MediaKind guessFromUri(String uri) {
        if (uri == null || uri.isEmpty()) {
            return MediaKind.AUDIO;
        }
        String lower = uri.toLowerCase(Locale.US);
        int q = lower.indexOf('?');
        if (q > 0) {
            lower = lower.substring(0, q);
        }
        if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                || lower.endsWith(".m4v") || lower.endsWith(".avi") || lower.endsWith(".mov")
                || lower.endsWith(".ts") || lower.endsWith(".m2ts") || lower.endsWith(".wmv")) {
            return MediaKind.VIDEO;
        }
        return MediaKind.AUDIO;
    }

    public String displayText() {
        if (title.isEmpty()) {
            return "";
        }
        if (artist.isEmpty()) {
            return title;
        }
        return artist + " - " + title;
    }

    public String subtitleLine() {
        if (!album.isEmpty()) {
            return album;
        }
        if (!artist.isEmpty() && !title.isEmpty()) {
            return artist;
        }
        return "DLNA";
    }
}
