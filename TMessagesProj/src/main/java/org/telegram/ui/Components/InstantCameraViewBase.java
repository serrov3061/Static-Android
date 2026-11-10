package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AppGlobalConfig;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.UserConfig;
import org.telegram.utils.settings.SharedSettings;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;

/** Common UI contract implemented by the legacy and Camera2 round-video views. */
public abstract class InstantCameraViewBase extends FrameLayout {

    /** Receives camera visibility changes used by ChatActivity animations. */
    public interface AnimationCallback {
        void onAnimation(boolean open, boolean fromPaused);
    }

    /** Receives normalized trim changes initiated by the camera ring. */
    public interface TrimCallback {
        void onTrimChanged(float start, float end);
    }

    /** Receives recording UI ticks synchronized with camera preview frames. */
    public interface RecordingUiFrameCallback {
        void onActiveChanged(boolean active);
        void onFrame(long durationMs);
    }

    private AnimationCallback animationCallback;
    private TrimCallback trimCallback;
    private RecordingUiFrameCallback recordingUiFrameCallback;
    private boolean recordingUiFrameClockActive;

    protected InstantCameraViewBase(Context context) {
        super(context);
    }

    /** Creates the currently selected round-video implementation. */
    public static InstantCameraViewBase create(
            Context context,
            InstantCameraView.Delegate delegate,
            Theme.ResourcesProvider resourcesProvider,
            boolean isNewDesign
    ) {
        // StaticGram/exteraGram: the exteraGram camera (CameraX, zoom slider, camera choice) stays the default;
        // the new 13.1.1 recorder is used only when enabled explicitly in the experimental settings.
        // exteraGram 13.1.1: the 13.1.1 recorder is the default (with exteraGram camera options);
        // the old camera is used when the experimental switch is turned off or Camera 1 is selected.
        final boolean useCamera2Impl = (!SharedSettings.roundVideoCamera2Enabled.isSet() || SharedSettings.roundVideoCamera2Enabled.get())
                && com.exteragram.messenger.ExteraConfig.getCameraType() != com.exteragram.messenger.CameraType.CAMERA_1;

        return useCamera2Impl
                ? new InstantCameraView2(context, delegate, resourcesProvider, isNewDesign)
                : new InstantCameraView(context, delegate, resourcesProvider, isNewDesign);
    }

    /** exteraGram 13.1.1: front/rear choice for the next recording. */
    public void setFrontface(boolean frontface) {
    }

    /** exteraGram 13.1.1: whether the camera can take zoom/focus commands. */
    public boolean isCameraReady() {
        return false;
    }

    /** exteraGram 13.1.1: the zoom slider of the 13.1.1 recorder, null for the old camera. */
    public com.exteragram.messenger.camera.InstantCameraZoomSlider getZoomSlider() {
        return null;
    }

    /** Returns whether the Camera2 implementation is used: the local choice, else the server flag. */
    public static boolean isCamera2Enabled(int account) {
        return SharedSettings.roundVideoCamera2Enabled.isSet() ?
            SharedSettings.roundVideoCamera2Enabled.get() :
            !AppGlobalConfig.getInstance(account).roundVideoRecorderV2Disabled.get();
    }

    /** Sets the host animation callback. */
    public final void setAnimationCallback(AnimationCallback animationCallback) {
        this.animationCallback = animationCallback;
    }

    /** Sets the callback used to keep the host timeline synchronized. */
    public final void setTrimCallback(TrimCallback trimCallback) {
        this.trimCallback = trimCallback;
    }

    /** Sets the callback used to synchronize surrounding recording UI. */
    public final void setRecordingUiFrameCallback(RecordingUiFrameCallback callback) {
        recordingUiFrameCallback = callback;
        if (callback != null) callback.onActiveChanged(recordingUiFrameClockActive);
    }

    /** Dispatches a visibility change without owning the host animation. */
    protected final void dispatchAnimationState(boolean open, boolean fromPaused) {
        if (animationCallback != null) animationCallback.onAnimation(open, fromPaused);
    }

    /** Dispatches a normalized trim range without exposing recorder internals. */
    protected final void dispatchTrimRange(float start, float end) {
        if (trimCallback != null) trimCallback.onTrimChanged(start, end);
    }

    /** Enables or disables preview-frame-driven recording UI updates. */
    protected final void dispatchRecordingUiFrameClockActive(boolean active) {
        if (recordingUiFrameClockActive == active) return;
        recordingUiFrameClockActive = active;
        if (recordingUiFrameCallback != null) {
            recordingUiFrameCallback.onActiveChanged(active);
        }
    }

    /** Dispatches the current recording duration on a preview UI frame. */
    protected final void dispatchRecordingUiFrame(long durationMs) {
        if (recordingUiFrameCallback != null) recordingUiFrameCallback.onFrame(durationMs);
    }

    /** Sets the background used by camera controls. */
    public abstract void setButtonsBackground(
            BlurredBackgroundDrawableViewFactory factory,
            BlurredBackgroundColorProvider colorProvider
    );

    /** Applies the bottom inset used by the host layout. */
    public abstract void setInternalPadding(int padding);

    /** Releases camera, playback and recording resources. */
    public abstract void destroy(boolean async);

    /** Switches between recording and recorded-video preview. */
    public abstract void togglePause();

    /** Returns whether recording is paused or changing pause state. */
    public abstract boolean isPaused();

    /** Opens the camera UI and starts the selected implementation. */
    public abstract void showCamera(boolean fromPaused);

    /** Animates the camera UI visibility. */
    public abstract void startAnimation(boolean open, boolean fromPaused);

    /** Returns the camera bounds in screen coordinates. */
    public abstract RectF getCameraRect();

    /** Applies an external preview playback command. */
    public abstract void changeVideoPreviewState(int state, float progress);

    /** Handles Telegram's round-video send command. */
    public abstract void send(
            int state,
            boolean notify,
            int scheduleDate,
            int scheduleRepeatPeriod,
            int ttl,
            long effectId,
            long stars
    );

    /** Cancels the active recording. */
    public abstract void cancel(boolean byGesture);

    /** Returns the camera controls container. */
    public abstract View getButtonsLayout();

    /** Returns the preview mute indicator. */
    public abstract View getMuteImageView();

    /** Returns the paint used by the send transition. */
    public abstract Paint getPaint();

    /** Hides the camera UI and releases its active resources. */
    public abstract void hideCamera(boolean async);

    /** Returns the texture used by preview or recorded-video playback. */
    public abstract TextureView getTextureView();

    /** Returns the container used by the send transition. */
    public abstract InstantViewCameraContainer getCameraContainer();

    /** Updates whether the camera is participating in a message transition. */
    public abstract void setIsMessageTransition(boolean messageTransition);

    /** Clears implementation-specific output file state. */
    public abstract void resetCameraFile();

    /** Applies the host pan translation. */
    public abstract void onPanTranslationUpdate(float translationY);

    /** Common camera container required by the send-to-message transition. */
    public abstract static class InstantViewCameraContainer extends FrameLayout {
        /** Creates a transition container. */
        public InstantViewCameraContainer(Context context) {
            super(context);
        }

        /** Sets the message image drawn during the send transition. */
        public abstract void setImageReceiver(ImageReceiver imageReceiver);
    }
}
