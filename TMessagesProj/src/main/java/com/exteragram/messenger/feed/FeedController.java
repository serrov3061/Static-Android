package com.exteragram.messenger.feed;

import android.util.SparseArray;
import android.util.SparseIntArray;

import androidx.collection.LongSparseArray;

import com.exteragram.messenger.ExteraConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;

/**
 * exteraGram channel feed: a merged timeline of the posts of all subscribed broadcast channels.
 * Local only: the timeline is read from the local database (FeedTimelineLoader) and older history is
 * fetched with the regular messages.getHistory (FeedBackfillCoordinator). No exteraGram servers are used;
 * the exteraGram feed ads (feed/ads, remote-config feed_ad*) are not ported.
 *
 * Results are delivered to the feed ChatActivity (chatMode MODE_SEARCH, searchType SEARCH_FEED) with
 * the same messagesDidLoad / hashtagSearchUpdated notifications HashtagSearchController uses.
 */
public class FeedController implements NotificationCenter.NotificationCenterDelegate {

    /** StaticGram: NotificationCenter ids of exteraGram's feedNeedReload / feedTabVisibleToggled (arbitrary ids are fine, observers live in a SparseArray). */
    public static final int feedNeedReload = 0x7FEED001;
    private int badgeConfigGeneration = -1;
    public static final int feedTabVisibleToggled = 0x7FEED002;

    /** ChatActivity searchType of the feed. */
    public static final int SEARCH_FEED = 4;

    private static final FeedController[] Instance = new FeedController[16];
    private static final Object[] lockObjects = new Object[16];

    static {
        for (int i = 0; i < lockObjects.length; i++) {
            lockObjects[i] = new Object();
        }
    }

    public interface ChannelsCallback {
        void onChannels(ArrayList<TLRPC.Chat> channels, int includedCount, boolean failed, int configGeneration);
    }

    public static final class SavedScrollPosition {
        public final long dialogId;
        public final int messageId;
        public final int offsetTop;

        private SavedScrollPosition(long dialogId, int messageId, int offsetTop) {
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.offsetTop = offsetTop;
        }
    }

    private int attemptRounds;
    private final FeedBackfillCoordinator backfill;
    private int cachedIncludedChannelCount = -1;
    private final int closedRefreshGuid = ConnectionsManager.generateClassGuid();
    private final Runnable closedRefreshRunnable = this::runClosedRefresh;
    private boolean closedRefreshScheduled;
    private int configGeneration;
    public final int currentAccount;
    private SavedScrollPosition drawerScrollPosition;
    private boolean hasChannels;
    private boolean hasIncludedChannels;
    private int heldGuid;
    private int heldLoadIndex;
    private final ArrayList<int[]> initialLoadWaiters = new ArrayList<>();
    private boolean initialUnreadScrollPending = true;
    private final FeedTimelineLoader loader;
    private boolean loading;
    private boolean loadingNewer;
    private boolean newerPagingBoundsDirty;
    private boolean olderPagingBoundsDirty;
    private int resumedUiClients;
    private int sessionGeneration;
    private int staleEnumerationRetries;
    private final FeedStore store = new FeedStore();
    private int uiActiveClients;
    private final FeedUnreadTracker unreadTracker;

    public static FeedController peekInstance(int num) {
        return num >= 0 && num < Instance.length ? Instance[num] : null;
    }

    public static FeedController getInstance(int num) {
        FeedController controller = Instance[num];
        if (controller != null) {
            return controller;
        }
        synchronized (lockObjects[num]) {
            controller = Instance[num];
            if (controller == null) {
                controller = new FeedController(num);
                Instance[num] = controller;
            }
        }
        return controller;
    }

    private FeedController(int account) {
        currentAccount = account;
        unreadTracker = new FeedUnreadTracker(account, store.getMessages());
        loader = new FeedTimelineLoader(account);
        backfill = new FeedBackfillCoordinator(account, this::onBackfillRoundFinished);
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter notificationCenter = NotificationCenter.getInstance(account);
            notificationCenter.addObserver(this, NotificationCenter.messagesDidLoad);
            notificationCenter.addObserver(this, NotificationCenter.loadingMessagesFailed);
            notificationCenter.addObserver(this, NotificationCenter.messagesDeleted);
            notificationCenter.addObserver(this, NotificationCenter.historyCleared);
            notificationCenter.addObserver(this, NotificationCenter.didReceiveNewMessages);
            FeedChannelRegistry.getInstance(account).addListener(this::onFeedChannelsChanged);
        });
    }

    private void postReload(Boolean truncated) {
        NotificationCenter.getInstance(currentAccount).postNotificationName(feedNeedReload, truncated);
    }

    private void onFeedChannelsChanged(HashSet<Long> added, HashSet<Long> removed) {
        loader.invalidateChannelCache();
        for (Long dialogId : removed) {
            deleteHistory(dialogId, Integer.MAX_VALUE);
        }
        if (added.isEmpty()) {
            postReload(false);
        } else {
            reconcileChannelSet(this::postReload);
        }
    }

    public FeedStore getStore() {
        return store;
    }

    public ArrayList<MessageObject> getMessages() {
        return store.getMessages();
    }

    public boolean isLoading() {
        return loading || loadingNewer;
    }

    public boolean hasMessagesForDialog(long dialogId) {
        return store.hasMessagesForDialog(dialogId);
    }

    public boolean hasChannels() {
        return hasChannels;
    }

    public boolean hasIncludedChannels() {
        return hasIncludedChannels;
    }

    public int getIncludedChannelCount() {
        return cachedIncludedChannelCount;
    }

    public void setUiActive(boolean active) {
        if (!active) {
            if (uiActiveClients == 0) {
                return;
            }
            uiActiveClients--;
            if (uiActiveClients == 0) {
                cancelLoads();
                trimForInactiveCache();
            }
            return;
        }
        uiActiveClients++;
        if (uiActiveClients > 1) {
            return;
        }
        if (closedRefreshScheduled) {
            AndroidUtilities.cancelRunOnUIThread(closedRefreshRunnable);
            closedRefreshScheduled = false;
        }
        if (loadingNewer) {
            cancelLoads();
        }
    }

    private boolean isUiActive() {
        return uiActiveClients > 0;
    }

    public void setUiResumed(boolean resumed) {
        if (resumed) {
            resumedUiClients++;
        } else if (resumedUiClients > 0) {
            resumedUiClients--;
        }
    }

    public void clear() {
        sessionGeneration++;
        configGeneration = FeedConfig.getInstance(currentAccount).getGeneration();
        unreadTracker.clear();
        drawerScrollPosition = null;
        store.clear();
        loading = false;
        loadingNewer = false;
        olderPagingBoundsDirty = false;
        newerPagingBoundsDirty = false;
        attemptRounds = 0;
        staleEnumerationRetries = 0;
        initialLoadWaiters.clear();
        backfill.cancel();
        backfill.clearExhausted();
        if (closedRefreshScheduled) {
            AndroidUtilities.cancelRunOnUIThread(closedRefreshRunnable);
            closedRefreshScheduled = false;
        }
    }

    public void cancelLoads() {
        sessionGeneration++;
        loading = false;
        loadingNewer = false;
        olderPagingBoundsDirty = false;
        newerPagingBoundsDirty = false;
        attemptRounds = 0;
        staleEnumerationRetries = 0;
        initialLoadWaiters.clear();
        backfill.cancel();
    }

    private static int getInactiveCacheCap() {
        int performanceClass = SharedConfig.getDevicePerformanceClass();
        if (performanceClass == SharedConfig.PERFORMANCE_CLASS_LOW) {
            return 300;
        }
        return performanceClass == SharedConfig.PERFORMANCE_CLASS_HIGH ? 1000 : 600;
    }

    public void trimForInactiveCache() {
        if (isUiActive() || store.isEmpty()) {
            return;
        }
        store.trim(getInactiveCacheCap());
    }

    public boolean isIncludedChannelPost(long dialogId) {
        if (!DialogObject.isChatDialog(dialogId) || !FeedConfig.getInstance(currentAccount).isShownInFeed(dialogId)) {
            return false;
        }
        return isEligibleChannel(MessagesController.getInstance(currentAccount).getChat(-dialogId));
    }

    public static boolean isEligibleChannel(TLRPC.Chat chat) {
        return chat != null && ChatObject.isChannelAndNotMegaGroup(chat) && !ChatObject.isCommunity(chat) && !ChatObject.isNotInChat(chat);
    }

    public boolean consumeInitialUnreadScroll() {
        boolean pending = initialUnreadScrollPending;
        initialUnreadScrollPending = false;
        return pending;
    }

    public int getUnreadCount() {
        if (ExteraConfig.getShowFeedUnreadCounter()) {
            return unreadTracker.getUnreadCount();
        }
        return 0;
    }

    public void onPostSeen(long dialogId, int messageId) {
        unreadTracker.onPostSeen(dialogId, messageId);
    }

    public void markAllRead() {
        unreadTracker.markAllRead();
    }

    public int findFirstUnreadIndex(ArrayList<MessageObject> messages) {
        return unreadTracker.findFirstUnreadIndex(messages);
    }

    public int countUnreadBelow(ArrayList<MessageObject> messages, int index) {
        return unreadTracker.countUnreadBelow(messages, index);
    }

    public void saveDrawerScrollPosition(long dialogId, int messageId, int offsetTop) {
        if (dialogId == 0 || messageId <= 0) {
            return;
        }
        drawerScrollPosition = new SavedScrollPosition(dialogId, messageId, offsetTop);
    }

    public SavedScrollPosition getDrawerScrollPosition() {
        return drawerScrollPosition;
    }

    public boolean hasNoSyntheticIds() {
        return store.hasNoSyntheticIds();
    }

    public MessageObject getMessage(long dialogId, int id) {
        return store.getMessage(dialogId, id);
    }

    public int resolveRealMessageId(long dialogId, int id) {
        return store.resolveRealMessageId(dialogId, id);
    }

    public long resolveRealDialogId(int id) {
        return store.resolveRealDialogId(id);
    }

    public boolean loadInitial(int guid, int loadIndex) {
        ensureCurrentConfig();
        FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        int generation = feedConfig.getGeneration();
        int cacheEpoch = loader.getChannelCacheEpoch();
        if (store.isEmpty()) {
            if (!loadMore(guid, loadIndex)) {
                initialLoadWaiters.add(new int[]{guid, loadIndex});
            }
            return false;
        }
        ArrayList<MessageObject> visible = store.getVisibleMessages();
        for (int i = 0; i < visible.size(); i++) {
            visible.get(i).viewsReloaded = false;
        }
        if (visible.isEmpty() && !store.isEndReached()) {
            if (!loadMore(guid, loadIndex)) {
                initialLoadWaiters.add(new int[]{guid, loadIndex});
            }
            return false;
        }
        int session = sessionGeneration;
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            FeedTimelineLoader.ChannelEnumeration enumeration = loader.enumerateChannels(feedConfig, session, true);
            AndroidUtilities.runOnUIThread(() -> {
                if (session != sessionGeneration) {
                    return;
                }
                if (!isEnumerationCurrent(enumeration, feedConfig, generation, cacheEpoch)) {
                    postFeedResults(guid, loadIndex, new ArrayList<>(), 0, false, true);
                } else {
                    applyEnumeration(enumeration);
                    postFeedResults(guid, loadIndex, visible, 0, false, enumeration.failed);
                }
                postFeedCount(guid);
            });
        });
        return true;
    }

    private void ensureCurrentConfig() {
        if (configGeneration != FeedConfig.getInstance(currentAccount).getGeneration()) {
            applyConfigChange(this::postReload);
        }
    }

    public void markConfigApplied() {
        configGeneration = FeedConfig.getInstance(currentAccount).getGeneration();
    }

    public void applyConfigChange(Utilities.Callback<Boolean> callback) {
        reconcileChannelSet(callback);
    }

    private void reconcileChannelSet(Utilities.Callback<Boolean> callback) {
        int session = sessionGeneration;
        FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        int generation = feedConfig.getGeneration();
        int cacheEpoch = loader.getChannelCacheEpoch();
        if (store.isEmpty()) {
            loadChannels((channels, includedCount, failed, configGen) -> {
                if (!failed) {
                    configGeneration = configGen;
                }
                if (callback != null) {
                    callback.run(false);
                }
            });
            return;
        }
        HashSet<Long> loadedDialogIds = store.getLoadedDialogIds();
        HashSet<Long> hiddenSnapshot = store.getHiddenSnapshot();
        FeedTimelineLoader.Cursor newest = new FeedTimelineLoader.Cursor();
        FeedTimelineLoader.Cursor oldest = new FeedTimelineLoader.Cursor();
        newest.set(store.getNewestCursor().date, store.getNewestCursor().uid, store.getNewestCursor().mid);
        oldest.set(store.getOldestCursor().date, store.getOldestCursor().uid, store.getOldestCursor().mid);
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            FeedTimelineLoader.ChannelEnumeration enumeration = loader.enumerateChannels(feedConfig, session, true);
            if (enumeration.failed) {
                AndroidUtilities.runOnUIThread(() -> onReconcileFailed(session, enumeration, feedConfig, generation, cacheEpoch, callback));
                return;
            }
            ArrayList<Long> toLoad = new ArrayList<>();
            for (int i = 0; i < enumeration.included.size(); i++) {
                long dialogId = enumeration.included.get(i).dialogId;
                if (!loadedDialogIds.contains(dialogId) || hiddenSnapshot.contains(dialogId)) {
                    toLoad.add(dialogId);
                }
            }
            FeedTimelineLoader.WindowPage window = toLoad.isEmpty() ? null : loader.loadChannelWindow(toLoad, newest, oldest);
            if (window != null && window.failed) {
                AndroidUtilities.runOnUIThread(() -> onReconcileFailed(session, enumeration, feedConfig, generation, cacheEpoch, callback));
                return;
            }
            ArrayList<MessageObject> windowObjects = window != null ? createMessageObjects(window.messages, window.users, window.chats) : null;
            boolean hadNewChannels = !toLoad.isEmpty();
            AndroidUtilities.runOnUIThread(() -> {
                if (session != sessionGeneration) {
                    return;
                }
                if (!isEnumerationCurrent(enumeration, feedConfig, generation, cacheEpoch) && canRetryStaleEnumeration()) {
                    reconcileChannelSet(callback);
                    return;
                }
                applyEnumeration(enumeration);
                configGeneration = enumeration.configGeneration;
                HashSet<Long> included = new HashSet<>();
                for (int i = 0; i < enumeration.included.size(); i++) {
                    included.add(enumeration.included.get(i).dialogId);
                }
                store.applyIncludedDialogs(included);
                boolean truncated = window != null && window.truncated;
                if (window != null && !truncated && !windowObjects.isEmpty()) {
                    MessagesController messagesController = MessagesController.getInstance(currentAccount);
                    messagesController.putUsers(window.users, true);
                    messagesController.putChats(window.chats, true);
                    store.mergeRows(windowObjects);
                }
                if (hadNewChannels) {
                    store.setEndReached(false);
                    if (loading) {
                        olderPagingBoundsDirty = true;
                    }
                    if (loadingNewer) {
                        newerPagingBoundsDirty = true;
                    }
                }
                if (callback != null) {
                    callback.run(truncated);
                }
            });
        });
    }

    private void onReconcileFailed(int session, FeedTimelineLoader.ChannelEnumeration enumeration, FeedConfig feedConfig, int generation, int cacheEpoch, Utilities.Callback<Boolean> callback) {
        if (session != sessionGeneration) {
            return;
        }
        if (!isEnumerationCurrent(enumeration, feedConfig, generation, cacheEpoch) && canRetryStaleEnumeration()) {
            reconcileChannelSet(callback);
        } else if (callback != null) {
            callback.run(false);
        }
    }

    public void refreshReadState(Runnable onDone) {
        int session = sessionGeneration;
        FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        int generation = feedConfig.getGeneration();
        int cacheEpoch = loader.getChannelCacheEpoch();
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            FeedTimelineLoader.ChannelEnumeration enumeration = loader.enumerateChannels(feedConfig, session, true);
            AndroidUtilities.runOnUIThread(() -> {
                if (session != sessionGeneration) {
                    return;
                }
                if (isEnumerationCurrent(enumeration, feedConfig, generation, cacheEpoch)) {
                    applyEnumeration(enumeration);
                }
                if (onDone != null) {
                    onDone.run();
                }
            });
        });
    }

    public boolean loadMore(int guid, int loadIndex) {
        ensureCurrentConfig();
        if (loading || (store.isEndReached() && !store.getOldestCursor().isEmpty())) {
            return false;
        }
        loading = true;
        heldGuid = guid;
        heldLoadIndex = loadIndex;
        attemptRounds = 0;
        runAttempt();
        return true;
    }

    private void runAttempt() {
        int guid = heldGuid;
        int loadIndex = heldLoadIndex;
        int session = sessionGeneration;
        FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        int generation = feedConfig.getGeneration();
        int cacheEpoch = loader.getChannelCacheEpoch();
        boolean initial = store.getOldestCursor().isEmpty();
        FeedTimelineLoader.Cursor cursor = new FeedTimelineLoader.Cursor();
        cursor.set(store.getOldestCursor().date, store.getOldestCursor().uid, store.getOldestCursor().mid);
        HashSet<Long> exhausted = backfill.getExhaustedSnapshot();
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            FeedTimelineLoader.ChannelEnumeration enumeration = loader.enumerateChannels(feedConfig, session, false);
            if (enumeration.failed) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (session != sessionGeneration) {
                        return;
                    }
                    if (!isEnumerationCurrent(enumeration, feedConfig, generation, cacheEpoch) && canRetryStaleEnumeration()) {
                        attemptRounds = 0;
                        runAttempt();
                    } else {
                        finishOlderLoadFailed(guid, loadIndex);
                    }
                });
                return;
            }
            if (enumeration.included.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (session != sessionGeneration) {
                        return;
                    }
                    if (!isEnumerationCurrent(enumeration, feedConfig, generation, cacheEpoch)) {
                        if (canRetryStaleEnumeration()) {
                            olderPagingBoundsDirty = false;
                            attemptRounds = 0;
                            runAttempt();
                        } else {
                            finishOlderLoadFailed(guid, loadIndex);
                        }
                        return;
                    }
                    applyEnumeration(enumeration);
                    olderPagingBoundsDirty = false;
                    unreadTracker.clear();
                    loading = false;
                    store.setEndReached(true);
                    postFeedResults(guid, loadIndex, new ArrayList<>(), 2);
                    postFeedCount(guid);
                    flushInitialLoadWaiters(false);
                });
                return;
            }
            FeedTimelineLoader.OlderPage page = loader.loadOlderPage(enumeration.included, cursor, exhausted);
            ArrayList<MessageObject> objects = createMessageObjects(page.messages, page.users, page.chats);
            AndroidUtilities.runOnUIThread(() -> onOlderPageLoaded(session, enumeration, feedConfig, generation, cacheEpoch, page, guid, loadIndex, initial, objects));
        });
    }

    private void finishOlderLoadFailed(int guid, int loadIndex) {
        loading = false;
        postFeedResults(guid, loadIndex, new ArrayList<>(), 2, false, true);
        postFeedCount(guid);
        flushInitialLoadWaiters(true);
    }

    private void onOlderPageLoaded(int session, FeedTimelineLoader.ChannelEnumeration enumeration, FeedConfig feedConfig, int generation, int cacheEpoch, FeedTimelineLoader.OlderPage page, int guid, int loadIndex, boolean initial, ArrayList<MessageObject> objects) {
        if (session != sessionGeneration) {
            return;
        }
        if (!isEnumerationCurrent(enumeration, feedConfig, generation, cacheEpoch) && canRetryStaleEnumeration()) {
            olderPagingBoundsDirty = false;
            attemptRounds = 0;
            runAttempt();
            return;
        }
        if (olderPagingBoundsDirty) {
            olderPagingBoundsDirty = false;
            attemptRounds = 0;
            runAttempt();
            return;
        }
        applyEnumeration(enumeration);
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        pruneStaleExclusions(FeedConfig.getInstance(currentAccount), messagesController);
        if (page.failed) {
            finishOlderLoadFailed(guid, loadIndex);
            return;
        }
        store.getOldestCursor().set(page.last.date, page.last.uid, page.last.mid);
        if (initial && !page.first.isEmpty()) {
            store.getNewestCursor().set(page.first.date, page.first.uid, page.first.mid);
        }
        messagesController.putUsers(page.users, true);
        messagesController.putChats(page.chats, true);
        ArrayList<MessageObject> added = store.appendMessages(objects, false);
        if (added.isEmpty() && page.lastChunkRowCount == FeedTimelineLoader.CHUNK_SIZE) {
            runAttempt();
            return;
        }
        boolean endReached = !page.hasIncomplete && page.lastChunkRowCount < FeedTimelineLoader.CHUNK_SIZE;
        if (!added.isEmpty() || endReached || page.backfillCandidates.isEmpty() || attemptRounds >= 3) {
            loading = false;
            store.setEndReached(endReached);
            postFeedResults(guid, loadIndex, added, 2);
            postFeedCount(guid);
            flushInitialLoadWaiters(false);
            return;
        }
        attemptRounds++;
        backfill.startRound(page.backfillCandidates);
    }

    private void postFeedResults(int guid, int loadIndex, ArrayList<MessageObject> messages, int loadType) {
        postFeedResults(guid, loadIndex, messages, loadType, false, false);
    }

    /**
     * Same layout as MessagesController/HashtagSearchController messagesDidLoad (mode MODE_SEARCH),
     * plus [15] hasMoreNewer and [16] failed (read only by the feed ChatActivity).
     */
    private void postFeedResults(int guid, int loadIndex, ArrayList<MessageObject> messages, int loadType, boolean hasMoreNewer, boolean failed) {
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.messagesDidLoad, 0L, messages.size(), messages, false, 0, 0, 0, 0, loadType, true, guid, loadIndex, 0, 0, ChatActivity.MODE_SEARCH, hasMoreNewer, failed);
    }

    private void postFeedCount(int guid) {
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.hashtagSearchUpdated, guid, store.getCount(), store.isEndReached(), 0, 0, 0);
    }

    private boolean isEnumerationCurrent(FeedTimelineLoader.ChannelEnumeration enumeration, FeedConfig feedConfig, int generation, int cacheEpoch) {
        boolean current = loader.isEnumerationCurrent(enumeration) && enumeration.configGeneration == feedConfig.getGeneration() && enumeration.configGeneration == generation && enumeration.cacheEpoch == cacheEpoch;
        if (current) {
            staleEnumerationRetries = 0;
        }
        return current;
    }

    private boolean canRetryStaleEnumeration() {
        if (staleEnumerationRetries >= 3) {
            staleEnumerationRetries = 0;
            return false;
        }
        staleEnumerationRetries++;
        return true;
    }

    private void applyEnumeration(FeedTimelineLoader.ChannelEnumeration enumeration) {
        if (enumeration.failed) {
            return;
        }
        hasChannels = enumeration.hasChannels;
        hasIncludedChannels = !enumeration.included.isEmpty();
        cachedIncludedChannelCount = enumeration.included.size();
        for (int i = 0; i < enumeration.included.size(); i++) {
            FeedTimelineLoader.ChannelSnapshot snapshot = enumeration.included.get(i);
            int readMax = snapshot.readInboxMax;
            if (readMax <= 0 && snapshot.unreadCount <= 0) {
                readMax = snapshot.topMessage;
            }
            unreadTracker.applyReadInboxMax(snapshot.dialogId, readMax);
        }
    }

    private void flushInitialLoadWaiters(boolean failed) {
        if (initialLoadWaiters.isEmpty()) {
            return;
        }
        ArrayList<int[]> waiters = new ArrayList<>(initialLoadWaiters);
        initialLoadWaiters.clear();
        ArrayList<MessageObject> visible = store.getVisibleMessages();
        for (int i = 0; i < waiters.size(); i++) {
            int[] waiter = waiters.get(i);
            postFeedResults(waiter[0], waiter[1], visible, 0, false, failed);
            postFeedCount(waiter[0]);
        }
    }

    private void onBackfillRoundFinished() {
        if (loading) {
            runAttempt();
        }
    }

    public boolean loadNewer(int guid, int loadIndex) {
        ensureCurrentConfig();
        if (loadingNewer || store.getNewestCursor().isEmpty()) {
            return false;
        }
        loadingNewer = true;
        runLoadNewer(guid, loadIndex);
        return true;
    }

    private void runLoadNewer(int guid, int loadIndex) {
        int session = sessionGeneration;
        FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        int generation = feedConfig.getGeneration();
        int cacheEpoch = loader.getChannelCacheEpoch();
        FeedTimelineLoader.Cursor cursor = new FeedTimelineLoader.Cursor();
        cursor.set(store.getNewestCursor().date, store.getNewestCursor().uid, store.getNewestCursor().mid);
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            FeedTimelineLoader.ChannelEnumeration enumeration = loader.enumerateChannels(feedConfig, session, false);
            if (enumeration.failed) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (session != sessionGeneration) {
                        return;
                    }
                    if (!isEnumerationCurrent(enumeration, feedConfig, generation, cacheEpoch) && canRetryStaleEnumeration()) {
                        runLoadNewer(guid, loadIndex);
                    } else {
                        loadingNewer = false;
                        postNewerMessagesLoaded(guid, loadIndex, null, false, true);
                    }
                });
                return;
            }
            if (enumeration.included.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (session != sessionGeneration) {
                        return;
                    }
                    if (!isEnumerationCurrent(enumeration, feedConfig, generation, cacheEpoch) && canRetryStaleEnumeration()) {
                        newerPagingBoundsDirty = false;
                        runLoadNewer(guid, loadIndex);
                    } else {
                        newerPagingBoundsDirty = false;
                        loadingNewer = false;
                        postNewerMessagesLoaded(guid, loadIndex, null, false, false);
                        postFeedCount(guid);
                    }
                });
                return;
            }
            FeedTimelineLoader.NewerPage page = loader.loadNewerPage(enumeration.included, cursor);
            ArrayList<MessageObject> objects = createMessageObjects(page.messages, page.users, page.chats);
            AndroidUtilities.runOnUIThread(() -> onNewerPageLoaded(session, enumeration, feedConfig, generation, cacheEpoch, guid, loadIndex, page, objects));
        });
    }

    private void onNewerPageLoaded(int session, FeedTimelineLoader.ChannelEnumeration enumeration, FeedConfig feedConfig, int generation, int cacheEpoch, int guid, int loadIndex, FeedTimelineLoader.NewerPage page, ArrayList<MessageObject> objects) {
        if (session != sessionGeneration) {
            return;
        }
        if (!isEnumerationCurrent(enumeration, feedConfig, generation, cacheEpoch) && canRetryStaleEnumeration()) {
            newerPagingBoundsDirty = false;
            runLoadNewer(guid, loadIndex);
            return;
        }
        if (newerPagingBoundsDirty) {
            newerPagingBoundsDirty = false;
            if (store.getNewestCursor().isEmpty()) {
                loadingNewer = false;
                postNewerMessagesLoaded(guid, loadIndex, null, false, false);
                postFeedCount(guid);
                return;
            }
            runLoadNewer(guid, loadIndex);
            return;
        }
        loadingNewer = false;
        applyEnumeration(enumeration);
        if (page.failed) {
            postNewerMessagesLoaded(guid, loadIndex, null, false, true);
            return;
        }
        store.getNewestCursor().set(page.first.date, page.first.uid, page.first.mid);
        if (page.messages.isEmpty()) {
            postNewerMessagesLoaded(guid, loadIndex, null, page.hasMore, false);
            if (!page.hasMore) {
                postFeedCount(guid);
            }
            return;
        }
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        messagesController.putUsers(page.users, true);
        messagesController.putChats(page.chats, true);
        postNewerMessagesLoaded(guid, loadIndex, store.appendMessages(objects, true), page.hasMore, false);
        if (!page.hasMore) {
            postFeedCount(guid);
        }
        trimForInactiveCache();
    }

    private void postNewerMessagesLoaded(int guid, int loadIndex, ArrayList<MessageObject> messages, boolean hasMore, boolean failed) {
        ArrayList<MessageObject> result = new ArrayList<>();
        int loadType;
        if (messages == null || messages.isEmpty()) {
            loadType = 0;
        } else {
            result.addAll(messages);
            Collections.reverse(result);
            loadType = 1;
        }
        postFeedResults(guid, loadIndex, result, loadType, hasMore, failed);
    }

    private ArrayList<MessageObject> createMessageObjects(ArrayList<TLRPC.Message> messages, ArrayList<TLRPC.User> users, ArrayList<TLRPC.Chat> chats) {
        HashMap<Long, TLRPC.User> usersMap = new HashMap<>();
        HashMap<Long, TLRPC.Chat> chatsMap = new HashMap<>();
        for (int i = 0; i < users.size(); i++) {
            usersMap.put(users.get(i).id, users.get(i));
        }
        for (int i = 0; i < chats.size(); i++) {
            chatsMap.put(chats.get(i).id, chats.get(i));
        }
        ArrayList<MessageObject> result = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            result.add(new MessageObject(currentAccount, messages.get(i), null, usersMap, chatsMap, null, null, true, true, 0, false, false, false, SEARCH_FEED));
        }
        return result;
    }

    public void loadChannels(ChannelsCallback callback) {
        loadChannels(false, callback);
    }

    public void loadChannels(boolean force, ChannelsCallback callback) {
        FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        int session = sessionGeneration;
        int generation = feedConfig.getGeneration();
        int cacheEpoch = loader.getChannelCacheEpoch();
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            FeedTimelineLoader.ChannelEnumeration enumeration = loader.enumerateChannels(feedConfig, session, force);
            AndroidUtilities.runOnUIThread(() -> {
                if (session != sessionGeneration || !isEnumerationCurrent(enumeration, feedConfig, generation, cacheEpoch)) {
                    if (callback != null) {
                        callback.onChannels(new ArrayList<>(), 0, true, enumeration.configGeneration);
                    }
                    return;
                }
                applyEnumeration(enumeration);
                if (!enumeration.failed) {
                    MessagesController.getInstance(currentAccount).putChats(enumeration.channels, true);
                }
                if (callback != null) {
                    callback.onChannels(enumeration.channels, enumeration.included.size(), enumeration.failed, enumeration.configGeneration);
                }
            });
        });
    }

    private void pruneStaleExclusions(FeedConfig feedConfig, MessagesController messagesController) {
        HashSet<Long> stale = null;
        for (Long dialogId : feedConfig.getExcludedSnapshot()) {
            TLRPC.Chat chat = messagesController.getChat(-dialogId);
            if (chat != null && !isEligibleChannel(chat)) {
                if (stale == null) {
                    stale = new HashSet<>();
                }
                stale.add(dialogId);
            }
        }
        HashSet<Long> staleInCategories = null;
        for (FeedConfig.Category category : feedConfig.getCategories()) {
            for (Long dialogId : category.getChannels()) {
                TLRPC.Chat chat = messagesController.getChat(-dialogId);
                if (chat != null && !isEligibleChannel(chat)) {
                    if (staleInCategories == null) {
                        staleInCategories = new HashSet<>();
                    }
                    staleInCategories.add(dialogId);
                }
            }
        }
        if (stale != null) {
            feedConfig.removeExcluded(stale);
        }
        if (staleInCategories != null) {
            feedConfig.removeFromCategories(staleInCategories);
        }
        if (stale != null || staleInCategories != null) {
            markConfigApplied();
        }
    }

    /** exteraGram 13.1.1: selects a feed category (0 is All); the feed reloads through the config generation. */
    public boolean selectCategory(int categoryId) {
        if (!FeedConfig.getInstance(currentAccount).selectCategory(categoryId)) {
            return false;
        }
        notifyCategoriesChanged();
        return true;
    }

    /** Refreshes the feed unread badge after the shown channels changed (category switched / edited). */
    public void notifyCategoriesChanged() {
        int generation = FeedConfig.getInstance(currentAccount).getGeneration();
        if (badgeConfigGeneration != generation) {
            badgeConfigGeneration = generation;
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_READ_DIALOG_MESSAGE);
        }
    }

    public void replaceMessage(MessageObject oldObject, MessageObject newObject) {
        store.replaceMessage(oldObject, newObject);
    }

    public ArrayList<Integer> deleteMessages(long dialogId, ArrayList<Integer> ids) {
        boolean[] changed = new boolean[1];
        ArrayList<Integer> result = store.deleteMessages(dialogId, ids, changed);
        if (changed[0]) {
            onFeedRowsRemoved();
        }
        return result;
    }

    public ArrayList<Integer> deleteHistory(long dialogId, int maxId) {
        boolean[] changed = new boolean[1];
        ArrayList<Integer> result = store.deleteHistory(dialogId, maxId, changed);
        if (changed[0]) {
            onFeedRowsRemoved();
        }
        return result;
    }

    private void onFeedRowsRemoved() {
        if (loading) {
            olderPagingBoundsDirty = true;
        }
        if (loadingNewer) {
            newerPagingBoundsDirty = true;
        }
    }

    public ArrayList<MessageObject> updateViews(LongSparseArray<SparseIntArray> views, LongSparseArray<SparseIntArray> forwards, LongSparseArray<SparseArray<TLRPC.MessageReplies>> replies, boolean increment) {
        ArrayList<MessageObject> updated = new ArrayList<>();
        updateCounters(views, true, updated);
        updateCounters(forwards, false, updated);
        updateReplies(replies, increment, updated);
        return updated;
    }

    private void updateCounters(LongSparseArray<SparseIntArray> counters, boolean views, ArrayList<MessageObject> updated) {
        if (counters == null) {
            return;
        }
        for (int i = 0; i < counters.size(); i++) {
            long dialogId = counters.keyAt(i);
            SparseIntArray values = counters.valueAt(i);
            for (int j = 0; j < values.size(); j++) {
                MessageObject messageObject = getMessage(dialogId, values.keyAt(j));
                if (messageObject == null) {
                    continue;
                }
                int value = values.valueAt(j);
                TLRPC.Message message = messageObject.messageOwner;
                if (views) {
                    if (value > message.views) {
                        message.views = value;
                        addUpdated(updated, messageObject);
                    }
                } else if (value > message.forwards) {
                    message.forwards = value;
                    addUpdated(updated, messageObject);
                }
            }
        }
    }

    private void updateReplies(LongSparseArray<SparseArray<TLRPC.MessageReplies>> replies, boolean increment, ArrayList<MessageObject> updated) {
        if (replies == null) {
            return;
        }
        for (int i = 0; i < replies.size(); i++) {
            long dialogId = replies.keyAt(i);
            SparseArray<TLRPC.MessageReplies> values = replies.valueAt(i);
            for (int j = 0; j < values.size(); j++) {
                MessageObject messageObject = getMessage(dialogId, values.keyAt(j));
                TLRPC.MessageReplies value = values.valueAt(j);
                if (messageObject == null || value == null) {
                    continue;
                }
                TLRPC.Message message = messageObject.messageOwner;
                if (increment) {
                    if (message.replies == null) {
                        message.replies = new TLRPC.TL_messageReplies();
                    }
                    message.replies.replies += value.replies;
                    for (int k = 0; k < value.recent_repliers.size(); k++) {
                        message.replies.recent_repliers.remove(value.recent_repliers.get(k));
                    }
                    message.replies.recent_repliers.addAll(0, value.recent_repliers);
                    while (message.replies.recent_repliers.size() > 3) {
                        message.replies.recent_repliers.remove(0);
                    }
                } else {
                    TLRPC.MessageReplies current = message.replies;
                    if (current == null || value.replies_pts > current.replies_pts || value.read_max_id > current.read_max_id || value.max_id > current.max_id) {
                        message.replies = value;
                    }
                }
                messageObject.animateComments = true;
                addUpdated(updated, messageObject);
            }
        }
    }

    private static void addUpdated(ArrayList<MessageObject> list, MessageObject messageObject) {
        if (!list.contains(messageObject)) {
            list.add(messageObject);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagesDidLoad) {
            backfill.onMessagesDidLoad(args);
        } else if (id == NotificationCenter.loadingMessagesFailed) {
            backfill.onLoadingMessagesFailed(args);
        } else if (id == NotificationCenter.messagesDeleted) {
            if (isUiActive() || (Boolean) args[2]) {
                return;
            }
            long channelId = (Long) args[1];
            if (channelId == 0) {
                return;
            }
            long dialogId = channelId > 0 ? -channelId : channelId;
            //noinspection unchecked
            deleteMessages(dialogId, (ArrayList<Integer>) args[0]);
        } else if (id == NotificationCenter.historyCleared) {
            if (isUiActive()) {
                return;
            }
            long dialogId = (Long) args[0];
            if (DialogObject.isChatDialog(dialogId)) {
                deleteHistory(dialogId, (Integer) args[1]);
            }
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            if (isUiActive() || (Boolean) args[2] || store.isEmpty() || store.getNewestCursor().isEmpty() || !isIncludedChannelPost((Long) args[0])) {
                return;
            }
            scheduleClosedRefresh();
        }
    }

    private void scheduleClosedRefresh() {
        if (closedRefreshScheduled) {
            return;
        }
        closedRefreshScheduled = true;
        AndroidUtilities.runOnUIThread(closedRefreshRunnable, 1000L);
    }

    private void runClosedRefresh() {
        closedRefreshScheduled = false;
        if (isUiActive() || loadingNewer || store.isEmpty() || store.getNewestCursor().isEmpty()) {
            return;
        }
        loadNewer(closedRefreshGuid, 0);
    }
}
