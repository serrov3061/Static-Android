package com.exteragram.messenger.feed;

import org.telegram.messenger.MessageObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;

/**
 * exteraGram feed: the loaded timeline (newest first), hidden channels and paging cursors.
 * UI thread only.
 */
public final class FeedStore {

    private int count;
    private boolean endReached;
    private final ArrayList<MessageObject> messages = new ArrayList<>();
    private final FeedMessageIdentityMap identityMap = new FeedMessageIdentityMap();
    private final HashSet<Long> hiddenDialogIds = new HashSet<>();
    private final FeedTimelineLoader.Cursor oldestCursor = new FeedTimelineLoader.Cursor();
    private final FeedTimelineLoader.Cursor newestCursor = new FeedTimelineLoader.Cursor();

    public ArrayList<MessageObject> getMessages() {
        return messages;
    }

    public ArrayList<MessageObject> getVisibleMessages() {
        if (hiddenDialogIds.isEmpty()) {
            return new ArrayList<>(messages);
        }
        ArrayList<MessageObject> result = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            MessageObject messageObject = messages.get(i);
            if (messageObject != null && !hiddenDialogIds.contains(messageObject.getDialogId())) {
                result.add(messageObject);
            }
        }
        return result;
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }

    public int getVisibleCount() {
        if (hiddenDialogIds.isEmpty()) {
            return messages.size();
        }
        int result = 0;
        for (int i = 0; i < messages.size(); i++) {
            MessageObject messageObject = messages.get(i);
            if (messageObject != null && !hiddenDialogIds.contains(messageObject.getDialogId())) {
                result++;
            }
        }
        return result;
    }

    public boolean hasMessagesForDialog(long dialogId) {
        for (int i = 0; i < messages.size(); i++) {
            MessageObject messageObject = messages.get(i);
            if (messageObject != null && messageObject.getDialogId() == dialogId) {
                return true;
            }
        }
        return false;
    }

    public HashSet<Long> getLoadedDialogIds() {
        HashSet<Long> result = new HashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            MessageObject messageObject = messages.get(i);
            if (messageObject != null) {
                result.add(messageObject.getDialogId());
            }
        }
        return result;
    }

    public HashSet<Long> getHiddenSnapshot() {
        return new HashSet<>(hiddenDialogIds);
    }

    public boolean setHidden(long dialogId, boolean hidden) {
        boolean changed = hidden ? hiddenDialogIds.add(dialogId) : hiddenDialogIds.remove(dialogId);
        if (changed) {
            updateCount();
        }
        return changed;
    }

    public boolean applyIncludedDialogs(HashSet<Long> included) {
        HashSet<Long> loaded = getLoadedDialogIds();
        boolean changed = false;
        for (Long id : loaded) {
            if (!included.contains(id)) {
                changed |= hiddenDialogIds.add(id);
            }
        }
        Iterator<Long> it = hiddenDialogIds.iterator();
        while (it.hasNext()) {
            Long id = it.next();
            if (included.contains(id) || !loaded.contains(id)) {
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            updateCount();
        }
        return changed;
    }

    public FeedTimelineLoader.Cursor getOldestCursor() {
        return oldestCursor;
    }

    public FeedTimelineLoader.Cursor getNewestCursor() {
        return newestCursor;
    }

    public boolean isEndReached() {
        return endReached;
    }

    public void setEndReached(boolean value) {
        endReached = value;
        updateCount();
    }

    public int getCount() {
        return count;
    }

    private void updateCount() {
        int visible = getVisibleCount();
        count = visible == 0 ? 0 : visible + (endReached ? 0 : 3);
    }

    public void clear() {
        messages.clear();
        identityMap.clear();
        hiddenDialogIds.clear();
        endReached = false;
        count = 0;
        oldestCursor.set(0, 0, 0);
        newestCursor.set(0, 0, 0);
    }

    public ArrayList<MessageObject> appendMessages(ArrayList<MessageObject> list, boolean newer) {
        ArrayList<MessageObject> added = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            MessageObject messageObject = list.get(i);
            if (identityMap.register(messageObject)) {
                added.add(messageObject);
            }
        }
        if (newer) {
            ArrayList<MessageObject> reversed = new ArrayList<>(added);
            Collections.reverse(reversed);
            messages.addAll(0, reversed);
        } else {
            messages.addAll(added);
        }
        updateCount();
        return added;
    }

    public ArrayList<MessageObject> mergeRows(ArrayList<MessageObject> list) {
        ArrayList<MessageObject> added = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            MessageObject messageObject = list.get(i);
            if (identityMap.register(messageObject)) {
                added.add(messageObject);
            }
        }
        int insertIndex = 0;
        int i = 0;
        while (i < added.size()) {
            MessageObject first = added.get(i);
            int end = i + 1;
            long groupId = first.getGroupId();
            while (groupId != 0 && end < added.size() && added.get(end).getGroupId() == groupId && added.get(end).getDialogId() == first.getDialogId()) {
                end++;
            }
            insertIndex = findMergeIndex(first, insertIndex);
            while (i < end) {
                messages.add(insertIndex, added.get(i));
                i++;
                insertIndex++;
            }
        }
        updateCount();
        return added;
    }

    private int findMergeIndex(MessageObject messageObject, int from) {
        int i = from;
        MessageObject current;
        while (i < messages.size() && ((current = messages.get(i)) == null || compareTimeline(current.messageOwner.date, current.getDialogId(), current.getRealId(), messageObject.messageOwner.date, messageObject.getDialogId(), messageObject.getRealId()) >= 0)) {
            i++;
        }
        while (i > 0 && i < messages.size()) {
            MessageObject prev = messages.get(i - 1);
            MessageObject next = messages.get(i);
            if (prev == null || next == null || prev.getGroupId() == 0 || prev.getGroupId() != next.getGroupId() || prev.getDialogId() != next.getDialogId()) {
                break;
            }
            i++;
        }
        return i;
    }

    public void replaceMessage(MessageObject oldObject, MessageObject newObject) {
        if (oldObject == null || newObject == null) {
            return;
        }
        int index = messages.indexOf(oldObject);
        if (index >= 0) {
            messages.set(index, newObject);
        }
        identityMap.replace(newObject);
    }

    public ArrayList<Integer> deleteMessages(long dialogId, ArrayList<Integer> ids, boolean[] changedOut) {
        ArrayList<Integer> removedIds = new ArrayList<>();
        if (ids == null) {
            return removedIds;
        }
        HashSet<Integer> idSet = new HashSet<>(ids);
        HashSet<Integer> seen = new HashSet<>();
        boolean changed = false;
        for (int i = 0; i < ids.size(); i++) {
            MessageObject messageObject = identityMap.getByRealId(dialogId, ids.get(i));
            if (messageObject != null) {
                messages.remove(messageObject);
                purgeRow(messageObject, removedIds, seen);
                changed = true;
            }
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageObject messageObject = messages.get(i);
            if (messageObject != null && messageObject.getDialogId() == dialogId && idSet.contains(messageObject.getRealId())) {
                messages.remove(i);
                purgeRow(messageObject, removedIds, seen);
                changed = true;
            }
        }
        if (changed) {
            if (!hasMessagesForDialog(dialogId)) {
                hiddenDialogIds.remove(dialogId);
            }
            onRowsRemoved();
        }
        changedOut[0] = changed;
        return removedIds;
    }

    public ArrayList<Integer> deleteHistory(long dialogId, int maxId, boolean[] changedOut) {
        ArrayList<Integer> removedIds = new ArrayList<>();
        HashSet<Integer> seen = new HashSet<>();
        boolean changed = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageObject messageObject = messages.get(i);
            if (messageObject != null && messageObject.getDialogId() == dialogId && messageObject.getRealId() > 0 && messageObject.getRealId() <= maxId) {
                messages.remove(i);
                purgeRow(messageObject, removedIds, seen);
                changed = true;
            }
        }
        if (changed) {
            if (!hasMessagesForDialog(dialogId)) {
                hiddenDialogIds.remove(dialogId);
            }
            onRowsRemoved();
        }
        changedOut[0] = changed;
        return removedIds;
    }

    public boolean trim(int cap) {
        if (cap <= 0 || messages.size() <= cap) {
            return false;
        }
        MessageObject boundary = messages.get(cap - 1);
        int date = boundary.messageOwner.date;
        long dialogId = boundary.getDialogId();
        int realId = boundary.getRealId();
        boolean changed = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageObject messageObject = messages.get(i);
            if (messageObject != null && compareTimeline(messageObject.messageOwner.date, messageObject.getDialogId(), messageObject.getRealId(), date, dialogId, realId) < 0) {
                messages.remove(i);
                identityMap.purge(messageObject);
                changed = true;
            }
        }
        if (!changed) {
            return false;
        }
        if (messages.isEmpty()) {
            oldestCursor.set(0, 0, 0);
        } else {
            int oldestDate = 0;
            int oldestMid = 0;
            long oldestUid = 0;
            for (int i = 0; i < messages.size(); i++) {
                MessageObject messageObject = messages.get(i);
                if (messageObject != null && (oldestDate == 0 || compareTimeline(messageObject.messageOwner.date, messageObject.getDialogId(), messageObject.getRealId(), oldestDate, oldestUid, oldestMid) < 0)) {
                    oldestDate = messageObject.messageOwner.date;
                    oldestUid = messageObject.getDialogId();
                    oldestMid = messageObject.getRealId();
                }
            }
            oldestCursor.set(oldestDate, oldestUid, oldestMid);
        }
        endReached = false;
        updateCount();
        return true;
    }

    private void onRowsRemoved() {
        if (!rebuildPagingCursorsFromLoadedRows()) {
            endReached = false;
        }
        updateCount();
    }

    private boolean rebuildPagingCursorsFromLoadedRows() {
        boolean oldestWasEmpty = oldestCursor.isEmpty();
        int newestDate = 0, newestMid = 0, oldestDate = 0, oldestMid = 0;
        long newestUid = 0, oldestUid = 0;
        for (int i = 0; i < messages.size(); i++) {
            MessageObject messageObject = messages.get(i);
            if (!isPagingRow(messageObject)) {
                continue;
            }
            int date = messageObject.messageOwner.date;
            long uid = messageObject.getDialogId();
            int mid = messageObject.getRealId();
            if (newestDate == 0 || compareTimeline(date, uid, mid, newestDate, newestUid, newestMid) > 0) {
                newestDate = date;
                newestUid = uid;
                newestMid = mid;
            }
            if (oldestDate == 0 || compareTimeline(date, uid, mid, oldestDate, oldestUid, oldestMid) < 0) {
                oldestDate = date;
                oldestUid = uid;
                oldestMid = mid;
            }
        }
        if (newestDate == 0) {
            oldestCursor.set(0, 0, 0);
            newestCursor.set(0, 0, 0);
            return false;
        }
        if (!oldestWasEmpty && compareTimeline(oldestDate, oldestUid, oldestMid, oldestCursor.date, oldestCursor.uid, oldestCursor.mid) > 0) {
            endReached = false;
        }
        newestCursor.set(newestDate, newestUid, newestMid);
        oldestCursor.set(oldestDate, oldestUid, oldestMid);
        return true;
    }

    private static boolean isPagingRow(MessageObject messageObject) {
        return messageObject != null && !messageObject.isDateObject && messageObject.messageOwner != null && messageObject.getRealId() > 0;
    }

    public static int compareTimeline(int date1, long uid1, int mid1, int date2, long uid2, int mid2) {
        if (date1 != date2) {
            return Integer.compare(date1, date2);
        }
        if (uid1 != uid2) {
            return Long.compare(uid1, uid2);
        }
        return Integer.compare(mid1, mid2);
    }

    private void purgeRow(MessageObject messageObject, ArrayList<Integer> removedIds, HashSet<Integer> seen) {
        identityMap.purge(messageObject);
        if (seen.add(messageObject.getId())) {
            removedIds.add(messageObject.getId());
        }
    }

    public boolean hasNoSyntheticIds() {
        return identityMap.isEmpty();
    }

    public MessageObject getMessage(long dialogId, int id) {
        return identityMap.getByAnyId(dialogId, id);
    }

    public int resolveRealMessageId(long dialogId, int id) {
        return identityMap.resolveRealMessageId(dialogId, id);
    }

    public long resolveRealDialogId(int id) {
        return identityMap.resolveRealDialogId(id);
    }
}
