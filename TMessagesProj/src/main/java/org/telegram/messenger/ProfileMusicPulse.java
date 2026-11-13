package org.telegram.messenger;

import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * Light-weight beat detector for the music player, used by the gift bubbles in the profile header
 * (the header backdrop switches to the next gift's colors on every kick and pulses with the bass).
 *
 * The audio sink feeds raw 16-bit PCM into {@link #consume} on the playback thread; it is split into
 * 20 ms blocks, a low band (bass) and a low-mid band (kick) are measured with one-pole filters, and the
 * blocks are released by {@link #advance} when the playback position reaches them, so the visuals follow
 * what is actually heard. The UI reads the {@link #latest} frame. Nothing is analysed while no profile
 * is showing gift bubbles ({@link #attach}/{@link #detach}).
 */
public final class ProfileMusicPulse {

    public static final class Frame {
        public final float bass;
        public final float kick;
        public final long at;
        public final long beat;

        private Frame(float bass, float kick, long at, long beat) {
            this.bass = bass;
            this.kick = kick;
            this.at = at;
            this.beat = beat;
        }
    }

    private static final class Block {
        final long timeUs;
        final float bass;
        final float kick;

        Block(long timeUs, float bass, float kick) {
            this.timeUs = timeUs;
            this.bass = bass;
            this.kick = kick;
        }
    }

    private static final Frame SILENT = new Frame(0, 0, 0, 0);

    private static volatile ProfileMusicPulse active;
    private static volatile int observers;

    private final ArrayDeque<Block> pending = new ArrayDeque<>();
    private volatile Frame output = SILENT;

    private int sampleRate;
    private int channels;
    private int blockFrames;
    private float floorAlpha, bassAlpha, kickAlpha;
    private float floor, bassLow, kickLow;
    private float bassPower, kickPower;
    private int frames;
    private long blockStartUs;
    private long lastKickUs;
    private float baseline, previous;
    private long beat;

    public ProfileMusicPulse() {
        active = this;
    }

    public static void attach() {
        observers++;
    }

    public static void detach() {
        observers = Math.max(0, observers - 1);
    }

    public static Frame latest() {
        final ProfileMusicPulse pulse = active;
        return pulse == null ? SILENT : pulse.output;
    }

    private float filterAlpha(int cutoffHz) {
        return 1.0f - (float) Math.exp(cutoffHz * -2.0 * Math.PI / sampleRate);
    }

    public void configure(int sampleRate, int channelCount, boolean pcm16) {
        this.sampleRate = sampleRate;
        this.channels = pcm16 && sampleRate > 0 ? channelCount : 0;
        if (this.channels > 0) {
            blockFrames = Math.max(1, sampleRate / 50);
            floorAlpha = filterAlpha(32);
            bassAlpha = filterAlpha(74);
            kickAlpha = filterAlpha(145);
        }
        clear();
    }

    public void clear() {
        pending.clear();
        output = SILENT;
        frames = 0;
        previous = 0;
        baseline = 0;
        kickPower = 0;
        bassPower = 0;
        kickLow = 0;
        bassLow = 0;
        floor = 0;
        lastKickUs = Long.MIN_VALUE / 2;
    }

    public void consume(ByteBuffer buffer, long presentationTimeUs) {
        if (observers == 0 || active != this || channels <= 0 || presentationTimeUs < 0) {
            return;
        }
        final int frameSize = channels * 2;
        final int startPosition = buffer.position();
        final int last = buffer.limit() - frameSize;
        for (int p = startPosition; p <= last; p += frameSize) {
            if (frames == 0) {
                blockStartUs = (long) (p - startPosition) / frameSize * 1_000_000L / sampleRate + presentationTimeUs;
            }
            float sum = 0;
            for (int c = 0; c < channels; ++c) {
                final int i = p + c * 2;
                sum += (short) ((buffer.get(i + 1) << 8) | (buffer.get(i) & 0xFF));
            }
            final float sample = sum / (channels * 32768.0f);
            floor += (sample - floor) * floorAlpha;
            bassLow += (sample - bassLow) * bassAlpha;
            kickLow += (sample - kickLow) * kickAlpha;
            final float bass = bassLow - floor;
            final float kick = kickLow - bassLow;
            bassPower += bass * bass;
            kickPower += kick * kick;
            if (++frames == blockFrames) {
                finishBlock();
            }
        }
    }

    private void finishBlock() {
        final float bass = (float) Math.sqrt(bassPower / frames);
        final float kickLevel = (float) Math.sqrt(kickPower / frames);
        float kick = 0.0f;
        if (blockStartUs - lastKickUs >= 200_000 && kickLevel > 0.01f && kickLevel > baseline * 1.55f + 0.005f && kickLevel > previous * 1.23f + 0.002f) {
            lastKickUs = blockStartUs;
            kick = Math.min(1.0f, (kickLevel - baseline) * 10.0f);
        }
        baseline += (kickLevel - baseline) * 0.048f;
        previous = kickLevel;
        if (pending.size() >= 384) {
            pending.removeFirst();
        }
        pending.addLast(new Block(blockStartUs, Math.min(1.0f, bass * 5.0f), kick));
        frames = 0;
        kickPower = 0;
        bassPower = 0;
    }

    /** Called with the current playback position; publishes the blocks that are being heard now. */
    public void advance(long positionUs) {
        Block block = null;
        float kick = 0.0f;
        while (!pending.isEmpty() && pending.peekFirst().timeUs <= positionUs) {
            block = pending.removeFirst();
            if (positionUs - block.timeUs < 100_000) {
                kick = Math.max(kick, block.kick);
            }
        }
        if (block == null || active != this || observers <= 0) {
            return;
        }
        if (kick > 0.0f) {
            beat++;
        } else {
            kick = output.kick;
        }
        output = new Frame(block.bass, kick, SystemClock.uptimeMillis(), beat);
    }
}
