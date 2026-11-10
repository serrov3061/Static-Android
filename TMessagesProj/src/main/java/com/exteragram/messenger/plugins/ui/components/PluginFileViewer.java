package com.exteragram.messenger.plugins.ui.components;

import android.app.Activity;
import android.content.DialogInterface;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.utils.MarkdownUtils;
import java.io.File;
import java.io.FileInputStream;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import kotlin.collections.ArraysKt;
import kotlin.io.CloseableKt;
import kotlin.io.FilesKt;
import kotlin.text.Charsets;
import kotlin.text.StringsKt;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_iv;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

public final class PluginFileViewer {
    public static final PluginFileViewer INSTANCE = new PluginFileViewer();
    private static final int MAX_BLOCK_LENGTH = 8192;
    private static final int MAX_FILE_SIZE = 524288;
    private static final int SNIFF_LENGTH = 8192;

    private PluginFileViewer() {
    }

    public static final boolean canOpen(File file) {
        if (file == null || !file.exists() || !file.isFile() || file.length() > 524288) {
            return false;
        }
        return INSTANCE.isText(file);
    }

    /** A file is treated as text when its first 8 KiB contain no NUL byte. */
    private final boolean isText(File file) {
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            byte[] buffer = new byte[SNIFF_LENGTH];
            int read = fileInputStream.read(buffer);
            if (read <= 0) {
                return false;
            }
            for (int i = 0; i < read; i++) {
                if (buffer[i] == 0) {
                    return false;
                }
            }
            return true;
        } catch (Throwable th) {
            FileLog.e(th);
            return false;
        }
    }

    public static boolean openExternally(BaseFragment baseFragment, File file) {
        return openExternally(baseFragment, file, null);
    }

    public static final boolean openExternally(BaseFragment fragment, File file, String fileName) {
        Activity parentActivity;
        String str;
        String str2;
        if (fragment != null && (parentActivity = fragment.getParentActivity()) != null && file != null && file.exists() && file.isFile()) {
            PluginFileViewer pluginFileViewer = INSTANCE;
            boolean zIsZip = pluginFileViewer.isZip(file);
            boolean z = !zIsZip && pluginFileViewer.isText(file);
            if (fileName == null) {
                fileName = file.getName();
            } else {
                if (StringsKt.isBlank(fileName)) {
                    fileName = null;
                }
                if (fileName == null) {
                    fileName = file.getName();
                }
            }
            if (!StringsKt.contains((CharSequence) fileName, '.', false)) {
                if (zIsZip) {
                    str2 = ".zip";
                } else if (z) {
                    str2 = ".txt";
                } else {
                    str2 = ".bin";
                }
                fileName = fileName + str2;
            }
            String str3 = fileName;
            if (zIsZip) {
                str = "application/zip";
            } else if (z) {
                str = "text/plain";
            } else {
                str = "application/octet-stream";
            }
            try {
                return AndroidUtilities.openForView(file, str3, str, parentActivity, fragment.getResourceProvider(), false);
            } catch (Throwable th) {
                FileLog.e(th);
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.ErrorOccurred)).show();
            }
        }
        return false;
    }

    /** "PK\3\4", "PK\5\6" (empty archive) or "PK\7\8" (spanned archive). */
    private final boolean isZip(File file) {
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            byte[] header = new byte[4];
            if (fileInputStream.read(header) != 4) {
                return false;
            }
            byte b2 = header[2];
            return header[0] == 80 && header[1] == 75 && (b2 == 3 || b2 == 5 || b2 == 7);
        } catch (Throwable th) {
            FileLog.e(th);
            return false;
        }
    }

    public final boolean open(BaseFragment fragment, File file) {
        return open(fragment, file, null);
    }

    public final boolean open(final BaseFragment fragment, final File file, final String fileName) {
        Activity parentActivity;
        if (fragment == null || (parentActivity = fragment.getParentActivity()) == null || file == null || !file.exists() || !file.isFile()) {
            return false;
        }
        if (!canOpen(file)) {
            return openExternally(fragment, file, fileName);
        }
        final AlertDialog alertDialog = new AlertDialog(parentActivity, 3, fragment.getResourceProvider());
        alertDialog.setCanceledOnTouchOutside(false);
        final boolean[] zArr = {false};
        alertDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public final void onCancel(DialogInterface dialogInterface) {
                PluginFileViewer.m1611$r8$lambda$vbpziXMO_HwI7YqbW1Hz2wiHAQ(zArr, dialogInterface);
            }
        });
        alertDialog.showDelayed(150L);
        Utilities.globalQueue.postRunnable(new Runnable() {
            @Override
            public final void run() {
                PluginFileViewer.m1610$r8$lambda$vQwAKkakqkeP6_e0FaokhJKWvo(file, fileName, alertDialog, zArr, fragment);
            }
        });
        return true;
    }

    public static void m1611$r8$lambda$vbpziXMO_HwI7YqbW1Hz2wiHAQ(boolean[] zArr, DialogInterface dialogInterface) {
        zArr[0] = true;
    }

    public static void m1610$r8$lambda$vQwAKkakqkeP6_e0FaokhJKWvo(File file, String str, final AlertDialog alertDialog, final boolean[] zArr, final BaseFragment baseFragment) {
        MessageObject created;
        try {
            created = INSTANCE.createMessageObject(file, INSTANCE.normalizeFileName(file, str));
        } catch (Throwable th) {
            FileLog.e(th);
            created = null;
        }
        final MessageObject messageObjectCreateMessageObject = created;
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public final void run() {
                PluginFileViewer.$r8$lambda$0xZUrpuuyQT7Lkt43HYs6kdYNiE(alertDialog, zArr, messageObjectCreateMessageObject, baseFragment);
            }
        });
    }

    public static void $r8$lambda$0xZUrpuuyQT7Lkt43HYs6kdYNiE(AlertDialog alertDialog, boolean[] zArr, MessageObject messageObject, BaseFragment baseFragment) {
        try {
            alertDialog.dismiss();
        } catch (Throwable unused) {
        }
        if (zArr[0]) {
            return;
        }
        if (messageObject != null) {
            baseFragment.createArticleViewer(false).open(messageObject);
        } else {
            BulletinFactory.of(baseFragment).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.ErrorOccurred)).show();
        }
    }

    private final MessageObject createMessageObject(File file, String fileName) throws java.io.IOException {
        String text = com.exteragram.messenger.plugins.utils.PluginFiles.readText(file, MAX_FILE_SIZE);
        TL_iv.TL_page tL_page = new TL_iv.TL_page();
        tL_page.local = file;
        tL_page.url = fileName;
        MarkdownUtils.appendPreformattedBlocks(tL_page.blocks, text, PluginsConstants.PYTHON, 8192);
        TLRPC.TL_webPage tL_webPage = new TLRPC.TL_webPage();
        tL_webPage.id = file.getAbsolutePath().hashCode();
        tL_webPage.url = fileName;
        tL_webPage.display_url = fileName;
        tL_webPage.title = fileName;
        tL_webPage.flags |= 1028;
        tL_webPage.cached_page = tL_page;
        long j = UserConfig.getInstance(UserConfig.selectedAccount).clientUserId;
        TLRPC.TL_message tL_message = new TLRPC.TL_message();
        tL_message.id = 0;
        tL_message.date = (int) (System.currentTimeMillis() / 1000);
        tL_message.message = fileName;
        tL_message.out = true;
        TLRPC.TL_peerUser tL_peerUser = new TLRPC.TL_peerUser();
        tL_peerUser.user_id = j;
        tL_message.peer_id = tL_peerUser;
        TLRPC.TL_peerUser tL_peerUser2 = new TLRPC.TL_peerUser();
        tL_peerUser2.user_id = j;
        tL_message.from_id = tL_peerUser2;
        TLRPC.TL_messageMediaWebPage tL_messageMediaWebPage = new TLRPC.TL_messageMediaWebPage();
        tL_messageMediaWebPage.webpage = tL_webPage;
        tL_message.media = tL_messageMediaWebPage;
        return new MessageObject(UserConfig.selectedAccount, tL_message, false, true);
    }

    private final String normalizeFileName(File file, String fileName) {
        if (fileName == null) {
            fileName = file.getName();
        } else {
            if (StringsKt.isBlank(fileName)) {
                fileName = null;
            }
            if (fileName == null) {
                fileName = file.getName();
            }
        }
        if (StringsKt.contains((CharSequence) fileName, '.', false)) {
            return fileName;
        }
        return fileName + PluginsConstants.PLUGINS_EXT;
    }
}
