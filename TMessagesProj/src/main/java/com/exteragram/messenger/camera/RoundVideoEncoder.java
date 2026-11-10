package com.exteragram.messenger.camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Surface;

import com.exteragram.messenger.utils.network.RemoteUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.video.MP4Builder;
import org.telegram.messenger.video.MediaCodecVideoConvertor;
import org.telegram.messenger.video.Mp4Movie;
import org.telegram.ui.Components.PermissionRequest;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * exteraGram 13.1.1: round-video encoder of the classic recorder (InstantCameraView).
 * Ported by logic from the 13.1.1 smali. Replaces the stock InstantCameraView.VideoRecorder:
 * one encoder queue, a separate AudioRecord thread with pooled audio batches, frame timing anchored to
 * the camera timestamps, a pause preview file and explicit COMPLETED / CANCELLED / FAILED results.
 *
 * StaticGram: InstantCameraView keeps the stock VideoRecorder as a fallback. {@link #isEnabled()} turns the
 * new encoder off after repeated failures on this build, and a start failure switches the current recording
 * to the stock recorder.
 */
public class RoundVideoEncoder {

    private static final int STATE_IDLE = 0;
    private static final int STATE_STARTING = 1;
    private static final int STATE_RECORDING = 2;
    private static final int STATE_PAUSING = 3;
    private static final int STATE_PAUSED = 4;
    private static final int STATE_FINISHING = 5;
    private static final int STATE_FINISHED = 6;
    private static final int STATE_FAILED = 7;

    private static final int SAMPLE_RATE = 48000;
    private static final int AUDIO_CHUNK_SIZE = 2048;
    private static final int AUDIO_CHUNKS_PER_BATCH = 10;
    private static final int AUDIO_BATCH_POOL_SIZE = 25;

    private static final int MAX_FAILURES = 3;
    private static final String PREF_FAILURES = "sg_round_encoder_failures";
    private static final String PREF_FAILURES_BUILD = "sg_round_encoder_failures_build";

    // region exteraGram remote config (SystemUtils in exteraGram)

    public static int getRoundVideoResolution() {
        return RemoteUtils.getIntConfigValue("round_video_resolution", 512);
    }

    public static int getRoundVideoBitrate() {
        return RemoteUtils.getIntConfigValue("round_video_bitrate", 1000);
    }

    public static int getRoundAudioBitrate() {
        return RemoteUtils.getIntConfigValue("round_audio_bitrate", 64);
    }

    /** Longest recording that fits into 10 MB at the configured bitrates, 10..60 s. */
    public static long getRoundVideoMaxDurationMs() {
        long bitsPerSecond = (long) (Math.max(1, getRoundVideoBitrate()) + Math.max(0, getRoundAudioBitrate())) * 1024L;
        return Utilities.clamp(83886080000L / bitsPerSecond, 60000L, 10000L);
    }

    // endregion

    // region StaticGram fallback gate

    private static SharedPreferences prefs() {
        return MessagesController.getGlobalMainSettings();
    }

    /** Whether InstantCameraView should record with this encoder (false after repeated failures on this build). */
    public static boolean isEnabled() {
        try {
            SharedPreferences p = prefs();
            if (!BuildVars.BUILD_VERSION_STRING.equals(p.getString(PREF_FAILURES_BUILD, null))) {
                return true;
            }
            return p.getInt(PREF_FAILURES, 0) < MAX_FAILURES;
        } catch (Throwable e) {
            FileLog.e(e);
            return true;
        }
    }

    public static void reportFailure() {
        try {
            SharedPreferences p = prefs();
            int failures = BuildVars.BUILD_VERSION_STRING.equals(p.getString(PREF_FAILURES_BUILD, null)) ? p.getInt(PREF_FAILURES, 0) : 0;
            p.edit().putString(PREF_FAILURES_BUILD, BuildVars.BUILD_VERSION_STRING).putInt(PREF_FAILURES, failures + 1).apply();
            FileLog.e("RoundVideoEncoder failure " + (failures + 1) + "/" + MAX_FAILURES);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void reportSuccess() {
        try {
            SharedPreferences p = prefs();
            if (p.getInt(PREF_FAILURES, 0) != 0) {
                p.edit().putInt(PREF_FAILURES, 0).apply();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // endregion

    public interface Callback {
        void onAudioAmplitude(double amplitude);

        void onFinished(FinishReason reason);

        void onPaused(File previewFile);

        void onRecordingStarted(boolean resumed);

        void onWriteData(long availableSize);
    }

    public enum FinishReason {
        COMPLETED,
        CANCELLED,
        FAILED
    }

    public interface Renderer {
        /** Draws one frame into the current encoder surface, dtNs = active time since the previous frame. */
        boolean onDrawEncoderFrame(long dtNs, FrameSnapshot frame);

        void onEncoderSurfaceCreated(int width, int height);

        void onEncoderSurfaceDestroyed();
    }

    public static final class FrameSnapshot {
        public long sourceTimestampNs;
        public long arrivalTimeNs;
        public int cameraId;
        public int surfaceIndex;
        public int textureId;
        public int previewWidth;
        public int previewHeight;
        public final float[] stMatrix = new float[16];
        public final float[] mvpMatrix = new float[16];
        public final float[] textureCoords = new float[8];

        public void copyFrom(FrameSnapshot other) {
            sourceTimestampNs = other.sourceTimestampNs;
            arrivalTimeNs = other.arrivalTimeNs;
            cameraId = other.cameraId;
            surfaceIndex = other.surfaceIndex;
            textureId = other.textureId;
            System.arraycopy(other.stMatrix, 0, stMatrix, 0, 16);
            System.arraycopy(other.mvpMatrix, 0, mvpMatrix, 0, 16);
            System.arraycopy(other.textureCoords, 0, textureCoords, 0, 8);
            previewWidth = other.previewWidth;
            previewHeight = other.previewHeight;
        }
    }

    public class AudioChunkBatch {
        public int drained;
        public int results;
        public final byte[][] data = new byte[AUDIO_CHUNKS_PER_BATCH][];
        public final ByteBuffer[] buffer = new ByteBuffer[AUDIO_CHUNKS_PER_BATCH];
        public final long[] startTimeNs = new long[AUDIO_CHUNKS_PER_BATCH];
        public final Runnable deliveryRunnable = () -> handleAudioBatch(this);

        public AudioChunkBatch() {
            for (int i = 0; i < AUDIO_CHUNKS_PER_BATCH; i++) {
                byte[] bytes = new byte[AUDIO_CHUNK_SIZE];
                data[i] = bytes;
                buffer[i] = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
            }
        }
    }

    public class AudioCaptureSession {
        public final AudioRecord audioRecorder;
        public final int generation;
        public boolean recorderReleased;
        public volatile Thread thread;
        public final AtomicBoolean stopRequested = new AtomicBoolean(false);
        public final Object recorderLock = new Object();
        public final Runnable stopRunnable = this::stopRecorder;
        public final Runnable completionRunnable = () -> handleAudioCaptureFinished(this);

        public AudioCaptureSession(AudioRecord audioRecord, int generation) {
            this.audioRecorder = audioRecord;
            this.generation = generation;
        }

        public void requestStop() {
            boolean first = stopRequested.compareAndSet(false, true);
            Thread t = thread;
            if (t != null && t != Thread.currentThread()) {
                t.interrupt();
            }
            if (first && !Utilities.globalQueue.postRunnable(stopRunnable)) {
                FileLog.e("RoundVideoEncoder unable to schedule AudioRecord stop");
            }
        }

        private void stopRecorder() {
            synchronized (recorderLock) {
                if (!recorderReleased) {
                    stopAudioRecorder(audioRecorder);
                }
            }
        }

        public void releaseRecorder() {
            synchronized (recorderLock) {
                if (recorderReleased) {
                    return;
                }
                recorderReleased = true;
                releaseAudioRecorder(audioRecorder);
            }
        }
    }

    private final Renderer renderer;
    private final Callback callback;
    private final boolean isSecretChat;
    private final DispatchQueue encoderQueue;

    private final AtomicBoolean finishRequested = new AtomicBoolean(false);
    private volatile boolean started;
    private volatile int state = STATE_IDLE;

    private File videoFile;
    private File fileToWrite;
    private boolean writingToDifferentFile;
    private File pausePreviewFile;
    private EGLContext pendingResumeContext;

    private int videoWidth;
    private int videoHeight;
    private int videoBitrate;
    private int frameRate = 30;
    private long maxDurationUs;
    private long audioCapUs;
    private boolean allowSendingWhileRecording;

    private MediaCodec videoEncoder;
    private MediaCodec audioEncoder;
    private MediaCodec.BufferInfo videoBufferInfo;
    private MediaCodec.BufferInfo audioBufferInfo;
    private Surface inputSurface;
    private MP4Builder mediaMuxer;
    private int videoTrackIndex = -5;
    private int audioTrackIndex = -5;
    private int prependHeaderSize;
    private boolean firstEncode;
    private boolean videoEosSignalled;
    private boolean videoEosSeen;
    private boolean audioEosQueued;
    private boolean audioEosSeen;
    private boolean waitingAudioTail;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private EGLConfig eglConfig;

    private final Object pendingFrameLock = new Object();
    private final FrameSnapshot pendingFrame = new FrameSnapshot();
    private final FrameSnapshot currentFrame = new FrameSnapshot();
    private boolean pendingFrameSet;

    private int lastCameraId = Integer.MIN_VALUE;
    private boolean sourceAnchorSet;
    private long sourceAnchorSourceNs;
    private long sourceAnchorMonotonicNs;
    private long lastSourceTimestampNs;
    private long segmentFirstArrivalNs = -1;
    private long segmentVideoOriginNs = -1;
    private long segmentActiveBaseNs;
    private long lastVideoFrameIndex = -1;
    private long lastVideoActiveTimeNs;
    private long minVideoFrameDeltaNs;
    private long lastSubmittedVideoPtsUs = -1;
    private long lastMuxedVideoPtsUs = -1;

    private final Object audioCaptureLock = new Object();
    private AudioCaptureSession activeAudioCapture;
    private int audioCaptureGeneration;
    private boolean audioCaptureRunning;
    private final ArrayBlockingQueue<AudioChunkBatch> audioBatchPool = new ArrayBlockingQueue<>(AUDIO_BATCH_POOL_SIZE);
    private boolean audioBatchesPrepared;
    private final ArrayList<AudioChunkBatch> pendingAudio = new ArrayList<>();
    private long audioSegmentBaseUs = -1;
    private long audioSegmentFramesSubmitted;
    private long audioTotalEndUs;
    private long lastSubmittedAudioEndUs = -1;
    private long lastMuxedAudioPtsUs = -1;

    /** 0 = none, 1 = stop, 2 = cancel (requested while pausing). */
    private int deferredFinish;
    /** 0 = none, 1 = cancel, 2 = failure (waiting for the audio thread). */
    private int deferredAudioCleanup;

    public RoundVideoEncoder(Renderer renderer, Callback callback, boolean isSecretChat) {
        this.renderer = renderer;
        this.callback = callback;
        this.isSecretChat = isSecretChat;
        encoderQueue = new DispatchQueue("RoundVideoEncoder", false);
        encoderQueue.setPriority(Thread.MAX_PRIORITY);
        encoderQueue.start();
    }

    public boolean isStarted() {
        return started;
    }

    /** Starts the recording, or resumes it after {@link #pause(File)}. Called on the camera GL thread. */
    public void startRecording(File file, EGLContext sharedContext, int frameRate) {
        started = true;
        encoderQueue.postRunnable(() -> handleStart(file, sharedContext, frameRate));
    }

    /** Called on the camera GL thread after the camera texture was updated. */
    public void frameAvailable(FrameSnapshot frame) {
        if (!started) {
            return;
        }
        synchronized (pendingFrameLock) {
            pendingFrame.copyFrom(frame);
            if (pendingFrameSet) {
                return;
            }
            pendingFrameSet = true;
            encoderQueue.postRunnable(this::handleFrame);
        }
    }

    public void pause(File previewFile) {
        AudioCaptureSession capture = getActiveAudioCapture();
        encoderQueue.postRunnable(() -> handlePause(previewFile));
        requestAudioCaptureStop(capture);
    }

    public void stop() {
        if (finishRequested.compareAndSet(false, true)) {
            AudioCaptureSession capture = getActiveAudioCapture();
            encoderQueue.postRunnable(() -> handleFinish(false));
            requestAudioCaptureStop(capture);
        }
    }

    public void cancel() {
        if (finishRequested.compareAndSet(false, true)) {
            AudioCaptureSession capture = getActiveAudioCapture();
            encoderQueue.postRunnable(() -> handleFinish(true));
            requestAudioCaptureStop(capture);
        }
    }

    private void handleStart(File file, EGLContext sharedContext, int requestedFrameRate) {
        if (state == STATE_PAUSED) {
            handleResume(sharedContext);
            return;
        }
        if (state == STATE_PAUSING) {
            pendingResumeContext = sharedContext;
            return;
        }
        if (state != STATE_IDLE) {
            return;
        }
        state = STATE_STARTING;
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("RoundVideoEncoder start " + file);
        }
        try {
            videoFile = file;
            int resolution = getRoundVideoResolution();
            videoWidth = resolution;
            videoHeight = resolution;
            videoBitrate = getRoundVideoBitrate() * 1024;
            frameRate = requestedFrameRate == 60 ? 60 : 30;
            maxDurationUs = getRoundVideoMaxDurationMs() * 1000;
            audioCapUs = maxDurationUs;
            allowSendingWhileRecording = SharedConfig.deviceIsHigh();
            prepareAudioBatches();
            videoBufferInfo = new MediaCodec.BufferInfo();
            audioBufferInfo = new MediaCodec.BufferInfo();

            MediaFormat audioFormat = new MediaFormat();
            audioFormat.setString(MediaFormat.KEY_MIME, MediaController.AUDIO_MIME_TYPE);
            audioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE);
            audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, getRoundAudioBitrate() * 1024);
            audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_CHUNK_SIZE * AUDIO_CHUNKS_PER_BATCH);
            audioEncoder = MediaCodec.createEncoderByType(MediaController.AUDIO_MIME_TYPE);
            audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioEncoder.start();

            MediaFormat videoFormat = MediaFormat.createVideoFormat(MediaController.VIDEO_MIME_TYPE, videoWidth, videoHeight);
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate);
            videoFormat.setInteger("max-bitrate", videoBitrate);
            videoEncoder = MediaCodec.createEncoderByType(MediaController.VIDEO_MIME_TYPE);
            try {
                if (videoEncoder.getCodecInfo().getCapabilitiesForType(MediaController.VIDEO_MIME_TYPE).getEncoderCapabilities()
                        .isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
                    videoFormat.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = videoEncoder.createInputSurface();
            videoEncoder.start();
            firstEncode = true;

            fileToWrite = videoFile;
            writingToDifferentFile = false;
            if (ImageLoader.isSdCardPath(videoFile)) {
                try {
                    fileToWrite = new File(ApplicationLoader.getFilesDirFixed(), "camera_tmp.mp4");
                    if (fileToWrite.exists()) {
                        fileToWrite.delete();
                    }
                    writingToDifferentFile = true;
                } catch (Throwable e) {
                    FileLog.e(e);
                    fileToWrite = videoFile;
                    writingToDifferentFile = false;
                }
            }
            Mp4Movie movie = new Mp4Movie();
            movie.setCacheFile(fileToWrite);
            movie.setRotation(0);
            movie.setSize(videoWidth, videoHeight);
            mediaMuxer = new MP4Builder().createMovie(movie, isSecretChat, false);
            mediaMuxer.setAllowSyncFiles(false);

            createEncoderEgl(sharedContext);
            startAudioCapture();
            state = STATE_RECORDING;
            AndroidUtilities.runOnUIThread(() -> callback.onRecordingStarted(false));
        } catch (Throwable e) {
            FileLog.e(e);
            fail();
        }
    }

    private void handleResume(EGLContext sharedContext) {
        if (state != STATE_PAUSED) {
            return;
        }
        state = STATE_STARTING;
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("RoundVideoEncoder resume");
        }
        try {
            sourceAnchorSet = false;
            segmentFirstArrivalNs = -1;
            segmentVideoOriginNs = -1;
            audioSegmentBaseUs = -1;
            audioCapUs = maxDurationUs;
            createEncoderEgl(sharedContext);
            startAudioCapture();
            state = STATE_RECORDING;
            AndroidUtilities.runOnUIThread(() -> callback.onRecordingStarted(true));
        } catch (Throwable e) {
            FileLog.e(e);
            fail();
        }
    }

    private void handleFrame() {
        synchronized (pendingFrameLock) {
            if (!pendingFrameSet) {
                return;
            }
            pendingFrameSet = false;
            currentFrame.copyFrom(pendingFrame);
        }
        final long sourceNs = currentFrame.sourceTimestampNs;
        final long arrivalNs = currentFrame.arrivalTimeNs;
        final int cameraId = currentFrame.cameraId;
        if (state != STATE_RECORDING) {
            return;
        }
        try {
            drainEncoders();
            feedPendingAudio();
            boolean cameraChanged = cameraId != lastCameraId;
            lastCameraId = cameraId;
            boolean discontinuity = true;
            long monotonicNs;
            if (sourceNs <= 0) {
                sourceAnchorSet = false;
                monotonicNs = arrivalNs;
            } else {
                if (!sourceAnchorSet || cameraChanged || sourceNs <= lastSourceTimestampNs || sourceNs - lastSourceTimestampNs > 1000000000L) {
                    sourceAnchorSourceNs = sourceNs;
                    sourceAnchorMonotonicNs = arrivalNs;
                    sourceAnchorSet = true;
                    monotonicNs = arrivalNs;
                } else {
                    monotonicNs = sourceAnchorMonotonicNs + (sourceNs - sourceAnchorSourceNs);
                    discontinuity = cameraChanged;
                }
                lastSourceTimestampNs = sourceNs;
            }
            if (segmentFirstArrivalNs == -1) {
                segmentFirstArrivalNs = arrivalNs;
            }
            // skip the first 200 ms of every segment, like the stock recorder
            if (arrivalNs - segmentFirstArrivalNs < 200000000L) {
                return;
            }
            if (segmentVideoOriginNs != -1) {
                long activeNs = segmentActiveBaseNs + (monotonicNs - segmentVideoOriginNs);
                long frameIndex = frameRate * activeNs / 1000000000L;
                if (frameIndex > lastVideoFrameIndex && activeNs / 1000 < maxDurationUs) {
                    acceptFrame(frameIndex, activeNs, discontinuity ? 0 : activeNs - lastVideoActiveTimeNs);
                }
                return;
            }
            long activeNs = lastVideoFrameIndex < 0 ? 0 : lastVideoActiveTimeNs + videoFallbackFrameDurationNs();
            if (activeNs / 1000 >= maxDurationUs) {
                return;
            }
            long frameIndex = frameRate * activeNs / 1000000000L;
            segmentVideoOriginNs = monotonicNs;
            segmentActiveBaseNs = activeNs;
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("RoundVideoEncoder segment origin at " + activeNs + "ns slot " + frameIndex);
            }
            if (acceptFrame(frameIndex, activeNs, 0)) {
                feedPendingAudio();
            }
        } catch (Exception e) {
            FileLog.e(e);
            fail();
        }
    }

    private boolean acceptFrame(long frameIndex, long activeNs, long dtNs) {
        if (!makeEglCurrent()) {
            fail();
            return false;
        }
        try {
            if (!renderer.onDrawEncoderFrame(dtNs, currentFrame)) {
                return false;
            }
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, activeNs);
            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                FileLog.e("RoundVideoEncoder eglSwapBuffers failed at frame " + frameIndex + ": " + GLUtils.getEGLErrorString(EGL14.eglGetError()));
                fail();
                return false;
            }
            if (lastVideoFrameIndex >= 0 && dtNs > 0 && (minVideoFrameDeltaNs == 0 || dtNs < minVideoFrameDeltaNs)) {
                minVideoFrameDeltaNs = dtNs;
            }
            lastVideoFrameIndex = frameIndex;
            lastVideoActiveTimeNs = activeNs;
            lastSubmittedVideoPtsUs = activeNs / 1000;
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            fail();
            return false;
        }
    }

    private void handleAudioBatch(AudioChunkBatch batch) {
        if (state == STATE_RECORDING || state == STATE_PAUSING || state == STATE_FINISHING && waitingAudioTail) {
            pendingAudio.add(batch);
            if (segmentVideoOriginNs == -1 && pendingAudio.size() > 24) {
                recycleAudioBatch(pendingAudio.remove(0));
            }
            try {
                drainEncoders();
                feedPendingAudio();
            } catch (Exception e) {
                FileLog.e(e);
                fail();
            }
            return;
        }
        recycleAudioBatch(batch);
    }

    private void handleAudioCaptureFinished(AudioCaptureSession session) {
        if (session.generation != audioCaptureGeneration) {
            return;
        }
        audioCaptureRunning = false;
        int cleanup = deferredAudioCleanup;
        if (cleanup != 0) {
            deferredAudioCleanup = 0;
            if (cleanup == 1) {
                finalizeCancel();
            } else {
                finalizeFailure();
            }
            return;
        }
        if (state == STATE_PAUSING) {
            finishPause();
        } else if (state == STATE_FINISHING && waitingAudioTail) {
            waitingAudioTail = false;
            finalizeStop();
        } else if (state == STATE_RECORDING) {
            FileLog.e("RoundVideoEncoder audio capture ended unexpectedly");
            fail();
        }
    }

    private void alignAudioSegment() {
        while (!pendingAudio.isEmpty()) {
            AudioChunkBatch batch = pendingAudio.get(0);
            while (batch.drained < batch.results) {
                ByteBuffer buffer = batch.buffer[batch.drained];
                int frames = buffer.remaining() / 2;
                if (frames > 0) {
                    long startNs = batch.startTimeNs[batch.drained];
                    long endNs = startNs + frames * 1000000000L / SAMPLE_RATE;
                    if (endNs > segmentVideoOriginNs) {
                        if (startNs < segmentVideoOriginNs) {
                            int skip = (int) ((segmentVideoOriginNs - startNs) * SAMPLE_RATE / 1000000000L);
                            if (skip >= frames) {
                                batch.drained++;
                                continue;
                            }
                            buffer.position(buffer.position() + skip * 2);
                            startNs += skip * 1000000000L / SAMPLE_RATE;
                        }
                        audioSegmentBaseUs = Math.max((segmentActiveBaseNs + (startNs - segmentVideoOriginNs)) / 1000, audioTotalEndUs);
                        audioSegmentFramesSubmitted = 0;
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("RoundVideoEncoder audio segment base " + audioSegmentBaseUs + "us");
                        }
                        return;
                    }
                }
                batch.drained++;
            }
            pendingAudio.remove(0);
            recycleAudioBatch(batch);
        }
    }

    private void feedPendingAudio() {
        if (audioEncoder == null || audioEosQueued || segmentVideoOriginNs == -1) {
            return;
        }
        if (audioSegmentBaseUs == -1) {
            alignAudioSegment();
            if (audioSegmentBaseUs == -1) {
                return;
            }
        }
        while (hasFeedablePendingAudio()) {
            long framesLeft = (audioCapUs - audioSegmentBaseUs) * SAMPLE_RATE / 1000000L - audioSegmentFramesSubmitted;
            if (framesLeft <= 0) {
                recyclePendingAudio();
                return;
            }
            int index;
            ByteBuffer input;
            try {
                index = audioEncoder.dequeueInputBuffer(0);
                if (index < 0) {
                    return;
                }
                input = audioEncoder.getInputBuffer(index);
            } catch (Exception e) {
                FileLog.e(e);
                failIfActive();
                return;
            }
            if (input == null) {
                FileLog.e("RoundVideoEncoder audio input buffer was null");
                failIfActive();
                return;
            }
            input.clear();
            long ptsUs = audioSegmentBaseUs + audioSegmentFramesSubmitted * 1000000L / SAMPLE_RATE;
            boolean capped = false;
            int written = 0;
            while (!pendingAudio.isEmpty() && !capped) {
                AudioChunkBatch batch = pendingAudio.get(0);
                boolean proceed;
                while (true) {
                    if (batch.drained >= batch.results) {
                        proceed = true;
                        break;
                    }
                    ByteBuffer buffer = batch.buffer[batch.drained];
                    int remaining = buffer.remaining();
                    if (remaining > 0) {
                        if (remaining / 2 > framesLeft) {
                            buffer.limit(buffer.position() + (int) framesLeft * 2);
                            remaining = buffer.remaining();
                            capped = true;
                            if (remaining <= 0) {
                                proceed = true;
                                break;
                            }
                        }
                        if (input.remaining() < remaining) {
                            proceed = false;
                            break;
                        }
                        input.put(buffer);
                        long frames = remaining / 2;
                        audioSegmentFramesSubmitted += frames;
                        framesLeft -= frames;
                        written += remaining;
                        if (capped) {
                            proceed = true;
                            break;
                        }
                    }
                    batch.drained++;
                }
                if (!proceed) {
                    break;
                }
                if (batch.drained >= batch.results) {
                    pendingAudio.remove(0);
                    recycleAudioBatch(batch);
                }
                if (capped) {
                    recyclePendingAudio();
                }
            }
            audioTotalEndUs = audioSegmentBaseUs + audioSegmentFramesSubmitted * 1000000L / SAMPLE_RATE;
            try {
                if (written > 0) {
                    audioEncoder.queueInputBuffer(index, 0, written, ptsUs, 0);
                    lastSubmittedAudioEndUs = audioTotalEndUs;
                } else {
                    audioEncoder.queueInputBuffer(index, 0, 0, ptsUs, 0);
                    return;
                }
            } catch (Exception e) {
                FileLog.e(e);
                failIfActive();
                return;
            }
        }
    }

    private void failIfActive() {
        if (state == STATE_STARTING || state == STATE_RECORDING || state == STATE_PAUSING || state == STATE_PAUSED) {
            fail();
        }
    }

    private boolean hasFeedablePendingAudio() {
        while (!pendingAudio.isEmpty()) {
            AudioChunkBatch batch = pendingAudio.get(0);
            for (int i = batch.drained; i < batch.results; i++) {
                if (batch.buffer[i].remaining() > 0) {
                    return true;
                }
            }
            pendingAudio.remove(0);
            recycleAudioBatch(batch);
        }
        return false;
    }

    private void handlePause(File previewFile) {
        if (state != STATE_RECORDING) {
            return;
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("RoundVideoEncoder pause");
        }
        state = STATE_PAUSING;
        pausePreviewFile = previewFile;
        audioCapUs = Math.min(audioCapUs, videoEndTimeUs());
        if (audioCaptureRunning) {
            stopAudioCapture(false);
        } else {
            finishPause();
        }
    }

    private void finishPause() {
        feedPendingAudio();
        try {
            long deadline = SystemClock.elapsedRealtime() + 500;
            while (SystemClock.elapsedRealtime() < deadline) {
                drainVideoOnce(10000);
                drainAudioOnce(10000);
                feedPendingAudio();
                if (!hasFeedablePendingAudio() && hasReachedPauseTargets()) {
                    break;
                }
            }
            recyclePendingAudio();
            if (mediaMuxer != null && pausePreviewFile != null) {
                try {
                    mediaMuxer.setAllowSyncFiles(allowSendingWhileRecording);
                    mediaMuxer.finishMovie(pausePreviewFile);
                    mediaMuxer.setAllowSyncFiles(false);
                } catch (Exception e) {
                    FileLog.e(e);
                    fail();
                    return;
                }
            }
            releaseEgl();
            final File previewFile = pausePreviewFile;
            pausePreviewFile = null;
            state = STATE_PAUSED;
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("RoundVideoEncoder paused, video end " + videoEndTimeUs() + "us audio end " + audioTotalEndUs + "us");
            }
            if (deferredFinish != 0) {
                boolean cancel = deferredFinish == 2;
                deferredFinish = 0;
                pendingResumeContext = null;
                handleFinish(cancel);
                return;
            }
            EGLContext resumeContext = pendingResumeContext;
            if (resumeContext != null) {
                pendingResumeContext = null;
                handleResume(resumeContext);
            } else {
                AndroidUtilities.runOnUIThread(() -> callback.onPaused(previewFile));
            }
        } catch (Exception e) {
            FileLog.e(e);
            fail();
        }
    }

    private void handleFinish(boolean cancel) {
        if (state == STATE_FINISHED || state == STATE_FAILED || state == STATE_FINISHING) {
            return;
        }
        if (state == STATE_PAUSING) {
            deferredFinish = cancel ? 2 : 1;
            return;
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("RoundVideoEncoder finish cancel=" + cancel + " state=" + state);
        }
        if (state == STATE_IDLE) {
            state = STATE_FINISHED;
            scheduleQueueRecycle();
            final FinishReason reason = cancel ? FinishReason.CANCELLED : FinishReason.COMPLETED;
            AndroidUtilities.runOnUIThread(() -> callback.onFinished(reason));
            return;
        }
        state = STATE_FINISHING;
        if (cancel) {
            if (stopAudioCapture(true)) {
                finalizeCancel();
            } else {
                deferredAudioCleanup = 1;
            }
            return;
        }
        audioCapUs = Math.min(audioCapUs, videoEndTimeUs());
        if (videoEncoder != null && !videoEosSignalled) {
            try {
                videoEncoder.signalEndOfInputStream();
                videoEosSignalled = true;
            } catch (Exception e) {
                FileLog.e(e);
                videoEosSeen = true;
            }
        }
        if (audioCaptureRunning) {
            stopAudioCapture(false);
            waitingAudioTail = true;
        } else {
            finalizeStop();
        }
    }

    private void finalizeStop() {
        if (segmentVideoOriginNs == -1 && audioSegmentBaseUs == -1) {
            recyclePendingAudio();
        }
        boolean success = drainToEndOfStream();
        releaseEgl();
        releaseCodecs();
        if (mediaMuxer != null) {
            try {
                mediaMuxer.setAllowSyncFiles(allowSendingWhileRecording);
                mediaMuxer.finishMovie();
            } catch (Exception e) {
                FileLog.e(e);
                success = false;
            }
            FileLog.d("RoundVideoEncoder finished muxer, video end " + videoEndTimeUs() + "us audio end " + audioTotalEndUs + "us");
            if (writingToDifferentFile) {
                if (videoFile.exists()) {
                    try {
                        videoFile.delete();
                    } catch (Exception e) {
                        FileLog.e("RoundVideoEncoder copying fileToWrite to videoFile, deleting videoFile error " + videoFile);
                        FileLog.e(e);
                    }
                }
                if (!fileToWrite.renameTo(videoFile)) {
                    FileLog.e("RoundVideoEncoder unable to rename file, try move file");
                    try {
                        if (AndroidUtilities.copyFile(fileToWrite, videoFile)) {
                            fileToWrite.delete();
                        } else {
                            FileLog.e("RoundVideoEncoder unable to copy file");
                            success = false;
                        }
                    } catch (IOException e) {
                        FileLog.e(e);
                        FileLog.e("RoundVideoEncoder unable to move file");
                    }
                }
            }
        }
        if (!success) {
            deleteQuietly(fileToWrite);
            if (videoFile != null && !videoFile.equals(fileToWrite)) {
                deleteQuietly(videoFile);
            }
        }
        releaseInputSurface();
        recyclePendingAudio();
        state = success ? STATE_FINISHED : STATE_FAILED;
        scheduleQueueRecycle();
        final FinishReason reason = success ? FinishReason.COMPLETED : FinishReason.FAILED;
        AndroidUtilities.runOnUIThread(() -> callback.onFinished(reason));
    }

    private void finalizeCancel() {
        releaseEgl();
        releaseCodecs();
        releaseInputSurface();
        recyclePendingAudio();
        if (mediaMuxer != null) {
            try {
                mediaMuxer.finishMovie();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        deleteQuietly(fileToWrite);
        deleteQuietly(videoFile);
        state = STATE_FINISHED;
        scheduleQueueRecycle();
        AndroidUtilities.runOnUIThread(() -> callback.onFinished(FinishReason.CANCELLED));
    }

    private void fail() {
        if (state == STATE_FINISHED || state == STATE_FAILED || deferredAudioCleanup != 0) {
            return;
        }
        FileLog.e("RoundVideoEncoder failed in state " + state);
        state = STATE_FINISHING;
        if (stopAudioCapture(true)) {
            finalizeFailure();
        } else {
            deferredAudioCleanup = 2;
        }
    }

    private void finalizeFailure() {
        releaseEgl();
        releaseCodecs();
        releaseInputSurface();
        recyclePendingAudio();
        if (mediaMuxer != null) {
            try {
                mediaMuxer.finishMovie();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        deleteQuietly(fileToWrite);
        deleteQuietly(videoFile);
        state = STATE_FAILED;
        scheduleQueueRecycle();
        AndroidUtilities.runOnUIThread(() -> callback.onFinished(FinishReason.FAILED));
    }

    private static void deleteQuietly(File file) {
        if (file != null) {
            try {
                file.delete();
            } catch (Throwable ignore) {
            }
        }
    }

    private boolean hasReachedPauseTargets() {
        boolean videoDone = lastSubmittedVideoPtsUs < 0 || lastMuxedVideoPtsUs >= lastSubmittedVideoPtsUs;
        long audioTarget = lastSubmittedAudioEndUs < 0 ? -1 : Math.max(0, lastSubmittedAudioEndUs - 21333);
        return videoDone && (audioTarget < 0 || lastMuxedAudioPtsUs >= audioTarget);
    }

    private void scheduleQueueRecycle() {
        final DispatchQueue queue = encoderQueue;
        queue.postRunnable(queue::recycle);
    }

    private long videoFallbackFrameDurationNs() {
        if (minVideoFrameDeltaNs > 0) {
            return Math.max(minVideoFrameDeltaNs, 1000000L);
        }
        return 1000000000L / frameRate;
    }

    private long videoEndTimeUs() {
        if (lastVideoFrameIndex < 0) {
            return 0;
        }
        return (lastVideoActiveTimeNs + videoFallbackFrameDurationNs()) / 1000;
    }

    private boolean drainToEndOfStream() {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        if (audioEncoder == null) {
            audioEosSeen = true;
        }
        if (videoEncoder == null || !videoEosSignalled) {
            videoEosSeen = true;
        }
        while (!(videoEosSeen && audioEosSeen) && SystemClock.elapsedRealtime() < deadline) {
            if (!audioEosQueued && !audioEosSeen) {
                feedPendingAudio();
                if (!hasFeedablePendingAudio()) {
                    queueAudioEndOfStream();
                }
            }
            try {
                if (!videoEosSeen) {
                    drainVideoOnce(10000);
                }
                if (!audioEosSeen) {
                    drainAudioOnce(10000);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        if (!videoEosSeen || !audioEosSeen) {
            FileLog.e("RoundVideoEncoder end of stream drain timed out, video=" + videoEosSeen + " audio=" + audioEosSeen);
        }
        return videoEosSeen && audioEosSeen;
    }

    private void queueAudioEndOfStream() {
        try {
            int index = audioEncoder.dequeueInputBuffer(10000);
            if (index >= 0) {
                audioEncoder.queueInputBuffer(index, 0, 0, audioTotalEndUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                audioEosQueued = true;
            }
        } catch (Exception e) {
            FileLog.e(e);
            audioEosSeen = true;
        }
    }

    private void drainEncoders() throws Exception {
        if (videoEncoder != null) {
            while (drainVideoOnce(0)) {
            }
        }
        if (audioEncoder != null) {
            while (drainAudioOnce(0)) {
            }
        }
    }

    private boolean drainVideoOnce(long timeoutUs) throws Exception {
        if (videoEncoder == null) {
            return false;
        }
        int status = videoEncoder.dequeueOutputBuffer(videoBufferInfo, timeoutUs);
        if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
            return false;
        }
        if (status == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
            return true;
        }
        if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            MediaFormat newFormat = videoEncoder.getOutputFormat();
            if (videoTrackIndex == -5 && mediaMuxer != null) {
                videoTrackIndex = mediaMuxer.addTrack(newFormat, false);
                if (newFormat.containsKey("prepend-sps-pps-to-idr-frames") && newFormat.getInteger("prepend-sps-pps-to-idr-frames") == 1) {
                    ByteBuffer spsBuff = newFormat.getByteBuffer("csd-0");
                    ByteBuffer ppsBuff = newFormat.getByteBuffer("csd-1");
                    prependHeaderSize = (spsBuff == null ? 0 : spsBuff.limit()) + (ppsBuff == null ? 0 : ppsBuff.limit());
                }
            }
            return true;
        }
        if (status < 0) {
            return false;
        }
        try {
            ByteBuffer encodedData = videoEncoder.getOutputBuffer(status);
            if (encodedData == null) {
                throw new RuntimeException("videoEncoderOutputBuffer " + status + " was null");
            }
            if (videoBufferInfo.size > 1) {
                if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    if (prependHeaderSize != 0 && (videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                        videoBufferInfo.offset += prependHeaderSize;
                        videoBufferInfo.size -= prependHeaderSize;
                    }
                    if (firstEncode && (videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                        MediaCodecVideoConvertor.cutOfNalData(MediaController.VIDEO_MIME_TYPE, encodedData, videoBufferInfo);
                        firstEncode = false;
                    }
                    if (mediaMuxer != null && videoTrackIndex >= 0) {
                        long availableSize = mediaMuxer.writeSampleData(videoTrackIndex, encodedData, videoBufferInfo, true);
                        lastMuxedVideoPtsUs = Math.max(lastMuxedVideoPtsUs, videoBufferInfo.presentationTimeUs);
                        if (availableSize != 0 && !writingToDifferentFile && allowSendingWhileRecording) {
                            callback.onWriteData(availableSize);
                        }
                    }
                } else if (videoTrackIndex == -5 && mediaMuxer != null) {
                    byte[] csd = new byte[videoBufferInfo.size];
                    encodedData.limit(videoBufferInfo.offset + videoBufferInfo.size);
                    encodedData.position(videoBufferInfo.offset);
                    encodedData.get(csd);
                    ByteBuffer sps = null;
                    ByteBuffer pps = null;
                    for (int a = videoBufferInfo.size - 1; a >= 0 && a > 3; a--) {
                        if (csd[a] == 1 && csd[a - 1] == 0 && csd[a - 2] == 0 && csd[a - 3] == 0) {
                            sps = ByteBuffer.allocate(a - 3);
                            pps = ByteBuffer.allocate(videoBufferInfo.size - (a - 3));
                            sps.put(csd, 0, a - 3).position(0);
                            pps.put(csd, a - 3, videoBufferInfo.size - (a - 3)).position(0);
                            break;
                        }
                    }
                    MediaFormat newFormat = MediaFormat.createVideoFormat(MediaController.VIDEO_MIME_TYPE, videoWidth, videoHeight);
                    if (sps != null) {
                        newFormat.setByteBuffer("csd-0", sps);
                        newFormat.setByteBuffer("csd-1", pps);
                    }
                    videoTrackIndex = mediaMuxer.addTrack(newFormat, false);
                }
            }
            if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                videoEosSeen = true;
                videoEncoder.releaseOutputBuffer(status, false);
                return false;
            }
            videoEncoder.releaseOutputBuffer(status, false);
            return true;
        } catch (Throwable e) {
            videoEncoder.releaseOutputBuffer(status, false);
            throw e;
        }
    }

    private boolean drainAudioOnce(long timeoutUs) throws Exception {
        if (audioEncoder == null) {
            return false;
        }
        int status = audioEncoder.dequeueOutputBuffer(audioBufferInfo, timeoutUs);
        if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
            return false;
        }
        if (status == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
            return true;
        }
        if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            MediaFormat newFormat = audioEncoder.getOutputFormat();
            if (audioTrackIndex == -5 && mediaMuxer != null) {
                audioTrackIndex = mediaMuxer.addTrack(newFormat, true);
            }
            return true;
        }
        if (status < 0) {
            return false;
        }
        try {
            ByteBuffer encodedData = audioEncoder.getOutputBuffer(status);
            if (encodedData == null) {
                throw new RuntimeException("audioEncoderOutputBuffer " + status + " was null");
            }
            if ((audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                audioBufferInfo.size = 0;
            }
            if (audioBufferInfo.size != 0 && mediaMuxer != null && audioTrackIndex >= 0) {
                long availableSize = mediaMuxer.writeSampleData(audioTrackIndex, encodedData, audioBufferInfo, false);
                lastMuxedAudioPtsUs = Math.max(lastMuxedAudioPtsUs, audioBufferInfo.presentationTimeUs);
                if (availableSize != 0 && !writingToDifferentFile && allowSendingWhileRecording) {
                    callback.onWriteData(availableSize);
                }
            }
            if ((audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                audioEosSeen = true;
                audioEncoder.releaseOutputBuffer(status, false);
                return false;
            }
            audioEncoder.releaseOutputBuffer(status, false);
            return true;
        } catch (Throwable e) {
            audioEncoder.releaseOutputBuffer(status, false);
            throw e;
        }
    }

    private void createEncoderEgl(EGLContext sharedContext) {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("EGL already set up");
        }
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("unable to get EGL14 display");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            throw new RuntimeException("unable to initialize EGL14");
        }
        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142, 1, // EGL_RECORDABLE_ANDROID
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0) || configs[0] == null) {
            throw new RuntimeException("Unable to find a suitable EGLConfig");
        }
        eglConfig = configs[0];
        int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, sharedContext, contextAttribs, 0);
        if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) {
            eglContext = EGL14.EGL_NO_CONTEXT;
            throw new RuntimeException("eglCreateContext failed " + GLUtils.getEGLErrorString(EGL14.eglGetError()));
        }
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, inputSurface, new int[]{EGL14.EGL_NONE}, 0);
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            eglSurface = EGL14.EGL_NO_SURFACE;
            throw new RuntimeException("eglCreateWindowSurface failed " + GLUtils.getEGLErrorString(EGL14.eglGetError()));
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new RuntimeException("eglMakeCurrent failed " + GLUtils.getEGLErrorString(EGL14.eglGetError()));
        }
        try {
            renderer.onEncoderSurfaceCreated(videoWidth, videoHeight);
        } catch (Throwable e) {
            throw new RuntimeException("encoder renderer initialization failed", e);
        }
    }

    private boolean makeEglCurrent() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) {
            return false;
        }
        if (eglContext.equals(EGL14.eglGetCurrentContext()) && eglSurface.equals(EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW))) {
            return true;
        }
        if (EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            return true;
        }
        FileLog.e("RoundVideoEncoder eglMakeCurrent failed " + GLUtils.getEGLErrorString(EGL14.eglGetError()));
        return false;
    }

    private void releaseEgl() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            return;
        }
        if (makeEglCurrent()) {
            try {
                renderer.onEncoderSurfaceDestroyed();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface);
            eglSurface = EGL14.EGL_NO_SURFACE;
        }
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext);
            eglContext = EGL14.EGL_NO_CONTEXT;
        }
        EGL14.eglReleaseThread();
        EGL14.eglTerminate(eglDisplay);
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglConfig = null;
    }

    private void releaseInputSurface() {
        if (inputSurface != null) {
            try {
                inputSurface.release();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            inputSurface = null;
        }
    }

    private void releaseCodecs() {
        if (videoEncoder != null) {
            try {
                videoEncoder.stop();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            try {
                videoEncoder.release();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            videoEncoder = null;
        }
        if (audioEncoder != null) {
            try {
                audioEncoder.stop();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            try {
                audioEncoder.release();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            audioEncoder = null;
            setBluetoothScoOn(false);
        }
    }

    private void recyclePendingAudio() {
        for (int i = 0; i < pendingAudio.size(); i++) {
            recycleAudioBatch(pendingAudio.get(i));
        }
        pendingAudio.clear();
    }

    private void prepareAudioBatches() {
        if (audioBatchesPrepared) {
            return;
        }
        for (int i = 0; i < AUDIO_BATCH_POOL_SIZE; i++) {
            audioBatchPool.add(new AudioChunkBatch());
        }
        audioBatchesPrepared = true;
    }

    private AudioChunkBatch obtainAudioBatch() throws InterruptedException {
        AudioChunkBatch batch = audioBatchPool.poll(250, TimeUnit.MILLISECONDS);
        if (batch == null) {
            return null;
        }
        batch.results = 0;
        batch.drained = 0;
        return batch;
    }

    private void recycleAudioBatch(AudioChunkBatch batch) {
        if (!audioBatchPool.offer(batch)) {
            FileLog.e("RoundVideoEncoder audio batch pool overflow");
        }
    }

    @SuppressLint("MissingPermission")
    private void startAudioCapture() {
        setBluetoothScoOn(true);
        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSize <= 0) {
            minBufferSize = 3584;
        }
        int bufferSize = 2048 * 24;
        if (bufferSize < minBufferSize) {
            bufferSize = ((minBufferSize / 2048) + 1) * 2048 * 2;
        }
        AudioRecord audioRecord = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        AudioCaptureSession session = null;
        try {
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new RuntimeException("AudioRecord init failed");
            }
            audioRecord.startRecording();
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("RoundVideoEncoder initied audio record with channels " + audioRecord.getChannelCount() + " sample rate = " + audioRecord.getSampleRate() + " bufferSize = " + bufferSize);
            }
            audioCaptureRunning = true;
            audioCaptureGeneration++;
            session = new AudioCaptureSession(audioRecord, audioCaptureGeneration);
            Thread thread = new Thread(new AudioCaptureRunnable(session), "RoundVideoAudioCapture");
            session.thread = thread;
            thread.setPriority(Thread.MAX_PRIORITY);
            synchronized (audioCaptureLock) {
                activeAudioCapture = session;
            }
            thread.start();
        } catch (RuntimeException | Error e) {
            synchronized (audioCaptureLock) {
                if (session != null && activeAudioCapture == session) {
                    activeAudioCapture = null;
                }
            }
            audioCaptureRunning = false;
            if (session != null) {
                session.releaseRecorder();
            } else {
                releaseAudioRecorder(audioRecord);
            }
            throw e;
        }
    }

    private AudioCaptureSession getActiveAudioCapture() {
        synchronized (audioCaptureLock) {
            return activeAudioCapture;
        }
    }

    private void requestAudioCaptureStop(AudioCaptureSession session) {
        if (session != null) {
            session.requestStop();
        }
    }

    /** Asks the audio thread to stop; with wait, joins it (encoder thread only). True when it is no longer running. */
    private boolean stopAudioCapture(boolean wait) {
        AudioCaptureSession session = getActiveAudioCapture();
        requestAudioCaptureStop(session);
        if (session == null) {
            return true;
        }
        Thread thread = session.thread;
        if (wait && thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                FileLog.e("RoundVideoEncoder audio thread did not stop within timeout");
            }
        }
        return thread == null || !thread.isAlive();
    }

    private void stopAudioRecorder(AudioRecord audioRecord) {
        try {
            if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_STOPPED) {
                audioRecord.stop();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void releaseAudioRecorder(AudioRecord audioRecord) {
        stopAudioRecorder(audioRecord);
        try {
            audioRecord.release();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public class AudioCaptureRunnable implements Runnable {
        private final AudioTimestamp audioTimestamp = new AudioTimestamp();
        private final AudioCaptureSession session;

        public AudioCaptureRunnable(AudioCaptureSession session) {
            this.session = session;
        }

        @Override
        public void run() {
            long framesRead = 0;
            boolean done = false;
            try {
                while (!done && !session.stopRequested.get()) {
                    AudioChunkBatch batch;
                    try {
                        batch = obtainAudioBatch();
                    } catch (InterruptedException e) {
                        if (session.stopRequested.get()) {
                            break;
                        }
                        continue;
                    }
                    if (batch == null) {
                        if (!session.stopRequested.get()) {
                            FileLog.e("RoundVideoEncoder audio batch pool stalled");
                        }
                        break;
                    }
                    try {
                        if (session.stopRequested.get()) {
                            recycleAudioBatch(batch);
                            done = true;
                            continue;
                        }
                        boolean useTimestamp;
                        try {
                            useTimestamp = session.audioRecorder.getTimestamp(audioTimestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS;
                        } catch (Exception e) {
                            useTimestamp = false;
                        }
                        for (int i = 0; i < AUDIO_CHUNKS_PER_BATCH; i++) {
                            ByteBuffer buffer = batch.buffer[i];
                            buffer.clear();
                            int read = session.audioRecorder.read(batch.data[i], 0, AUDIO_CHUNK_SIZE);
                            if (read <= 0) {
                                done = true;
                                break;
                            }
                            buffer.position(0);
                            buffer.limit(read);
                            if (i % 2 == 0) {
                                double sum = 0;
                                for (int s = 0; s < read / 2; s++) {
                                    short sample = buffer.getShort();
                                    sum += sample * sample;
                                }
                                buffer.position(0);
                                double amplitude = Math.sqrt(sum / read / 2);
                                try {
                                    callback.onAudioAmplitude(amplitude);
                                } catch (Throwable e) {
                                    FileLog.e(e);
                                }
                            }
                            long frames = read / 2;
                            long startNs;
                            if (useTimestamp) {
                                startNs = audioTimestamp.nanoTime + (framesRead - audioTimestamp.framePosition) * 1000000000L / SAMPLE_RATE;
                            } else {
                                startNs = System.nanoTime() - frames * 1000000000L / SAMPLE_RATE;
                            }
                            batch.startTimeNs[i] = startNs;
                            batch.results = i + 1;
                            framesRead += frames;
                            if (session.stopRequested.get()) {
                                done = true;
                                break;
                            }
                        }
                        boolean posted = batch.results > 0 && postBatch(batch);
                        if (!posted) {
                            recycleAudioBatch(batch);
                        }
                    } catch (Throwable e) {
                        recycleAudioBatch(batch);
                        throw e;
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                session.releaseRecorder();
                synchronized (audioCaptureLock) {
                    if (activeAudioCapture == session) {
                        activeAudioCapture = null;
                    }
                }
                Handler handler = encoderQueue.getHandler();
                if ((handler == null || !handler.post(session.completionRunnable)) && BuildVars.LOGS_ENABLED) {
                    FileLog.e("RoundVideoEncoder unable to post audio capture completion");
                }
            }
        }

        private boolean postBatch(AudioChunkBatch batch) {
            Handler handler = encoderQueue.getHandler();
            return handler != null && handler.post(batch.deliveryRunnable);
        }
    }

    private void setBluetoothScoOn(boolean scoOn) {
        try {
            AudioManager am = (AudioManager) ApplicationLoader.applicationContext.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return;
            }
            if (SharedConfig.recordViaSco && !PermissionRequest.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                SharedConfig.recordViaSco = false;
                SharedConfig.saveConfig();
            }
            if (am.isBluetoothScoAvailableOffCall() && SharedConfig.recordViaSco || !scoOn) {
                BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
                try {
                    if (btAdapter != null && btAdapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED || !scoOn) {
                        if (scoOn && !am.isBluetoothScoOn()) {
                            am.startBluetoothSco();
                        } else if (!scoOn && am.isBluetoothScoOn()) {
                            am.stopBluetoothSco();
                        }
                    }
                } catch (SecurityException ignored) {
                } catch (Throwable e) {
                    FileLog.e(e);
                    try {
                        if (!scoOn && am.isBluetoothScoOn()) {
                            am.stopBluetoothSco();
                        }
                    } catch (Exception e2) {
                        FileLog.e(e2);
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
