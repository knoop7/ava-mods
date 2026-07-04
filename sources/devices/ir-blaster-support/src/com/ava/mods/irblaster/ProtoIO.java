package com.ava.mods.irblaster;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal protobuf wire-format + ESPHome plaintext frame helpers.
 *
 * Plaintext frame (see developers.esphome.io/architecture/api/protocol_details):
 *   [0x00 indicator][varint payload_size][varint message_type][protobuf payload]
 *
 * Only the handful of messages this mod exchanges are handled; unknown fields
 * are skipped by wire type so the parser stays forward-compatible.
 */
final class ProtoIO {

    private ProtoIO() {
    }

    static final class Frame {
        final int type;
        final byte[] payload;

        Frame(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    /** Reads one plaintext frame; returns null on a clean end-of-stream. */
    static Frame readFrame(InputStream in) throws IOException {
        int indicator = in.read();
        if (indicator < 0) {
            return null;
        }
        if (indicator != 0x00) {
            throw new IOException("bad plaintext indicator byte: " + indicator);
        }
        int size = (int) readVarintStream(in);
        int type = (int) readVarintStream(in);
        byte[] payload = new byte[size];
        readFully(in, payload);
        return new Frame(type, payload);
    }

    /** Serialises one plaintext frame. Synchronised so concurrent writers cannot interleave. */
    static void writeFrame(OutputStream out, int type, byte[] payload) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream(8);
        header.write(0x00);
        writeVarint(header, payload.length);
        writeVarint(header, type);
        synchronized (out) {
            header.writeTo(out);
            out.write(payload);
            out.flush();
        }
    }

    // ---- varint (stream) ----

    private static long readVarintStream(InputStream in) throws IOException {
        long result = 0;
        int shift = 0;
        while (shift < 64) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("unexpected EOF in varint");
            }
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        throw new IOException("varint too long");
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) {
                throw new EOFException("unexpected EOF in payload");
            }
            off += r;
        }
    }

    // ---- varint (buffer) ----

    static void writeVarint(ByteArrayOutputStream out, long value) {
        while (true) {
            int b = (int) (value & 0x7F);
            value >>>= 7;
            if (value != 0) {
                out.write(b | 0x80);
            } else {
                out.write(b);
                return;
            }
        }
    }

    // ---- field writers ----

    static void writeVarintField(ByteArrayOutputStream out, int field, long value) {
        writeVarint(out, ((long) field << 3));
        writeVarint(out, value);
    }

    static void writeStringField(ByteArrayOutputStream out, int field, String value) {
        if (value == null) {
            value = "";
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarint(out, ((long) field << 3) | 2);
        writeVarint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    static void writeFixed32Field(ByteArrayOutputStream out, int field, int value) {
        writeVarint(out, ((long) field << 3) | 5);
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    // ---- zigzag ----

    static int zigzagDecode(long raw) {
        return (int) ((raw >>> 1) ^ -(raw & 1));
    }

    // ---- buffer reader ----

    static final class Reader {
        private final byte[] buf;
        private int pos;
        private final int end;

        Reader(byte[] buf) {
            this.buf = buf;
            this.pos = 0;
            this.end = buf.length;
        }

        boolean hasMore() {
            return pos < end;
        }

        int position() {
            return pos;
        }

        long readVarint() {
            long result = 0;
            int shift = 0;
            while (pos < end) {
                int b = buf[pos++] & 0xFF;
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
            }
            return result;
        }

        int readFixed32() {
            int r = (buf[pos] & 0xFF)
                    | ((buf[pos + 1] & 0xFF) << 8)
                    | ((buf[pos + 2] & 0xFF) << 16)
                    | ((buf[pos + 3] & 0xFF) << 24);
            pos += 4;
            return r;
        }

        /** Reads a length-delimited chunk of packed zigzag varints into an int list. */
        List<Integer> readPackedSint32() {
            int len = (int) readVarint();
            int limit = pos + len;
            List<Integer> out = new ArrayList<>();
            while (pos < limit) {
                out.add(zigzagDecode(readVarint()));
            }
            pos = limit;
            return out;
        }

        void skip(int wireType) {
            switch (wireType) {
                case 0:
                    readVarint();
                    break;
                case 1:
                    pos += 8;
                    break;
                case 5:
                    pos += 4;
                    break;
                case 2:
                    int len = (int) readVarint();
                    pos += len;
                    break;
                default:
                    pos = end;
                    break;
            }
        }
    }
}
