package com.ava.mods.edgetts;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

final class Mp3Decoder {
    private static final String TAG = "Mp3Decoder";

    private Mp3Decoder() {
    }

    static byte[] decodeToPcm16(byte[] mp3Data, File cacheDir) throws Exception {
        File tmpFile = new File(cacheDir, "edge_tts_tmp.mp3");
        try {
            FileOutputStream fos = new FileOutputStream(tmpFile);
            fos.write(mp3Data);
            fos.close();

            MediaExtractor extractor = new MediaExtractor();
            extractor.setDataSource(tmpFile.getAbsolutePath());

            int audioTrack = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    break;
                }
            }

            if (audioTrack < 0) {
                throw new IllegalStateException("No audio track in MP3");
            }

            MediaFormat inputFormat = extractor.getTrackFormat(audioTrack);
            extractor.selectTrack(audioTrack);

            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            MediaCodec decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            ByteArrayOutputStream pcmBuffer = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean eos = false;

            while (true) {
                if (!eos) {
                    int inputIndex = decoder.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            eos = true;
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = decoder.dequeueOutputBuffer(info, eos ? 5000 : 10000);
                if (outputIndex >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        if (info.size > 0) {
                            ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);
                            byte[] chunk = new byte[info.size];
                            outputBuffer.get(chunk);
                            pcmBuffer.write(chunk);
                        }
                        decoder.releaseOutputBuffer(outputIndex, false);
                        break;
                    }
                    if (info.size > 0) {
                        ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);
                        byte[] chunk = new byte[info.size];
                        outputBuffer.get(chunk);
                        pcmBuffer.write(chunk);
                    }
                    decoder.releaseOutputBuffer(outputIndex, false);
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (eos) break;
                }
            }

            decoder.stop();
            decoder.release();
            extractor.release();

            return pcmBuffer.toByteArray();
        } finally {
            tmpFile.delete();
        }
    }
}
