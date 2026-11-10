package com.exteragram.messenger.debug;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.view.View;

import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.utils.network.RemoteUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.io.File;
import java.util.ArrayList;

/**
 * exteraGram 13.1.1 hidden debug screen (long press on the exteraGram item in Settings).
 * StaticGram keeps the camera / music / chat fade switches and the heap, load and freeze monitors;
 * exteraGram-server items (API requests, supporter sheets, exteraDatabase, ipconfigv3 override) are left out.
 */
public final class DebugActivity extends BasePreferencesActivity {

    public enum DebugItem {
        DEBUG_CAMERA_METRICS,
        FORCE_COMPACT_SAVED_MUSIC,
        DISABLE_CHAT_FADE_WALLPAPER_BLEND,
        CHAT_FADE_USE_WHITE_BACKGROUND,
        FORCE_REFRESH_REMOTE_CONFIG,
        HEAP_MONITOR,
        HEAP_DUMP_ON_OOM,
        DUMP_HEAP,
        SHARE_HEAP_DUMP,
        DELETE_HEAP_DUMPS,
        LOAD_MONITOR,
        CAPTURE_LOAD_REPORT,
        SHARE_LOAD_REPORT,
        DELETE_LOAD_REPORTS,
        FREEZE_MONITOR,
        SHARE_FREEZE_REPORT,
        DELETE_FREEZE_REPORTS;

        public int getId() {
            return ordinal() + 1;
        }
    }

    @Override
    public String getTitle() {
        return getString(R.string.DebugTitle);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCheck(DebugItem.DEBUG_CAMERA_METRICS.getId(), getString(R.string.DebugCameraMetrics)).setChecked(DebugConfig.getDebugCameraMetrics()));
        items.add(UItem.asCheck(DebugItem.FORCE_COMPACT_SAVED_MUSIC.getId(), getString(R.string.DebugCompactSavedMusic)).setChecked(DebugConfig.getForceCompactSavedMusic()));
        items.add(UItem.asCheck(DebugItem.DISABLE_CHAT_FADE_WALLPAPER_BLEND.getId(), getString(R.string.DebugDisableChatFadeBlend)).setChecked(DebugConfig.getDisableChatFadeWallpaperBlend()));
        items.add(UItem.asCheck(DebugItem.CHAT_FADE_USE_WHITE_BACKGROUND.getId(), getString(R.string.DebugChatFadeWhite)).setChecked(DebugConfig.getChatFadeUseWhiteBackground()));
        items.add(UItem.asShadow());
        items.add(UItem.asButton(DebugItem.FORCE_REFRESH_REMOTE_CONFIG.getId(), getString(R.string.DebugRefreshRemoteConfig)));
        items.add(UItem.asShadow());

        items.add(UItem.asCheck(DebugItem.HEAP_MONITOR.getId(), getString(R.string.DebugHeapMonitor)).setChecked(DebugConfig.getHeapMonitorEnabled()));
        if (DebugConfig.getHeapMonitorEnabled()) {
            items.add(createHeapLimitSliderItem());
        }
        items.add(UItem.asCheck(DebugItem.HEAP_DUMP_ON_OOM.getId(), getString(R.string.DebugHeapDumpOnOom)).setChecked(DebugConfig.getHeapDumpOnOom()));
        items.add(UItem.asButton(DebugItem.DUMP_HEAP.getId(), getString(R.string.DebugDumpHeap), getHeapUsageValue()));
        HeapMonitor heap = HeapMonitor.INSTANCE;
        File lastDump = heap.getLastDump();
        if (lastDump != null) {
            items.add(UItem.asButton(DebugItem.SHARE_HEAP_DUMP.getId(), getString(R.string.DebugShareHeapDump), AndroidUtilities.formatFileSize(lastDump.length())));
        }
        long dumpsSize = heap.getDumpsSize();
        if (dumpsSize > 0) {
            items.add(UItem.asButton(DebugItem.DELETE_HEAP_DUMPS.getId(), getString(R.string.DebugDeleteHeapDumps), AndroidUtilities.formatFileSize(dumpsSize)));
        }
        items.add(UItem.asShadow(getString(R.string.DebugHeapInfo)));

        items.add(UItem.asCheck(DebugItem.LOAD_MONITOR.getId(), getString(R.string.DebugLoadMonitor)).setChecked(DebugConfig.getLoadMonitorEnabled()));
        if (DebugConfig.getLoadMonitorEnabled()) {
            items.add(createLoadThresholdSliderItem());
        }
        LoadMonitor load = LoadMonitor.INSTANCE;
        items.add(UItem.asButton(DebugItem.CAPTURE_LOAD_REPORT.getId(), getString(R.string.DebugCaptureLoadReport), load.getSummary()));
        File lastReport = load.getLastReport();
        if (lastReport != null) {
            items.add(UItem.asButton(DebugItem.SHARE_LOAD_REPORT.getId(), getString(R.string.DebugShareLoadReport), AndroidUtilities.formatFileSize(lastReport.length())));
        }
        long loadSize = load.getReportsSize();
        if (loadSize > 0) {
            items.add(UItem.asButton(DebugItem.DELETE_LOAD_REPORTS.getId(), getString(R.string.DebugDeleteLoadReports), AndroidUtilities.formatFileSize(loadSize)));
        }
        items.add(UItem.asShadow());

        items.add(UItem.asCheck(DebugItem.FREEZE_MONITOR.getId(), getString(R.string.DebugFreezeMonitor)).setChecked(DebugConfig.getFreezeMonitorEnabled()));
        if (DebugConfig.getFreezeMonitorEnabled()) {
            items.add(createFreezeThresholdSliderItem());
        }
        FreezeMonitor freeze = FreezeMonitor.INSTANCE;
        File freezeReport = freeze.getReport();
        if (freezeReport != null) {
            items.add(UItem.asButton(DebugItem.SHARE_FREEZE_REPORT.getId(), getString(R.string.DebugShareFreezeReport), AndroidUtilities.formatFileSize(freezeReport.length())));
        }
        long freezeSize = freeze.getReportsSize();
        if (freezeSize > 0) {
            items.add(UItem.asButton(DebugItem.DELETE_FREEZE_REPORTS.getId(), getString(R.string.DebugDeleteFreezeReports), AndroidUtilities.formatFileSize(freezeSize)));
        }
        items.add(UItem.asShadow());
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        DebugItem[] values = DebugItem.values();
        if (id <= 0 || id > values.length) {
            return;
        }
        Activity activity = getParentActivity();
        switch (values[id - 1]) {
            case DEBUG_CAMERA_METRICS:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, DebugConfig::setDebugCameraMetrics);
                break;
            case FORCE_COMPACT_SAVED_MUSIC:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, DebugConfig::setForceCompactSavedMusic);
                break;
            case DISABLE_CHAT_FADE_WALLPAPER_BLEND:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, DebugConfig::setDisableChatFadeWallpaperBlend);
                break;
            case CHAT_FADE_USE_WHITE_BACKGROUND:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, DebugConfig::setChatFadeUseWhiteBackground);
                break;
            case FORCE_REFRESH_REMOTE_CONFIG:
                RemoteUtils.forceRefresh();
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, getString(R.string.DebugDone)).show();
                break;
            case HEAP_MONITOR:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, HeapMonitor.INSTANCE::setEnabled);
                break;
            case HEAP_DUMP_ON_OOM:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, DebugConfig::setHeapDumpOnOom);
                break;
            case DUMP_HEAP:
                if (activity != null) {
                    HeapMonitor.INSTANCE.dumpAndShare(activity, resourceProvider);
                }
                break;
            case SHARE_HEAP_DUMP:
                if (activity != null) {
                    HeapMonitor.INSTANCE.shareLastDump(activity);
                }
                break;
            case DELETE_HEAP_DUMPS:
                HeapMonitor.INSTANCE.deleteDumps(this::onFilesDeleted);
                break;
            case LOAD_MONITOR:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, LoadMonitor.INSTANCE::setEnabled);
                break;
            case CAPTURE_LOAD_REPORT:
                if (activity != null) {
                    LoadMonitor.INSTANCE.captureAndShare(activity);
                }
                break;
            case SHARE_LOAD_REPORT:
                if (activity != null) {
                    LoadMonitor.INSTANCE.shareLastReport(activity);
                }
                break;
            case DELETE_LOAD_REPORTS:
                LoadMonitor.INSTANCE.deleteReports(this::onFilesDeleted);
                break;
            case FREEZE_MONITOR:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, FreezeMonitor.INSTANCE::setEnabled);
                break;
            case SHARE_FREEZE_REPORT:
                if (activity != null) {
                    FreezeMonitor.INSTANCE.shareReport(activity);
                }
                break;
            case DELETE_FREEZE_REPORTS:
                FreezeMonitor.INSTANCE.deleteReports(this::onFilesDeleted);
                break;
        }
    }

    private void onFilesDeleted() {
        if (getListView$TMessagesProj() != null && getListView$TMessagesProj().adapter != null) {
            getListView$TMessagesProj().adapter.update(true);
        }
        BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, getString(R.string.DebugFilesDeleted)).show();
    }

    private UItem createFreezeThresholdSliderItem() {
        final int step = 100;
        return UItem.asIntSlideView(1, 2, FreezeMonitor.INSTANCE.getThresholdMs() / step, 20,
                v -> (v * step) + " ms",
                v -> FreezeMonitor.INSTANCE.setThresholdMs(v * step));
    }

    private UItem createLoadThresholdSliderItem() {
        return UItem.asIntSlideView(1, 1, LoadMonitor.INSTANCE.getCpuPercent(), 50,
                v -> LocaleController.formatString(R.string.DebugCpuPercentOfCore, v),
                LoadMonitor.INSTANCE::setCpuPercent);
    }

    private UItem createHeapLimitSliderItem() {
        final int step = 16;
        HeapMonitor heap = HeapMonitor.INSTANCE;
        return UItem.asIntSlideView(1, 4, heap.getLimitMb() / step, heap.getMaxLimitMb() / step,
                v -> (v * step) + " MB",
                v -> heap.setLimitMb(v * step));
    }

    private String getHeapUsageValue() {
        return AndroidUtilities.formatFileSize(HeapMonitor.INSTANCE.getUsedBytes(), true, false) + " / " + AndroidUtilities.formatFileSize(Runtime.getRuntime().maxMemory(), true, false);
    }
}
