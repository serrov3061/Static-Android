package com.exteragram.messenger.camera;

import android.graphics.Bitmap;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.view.View;
import android.view.ViewGroup;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.VideoMessagesCamera;
import com.exteragram.messenger.debug.DebugConfig;
import com.exteragram.messenger.debug.DebugOverlayView;
import java.io.File;
import java.io.FileOutputStream;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Stories.recorder.FlashViews;
import org.telegram.ui.Stories.recorder.SliderView;
import org.telegram.utils.recorder.round.RoundVideoSession;

public abstract class RoundVideoUiHelper {
    private static Bitmap backCameraThumb;
    private static Bitmap frontCameraThumb;

    public static void applySessionSettings(RoundVideoSession.Builder builder, boolean z) {
        VideoMessagesCamera videoMessagesCamera = ExteraConfig.getVideoMessagesCamera();
        if (videoMessagesCamera != VideoMessagesCamera.ASK) {
            z = videoMessagesCamera == VideoMessagesCamera.FRONT;
        }
        builder.setInitialFacing(z ? RoundVideoSession.CameraFacing.FRONT : RoundVideoSession.CameraFacing.BACK);
        if (ExteraConfig.getExtendedFramesPerSecond()) {
            builder.setFrameRate(RoundVideoSession.FrameRate.FPS_60);
        }
    }

    public static void onActiveFacingChanged(RoundVideoSession.CameraFacing cameraFacing) {
        if (!ExteraConfig.getRememberLastUsedCamera() || ExteraConfig.getVideoMessagesCamera() == VideoMessagesCamera.ASK) {
            return;
        }
        ExteraConfig.setVideoMessagesCamera(cameraFacing == RoundVideoSession.CameraFacing.FRONT ? VideoMessagesCamera.FRONT : VideoMessagesCamera.REAR);
    }

    public static Bitmap getCameraThumb(RoundVideoSession.CameraFacing cameraFacing) {
        boolean z = cameraFacing == RoundVideoSession.CameraFacing.FRONT;
        Bitmap bitmapDecodeFile = z ? frontCameraThumb : backCameraThumb;
        if (bitmapDecodeFile != null) {
            return bitmapDecodeFile;
        }
        try {
            bitmapDecodeFile = BitmapFactory.decodeFile(getCameraThumbFile(cameraFacing).getAbsolutePath());
        } catch (Throwable unused) {
        }
        if (z) {
            frontCameraThumb = bitmapDecodeFile;
        } else {
            backCameraThumb = bitmapDecodeFile;
        }
        return bitmapDecodeFile;
    }

    public static void storeCameraThumb(RoundVideoSession.CameraFacing cameraFacing, final Bitmap bitmap) {
        if (cameraFacing == RoundVideoSession.CameraFacing.FRONT) {
            frontCameraThumb = bitmap;
        } else {
            backCameraThumb = bitmap;
        }
        final File cameraThumbFile = getCameraThumbFile(cameraFacing);
        Utilities.globalQueue.postRunnable(new Runnable() { // from class: com.exteragram.messenger.camera.RoundVideoUiHelper$$ExternalSyntheticLambda2
            @Override // java.lang.Runnable
            public final void run() {
                RoundVideoUiHelper.m1205$r8$lambda$gLjtP16RJ0x9cVMMVT7R5ZfXk(cameraThumbFile, bitmap);
            }
        });
    }

    /* JADX INFO: renamed from: $r8$lambda$gLjt-P16RJ0x9cVMM-VT7R5ZfXk, reason: not valid java name */
    public static /* synthetic */ void m1205$r8$lambda$gLjtP16RJ0x9cVMMVT7R5ZfXk(File file, Bitmap bitmap) {
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(file);
            try {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 87, fileOutputStream);
                fileOutputStream.close();
            } catch (Throwable th) {
                try {
                    fileOutputStream.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        } catch (Throwable unused) {
        }
    }

    private static File getCameraThumbFile(RoundVideoSession.CameraFacing cameraFacing) {
        return new File(ApplicationLoader.getFilesDirFixed(), cameraFacing == RoundVideoSession.CameraFacing.FRONT ? "icthumb_front.jpg" : "icthumb_back.jpg");
    }

    public static void applyFlashSettings(FlashViews flashViews) {
        flashViews.setWarmth(ExteraConfig.getFlashWarmth());
        flashViews.setIntensity(ExteraConfig.getFlashIntensity());
    }

    public static ItemOptions showFlashOptions(ViewGroup viewGroup, Theme.ResourcesProvider resourcesProvider, View view, final FlashViews flashViews, Runnable runnable) {
        return ItemOptions.makeOptions(viewGroup, resourcesProvider, view).addView(new SliderView(viewGroup.getContext(), 1).setValue(ExteraConfig.getFlashWarmth()).setOnValueChange(new Utilities.Callback() { // from class: com.exteragram.messenger.camera.RoundVideoUiHelper$$ExternalSyntheticLambda0
            @Override // org.telegram.messenger.Utilities.Callback
            public final void run(Object obj) {
                RoundVideoUiHelper.m1204$r8$lambda$HTcZLs1ziyymvk98rr3jkv3eS4(flashViews, (Float) obj);
            }
        })).addSpaceGap().addView(new SliderView(viewGroup.getContext(), 2).setMinMax(0.5f, 1.0f).setValue(ExteraConfig.getFlashIntensity()).setOnValueChange(new Utilities.Callback() { // from class: com.exteragram.messenger.camera.RoundVideoUiHelper$$ExternalSyntheticLambda1
            @Override // org.telegram.messenger.Utilities.Callback
            public final void run(Object obj) {
                RoundVideoUiHelper.$r8$lambda$l_o1vPRrcg_YKf9zKdsoeSTz5m4(flashViews, (Float) obj);
            }
        })).setOnDismiss(runnable).setDimAlpha(50).setGravity(5).translate(AndroidUtilities.dp(46.0f), AndroidUtilities.dp(4.0f)).setBackgroundColor(-1155851493).show();
    }

    /* JADX INFO: renamed from: $r8$lambda$HTcZ-Ls1ziyymvk98rr3jkv3eS4, reason: not valid java name */
    public static /* synthetic */ void m1204$r8$lambda$HTcZLs1ziyymvk98rr3jkv3eS4(FlashViews flashViews, Float f) {
        ExteraConfig.setFlashWarmth(f.floatValue());
        flashViews.setWarmth(f.floatValue());
    }

    public static /* synthetic */ void $r8$lambda$l_o1vPRrcg_YKf9zKdsoeSTz5m4(FlashViews flashViews, Float f) {
        ExteraConfig.setFlashIntensity(f.floatValue());
        flashViews.setIntensity(f.floatValue());
    }

    /** exteraGram 13.1.1: camera metrics overlay (DebugConfig.getDebugCameraMetrics). */
    public static void addDebugOverlay(ViewGroup parent, DebugOverlayView.DataSource dataSource) {
        if (!DebugConfig.getDebugCameraMetrics()) {
            return;
        }
        DebugOverlayView overlay = new DebugOverlayView(parent.getContext());
        overlay.setDataSource(dataSource);
        parent.addView(overlay, DebugOverlayView.createLayoutParams());
    }

    public static void populateDebugOverlay(DebugOverlayView.ContentBuilder builder, Context context, RoundVideoSession session,
                                            RoundVideoSession.StateInfo stateInfo, RoundVideoSession.CameraCapabilities capabilities,
                                            InstantCameraZoomSlider zoomSlider) {
        builder.title("InstantCamera2");
        if (session == null) {
            builder.line("session=null");
            return;
        }
        builder.kv("output", session.getOutputResolution().getSize() + "p " + (session.getVideoBitrate() / 1000) + "kbps");
        if (stateInfo != null) {
            builder.kv("state", stateInfo.getState())
                    .kv("recordedMs", stateInfo.getRecordedDurationMs() + "/" + stateInfo.getMaximumDurationMs())
                    .kv("flash", stateInfo.isFlashEnabled())
                    .kv("switching", stateInfo.isCameraSwitching());
        }
        builder.section("Camera2");
        if (capabilities == null) {
            builder.line("capabilities=null");
            return;
        }
        RoundVideoSession.FrameRate activeFrameRate = capabilities.getActiveFrameRate();
        builder.kv("id", capabilities.getActiveCameraId())
                .kv("facing", capabilities.getRequestedFacing() + "->" + capabilities.getActiveFacing())
                .kv("mode", capabilities.getRequestedResolution() + "->" + capabilities.getActiveResolution())
                .kv("fps", capabilities.getRequestedFrameRate().getValue() + "->" + (activeFrameRate == null ? "null" : String.valueOf(activeFrameRate.getValue())))
                .kv("flashType", capabilities.getFlashType())
                .kv("stabilization", ExteraConfig.getCameraStabilization());
        if (zoomSlider != null) {
            builder.line("zoom=" + zoomSlider.getZoom() + " [" + zoomSlider.getMinimumZoom() + ".." + zoomSlider.getMaximumZoom() + "]")
                    .line("zoom.stops=" + CameraDebugUtils.formatZoomStops(zoomSlider.getToggleStops()))
                    .line("zoom.lenses=" + CameraDebugUtils.formatZoomStops(zoomSlider.getOpticalZoomRatios()));
        }
        builder.line("c2.fpsRanges=" + CameraDebugUtils.getCamera2SupportedFpsRanges(context, capabilities.getActiveCameraId()))
                .line("ids=" + CameraDebugUtils.getCamera2CameraList(context));
    }
}
