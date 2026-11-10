package com.exteragram.messenger.speech.utils;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import org.telegram.messenger.FileLog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decodes the audio track of a voice message (OGG/Opus) or a round video (MP4/AAC) with the platform
 * MediaCodec and converts it on the fly to 16-bit little-endian mono PCM at 16 kHz for Vosk.
 * (Our 12.10.5 libtmessages no longer exports Opus PCM decoding to Java, only waveform helpers.)
 */
public abstract class FormatConverter {

    public static final int TARGET_SAMPLE_RATE = 16000;

    public static int getSampleRate(String path) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(path);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/") && format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    return format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                }
            }
        } catch (Exception e) {
            FileLog.e("Error detecting sample rate", e);
        } finally {
            extractor.release();
        }
        return 48000;
    }

    public static PcmInputStream extractAndConvertToPcm(String path, boolean limitToOneMinute) throws IOException {
        return new PcmInputStream(path, limitToOneMinute);
    }

    public static class PcmInputStream extends InputStream {
        private static final long ONE_MINUTE_US = 60_000_000L;
        private static final long TIMEOUT_US = 10_000L;

        private final MediaExtractor extractor;
        private final MediaCodec codec;
        private final boolean limitToOneMinute;
        private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        private int sampleRate;
        private int channelCount;
        private boolean inputDone;
        private boolean outputDone;
        private boolean released;

        // resampler state (source position in source samples per output sample)
        private double resamplePos;
        private short lastSample;
        private boolean hasLastSample;

        private byte[] pending = new byte[0];
        private int pendingOffset;
        private int pendingLength;

        public PcmInputStream(String path, boolean limitToOneMinute) throws IOException {
            this.limitToOneMinute = limitToOneMinute;
            extractor = new MediaExtractor();
            MediaCodec decoder = null;
            try {
                extractor.setDataSource(path);
                int track = -1;
                MediaFormat format = null;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat f = extractor.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        track = i;
                        format = f;
                        break;
                    }
                }
                if (track < 0) {
                    throw new IOException("Not an audio file");
                }
                extractor.selectTrack(track);
                sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
                channelCount = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
                decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
                decoder.configure(format, null, null, 0);
                decoder.start();
            } catch (Exception e) {
                if (decoder != null) {
                    try {
                        decoder.release();
                    } catch (Exception ignore) {
                    }
                }
                extractor.release();
                throw e instanceof IOException ? (IOException) e : new IOException(e);
            }
            codec = decoder;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) == -1 ? -1 : (b[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int total = 0;
            while (total < len) {
                if (pendingLength == 0 && !fill()) {
                    break;
                }
                int n = Math.min(len - total, pendingLength);
                System.arraycopy(pending, pendingOffset, b, off + total, n);
                pendingOffset += n;
                pendingLength -= n;
                total += n;
            }
            return total > 0 ? total : -1;
        }

        /** Decodes until some converted PCM is available. Returns false at end of stream. */
        private boolean fill() throws IOException {
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        int inIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                        if (inIndex >= 0) {
                            ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                            int size = inBuf == null ? -1 : extractor.readSampleData(inBuf, 0);
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
                                extractor.advance();
                            }
                        }
                    }
                    int outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat outFormat = codec.getOutputFormat();
                        if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        }
                        if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        }
                    } else if (outIndex >= 0) {
                        boolean eos = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                        int produced = 0;
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset);
                            outBuf.limit(bufferInfo.offset + bufferInfo.size);
                            produced = convert(outBuf.slice().order(ByteOrder.LITTLE_ENDIAN));
                        }
                        codec.releaseOutputBuffer(outIndex, false);
                        if (eos || (limitToOneMinute && bufferInfo.presentationTimeUs >= ONE_MINUTE_US)) {
                            outputDone = true;
                        }
                        if (produced > 0) {
                            return true;
                        }
                    }
                }
            } catch (IllegalStateException e) {
                throw new IOException(e);
            }
            return false;
        }

        /** Downmixes to mono and resamples to 16 kHz into {@link #pending}. Returns produced bytes. */
        private int convert(ByteBuffer buf) {
            int channels = Math.max(1, channelCount);
            int frames = buf.remaining() / 2 / channels;
            if (frames <= 0) {
                return 0;
            }
            short[] mono = new short[frames];
            for (int i = 0; i < frames; i++) {
                int sum = 0;
                for (int c = 0; c < channels; c++) {
                    sum += buf.getShort();
                }
                mono[i] = (short) (sum / channels);
            }
            int maxOut = (int) ((long) frames * TARGET_SAMPLE_RATE / Math.max(1, sampleRate)) + 2;
            if (pending.length < maxOut * 2) {
                pending = new byte[maxOut * 2];
            }
            int out = 0;
            if (sampleRate == TARGET_SAMPLE_RATE) {
                for (short s : mono) {
                    out = put(out, s);
                }
            } else if (sampleRate > TARGET_SAMPLE_RATE && sampleRate % TARGET_SAMPLE_RATE == 0) {
                // integer decimation (48k -> 16k for Opus) with box averaging as a simple low-pass
                int factor = sampleRate / TARGET_SAMPLE_RATE;
                int start = (int) resamplePos;
                int i = start;
                for (; i + factor <= frames; i += factor) {
                    int sum = 0;
                    for (int k = 0; k < factor; k++) {
                        sum += mono[i + k];
                    }
                    out = put(out, (short) (sum / factor));
                }
                resamplePos = 0; // drop the incomplete tail (< factor samples, at most 0.1 ms)
            } else {
                // linear interpolation for other rates (e.g. 44.1k AAC in round videos)
                double step = sampleRate / (double) TARGET_SAMPLE_RATE;
                double pos = resamplePos;
                while (pos < frames) {
                    int idx = (int) pos;
                    double frac = pos - idx;
                    short a = idx == 0 ? (hasLastSample ? lastSample : mono[0]) : mono[idx - 1];
                    short b = mono[idx];
                    out = put(out, (short) (a + (b - a) * frac));
                    pos += step;
                }
                resamplePos = pos - frames;
                lastSample = mono[frames - 1];
                hasLastSample = true;
            }
            pendingOffset = 0;
            pendingLength = out;
            return out;
        }

        private int put(int out, short s) {
            pending[out] = (byte) (s & 0xFF);
            pending[out + 1] = (byte) ((s >> 8) & 0xFF);
            return out + 2;
        }

        @Override
        public void close() throws IOException {
            if (released) {
                return;
            }
            released = true;
            try {
                codec.stop();
            } catch (Exception ignore) {
            }
            try {
                codec.release();
            } catch (Exception ignore) {
            }
            extractor.release();
            super.close();
        }
    }
}
