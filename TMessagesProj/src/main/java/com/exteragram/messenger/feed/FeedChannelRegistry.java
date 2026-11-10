package com.exteragram.messenger.feed;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * exteraGram feed: tracks the set of subscribed broadcast channels in the dialog list and notifies
 * listeners about joined / left channels (debounced dialogsNeedReload).
 */
public class FeedChannelRegistry implements NotificationCenter.NotificationCenterDelegate {

    private static final FeedChannelRegistry[] instances = new FeedChannelRegistry[16];
    private static final Object[] locks = new Object[16];

    static {
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
    }

    public interface Listener {
        void onFeedChannelsChanged(HashSet<Long> added, HashSet<Long> removed);
    }

    private boolean built;
    public final int currentAccount;
    private boolean rebuildScheduled;
    private final HashSet<Long> channelIds = new HashSet<>();
    private final ArrayList<Listener> listeners = new ArrayList<>();
    private final Runnable rebuildRunnable = () -> {
        rebuildScheduled = false;
        rebuild(true);
    };

    public static FeedChannelRegistry getInstance(int num) {
        FeedChannelRegistry registry = instances[num];
        if (registry != null) {
            return registry;
        }
        synchronized (locks[num]) {
            registry = instances[num];
            if (registry == null) {
                registry = new FeedChannelRegistry(num);
                instances[num] = registry;
            }
        }
        return registry;
    }

    private FeedChannelRegistry(int account) {
        currentAccount = account;
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.dialogsNeedReload));
    }

    public void addListener(Listener listener) {
        ensureBuilt();
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    private void ensureBuilt() {
        if (built) {
            return;
        }
        built = true;
        rebuild(false);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogsNeedReload) {
            ensureBuilt();
            if (rebuildScheduled) {
                return;
            }
            rebuildScheduled = true;
            AndroidUtilities.runOnUIThread(rebuildRunnable, 500L);
        }
    }

    private void rebuild(boolean notify) {
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        LongSparseArray<TLRPC.Dialog> dialogs = messagesController.dialogs_dict;
        HashSet<Long> current = new HashSet<>();
        for (int i = 0; i < dialogs.size(); i++) {
            TLRPC.Dialog dialog = dialogs.valueAt(i);
            if (dialog != null && DialogObject.isChatDialog(dialog.id) && FeedController.isEligibleChannel(messagesController.getChat(-dialog.id))) {
                current.add(dialog.id);
            }
        }
        HashSet<Long> added = null;
        HashSet<Long> removed = null;
        for (Long id : current) {
            if (!channelIds.contains(id)) {
                if (added == null) {
                    added = new HashSet<>();
                }
                added.add(id);
            }
        }
        for (Long id : channelIds) {
            if (!current.contains(id)) {
                if (removed == null) {
                    removed = new HashSet<>();
                }
                removed.add(id);
            }
        }
        if (added == null && removed == null) {
            return;
        }
        channelIds.clear();
        channelIds.addAll(current);
        if (notify) {
            if (added == null) {
                added = new HashSet<>();
            }
            if (removed == null) {
                removed = new HashSet<>();
            }
            for (int i = listeners.size() - 1; i >= 0; i--) {
                listeners.get(i).onFeedChannelsChanged(added, removed);
            }
        }
    }
}
