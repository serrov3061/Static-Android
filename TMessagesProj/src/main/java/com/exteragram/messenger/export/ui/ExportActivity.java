package com.exteragram.messenger.export.ui;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.View;

import com.exteragram.messenger.export.ExportSettings;
import com.exteragram.messenger.export.controllers.ExportController;
import com.exteragram.messenger.export.output.AbstractWriter;
import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.preferences.SwitchGroup;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.function.BooleanSupplier;

/**
 * Export of chats and account data (port of exteraGram's ExportActivity, itself a port of tdesktop's export).
 * Rewritten in Java: localized strings, progress shown on the screen, media size limit, cancel,
 * single-chat mode ({@code new ExportActivity(peer)}). Works without takeout sessions (see ExportRequestsController).
 */
public class ExportActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate {

    private enum ExportItem {
        HEADER,
        EXPORT_SETTINGS,
        ACCOUNT_INFO,
        CONTACTS_LIST,
        STORY_ARCHIVE,
        ACTIVE_SESSIONS,
        CHATS_SETTINGS,
        PERSONAL_CHATS,
        BOT_CHATS,
        PRIVATE_GROUPS,
        PRIVATE_CHANNELS,
        PUBLIC_GROUPS,
        PUBLIC_CHANNELS,
        MEDIA_SETTINGS,
        PHOTOS,
        VIDEOS,
        VOICE_MESSAGES,
        VIDEO_MESSAGES,
        STICKERS,
        GIFS,
        FILES,
        SIZE_LIMIT,
        FORMAT,
        START_EXPORT,
        CANCEL_EXPORT,
        STATUS
    }

    private static final long[] SIZE_LIMITS = {8L * 1024 * 1024, 64L * 1024 * 1024, 512L * 1024 * 1024, 2000L * 1024 * 1024};

    // tdesktop Settings::Type flags
    private static final int TYPE_PERSONAL_INFO = 1;
    private static final int TYPE_CONTACTS = 4;
    private static final int TYPE_SESSIONS = 8;
    private static final int TYPE_ANY_CHATS = 32 | 64 | 128 | 256 | 512 | 1024;
    private static final int TYPE_STORIES = 2048;

    private static final int[] NOTIFICATIONS = {
            ExportController.INITIALIZATING_NOTIFICATION, ExportController.PERSONAL_INFO_NOTIFICATION,
            ExportController.USERPICS_NOTIFICATION, ExportController.STORIES_NOTIFICATION, ExportController.CONTACTS_NOTIFICATION,
            ExportController.SESSIONS_NOTIFICATION, ExportController.OTHER_DATA_NOTIFICATION, ExportController.DIALOGS_NOTIFICATION,
            ExportController.FINISH_NOTIFICATION
    };

    private final TLRPC.InputPeer peer;
    private final ExportSettings settings;
    private final SwitchGroup exportSettings;
    private final SwitchGroup chatsSettings;
    private final SwitchGroup mediaSettings;
    private String status;

    public ExportActivity() {
        this(null);
    }

    public ExportActivity(TLRPC.InputPeer peer) {
        this.peer = peer;
        this.settings = new ExportSettings();
        if (peer != null) {
            settings.singlePeer = peer;
            settings.types = TYPE_ANY_CHATS;
        }
        settings.format = AbstractWriter.Format.Html;

        exportSettings = new SwitchGroup(this, ExportItem.EXPORT_SETTINGS, R.string.SgExportMainSettings, null, null, false);
        exportSettings.add(ExportItem.ACCOUNT_INFO, R.string.SgExportAccountInfo, type(TYPE_PERSONAL_INFO), setType(TYPE_PERSONAL_INFO));
        exportSettings.add(ExportItem.CONTACTS_LIST, R.string.SgExportContacts, type(TYPE_CONTACTS), setType(TYPE_CONTACTS));
        exportSettings.add(ExportItem.STORY_ARCHIVE, R.string.SgExportStories, type(TYPE_STORIES), setType(TYPE_STORIES));
        exportSettings.add(ExportItem.ACTIVE_SESSIONS, R.string.SgExportSessions, type(TYPE_SESSIONS), setType(TYPE_SESSIONS));

        chatsSettings = new SwitchGroup(this, ExportItem.CHATS_SETTINGS, R.string.SgExportChats, null, null, false);
        chatsSettings.add(ExportItem.PERSONAL_CHATS, R.string.SgExportPersonalChats, type(32), setType(32));
        chatsSettings.add(ExportItem.BOT_CHATS, R.string.SgExportBotChats, type(64), setType(64));
        chatsSettings.add(ExportItem.PRIVATE_GROUPS, R.string.SgExportPrivateGroups, type(128), setType(128));
        chatsSettings.add(ExportItem.PRIVATE_CHANNELS, R.string.SgExportPrivateChannels, type(512), setType(512));
        chatsSettings.add(ExportItem.PUBLIC_GROUPS, R.string.SgExportPublicGroups, type(256), setType(256));
        chatsSettings.add(ExportItem.PUBLIC_CHANNELS, R.string.SgExportPublicChannels, type(1024), setType(1024));

        mediaSettings = new SwitchGroup(this, ExportItem.MEDIA_SETTINGS, R.string.SgExportMedia, null, null, false);
        mediaSettings.add(ExportItem.PHOTOS, R.string.SgExportPhotos, media(1), setMedia(1));
        mediaSettings.add(ExportItem.VIDEOS, R.string.SgExportVideos, media(2), setMedia(2));
        mediaSettings.add(ExportItem.VOICE_MESSAGES, R.string.SgExportVoice, media(4), setMedia(4));
        mediaSettings.add(ExportItem.VIDEO_MESSAGES, R.string.SgExportRound, media(8), setMedia(8));
        mediaSettings.add(ExportItem.STICKERS, R.string.SgExportStickers, media(16), setMedia(16));
        mediaSettings.add(ExportItem.GIFS, R.string.SgExportGifs, media(32), setMedia(32));
        mediaSettings.add(ExportItem.FILES, R.string.SgExportFiles, media(64), setMedia(64));
    }

    @Override
    public boolean onFragmentCreate() {
        for (int id : NOTIFICATIONS) {
            getNotificationCenter().addObserver(this, id);
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        for (int id : NOTIFICATIONS) {
            getNotificationCenter().removeObserver(this, id);
        }
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (args.length == 0 || !(args[0] instanceof ExportController.ProcessingState)) {
            return;
        }
        if (id == ExportController.FINISH_NOTIFICATION && args[0] instanceof ExportController.FinishedState) {
            ExportController.FinishedState state = (ExportController.FinishedState) args[0];
            status = LocaleController.formatString(R.string.SgExportDone, state.path) + "\n"
                    + LocaleController.formatString(R.string.SgExportDoneInfo, state.filesCount, AndroidUtilities.formatFileSize(state.bytesCount));
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.formatString(R.string.SgExportDone, state.path)).show();
        } else {
            status = describe((ExportController.ProcessingState) args[0]);
        }
        updateList();
    }

    private String describe(ExportController.ProcessingState state) {
        String text;
        if (state.bytesCount > 0) {
            text = LocaleController.formatString(R.string.SgExportStateDownloading, state.bytesName == null ? "" : state.bytesName,
                    AndroidUtilities.formatFileSize(state.bytesLoaded), AndroidUtilities.formatFileSize(state.bytesCount));
        } else if (state.entityCount > 0) {
            text = LocaleController.formatString(R.string.SgExportStateChat, state.entityName == null ? "" : state.entityName,
                    state.entityIndex + 1, state.entityCount, state.itemIndex, state.itemCount);
        } else {
            text = LocaleController.formatString(R.string.SgExportStateStep, stepName(state.step));
        }
        if (state.substepsTotal > 0) {
            int percent = Math.min(100, Math.round(100f * state.substepsPassed / state.substepsTotal));
            text = percent + "% · " + text;
        }
        return text;
    }

    private static String stepName(ExportController.ProcessingState.Step step) {
        if (step == null) {
            return "";
        }
        switch (step) {
            case Initializing:
                return LocaleController.getString(R.string.SgExportStateInit);
            case DialogsList:
                return LocaleController.getString(R.string.SgExportDialogsList);
            case PersonalInfo:
                return LocaleController.getString(R.string.SgExportAccountInfo);
            case Userpics:
                return LocaleController.getString(R.string.SgExportUserpics);
            case Stories:
                return LocaleController.getString(R.string.SgExportStories);
            case Contacts:
                return LocaleController.getString(R.string.SgExportContacts);
            case Sessions:
                return LocaleController.getString(R.string.SgExportSessions);
            case OtherData:
                return LocaleController.getString(R.string.SgExportOtherData);
            default:
                return LocaleController.getString(R.string.SgExportChats);
        }
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(peer != null ? R.string.SgExportChat : R.string.SgExportTitle);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean running = ExportController.isRunning(currentAccount);
        if (status != null) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.SgExportProgress)));
            items.add(UItem.asShadow(ExportItem.STATUS.ordinal() + 1, status));
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.SgExportWhat)));
        if (peer == null) {
            exportSettings.fill(items);
            chatsSettings.fill(items);
        }
        mediaSettings.fill(items);
        items.add(uButton$TMessagesProj(ExportItem.SIZE_LIMIT, LocaleController.getString(R.string.SgExportSizeLimit), 0,
                AndroidUtilities.formatFileSize(settings.media.sizeLimit), null, null, false, null, false, false, null));
        items.add(uButton$TMessagesProj(ExportItem.FORMAT, LocaleController.getString(R.string.SgExportFormat), 0,
                formats()[getIndexOfFormat()], null, null, false, null, false, false, null));
        items.add(UItem.asShadow(LocaleController.getString(peer != null ? R.string.SgExportChatInfo : R.string.SgExportInfo)));
        if (running) {
            items.add(uButton$TMessagesProj(ExportItem.CANCEL_EXPORT, LocaleController.getString(R.string.SgExportCancel), 0,
                    null, null, null, false, null, true, false, null));
        } else {
            items.add(uButton$TMessagesProj(ExportItem.START_EXPORT, LocaleController.getString(R.string.SgExportStart), 0,
                    null, null, null, false, null, false, true, null));
        }
        items.add(UItem.asShadow());
    }

    private static CharSequence[] formats() {
        return new CharSequence[]{"HTML", "JSON", LocaleController.getString(R.string.SgExportHtmlAndJson)};
    }

    private CharSequence[] sizeLimits() {
        CharSequence[] result = new CharSequence[SIZE_LIMITS.length];
        for (int i = 0; i < SIZE_LIMITS.length; i++) {
            result[i] = AndroidUtilities.formatFileSize(SIZE_LIMITS[i]);
        }
        return result;
    }

    private int getIndexOfSizeLimit() {
        for (int i = 0; i < SIZE_LIMITS.length; i++) {
            if (SIZE_LIMITS[i] == settings.media.sizeLimit) {
                return i;
            }
        }
        return 0;
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        int index = item.id - 1;
        if (index < 0 || index >= ExportItem.values().length) {
            return;
        }
        ExportItem exportItem = ExportItem.values()[index];
        switch (exportItem) {
            case EXPORT_SETTINGS:
            case ACCOUNT_INFO:
            case CONTACTS_LIST:
            case STORY_ARCHIVE:
            case ACTIVE_SESSIONS:
                exportSettings.onClick(item);
                break;
            case CHATS_SETTINGS:
            case PERSONAL_CHATS:
            case BOT_CHATS:
            case PRIVATE_GROUPS:
            case PRIVATE_CHANNELS:
            case PUBLIC_GROUPS:
            case PUBLIC_CHANNELS:
                chatsSettings.onClick(item);
                break;
            case MEDIA_SETTINGS:
            case PHOTOS:
            case VIDEOS:
            case VOICE_MESSAGES:
            case VIDEO_MESSAGES:
            case STICKERS:
            case GIFS:
            case FILES:
                mediaSettings.onClick(item);
                break;
            case SIZE_LIMIT:
                showListDialog(item, sizeLimits(), LocaleController.getString(R.string.SgExportSizeLimit), getIndexOfSizeLimit(), which -> {
                    settings.media.sizeLimit = SIZE_LIMITS[which];
                    updateList();
                });
                return;
            case FORMAT:
                showListDialog(item, formats(), LocaleController.getString(R.string.SgExportFormat), getIndexOfFormat(), which -> {
                    settings.format = which == 0 ? AbstractWriter.Format.Html : which == 1 ? AbstractWriter.Format.Json : AbstractWriter.Format.HtmlAndJson;
                    updateList();
                });
                return;
            case START_EXPORT:
                startExport();
                break;
            case CANCEL_EXPORT:
                ExportController.cancel(currentAccount);
                status = LocaleController.getString(R.string.SgExportCancelled);
                break;
            default:
                return;
        }
        updateList();
    }

    private void startExport() {
        if (ExportController.isRunning(currentAccount)) {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.SgExportAlreadyRunning)).show();
            return;
        }
        if ((settings.types & (TYPE_PERSONAL_INFO | TYPE_CONTACTS | TYPE_SESSIONS | TYPE_STORIES | TYPE_ANY_CHATS)) == 0) {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.SgExportNothingSelected)).show();
            return;
        }
        Activity activity = getParentActivity();
        if (activity != null && Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4);
            BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.SgExportPermission)).show();
            return;
        }
        // copy, so changing switches during the export does not affect it
        ExportSettings run = new ExportSettings();
        run.singlePeer = settings.singlePeer;
        run.format = settings.format;
        run.types = settings.types;
        run.singlePeerFrom = settings.singlePeerFrom;
        run.singlePeerTill = settings.singlePeerTill;
        run.media.type = settings.media.type;
        run.media.sizeLimit = settings.media.sizeLimit;
        try {
            ExportController.reset(currentAccount);
            ExportController.getInstance(currentAccount).startExport(run);
            status = LocaleController.getString(R.string.SgExportStateInit);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.SgExportStarted)).show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void updateList() {
        if (getListView$TMessagesProj() != null && getListView$TMessagesProj().adapter != null) {
            getListView$TMessagesProj().adapter.update(true);
        }
    }

    private BooleanSupplier type(int flag) {
        return () -> (settings.types & flag) != 0;
    }

    private SwitchGroup.Setter setType(int flag) {
        return value -> settings.types = value ? (settings.types | flag) : (settings.types & ~flag);
    }

    private BooleanSupplier media(int flag) {
        return () -> (settings.media.type & flag) != 0;
    }

    private SwitchGroup.Setter setMedia(int flag) {
        return value -> settings.media.type = value ? (settings.media.type | flag) : (settings.media.type & ~flag);
    }

    private int getIndexOfFormat() {
        if (settings.format == AbstractWriter.Format.Json) {
            return 1;
        }
        if (settings.format == AbstractWriter.Format.HtmlAndJson) {
            return 2;
        }
        return 0;
    }
}
