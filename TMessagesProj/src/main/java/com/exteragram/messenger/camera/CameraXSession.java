package com.exteragram.messenger.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Looper;
import android.util.Range;
import android.util.Size;
import android.view.Display;
import android.view.Surface;

import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraState;
import androidx.camera.core.ConcurrentCamera;
import androidx.camera.core.DisplayOrientedMeteringPointFactory;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.MirrorMode;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ZoomState;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import com.exteragram.messenger.ExteraConfig;
import com.google.common.util.concurrent.ListenableFuture;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.Stories.recorder.DualCameraView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * exteraGram: CameraX session for round video messages.
 * <p>
 * Ported from exteraGram (written for CameraX 1.6.2) to CameraX 1.4.2 (the last line with minSdk 21):
 * <ul>
 *     <li>1.4.2 has no {@code SessionConfig}: the frame rate is requested with
 *     {@link Preview.Builder#setTargetFrameRate(Range)} and validated against
 *     {@link CameraInfo#getSupportedFrameRateRanges()}; an unsupported combination is detected by the
 *     exception from {@code bindToLifecycle} and retried with weaker settings;</li>
 *     <li>{@code isSessionConfigSupported} is replaced by try/catch around the bind.</li>
 * </ul>
 * Any init failure (no CameraX classes, provider future failed, no camera, bind failed) is reported
 * through the error callback of {@link #initCamera}; InstantCameraView then falls back to the stock
 * Telegram camera (CameraController / Camera2Session). After the first failure CameraX is not tried
 * again until the process restarts.
 */
@SuppressLint({"UnsafeOptInUsageError", "RestrictedApi"})
public class CameraXSession {

    private static volatile Boolean seamlessSwitchingAvailableCache;
    private static volatile Boolean classesAvailableCache;
    private static volatile boolean failedInThisProcess;

    private static final Map<CameraSelector, Boolean> STABILIZATION_SUPPORT_CACHE = new ConcurrentHashMap<>();
    private static final Range<Integer> FPS_60_RANGE = new Range<>(60, 60);
    private static final Range<Integer> FPS_30_RANGE = new Range<>(30, 30);
    private static final Size FALLBACK_SENSOR_ASPECT = new Size(4, 3);

    Camera camera;
    Camera cameraBack;
    Camera cameraFront;
    ProcessCameraProvider provider;

    private CameraControl cameraControl;
    private CameraControl cameraControlBack;
    private CameraControl cameraControlFront;
    private CameraSelector cameraSelector;
    private boolean isFrontface;
    private final CameraLifecycle lifecycle;
    private Preview previewUseCase;
    private Preview previewUseCaseBack;
    private final Preview.SurfaceProvider surfaceProviderPrimary;
    private Preview.SurfaceProvider surfaceProviderSecondary;

    private boolean isInitiated = false;
    private boolean isDualMode = false;
    private boolean isBinding = false;
    private volatile int recordingFrameRate = 30;
    private boolean isTorchOn = false;

    public interface PreviewSizeListener {
        void onPreviewSize(int width, int height);
    }

    public static class CameraLifecycle implements LifecycleOwner {
        private final LifecycleRegistry lifecycleRegistry;

        public CameraLifecycle() {
            lifecycleRegistry = new LifecycleRegistry(this);
            lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        }

        public void start() {
            try {
                if (lifecycleRegistry.getCurrentState() != Lifecycle.State.DESTROYED) {
                    lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }

        public void stop() {
            try {
                if (lifecycleRegistry.getCurrentState() != Lifecycle.State.DESTROYED) {
                    lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }

        public boolean isDestroyed() {
            return lifecycleRegistry.getCurrentState() == Lifecycle.State.DESTROYED;
        }

        @Override
        public Lifecycle getLifecycle() {
            return lifecycleRegistry;
        }
    }

    public CameraXSession(CameraLifecycle cameraLifecycle, Preview.SurfaceProvider surfaceProvider) {
        this.lifecycle = cameraLifecycle;
        this.surfaceProviderPrimary = surfaceProvider;
    }

    /**
     * CameraX classes are present and CameraX did not fail earlier in this process.
     */
    public static boolean isAvailable() {
        if (failedInThisProcess) {
            return false;
        }
        if (classesAvailableCache == null) {
            boolean available;
            try {
                // direct class literals (not Class.forName) so R8 renaming cannot break the check
                available = ProcessCameraProvider.class != null && Camera2CameraInfo.class != null;
            } catch (Throwable e) {
                FileLog.e(e);
                available = false;
            }
            classesAvailableCache = available;
        }
        return classesAvailableCache;
    }

    public static void markFailed(String reason) {
        FileLog.e("CameraX disabled for this process: " + reason);
        failedInThisProcess = true;
    }

    public static Preview.SurfaceProvider createSurfaceProvider(final Context context, final SurfaceTexture surfaceTexture, final PreviewSizeListener previewSizeListener) {
        return surfaceRequest -> {
            try {
                final Size resolution = surfaceRequest.getResolution();
                previewSizeListener.onPreviewSize(resolution.getWidth(), resolution.getHeight());
                surfaceRequest.setTransformationInfoListener(ContextCompat.getMainExecutor(context), transformationInfo -> {
                    Rect cropRect = transformationInfo.getCropRect();
                    previewSizeListener.onPreviewSize(cropRect.width() > 0 ? cropRect.width() : resolution.getWidth(), cropRect.height() > 0 ? cropRect.height() : resolution.getHeight());
                });
                surfaceTexture.setDefaultBufferSize(resolution.getWidth(), resolution.getHeight());
                final Surface surface = new Surface(surfaceTexture);
                surfaceRequest.provideSurface(surface, ContextCompat.getMainExecutor(context), result -> {
                    surfaceRequest.clearTransformationInfoListener();
                    surface.release();
                });
            } catch (Exception e) {
                FileLog.e(e);
                surfaceRequest.willNotProvideSurface();
            }
        };
    }

    public void setSecondSurfaceProvider(Preview.SurfaceProvider surfaceProvider) {
        surfaceProviderSecondary = surfaceProvider;
        if (isInitiated && isDualMode && previewUseCaseBack != null) {
            previewUseCaseBack.setSurfaceProvider(surfaceProvider);
        }
    }

    public boolean isInitiated() {
        return isInitiated;
    }

    public boolean isReady() {
        return isInitiated && !isBinding && camera != null;
    }

    public boolean isDualMode() {
        return isDualMode;
    }

    public boolean isFrontface() {
        return isFrontface;
    }

    public boolean isActiveCameraFrontface() {
        Camera camera = this.camera;
        if (camera == null) {
            return isFrontface;
        }
        int lensFacing = camera.getCameraInfo().getLensFacing();
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            return true;
        }
        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            return false;
        }
        return isFrontface;
    }

    public int getRecordingFrameRate() {
        return recordingFrameRate;
    }

    /**
     * @param onReady called on the main thread after a successful bind
     * @param onError called on the main thread if CameraX could not be started (the caller falls back to the stock camera)
     */
    public void initCamera(Context context, boolean front, final boolean dual, final Runnable onReady, final Runnable onError) {
        isFrontface = front;
        final ListenableFuture<ProcessCameraProvider> future;
        try {
            future = ProcessCameraProvider.getInstance(context);
        } catch (Throwable e) {
            FileLog.e(e);
            markFailed("getInstance: " + e);
            if (onError != null) {
                AndroidUtilities.runOnUIThread(onError);
            }
            return;
        }
        future.addListener(() -> {
            boolean ok = false;
            try {
                provider = future.get();
                if (lifecycle.isDestroyed()) {
                    return;
                }
                isDualMode = dual && supportsConcurrentFrontBackPair();
                rebindCamera();
                ok = isInitiated;
                if (ok) {
                    lifecycle.start();
                }
            } catch (Throwable e) {
                FileLog.e(e);
                isInitiated = false;
            }
            if (lifecycle.isDestroyed()) {
                return;
            }
            if (ok) {
                if (onReady != null) {
                    onReady.run();
                }
            } else {
                markFailed("bind failed");
                if (onError != null) {
                    onError.run();
                }
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private boolean supportsConcurrentFrontBackPair() {
        if (provider == null) {
            return false;
        }
        try {
            for (List<CameraInfo> infos : provider.getAvailableConcurrentCameraInfos()) {
                boolean front = false, back = false;
                for (CameraInfo cameraInfo : infos) {
                    if (cameraInfo.getLensFacing() == CameraSelector.LENS_FACING_FRONT) {
                        front = true;
                    }
                    if (cameraInfo.getLensFacing() == CameraSelector.LENS_FACING_BACK) {
                        back = true;
                    }
                }
                if (front && back) {
                    return true;
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return false;
    }

    public void switchCamera() {
        isFrontface = !isFrontface;
        if (isDualMode && cameraFront != null && cameraBack != null) {
            updateActiveControl(isFrontface);
            updateTorchState();
        } else {
            rebindCamera();
        }
    }

    private void updateActiveControl(boolean front) {
        if (front) {
            camera = cameraFront;
            cameraControl = cameraControlFront;
        } else {
            camera = cameraBack;
            cameraControl = cameraControlBack;
        }
    }

    public void closeCamera() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            AndroidUtilities.runOnUIThread(this::closeCamera);
            return;
        }
        try {
            if (provider != null) {
                provider.unbindAll();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        try {
            if (previewUseCase != null) {
                previewUseCase.setSurfaceProvider(null);
            }
            if (previewUseCaseBack != null) {
                previewUseCaseBack.setSurfaceProvider(null);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        lifecycle.stop();
        isInitiated = false;
        clearCameraReferences();
    }

    private void clearCameraReferences() {
        cameraBack = null;
        cameraFront = null;
        camera = null;
        cameraControlBack = null;
        cameraControlFront = null;
        cameraControl = null;
        previewUseCaseBack = null;
        previewUseCase = null;
    }

    public void setTorchEnabled(boolean enabled) {
        isTorchOn = enabled;
        updateTorchState();
    }

    private void updateTorchState() {
        try {
            if (isDualMode) {
                if (cameraControlFront != null) {
                    cameraControlFront.enableTorch(false);
                }
                if (cameraControlBack == null || cameraBack == null || !cameraBack.getCameraInfo().hasFlashUnit()) {
                    return;
                }
                cameraControlBack.enableTorch(isTorchOn && !isFrontface);
                return;
            }
            if (camera == null || cameraControl == null || !camera.getCameraInfo().hasFlashUnit()) {
                return;
            }
            boolean back = camera.getCameraInfo().getLensFacing() == CameraSelector.LENS_FACING_BACK;
            cameraControl.enableTorch(isTorchOn && back);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private Preview buildPreview(CameraSelector cameraSelector, boolean stabilization, Range<Integer> frameRate, boolean prefer60) {
        final Size sensorAspect = getSensorAspect(cameraSelector);
        final Set<Size> fps60Sizes = prefer60 ? get60FpsCapableSizes(cameraSelector) : Collections.emptySet();
        Preview.Builder builder = new Preview.Builder().setResolutionSelector(
                new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionFilter((list, rotation) -> sortRoundPreviewSizes(list, sensorAspect, fps60Sizes))
                        .setAllowedResolutionMode(ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION)
                        .build()
        );
        if (frameRate != null) {
            builder.setTargetFrameRate(frameRate);
        }
        builder.setPreviewStabilizationEnabled(!isDualMode && stabilization);
        if (!ExteraConfig.getCameraMirrorMode()) {
            builder.setMirrorMode(MirrorMode.MIRROR_MODE_OFF);
        }
        return builder.build();
    }

    private boolean isPreviewStabilizationSupported(CameraSelector cameraSelector) {
        if (cameraSelector == null) {
            return false;
        }
        try {
            if (provider != null && provider.hasCamera(cameraSelector)) {
                Boolean cached = STABILIZATION_SUPPORT_CACHE.get(cameraSelector);
                if (cached != null) {
                    return cached;
                }
                boolean supported = Preview.getPreviewCapabilities(provider.getCameraInfo(cameraSelector)).isStabilizationSupported();
                STABILIZATION_SUPPORT_CACHE.put(cameraSelector, supported);
                return supported;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    private Set<Range<Integer>> getSupportedFrameRates() {
        try {
            return provider.getCameraInfo(cameraSelector).getSupportedFrameRateRanges();
        } catch (Exception e) {
            FileLog.e(e);
            return Collections.emptySet();
        }
    }

    // CameraX 1.4.2: no SessionConfig, the rate goes into the Preview use case itself
    private Camera tryBindSingle(boolean stabilization, Range<Integer> range, boolean prefer60) {
        try {
            provider.unbindAll();
            previewUseCase = buildPreview(cameraSelector, stabilization, range, prefer60);
            previewUseCase.setSurfaceProvider(surfaceProviderPrimary);
            Camera bound = provider.bindToLifecycle(lifecycle, cameraSelector, previewUseCase);
            if (bound != null && range != null) {
                recordingFrameRate = range.getUpper();
            }
            return bound;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static Range<Integer> selectExtendedFpsRange(Collection<Range<Integer>> ranges) {
        Range<Integer> best = null;
        if (ranges == null) {
            return null;
        }
        for (Range<Integer> range : ranges) {
            if (range == null || !range.getUpper().equals(FPS_60_RANGE.getUpper())) {
                continue;
            }
            if (FPS_60_RANGE.equals(range)) {
                return range;
            }
            if (best == null || range.getLower() > best.getLower()) {
                best = range;
            }
        }
        return best;
    }

    private void rebindCamera() {
        if (provider == null || lifecycle.isDestroyed() || isBinding) {
            return;
        }
        isBinding = true;
        recordingFrameRate = 30;
        clearCameraReferences();
        try {
            provider.unbindAll();
            if (isDualMode) {
                bindDualUseCases();
            } else {
                bindSingleUseCases();
            }
            isInitiated = camera != null;
        } catch (Exception e) {
            FileLog.e(e);
            isInitiated = false;
            try {
                provider.unbindAll();
            } catch (Exception e2) {
                FileLog.e(e2);
            }
            clearCameraReferences();
        } finally {
            isBinding = false;
        }
    }

    private void bindSingleUseCases() {
        try {
            cameraSelector = isFrontface ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
            if (!provider.hasCamera(cameraSelector)) {
                isInitiated = false;
                return;
            }
            boolean extended = ExteraConfig.getExtendedFramesPerSecond();
            boolean stabilization = ExteraConfig.getCameraStabilization() && isPreviewStabilizationSupported(cameraSelector);
            Set<Range<Integer>> supported = getSupportedFrameRates();
            if (extended) {
                Range<Integer> range = selectExtendedFpsRange(supported);
                if (range != null) {
                    camera = tryBindSingle(stabilization, range, true);
                    if (camera == null && stabilization) {
                        camera = tryBindSingle(false, range, true);
                    }
                }
            }
            if (camera == null && supported.contains(FPS_30_RANGE)) {
                camera = tryBindSingle(stabilization, FPS_30_RANGE, false);
            }
            if (camera == null) {
                camera = tryBindSingle(stabilization, null, false);
            }
            if (camera == null && stabilization) {
                camera = tryBindSingle(false, null, false);
            }
            if (camera == null) {
                isInitiated = false;
                return;
            }
            cameraControl = camera.getCameraControl();
            observeCameraState(camera);
            applyInitialZoom(camera, cameraControl);
            updateTorchState();
        } catch (Exception e) {
            FileLog.e(e);
            isInitiated = false;
        }
    }

    private void bindDualUseCases() {
        boolean extended = ExteraConfig.getExtendedFramesPerSecond();
        Range<Integer> range = extended ? FPS_60_RANGE : FPS_30_RANGE;
        ConcurrentCamera concurrentCamera = tryBindConcurrentCameras(range);
        if (concurrentCamera == null && extended) {
            range = FPS_30_RANGE;
            concurrentCamera = tryBindConcurrentCameras(range);
        }
        if (concurrentCamera == null) {
            range = null;
            concurrentCamera = tryBindConcurrentCameras(null);
        }
        if (concurrentCamera == null) {
            isDualMode = false;
            bindSingleUseCases();
            return;
        }
        if (range != null) {
            recordingFrameRate = range.getLower();
        }
        for (Camera c : concurrentCamera.getCameras()) {
            if (c.getCameraInfo().getLensFacing() == CameraSelector.LENS_FACING_FRONT) {
                cameraFront = c;
                cameraControlFront = c.getCameraControl();
                applyInitialZoom(cameraFront, cameraControlFront);
            } else {
                cameraBack = c;
                cameraControlBack = c.getCameraControl();
                applyInitialZoom(cameraBack, cameraControlBack);
            }
            observeCameraState(c);
        }
        if (cameraFront == null || cameraBack == null) {
            try {
                provider.unbindAll();
            } catch (Exception e) {
                FileLog.e(e);
            }
            clearCameraReferences();
            isDualMode = false;
            recordingFrameRate = 30;
            bindSingleUseCases();
            return;
        }
        updateActiveControl(isFrontface);
        updateTorchState();
    }

    private ConcurrentCamera tryBindConcurrentCameras(Range<Integer> range) {
        try {
            boolean is60 = FPS_60_RANGE.equals(range);
            CameraSelector front = CameraSelector.DEFAULT_FRONT_CAMERA;
            previewUseCase = buildPreview(front, false, range, is60);
            previewUseCase.setSurfaceProvider(surfaceProviderPrimary);
            CameraSelector back = CameraSelector.DEFAULT_BACK_CAMERA;
            previewUseCaseBack = buildPreview(back, false, range, is60);
            if (surfaceProviderSecondary != null) {
                previewUseCaseBack.setSurfaceProvider(surfaceProviderSecondary);
            }
            ArrayList<ConcurrentCamera.SingleCameraConfig> configs = new ArrayList<>(2);
            configs.add(new ConcurrentCamera.SingleCameraConfig(front, new UseCaseGroup.Builder().addUseCase(previewUseCase).build(), lifecycle));
            configs.add(new ConcurrentCamera.SingleCameraConfig(back, new UseCaseGroup.Builder().addUseCase(previewUseCaseBack).build(), lifecycle));
            return provider.bindToLifecycle(configs);
        } catch (Exception e) {
            FileLog.e(e);
            try {
                provider.unbindAll();
            } catch (Exception e2) {
                FileLog.e(e2);
            }
            return null;
        }
    }

    private void observeCameraState(Camera camera) {
        try {
            camera.getCameraInfo().getCameraState().observe(lifecycle, cameraState -> {
                CameraState.StateError error = cameraState.getError();
                if (error != null) {
                    FileLog.e("CameraX camera state error: code=" + error.getCode() + " type=" + cameraState.getType());
                    if (error.getCause() != null) {
                        FileLog.e(error.getCause());
                    }
                }
            });
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void applyInitialZoom(final Camera camera, final CameraControl cameraControl) {
        if (cameraControl == null || camera == null) {
            return;
        }
        cameraControl.setZoomRatio(1.0f);
        if (wantsWideAngleStart(camera)) {
            final LiveData<ZoomState> zoomState = camera.getCameraInfo().getZoomState();
            if (zoomState.getValue() != null) {
                applyWideAngle(cameraControl, zoomState.getValue());
            } else {
                zoomState.observe(lifecycle, new Observer<ZoomState>() {
                    @Override
                    public void onChanged(ZoomState state) {
                        if (state == null) {
                            return;
                        }
                        zoomState.removeObserver(this);
                        if (wantsWideAngleStart(camera)) {
                            applyWideAngle(cameraControl, state);
                        }
                    }
                });
            }
        }
    }

    private void applyWideAngle(CameraControl cameraControl, ZoomState zoomState) {
        if (zoomState == null || zoomState.getMinZoomRatio() >= 1.0f) {
            return;
        }
        cameraControl.setZoomRatio(zoomState.getMinZoomRatio());
    }

    private boolean wantsWideAngleStart(Camera camera) {
        return ExteraConfig.getStartWithWideAngleCamera() && camera != null && camera.getCameraInfo().getLensFacing() == CameraSelector.LENS_FACING_BACK;
    }

    public float getLinearZoom() {
        ZoomState value;
        Camera camera = this.camera;
        if (camera == null || (value = camera.getCameraInfo().getZoomState().getValue()) == null) {
            return 0.0f;
        }
        return value.getLinearZoom();
    }

    public float getZoomRatio() {
        ZoomState value;
        Camera camera = this.camera;
        if (camera == null || (value = camera.getCameraInfo().getZoomState().getValue()) == null) {
            return 1.0f;
        }
        return value.getZoomRatio();
    }

    public void setZoomRatio(float ratio) {
        if (cameraControl == null) {
            return;
        }
        try {
            cameraControl.setZoomRatio(ratio);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public float getMinZoomRatio() {
        ZoomState value;
        Camera camera = this.camera;
        if (camera == null || (value = camera.getCameraInfo().getZoomState().getValue()) == null) {
            return 1.0f;
        }
        return value.getMinZoomRatio();
    }

    public float getMaxZoomRatio() {
        ZoomState value;
        Camera camera = this.camera;
        if (camera == null || (value = camera.getCameraInfo().getZoomState().getValue()) == null) {
            return 1.0f;
        }
        return value.getMaxZoomRatio();
    }

    public String getActiveCameraId() {
        Camera camera = this.camera;
        if (camera == null) {
            return null;
        }
        try {
            return Camera2CameraInfo.from(camera.getCameraInfo()).getCameraId();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    public void focusToPoint(float x, float y, float width, float height) {
        Display display;
        if (cameraControl == null || camera == null || width <= 0.0f || height <= 0.0f || (display = getDefaultDisplay()) == null) {
            return;
        }
        try {
            cameraControl.startFocusAndMetering(new FocusMeteringAction.Builder(
                    new DisplayOrientedMeteringPointFactory(display, camera.getCameraInfo(), width, height).createPoint(x, y),
                    FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE
            ).build());
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public int getDisplayOrientation() {
        Display display = getDefaultDisplay();
        int rotation = display != null ? display.getRotation() : Surface.ROTATION_0;
        switch (rotation) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }

    private Size getSensorAspect(CameraSelector cameraSelector) {
        try {
            if (provider != null && cameraSelector != null && provider.hasCamera(cameraSelector)) {
                Rect rect = Camera2CameraInfo.from(provider.getCameraInfo(cameraSelector)).getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                if (rect != null && rect.width() > 0 && rect.height() > 0) {
                    return new Size(rect.width(), rect.height());
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return FALLBACK_SENSOR_ASPECT;
    }

    private Set<Size> get60FpsCapableSizes(CameraSelector cameraSelector) {
        HashSet<Size> sizes = new HashSet<>();
        try {
            if (provider != null && cameraSelector != null && provider.hasCamera(cameraSelector)) {
                StreamConfigurationMap map = Camera2CameraInfo.from(provider.getCameraInfo(cameraSelector)).getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size[] outputSizes = map != null ? map.getOutputSizes(SurfaceTexture.class) : null;
                if (outputSizes != null) {
                    for (Size size : outputSizes) {
                        long minFrameDuration = map.getOutputMinFrameDuration(SurfaceTexture.class, size);
                        if (minFrameDuration > 0 && minFrameDuration <= 16666667) {
                            sizes.add(size);
                        }
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return sizes;
    }

    private static int getRoundVideoResolution() {
        try {
            return MessagesController.getInstance(UserConfig.selectedAccount).roundVideoSize;
        } catch (Exception e) {
            return 384;
        }
    }

    private static List<Size> sortRoundPreviewSizes(List<Size> list, Size sensorAspect, final Set<Size> fps60Sizes) {
        ArrayList<Size> sorted = new ArrayList<>(list);
        final int roundVideoResolution = getRoundVideoResolution();
        final int targetMin = Math.min(1440, Math.max(1024, roundVideoResolution * 2));
        final double aspect = aspectRatioOf(sensorAspect);
        final boolean prefer60 = !fps60Sizes.isEmpty();
        Collections.sort(sorted, (a, b) -> {
            int c = Integer.compare(previewTier(a, roundVideoResolution, prefer60, fps60Sizes), previewTier(b, roundVideoResolution, prefer60, fps60Sizes));
            if (c != 0) {
                return c;
            }
            c = Double.compare(previewScore(a, aspect, targetMin), previewScore(b, aspect, targetMin));
            if (c != 0) {
                return c;
            }
            return Long.compare((long) a.getWidth() * a.getHeight(), (long) b.getWidth() * b.getHeight());
        });
        return sorted;
    }

    private static int previewTier(Size size, int minSide, boolean prefer60, Set<Size> fps60Sizes) {
        if (Math.min(size.getWidth(), size.getHeight()) < minSide) {
            return 3;
        }
        if (Math.max(size.getWidth(), size.getHeight()) > 1920 || size.getWidth() * size.getHeight() > 2100000) {
            return 2;
        }
        return (!prefer60 || fps60Sizes.contains(size)) ? 0 : 1;
    }

    private static double previewScore(Size size, double aspect, int targetMin) {
        int min = Math.min(size.getWidth(), size.getHeight());
        int max = Math.max(size.getWidth(), size.getHeight());
        if (min <= 0 || max <= 0 || targetMin <= 0) {
            return Double.MAX_VALUE;
        }
        double dMax = max;
        double dMin = min;
        return ((Math.max(0.0d, (dMax / dMin) - aspect) / aspect) * 2.0d) + ((Math.abs(min - targetMin) / (double) targetMin) * 1.0d) + ((1.0d - (dMin / dMax)) * 0.5d);
    }

    private static double aspectRatioOf(Size size) {
        int min = Math.min(size.getWidth(), size.getHeight());
        int max = Math.max(size.getWidth(), size.getHeight());
        if (min > 0) {
            return max / (double) min;
        }
        return 4.0d / 3.0d;
    }

    private static Display getDefaultDisplay() {
        DisplayManager displayManager = (DisplayManager) ApplicationLoader.applicationContext.getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager != null) {
            return displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        }
        return null;
    }

    public static boolean isRoundDualAvailable(Context context) {
        return DualCameraView.roundDualAvailableStatic(context) && isSeamlessSwitchingAvailable(context);
    }

    public static boolean isSeamlessSwitchingAvailable(Context context) {
        if (seamlessSwitchingAvailableCache != null) {
            return seamlessSwitchingAvailableCache;
        }
        seamlessSwitchingAvailableCache = SharedConfig.getDevicePerformanceClass() >= SharedConfig.PERFORMANCE_CLASS_AVERAGE && SharedConfig.allowPreparingHevcPlayers() && hasConcurrentFrontBackPair(context);
        return seamlessSwitchingAvailableCache;
    }

    private static boolean hasConcurrentFrontBackPair(Context context) {
        PackageManager packageManager;
        CameraManager cameraManager;
        if (context == null || Build.VERSION.SDK_INT < 30 || (packageManager = context.getPackageManager()) == null || !packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_CONCURRENT) || (cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE)) == null) {
            return false;
        }
        try {
            for (Set<String> ids : cameraManager.getConcurrentCameraIds()) {
                boolean front = false, back = false;
                for (String id : ids) {
                    Integer facing = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                    if (facing != null) {
                        if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                            front = true;
                        } else if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                            back = true;
                        }
                    }
                }
                if (front && back) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }
}
