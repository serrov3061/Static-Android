package com.exteragram.messenger.feed;

import android.text.TextUtils;

import androidx.collection.LongSparseArray;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * exteraGram feed: reads the merged timeline of the subscribed channels from the local database
 * (dialogs, messages_v2, messages_holes). Runs on the MessagesStorage queue. No network here: channels
 * without a cached history are reported as backfill candidates and loaded by FeedBackfillCoordinator
 * through the regular messages.getHistory.
 */
final class FeedTimelineLoader {

    static final int CHUNK_SIZE = 30;
    private static final int NEWER_PAGE_SIZE = 50;
    private static final int WINDOW_LIMIT = 500;
    private static final int MAX_UNREAD_ROWS = 200;

    private final AtomicInteger channelCacheEpoch = new AtomicInteger();
    private volatile ChannelSet channelSetCache;
    private final int currentAccount;

    static final class ChannelEnumeration {
        int cacheEpoch;
        int configGeneration;
        boolean failed;
        boolean hasChannels;
        final ArrayList<ChannelSnapshot> included = new ArrayList<>();
        final ArrayList<TLRPC.Chat> channels = new ArrayList<>();
    }

    static final class NewerPage {
        boolean failed;
        boolean hasMore;
        final ArrayList<TLRPC.Message> messages = new ArrayList<>();
        final ArrayList<TLRPC.User> users = new ArrayList<>();
        final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        final Cursor first = new Cursor();
    }

    static final class OlderPage {
        boolean failed;
        boolean hasIncomplete;
        int lastChunkRowCount;
        final ArrayList<TLRPC.Message> messages = new ArrayList<>();
        final ArrayList<TLRPC.User> users = new ArrayList<>();
        final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        final ArrayList<long[]> backfillCandidates = new ArrayList<>();
        final Cursor last = new Cursor();
        final Cursor first = new Cursor();
    }

    static final class WindowPage {
        boolean failed;
        boolean truncated;
        final ArrayList<TLRPC.Message> messages = new ArrayList<>();
        final ArrayList<TLRPC.User> users = new ArrayList<>();
        final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
    }

    public static final class Cursor {
        int date;
        int mid;
        long uid;

        public boolean isEmpty() {
            return date == 0;
        }

        public void set(int date, long uid, int mid) {
            this.date = date;
            this.uid = uid;
            this.mid = mid;
        }
    }

    static final class ChannelSnapshot {
        int depthDate;
        int depthMid;
        final long dialogId;
        boolean hasCached;
        boolean hasHole;
        int holeEnd;
        boolean incomplete;
        boolean localStartReached;
        final int readInboxMax;
        final int topMessage;
        final int unreadCount;

        ChannelSnapshot(long dialogId, int readInboxMax, int unreadCount, int topMessage) {
            this.dialogId = dialogId;
            this.readInboxMax = readInboxMax;
            this.unreadCount = unreadCount;
            this.topMessage = topMessage;
        }
    }

    static final class ChannelSet {
        final int configGen;
        boolean failed;
        boolean hasChannels;
        final int sessionGen;
        final ArrayList<long[]> includedRows = new ArrayList<>();
        final ArrayList<TLRPC.Chat> channels = new ArrayList<>();

        ChannelSet(int sessionGen, int configGen) {
            this.sessionGen = sessionGen;
            this.configGen = configGen;
        }
    }

    FeedTimelineLoader(int account) {
        currentAccount = account;
    }

    public synchronized void invalidateChannelCache() {
        channelCacheEpoch.incrementAndGet();
        channelSetCache = null;
    }

    public ChannelEnumeration enumerateChannels(FeedConfig feedConfig, int sessionGen, boolean force) {
        ChannelSet set;
        int epoch;
        int attempt = 0;
        while (true) {
            synchronized (this) {
                set = channelSetCache;
                epoch = channelCacheEpoch.get();
            }
            FeedConfig.Snapshot snapshot = feedConfig.snapshot();
            int generation = snapshot.getGeneration();
            if (force || set == null || set.sessionGen != sessionGen || set.configGen != generation) {
                set = buildChannelSet(snapshot.getIncludeArchived(), snapshot, sessionGen, generation);
                if (!set.failed) {
                    synchronized (this) {
                        if (epoch == channelCacheEpoch.get()) {
                            channelSetCache = set;
                        }
                    }
                }
            }
            if (set.failed || attempt >= 3) {
                break;
            }
            synchronized (this) {
                if (epoch == channelCacheEpoch.get()) {
                    break;
                }
            }
            attempt++;
            force = true;
        }
        ChannelEnumeration enumeration = new ChannelEnumeration();
        enumeration.hasChannels = set.hasChannels;
        enumeration.failed = set.failed;
        enumeration.configGeneration = set.configGen;
        enumeration.cacheEpoch = epoch;
        enumeration.channels.addAll(set.channels);
        for (int i = 0; i < set.includedRows.size(); i++) {
            long[] row = set.includedRows.get(i);
            enumeration.included.add(new ChannelSnapshot(row[0], (int) row[1], (int) row[2], (int) row[3]));
        }
        return enumeration;
    }

    public synchronized int getChannelCacheEpoch() {
        return channelCacheEpoch.get();
    }

    public synchronized boolean isEnumerationCurrent(ChannelEnumeration enumeration) {
        return enumeration != null && enumeration.cacheEpoch == channelCacheEpoch.get();
    }

    private ChannelSet buildChannelSet(boolean includeArchived, FeedConfig.Snapshot filter, int sessionGen, int configGen) {
        MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
        ChannelSet set = new ChannelSet(sessionGen, configGen);
        ArrayList<long[]> rows = new ArrayList<>();
        ArrayList<Long> chatIds = new ArrayList<>();
        try {
            SQLiteCursor cursor = storage.getDatabase().queryFinalized("SELECT did, inbox_max, unread_count, last_mid, folder_id FROM dialogs WHERE did < 0");
            while (cursor.next()) {
                long did = cursor.longValue(0);
                if (DialogObject.isChatDialog(did)) {
                    rows.add(new long[]{did, cursor.intValue(1), cursor.intValue(2), cursor.intValue(3), cursor.intValue(4)});
                    chatIds.add(-did);
                }
            }
            cursor.dispose();
            if (rows.isEmpty()) {
                return set;
            }
            ArrayList<TLRPC.Chat> chats = new ArrayList<>();
            storage.getChatsInternal(TextUtils.join(",", chatIds), chats);
            LongSparseArray<TLRPC.Chat> chatsById = new LongSparseArray<>();
            for (int i = 0; i < chats.size(); i++) {
                chatsById.put(chats.get(i).id, chats.get(i));
            }
            for (int i = 0; i < rows.size(); i++) {
                long[] row = rows.get(i);
                long did = row[0];
                TLRPC.Chat chat = chatsById.get(-did);
                if (FeedController.isEligibleChannel(chat) && (row[4] != 1 || includeArchived)) {
                    set.hasChannels = true;
                    set.channels.add(chat);
                    if (filter.isIncluded(did)) { // exteraGram 13.1.1: All or the selected category
                        set.includedRows.add(new long[]{did, row[1], row[2], row[3]});
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            set.failed = true;
        }
        return set;
    }

    public OlderPage loadOlderPage(ArrayList<ChannelSnapshot> channels, Cursor cursor, HashSet<Long> exhausted) {
        OlderPage page = new OlderPage();
        boolean initial = cursor.isEmpty();
        page.last.set(cursor.date, cursor.uid, cursor.mid);
        try {
            ArrayList<Long> dialogIds = new ArrayList<>(channels.size());
            for (int i = 0; i < channels.size(); i++) {
                dialogIds.add(channels.get(i).dialogId);
            }
            String idsString = TextUtils.join(",", dialogIds);
            MessagesStorage storage = MessagesStorage.getInstance(currentAccount);

            HashMap<Long, Integer> holes = new HashMap<>();
            SQLiteCursor holesCursor = storage.getDatabase().queryFinalized("SELECT uid, max(end) FROM messages_holes WHERE uid IN (" + idsString + ") GROUP BY uid");
            while (holesCursor.next()) {
                holes.put(holesCursor.longValue(0), holesCursor.intValue(1));
            }
            holesCursor.dispose();
            for (int i = 0; i < channels.size(); i++) {
                ChannelSnapshot snapshot = channels.get(i);
                Integer end = holes.get(snapshot.dialogId);
                snapshot.hasHole = end != null;
                snapshot.holeEnd = end != null ? end : 0;
            }
            loadChannelDepths(storage, channels);

            int minDate = 0;
            for (int i = 0; i < channels.size(); i++) {
                ChannelSnapshot snapshot = channels.get(i);
                snapshot.incomplete = !snapshot.localStartReached && !exhausted.contains(snapshot.dialogId);
                if (snapshot.incomplete) {
                    page.hasIncomplete = true;
                    minDate = Math.max(minDate, snapshot.depthDate);
                    long offsetId;
                    if (snapshot.hasCached) {
                        offsetId = snapshot.depthMid;
                    } else {
                        int top = Math.max(snapshot.holeEnd, snapshot.topMessage);
                        offsetId = top > 0 ? top + 1 : 0;
                    }
                    page.backfillCandidates.add(new long[]{snapshot.dialogId, offsetId, snapshot.depthDate});
                }
            }
            Collections.sort(page.backfillCandidates, (a, b) -> Long.compare(b[2], a[2]));
            if (minDate == Integer.MAX_VALUE) {
                return page;
            }

            Cursor unreadBoundary = initial ? findUnreadBoundary(storage, channels, minDate) : null;
            ArrayList<Long> usersToLoad = new ArrayList<>();
            ArrayList<Long> chatsToLoad = new ArrayList<>();
            int total = 0;
            while (true) {
                int count = loadChunk(storage, idsString, minDate, page, usersToLoad, chatsToLoad);
                page.lastChunkRowCount = count;
                total += count;
                if (count < CHUNK_SIZE || unreadBoundary == null || total >= MAX_UNREAD_ROWS) {
                    break;
                }
                if (compareDesc(page.last, unreadBoundary) >= 0) {
                    break;
                }
            }
            completeTrailingAlbum(storage, page, usersToLoad, chatsToLoad);
            for (int i = 0; i < dialogIds.size(); i++) {
                long chatId = -dialogIds.get(i);
                if (!chatsToLoad.contains(chatId)) {
                    chatsToLoad.add(chatId);
                }
            }
            if (!usersToLoad.isEmpty()) {
                storage.getUsersInternal(usersToLoad, page.users);
            }
            if (!chatsToLoad.isEmpty()) {
                storage.getChatsInternal(TextUtils.join(",", chatsToLoad), page.chats);
            }
        } catch (Exception e) {
            FileLog.e(e);
            page.failed = true;
        }
        clusterGroupedMessages(page.messages);
        return page;
    }

    private int loadChunk(MessagesStorage storage, String idsString, int minDate, OlderPage page, ArrayList<Long> usersToLoad, ArrayList<Long> chatsToLoad) throws Exception {
        StringBuilder sb = new StringBuilder("SELECT data, mid, date, uid FROM messages_v2 WHERE uid IN (");
        sb.append(idsString);
        sb.append(") AND mid > 0");
        if (minDate > 0) {
            sb.append(" AND date >= ").append(minDate);
        }
        if (!page.last.isEmpty()) {
            appendCursorBound(sb, page.last, true, false);
        }
        sb.append(" ORDER BY date DESC, uid DESC, mid DESC LIMIT ").append(CHUNK_SIZE);
        int count = 0;
        SQLiteCursor cursor = storage.getDatabase().queryFinalized(sb.toString());
        try {
            while (cursor.next()) {
                count++;
                page.last.set(cursor.intValue(2), cursor.longValue(3), cursor.intValue(1));
                if (page.first.isEmpty()) {
                    page.first.set(page.last.date, page.last.uid, page.last.mid);
                }
                TLRPC.Message message = readMessage(cursor);
                if (message != null) {
                    page.messages.add(message);
                    MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null);
                }
            }
        } finally {
            cursor.dispose();
        }
        return count;
    }

    private Cursor findUnreadBoundary(MessagesStorage storage, ArrayList<ChannelSnapshot> channels, int minDate) {
        StringBuilder sb = new StringBuilder();
        Cursor result = null;
        int count = 0;
        for (int i = 0; i < channels.size(); i++) {
            ChannelSnapshot snapshot = channels.get(i);
            if (snapshot.topMessage > snapshot.readInboxMax || snapshot.unreadCount > 0) {
                if (sb.length() > 0) {
                    sb.append(" OR ");
                }
                sb.append("uid = ").append(snapshot.dialogId).append(" AND mid > ").append(snapshot.readInboxMax);
                count++;
            }
            if (count > 0 && (count == 64 || i == channels.size() - 1)) {
                Cursor boundary = queryUnreadBoundary(storage, sb, minDate);
                if (boundary != null && (result == null || compareDesc(boundary, result) > 0)) {
                    result = boundary;
                }
                sb.setLength(0);
                count = 0;
            }
        }
        return result;
    }

    private Cursor queryUnreadBoundary(MessagesStorage storage, StringBuilder condition, int minDate) {
        StringBuilder sb = new StringBuilder("SELECT date, uid, mid FROM messages_v2 WHERE mid > 0 AND (");
        sb.append(condition).append(")");
        if (minDate > 0) {
            sb.append(" AND date >= ").append(minDate);
        }
        sb.append(" ORDER BY date ASC, uid ASC, mid ASC LIMIT 1");
        try {
            SQLiteCursor cursor = storage.getDatabase().queryFinalized(sb.toString());
            try {
                if (!cursor.next()) {
                    return null;
                }
                Cursor result = new Cursor();
                result.set(cursor.intValue(0), cursor.longValue(1), cursor.intValue(2));
                return result;
            } finally {
                cursor.dispose();
            }
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void appendCursorBound(StringBuilder sb, Cursor cursor, boolean older, boolean inclusive) {
        String op = older ? "<" : ">";
        sb.append(" AND (date ").append(op).append(' ').append(cursor.date)
            .append(" OR date = ").append(cursor.date)
            .append(" AND (uid ").append(op).append(' ').append(cursor.uid)
            .append(" OR uid = ").append(cursor.uid)
            .append(" AND mid ").append(op).append(inclusive ? "= " : " ").append(cursor.mid)
            .append("))");
    }

    /** Negative when a is newer than b in the descending timeline order. */
    private static int compareDesc(Cursor a, Cursor b) {
        if (a.date != b.date) {
            return a.date > b.date ? -1 : 1;
        }
        if (a.uid != b.uid) {
            return a.uid > b.uid ? -1 : 1;
        }
        return -Integer.compare(a.mid, b.mid);
    }

    public NewerPage loadNewerPage(ArrayList<ChannelSnapshot> channels, Cursor cursor) {
        NewerPage page = new NewerPage();
        page.first.set(cursor.date, cursor.uid, cursor.mid);
        try {
            ArrayList<Long> dialogIds = new ArrayList<>(channels.size());
            for (int i = 0; i < channels.size(); i++) {
                dialogIds.add(channels.get(i).dialogId);
            }
            MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
            ArrayList<Long> usersToLoad = new ArrayList<>();
            ArrayList<Long> chatsToLoad = new ArrayList<>();
            StringBuilder sb = new StringBuilder("SELECT data, mid, date, uid FROM messages_v2 WHERE uid IN (");
            sb.append(TextUtils.join(",", dialogIds)).append(") AND mid > 0");
            appendCursorBound(sb, cursor, false, false);
            sb.append(" ORDER BY date ASC, uid ASC, mid ASC LIMIT ").append(NEWER_PAGE_SIZE);
            int count = 0;
            SQLiteCursor dbCursor = storage.getDatabase().queryFinalized(sb.toString());
            try {
                while (dbCursor.next()) {
                    count++;
                    page.first.set(dbCursor.intValue(2), dbCursor.longValue(3), dbCursor.intValue(1));
                    TLRPC.Message message = readMessage(dbCursor);
                    if (message != null) {
                        page.messages.add(message);
                        MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null);
                    }
                }
            } finally {
                dbCursor.dispose();
            }
            page.hasMore = count == NEWER_PAGE_SIZE;
            if (!usersToLoad.isEmpty()) {
                storage.getUsersInternal(usersToLoad, page.users);
            }
            if (!chatsToLoad.isEmpty()) {
                storage.getChatsInternal(TextUtils.join(",", chatsToLoad), page.chats);
            }
        } catch (Exception e) {
            FileLog.e(e);
            page.failed = true;
        }
        clusterGroupedMessages(page.messages);
        return page;
    }

    public WindowPage loadChannelWindow(ArrayList<Long> dialogIds, Cursor newest, Cursor oldest) {
        WindowPage page = new WindowPage();
        if (dialogIds.isEmpty() || newest.isEmpty() || oldest.isEmpty()) {
            return page;
        }
        try {
            MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
            ArrayList<Long> usersToLoad = new ArrayList<>();
            ArrayList<Long> chatsToLoad = new ArrayList<>();
            StringBuilder sb = new StringBuilder("SELECT data, mid, date, uid FROM messages_v2 WHERE uid IN (");
            sb.append(TextUtils.join(",", dialogIds)).append(") AND mid > 0");
            appendCursorBound(sb, newest, true, true);
            appendCursorBound(sb, oldest, false, true);
            sb.append(" ORDER BY date DESC, uid DESC, mid DESC LIMIT ").append(WINDOW_LIMIT + 1);
            int count = 0;
            SQLiteCursor cursor = storage.getDatabase().queryFinalized(sb.toString());
            try {
                while (cursor.next()) {
                    count++;
                    if (count > WINDOW_LIMIT) {
                        page.truncated = true;
                        break;
                    }
                    TLRPC.Message message = readMessage(cursor);
                    if (message != null) {
                        page.messages.add(message);
                        MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null);
                    }
                }
            } finally {
                cursor.dispose();
            }
            if (!usersToLoad.isEmpty()) {
                storage.getUsersInternal(usersToLoad, page.users);
            }
            if (!chatsToLoad.isEmpty()) {
                storage.getChatsInternal(TextUtils.join(",", chatsToLoad), page.chats);
            }
        } catch (Exception e) {
            FileLog.e(e);
            page.failed = true;
            page.messages.clear();
            page.users.clear();
            page.chats.clear();
        }
        clusterGroupedMessages(page.messages);
        return page;
    }

    private void completeTrailingAlbum(MessagesStorage storage, OlderPage page, ArrayList<Long> usersToLoad, ArrayList<Long> chatsToLoad) throws Exception {
        if (page.messages.isEmpty()) {
            return;
        }
        TLRPC.Message last = page.messages.get(page.messages.size() - 1);
        if (last.grouped_id == 0) {
            return;
        }
        SQLiteCursor cursor = storage.getDatabase().queryFinalized("SELECT data, mid, date, uid FROM messages_v2 WHERE uid = " + last.dialog_id + " AND mid > 0 AND mid < " + last.id + " ORDER BY date DESC, mid DESC LIMIT 9");
        try {
            while (cursor.next()) {
                TLRPC.Message message = readMessage(cursor);
                if (message == null || message.grouped_id != last.grouped_id) {
                    break;
                }
                page.messages.add(message);
                MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null);
            }
        } finally {
            cursor.dispose();
        }
    }

    private TLRPC.Message readMessage(SQLiteCursor cursor) throws Exception {
        NativeByteBuffer data = cursor.byteBufferValue(0);
        if (data == null) {
            return null;
        }
        TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
        if (message == null) {
            data.reuse();
            return null;
        }
        message.readAttachPath(data, UserConfig.getInstance(currentAccount).clientUserId);
        data.reuse();
        if (message instanceof TLRPC.TL_messageEmpty || message.action != null) {
            return null;
        }
        message.id = cursor.intValue(1);
        message.date = cursor.intValue(2);
        message.dialog_id = cursor.longValue(3);
        return message;
    }

    private static void loadChannelDepths(MessagesStorage storage, ArrayList<ChannelSnapshot> channels) throws Exception {
        LongSparseArray<ChannelSnapshot> byDialog = new LongSparseArray<>(channels.size());
        for (int i = 0; i < channels.size(); i++) {
            ChannelSnapshot snapshot = channels.get(i);
            snapshot.depthMid = 0;
            snapshot.depthDate = Integer.MAX_VALUE;
            snapshot.hasCached = false;
            snapshot.localStartReached = false;
            byDialog.put(snapshot.dialogId, snapshot);
        }
        int index = 0;
        while (index < channels.size()) {
            int end = Math.min(index + 64, channels.size());
            StringBuilder sb = new StringBuilder();
            for (; index < end; index++) {
                if (sb.length() > 0) {
                    sb.append(" UNION ALL ");
                }
                ChannelSnapshot snapshot = channels.get(index);
                int fromMid = Math.max(snapshot.holeEnd, 1);
                sb.append("SELECT uid, mid, date FROM (SELECT uid, mid, date FROM messages_v2 WHERE uid = ")
                    .append(snapshot.dialogId)
                    .append(" AND mid >= ").append(fromMid)
                    .append(" ORDER BY date ASC, mid ASC LIMIT 1)");
            }
            SQLiteCursor cursor = storage.getDatabase().queryFinalized(sb.toString());
            try {
                while (cursor.next()) {
                    ChannelSnapshot snapshot = byDialog.get(cursor.longValue(0));
                    if (snapshot != null) {
                        snapshot.depthMid = cursor.intValue(1);
                        snapshot.depthDate = cursor.intValue(2);
                        snapshot.hasCached = true;
                    }
                }
            } finally {
                cursor.dispose();
            }
        }
        for (int i = 0; i < channels.size(); i++) {
            ChannelSnapshot snapshot = channels.get(i);
            snapshot.localStartReached = !snapshot.hasHole && snapshot.hasCached;
        }
    }

    private static void clusterGroupedMessages(ArrayList<TLRPC.Message> messages) {
        if (messages.size() < 3) {
            return;
        }
        HashMap<Long, ArrayList<TLRPC.Message>> groups = new HashMap<>();
        boolean split = false;
        for (int i = 0; i < messages.size(); i++) {
            long groupedId = messages.get(i).grouped_id;
            if (groupedId != 0) {
                ArrayList<TLRPC.Message> group = groups.get(groupedId);
                if (group == null) {
                    group = new ArrayList<>();
                    groups.put(groupedId, group);
                } else {
                    split = true;
                }
                group.add(messages.get(i));
            }
        }
        if (!split) {
            return;
        }
        ArrayList<TLRPC.Message> result = new ArrayList<>(messages.size());
        HashSet<Long> added = new HashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            TLRPC.Message message = messages.get(i);
            if (message.grouped_id == 0) {
                result.add(message);
            } else if (added.add(message.grouped_id)) {
                result.addAll(groups.get(message.grouped_id));
            }
        }
        messages.clear();
        messages.addAll(result);
    }
}
