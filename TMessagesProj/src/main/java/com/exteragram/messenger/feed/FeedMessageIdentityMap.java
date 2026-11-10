package com.exteragram.messenger.feed;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * exteraGram feed: posts of different channels share one ChatActivity list, so each post gets a
 * synthetic message id (counting down from Integer.MAX_VALUE - 10) unique within the feed;
 * message.realId keeps the channel's own id. FeedRequestNormalizer maps synthetic ids back before a
 * request is sent.
 */
final class FeedMessageIdentityMap {

    private static final int FIRST_GENERATED_ID = Integer.MAX_VALUE - 10;

    private final HashMap<MessageCompositeID, Integer> generatedIds = new HashMap<>();
    private final ConcurrentHashMap<Integer, MessageCompositeID> realIdsByGeneratedId = new ConcurrentHashMap<>();
    private final HashMap<MessageCompositeID, MessageObject> messagesByRealId = new HashMap<>();
    private final HashMap<GroupKey, ArrayList<MessageObject>> groupMembers = new HashMap<>();
    private int lastGeneratedId = FIRST_GENERATED_ID;

    /** @return true when the message was not registered before. */
    public boolean register(MessageObject messageObject) {
        int realId = messageObject.getRealId();
        MessageCompositeID key = new MessageCompositeID(messageObject.getDialogId(), realId);
        Integer generated = generatedIds.get(key);
        if (generated == null) {
            generated = lastGeneratedId--;
            generatedIds.put(key, generated);
        }
        realIdsByGeneratedId.put(generated, key);
        boolean added;
        if (messagesByRealId.containsKey(key)) {
            added = false;
        } else {
            messagesByRealId.put(key, messageObject);
            addToGroup(messageObject, key.dialogId);
            added = true;
        }
        TLRPC.Message message = messageObject.messageOwner;
        message.realId = realId;
        message.id = generated;
        return added;
    }

    public void replace(MessageObject messageObject) {
        MessageCompositeID key = new MessageCompositeID(messageObject.getDialogId(), messageObject.getRealId());
        generatedIds.put(key, messageObject.getId());
        realIdsByGeneratedId.put(messageObject.getId(), key);
        MessageObject old = messagesByRealId.put(key, messageObject);
        if (old != null) {
            removeFromGroup(old, key.dialogId);
        }
        addToGroup(messageObject, key.dialogId);
    }

    public void purge(MessageObject messageObject) {
        MessageCompositeID key = new MessageCompositeID(messageObject.getDialogId(), messageObject.getRealId());
        generatedIds.remove(key);
        messagesByRealId.remove(key);
        realIdsByGeneratedId.remove(messageObject.getId());
        removeFromGroup(messageObject, key.dialogId);
    }

    public MessageObject getByRealId(long dialogId, int id) {
        return messagesByRealId.get(new MessageCompositeID(dialogId, id));
    }

    public MessageObject getByAnyId(long dialogId, int id) {
        MessageObject messageObject = messagesByRealId.get(new MessageCompositeID(dialogId, id));
        if (messageObject != null) {
            return messageObject;
        }
        int realId = resolveRealMessageId(dialogId, id);
        if (realId != id) {
            return messagesByRealId.get(new MessageCompositeID(dialogId, realId));
        }
        return null;
    }

    public int resolveRealMessageId(long dialogId, int id) {
        MessageCompositeID key = realIdsByGeneratedId.get(id);
        return key == null || key.dialogId != dialogId ? id : key.id;
    }

    public long resolveRealDialogId(int id) {
        MessageCompositeID key = realIdsByGeneratedId.get(id);
        return key != null ? key.dialogId : 0;
    }

    public boolean isEmpty() {
        return realIdsByGeneratedId.isEmpty();
    }

    public void clear() {
        generatedIds.clear();
        realIdsByGeneratedId.clear();
        messagesByRealId.clear();
        groupMembers.clear();
        lastGeneratedId = FIRST_GENERATED_ID;
    }

    private void addToGroup(MessageObject messageObject, long dialogId) {
        if (!messageObject.hasValidGroupId()) {
            messageObject.isPrimaryGroupMessage = false;
            return;
        }
        GroupKey key = new GroupKey(dialogId, messageObject.messageOwner.grouped_id);
        ArrayList<MessageObject> members = groupMembers.get(key);
        if (members == null) {
            members = new ArrayList<>();
            groupMembers.put(key, members);
        }
        if (!members.contains(messageObject)) {
            members.add(messageObject);
        }
        electPrimary(members);
    }

    private void removeFromGroup(MessageObject messageObject, long dialogId) {
        if (!messageObject.hasValidGroupId()) {
            return;
        }
        GroupKey key = new GroupKey(dialogId, messageObject.messageOwner.grouped_id);
        ArrayList<MessageObject> members = groupMembers.get(key);
        if (members == null) {
            return;
        }
        members.remove(messageObject);
        messageObject.isPrimaryGroupMessage = false;
        if (members.isEmpty()) {
            groupMembers.remove(key);
        } else {
            electPrimary(members);
        }
    }

    private static void electPrimary(ArrayList<MessageObject> members) {
        MessageObject primary = null;
        for (int i = 0; i < members.size(); i++) {
            MessageObject member = members.get(i);
            if (primary == null || member.getRealId() > primary.getRealId()) {
                primary = member;
            }
        }
        for (int i = 0; i < members.size(); i++) {
            members.get(i).isPrimaryGroupMessage = members.get(i) == primary;
        }
    }

    static final class GroupKey {
        final long dialogId;
        final long groupedId;

        GroupKey(long dialogId, long groupedId) {
            this.dialogId = dialogId;
            this.groupedId = groupedId;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || obj.getClass() != GroupKey.class) {
                return false;
            }
            GroupKey other = (GroupKey) obj;
            return dialogId == other.dialogId && groupedId == other.groupedId;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(dialogId) * 31 + Long.hashCode(groupedId);
        }
    }

    static final class MessageCompositeID {
        final long dialogId;
        final int id;

        MessageCompositeID(long dialogId, int id) {
            this.dialogId = dialogId;
            this.id = id;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || obj.getClass() != MessageCompositeID.class) {
                return false;
            }
            MessageCompositeID other = (MessageCompositeID) obj;
            return dialogId == other.dialogId && id == other.id;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(dialogId) * 31 + id;
        }
    }
}
