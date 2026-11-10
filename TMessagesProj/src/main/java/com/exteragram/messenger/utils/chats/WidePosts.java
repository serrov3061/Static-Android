package com.exteragram.messenger.utils.chats;

import android.graphics.Point;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.feed.FeedMessageUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

/**
 * Wide Posts (exteraGram 13.1.1): channel posts and Channel Feed posts laid out
 * full-width, with the author/channel profile avatar embedded into the bubble header.
 */
public abstract class WidePosts {

    private static final Map<MessageObject.GroupedMessagePosition, GroupPositionState> GROUP_POSITION_STATES = new WeakHashMap<>();
    private static final Map<MessageObject, CommentsPostContext> COMMENTS_POST_CONTEXTS = new WeakHashMap<>();

    private static final int SEARCH_FEED = 4;
    /**
     * exteraGram also embeds the header into round videos / stickers (a separate "media profile header"
     * drawn over the media). StaticGram does not port that layout yet: such messages keep the regular avatar.
     */
    private static final boolean MEDIA_PROFILE_HEADER = false;

    public static void registerCommentsPostContext(MessageObject messageObject, TLRPC.Chat chat, MessageObject originalMessage) {
        if (messageObject == null || messageObject.messageOwner == null || !ChatObject.isChannelAndNotMegaGroup(chat)) {
            return;
        }
        TLRPC.Message message = messageObject.messageOwner;
        TLRPC.MessageFwdHeader fwd = message.fwd_from;
        long fromChannelId = message.from_id != null ? message.from_id.channel_id : 0L;
        long peerChannelId = message.peer_id != null ? message.peer_id.channel_id : 0L;
        long savedFromChannelId = fwd != null && fwd.saved_from_peer != null ? fwd.saved_from_peer.channel_id : 0L;
        long channelId = chat.id;
        boolean fromChannelInDiscussion = fromChannelId == channelId && peerChannelId != 0 && peerChannelId != channelId;
        boolean isPostCopy = message.reply_to == null && fwd != null && (message.isThreadMessage || savedFromChannelId == channelId || fwd.channel_post != 0 || message.replies != null);
        if (fromChannelInDiscussion && isPostCopy) {
            String author = originalMessage == null || originalMessage.messageOwner == null || originalMessage.getDialogId() != -chat.id ? null : originalMessage.messageOwner.post_author;
            CommentsPostContext context = COMMENTS_POST_CONTEXTS.get(messageObject);
            if (context != null && context.channelId == chat.id) {
                context.updatePostAuthor(author);
            } else {
                COMMENTS_POST_CONTEXTS.put(messageObject, new CommentsPostContext(chat.id, author));
            }
        }
    }

    public static boolean isCommentsChannelPost(MessageObject messageObject) {
        return getCommentsChannelId(messageObject) != 0;
    }

    public static void copyCommentsPostContext(MessageObject from, MessageObject to) {
        if (from == null || to == null) {
            return;
        }
        CommentsPostContext context = COMMENTS_POST_CONTEXTS.get(from);
        if (context != null) {
            COMMENTS_POST_CONTEXTS.put(to, context);
        }
    }

    public static String getCommentsPostAuthor(MessageObject messageObject) {
        CommentsPostContext context = getCommentsPostContext(messageObject);
        return context != null ? context.postAuthor : null;
    }

    public static boolean isEnabledFor(boolean feed, boolean channelPost) {
        if (!channelPost) {
            return false;
        }
        return feed ? ExteraConfig.getWidePostsInFeed() : ExteraConfig.getWidePostsInChannels();
    }

    public static boolean isEnabledFor(MessageObject messageObject) {
        if (messageObject == null || !(ExteraConfig.getWidePostsInFeed() || ExteraConfig.getWidePostsInChannels())) {
            return false;
        }
        return isEnabledFor(messageObject.searchType == SEARCH_FEED, isChannelPostOrSponsored(messageObject));
    }

    public static int getBubbleRight(int width) {
        return Math.max(AndroidUtilities.dp(120), width - AndroidUtilities.dp(9));
    }

    private static boolean shouldExpand(MessageObject messageObject) {
        return isEnabledFor(messageObject) && !messageObject.shouldDrawWithoutBackground();
    }

    public static boolean shouldDrawBackground(MessageObject messageObject) {
        if (messageObject == null) {
            return false;
        }
        if (messageObject.shouldDrawWithoutBackground()) {
            return messageObject.isRoundVideo() && messageObject.isVoiceTranscriptionOpen();
        }
        return true;
    }

    public static boolean isTextMessage(MessageObject messageObject) {
        int type = messageObject.type;
        return type == MessageObject.TYPE_TEXT || type == MessageObject.TYPE_ARTICLE || type == MessageObject.TYPE_STORY_MENTION;
    }

    public static boolean isExpandableMedia(MessageObject messageObject) {
        int type = messageObject.type;
        return type == MessageObject.TYPE_PHOTO || type == MessageObject.TYPE_EXTENDED_MEDIA_PREVIEW || type == MessageObject.TYPE_VIDEO || type == MessageObject.TYPE_GIF;
    }

    public static int getImageHeight(int photoWidth, int photoHeight, int minHeight, int width) {
        return Math.max(AndroidUtilities.dp(120), Math.min(Math.round(width * (photoHeight / (float) photoWidth)), Math.max(minHeight, Math.min(AndroidUtilities.getPhotoSize(), width + AndroidUtilities.dp(100)))));
    }

    public static boolean isProfileResolved(MessageObject messageObject) {
        if (messageObject == null || !shouldEmbedProfileAvatar(messageObject)) {
            return true;
        }
        return isPeerResolved(messageObject.currentAccount, getEmbeddedProfileDialogId(messageObject)) && isPeerResolved(messageObject.currentAccount, getFeedSignatureProfileDialogId(messageObject));
    }

    private static boolean isPeerResolved(int account, long dialogId) {
        if (dialogId > 0) {
            return MessagesController.getInstance(account).getUser(dialogId) != null;
        }
        return dialogId >= 0 || MessagesController.getInstance(account).getChat(-dialogId) != null;
    }

    public static boolean shouldEmbedProfileAvatar(MessageObject messageObject) {
        return shouldEmbedFeedChannelProfileAvatar(messageObject) || shouldEmbedCommentsChannelProfileAvatar(messageObject) || shouldEmbedAuthorProfileAvatar(messageObject);
    }

    public static boolean shouldSuppressExternalAvatar(MessageObject messageObject) {
        return isEnabledFor(messageObject) && messageObject.searchType == SEARCH_FEED && messageObject.isSponsored();
    }

    private static boolean shouldEmbedFeedChannelProfileAvatar(MessageObject messageObject) {
        return isEmbeddedProfileAvatarMessage(messageObject) && FeedMessageUtils.resolveDisplayChannel(messageObject) != null;
    }

    public static boolean shouldEmbedAuthorProfileAvatar(MessageObject messageObject) {
        if (!isEmbeddedProfileAvatarMessage(messageObject) || messageObject.searchType == SEARCH_FEED) {
            return false;
        }
        TLRPC.Chat channel = getBroadcastChannel(messageObject);
        return channel != null && (channel.signature_profiles || messageObject.currentEvent != null) && ChatObject.isChannelAndNotMegaGroup(channel);
    }

    private static boolean shouldEmbedCommentsChannelProfileAvatar(MessageObject messageObject) {
        boolean embedded = isEmbeddedProfileAvatarMessage(messageObject);
        long commentsChannelId = getCommentsChannelId(messageObject);
        CommentsPostContext context = getCommentsPostContext(messageObject);
        TLRPC.Chat chat = messageObject == null || commentsChannelId == 0 ? null : MessagesController.getInstance(messageObject.currentAccount).getChat(commentsChannelId);
        if (!embedded || commentsChannelId == 0) {
            return false;
        }
        return context != null || ChatObject.isChannelAndNotMegaGroup(chat);
    }

    public static long getEmbeddedProfileDialogId(MessageObject messageObject) {
        if (shouldEmbedFeedChannelProfileAvatar(messageObject)) {
            TLRPC.Chat chat = FeedMessageUtils.resolveDisplayChannel(messageObject);
            return chat != null ? -chat.id : 0L;
        }
        if (shouldEmbedCommentsChannelProfileAvatar(messageObject)) {
            long commentsChannelId = getCommentsChannelId(messageObject);
            return commentsChannelId != 0 ? -commentsChannelId : 0L;
        }
        if (!shouldEmbedAuthorProfileAvatar(messageObject) || messageObject.messageOwner.from_id == null) {
            return 0L;
        }
        return DialogObject.getPeerDialogId(messageObject.messageOwner.from_id);
    }

    public static long getFeedSignatureProfileDialogId(MessageObject messageObject) {
        if (FeedMessageUtils.hasProfileSignature(messageObject) && messageObject.messageOwner.from_id != null) {
            long dialogId = DialogObject.getPeerDialogId(messageObject.messageOwner.from_id);
            if (dialogId != DialogObject.getPeerDialogId(messageObject.messageOwner.peer_id)) {
                return dialogId;
            }
        }
        return 0L;
    }

    public static boolean shouldDrawFeedAuthorSignature(MessageObject messageObject) {
        return FeedMessageUtils.hasProfileSignature(messageObject);
    }

    public static String getFeedPostAuthor(MessageObject messageObject) {
        if (FeedMessageUtils.hasProfileSignature(messageObject)) {
            return messageObject.messageOwner.post_author;
        }
        return null;
    }

    public static boolean hasSameEmbeddedProfileHeader(MessageObject a, MessageObject b) {
        return shouldEmbedFeedChannelProfileAvatar(a) == shouldEmbedFeedChannelProfileAvatar(b) && getEmbeddedProfileDialogId(a) == getEmbeddedProfileDialogId(b);
    }

    private static boolean isEmbeddedProfileAvatarMessage(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        if (!shouldExpand(messageObject) && !(MEDIA_PROFILE_HEADER && isEnabledFor(messageObject) && isEmbeddedProfileAvatarMessageWithoutBackground(messageObject))) {
            return false;
        }
        if (messageObject.isOutOwner() || messageObject.isSponsored() || messageObject.isExpiredStory() || !messageObject.needDrawAvatar()) {
            return false;
        }
        TLRPC.MessageFwdHeader fwd = messageObject.messageOwner.fwd_from;
        if (fwd != null && fwd.psa_type != null && !fwd.psa_type.isEmpty()) {
            return false;
        }
        return getChannelId(messageObject) != 0;
    }

    public static boolean isEmbeddedProfileAvatarMessageWithoutBackground(MessageObject messageObject) {
        if (messageObject == null) {
            return false;
        }
        int type = messageObject.type;
        return type == MessageObject.TYPE_ROUND_VIDEO || type == MessageObject.TYPE_STICKER || type == MessageObject.TYPE_ANIMATED_STICKER || type == MessageObject.TYPE_EMOJIS;
    }

    public static boolean shouldHideAuxiliaryActions(MessageObject messageObject) {
        return isEnabledFor(messageObject) && !messageObject.isSponsored();
    }

    public static boolean isWideGroupedMedia(MessageObject.GroupedMessages groupedMessages) {
        return isGroupedLayout(groupedMessages) && !groupedMessages.isDocuments;
    }

    public static int getScaledGroupWidth(MessageObject.GroupedMessages groupedMessages) {
        if (groupedMessages == null || groupedMessages.posArray == null || groupedMessages.posArray.isEmpty()) {
            return 0;
        }
        GroupPositionState state = GROUP_POSITION_STATES.get(groupedMessages.posArray.get(0));
        return state != null ? state.scaledForWidth : 0;
    }

    public static int getDefaultGroupWidth() {
        Point size = AndroidUtilities.displaySize;
        int width = size.x;
        boolean landscape = width > size.y;
        if (AndroidUtilities.isInMultiwindow || !AndroidUtilities.isTablet()) {
            return width;
        }
        return (!AndroidUtilities.isSmallTablet() || landscape) ? width - Math.max(width * 35 / 100, AndroidUtilities.dp(320)) : width;
    }

    public static void updateGroupedLayout(MessageObject.GroupedMessages groupedMessages, int width) {
        if (!isWideGroupedMedia(groupedMessages) || width <= 0) {
            restoreGroupedLayout(groupedMessages);
            return;
        }
        MessageObject primary = getPrimaryGroupMessage(groupedMessages);
        if (!shouldExpand(primary)) {
            restoreGroupedLayout(groupedMessages);
            return;
        }
        boolean noAvatar = shouldEmbedProfileAvatar(primary) || shouldSuppressExternalAvatar(primary);
        ArrayList<MessageObject.GroupedMessagePosition> posArray = groupedMessages.posArray;
        for (int i = 0, n = posArray.size(); i < n; i++) {
            MessageObject.GroupedMessagePosition position = posArray.get(i);
            if (position == null) {
                continue;
            }
            GroupPositionState state = GROUP_POSITION_STATES.get(position);
            if (state == null) {
                state = new GroupPositionState(position);
                GROUP_POSITION_STATES.put(position, state);
            }
            state.restore(position);
            if (noAvatar) {
                removeGroupAvatarOffset(position);
            }
        }
        int baseWidth = getBaseGroupVisualWidth(groupedMessages);
        if (baseWidth <= 0) {
            restoreGroupedLayout(groupedMessages);
            return;
        }
        int targetWidth = Utilities.clamp(Math.round(getBubbleRight(width) * 1000 / (float) width), 1000, 1);
        float scale = targetWidth / (float) baseWidth;
        for (int i = 0, n = posArray.size(); i < n; i++) {
            MessageObject.GroupedMessagePosition position = posArray.get(i);
            if (position == null || GROUP_POSITION_STATES.get(position) == null) {
                continue;
            }
            boolean hasLeftOffset = position.leftSpanOffset != 0;
            position.pw = Math.max(1, Math.round(position.pw * scale));
            position.leftSpanOffset = hasLeftOffset ? Math.max(0, targetWidth - position.pw) : 0;
        }
        normalizeGroupRows(groupedMessages, targetWidth, noAvatar);
        int restWidth = 1000 - targetWidth;
        boolean changed = false;
        for (int i = 0, n = posArray.size(); i < n; i++) {
            MessageObject.GroupedMessagePosition position = posArray.get(i);
            if (position == null) {
                continue;
            }
            GroupPositionState state = GROUP_POSITION_STATES.get(position);
            if (state == null) {
                continue;
            }
            boolean fullSpan = noAvatar ? (position.spanSize == 1000 && position.leftSpanOffset > 0) : (state.spanSize == 1000 && state.leftSpanOffset > 0);
            if (fullSpan) {
                position.spanSize = 1000;
            } else {
                position.spanSize = position.pw;
                if ((position.flags & MessageObject.POSITION_FLAG_RIGHT) != 0) {
                    position.spanSize = position.pw + restWidth;
                }
            }
            state.scaledForWidth = width;
            changed |= state.apply(position);
        }
        if (changed) {
            groupedMessages.cachedWidthForCaption = -1;
        }
    }

    private static boolean isChannelPostOrSponsored(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.preview || messageObject.type == MessageObject.TYPE_JOINED_CHANNEL) {
            return false;
        }
        if (messageObject.isSponsored()) {
            return true;
        }
        long channelId = getCommentsChannelId(messageObject);
        if (channelId == 0 && messageObject.messageOwner.peer_id == null) {
            return false;
        }
        if (channelId == 0) {
            channelId = getChannelId(messageObject);
        }
        if (channelId == 0) {
            return false;
        }
        TLRPC.Chat chat = MessagesController.getInstance(messageObject.currentAccount).getChat(channelId);
        if (chat != null) {
            return ChatObject.isChannelAndNotMegaGroup(chat);
        }
        // exteraGram: post || channelId != 0, and channelId is non-zero here
        return true;
    }

    private static long getChannelId(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return 0L;
        }
        long commentsChannelId = getCommentsChannelId(messageObject);
        if (commentsChannelId != 0) {
            return commentsChannelId;
        }
        long peerChannelId = messageObject.messageOwner.peer_id != null ? messageObject.messageOwner.peer_id.channel_id : 0L;
        if (peerChannelId != 0) {
            return peerChannelId;
        }
        long dialogId = messageObject.getDialogId();
        return dialogId < 0 ? -dialogId : 0L;
    }

    private static long getCommentsChannelId(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return 0L;
        }
        CommentsPostContext context = getCommentsPostContext(messageObject);
        return context != null ? context.channelId : 0L;
    }

    private static CommentsPostContext getCommentsPostContext(MessageObject messageObject) {
        return messageObject == null ? null : COMMENTS_POST_CONTEXTS.get(messageObject);
    }

    private static TLRPC.Chat getBroadcastChannel(MessageObject messageObject) {
        long channelId = getChannelId(messageObject);
        return channelId != 0 ? MessagesController.getInstance(messageObject.currentAccount).getChat(channelId) : null;
    }

    private static boolean isGroupedLayout(MessageObject.GroupedMessages groupedMessages) {
        if (groupedMessages == null) {
            return false;
        }
        return (groupedMessages.posArray != null && groupedMessages.posArray.size() > 1) || (groupedMessages.messages != null && groupedMessages.messages.size() > 1);
    }

    private static MessageObject getPrimaryGroupMessage(MessageObject.GroupedMessages groupedMessages) {
        if (groupedMessages == null) {
            return null;
        }
        int flags = groupedMessages.reversed ? (MessageObject.POSITION_FLAG_RIGHT | MessageObject.POSITION_FLAG_BOTTOM) : (MessageObject.POSITION_FLAG_LEFT | MessageObject.POSITION_FLAG_TOP);
        if (groupedMessages.messages != null) {
            for (int i = 0, n = groupedMessages.messages.size(); i < n; i++) {
                MessageObject messageObject = groupedMessages.messages.get(i);
                MessageObject.GroupedMessagePosition position = groupedMessages.getPosition(messageObject);
                if (position != null && (position.flags & flags) == flags) {
                    return messageObject;
                }
            }
        }
        if (groupedMessages.messages == null || groupedMessages.messages.isEmpty()) {
            return null;
        }
        return groupedMessages.messages.get(0);
    }

    private static int getBaseGroupVisualWidth(MessageObject.GroupedMessages groupedMessages) {
        ArrayList<MessageObject.GroupedMessagePosition> posArray = groupedMessages.posArray;
        int maxY = 0;
        for (int i = 0, n = posArray.size(); i < n; i++) {
            MessageObject.GroupedMessagePosition position = posArray.get(i);
            if (position != null) {
                maxY = Math.max(maxY, position.maxY);
            }
        }
        int maxWidth = 0;
        for (int row = 0; row <= maxY; row++) {
            int rowWidth = 0;
            for (int i = 0, n = posArray.size(); i < n; i++) {
                MessageObject.GroupedMessagePosition position = posArray.get(i);
                if (position != null && position.minY <= row && position.maxY >= row) {
                    rowWidth += position.pw;
                }
            }
            maxWidth = Math.max(maxWidth, rowWidth);
        }
        return Math.min(1000, maxWidth);
    }

    private static void removeGroupAvatarOffset(MessageObject.GroupedMessagePosition position) {
        if (position.edge) {
            position.pw = Math.max(1, position.pw - 108);
            if (position.spanSize != 1000) {
                position.spanSize = Math.max(1, position.spanSize - 108);
            }
            return;
        }
        if ((position.flags & MessageObject.POSITION_FLAG_RIGHT) != 0) {
            if (position.spanSize != 1000) {
                position.spanSize += 108;
            } else if (position.leftSpanOffset != 0) {
                position.leftSpanOffset = Math.max(0, position.leftSpanOffset - 108);
            }
        }
    }

    private static void normalizeGroupRows(MessageObject.GroupedMessages groupedMessages, int targetWidth, boolean noAvatar) {
        ArrayList<MessageObject.GroupedMessagePosition> posArray = groupedMessages.posArray;
        int maxY = 0;
        for (int i = 0, n = posArray.size(); i < n; i++) {
            MessageObject.GroupedMessagePosition position = posArray.get(i);
            if (position != null) {
                maxY = Math.max(maxY, position.maxY);
            }
        }
        for (int row = 0; row <= maxY; row++) {
            MessageObject.GroupedMessagePosition last = null;
            int rowWidth = 0;
            for (int i = 0, n = posArray.size(); i < n; i++) {
                MessageObject.GroupedMessagePosition position = posArray.get(i);
                if (position != null && position.minY <= row && position.maxY >= row) {
                    rowWidth += position.pw;
                    boolean right = (position.flags & MessageObject.POSITION_FLAG_RIGHT) != 0;
                    boolean lastRight = last != null && (last.flags & MessageObject.POSITION_FLAG_RIGHT) != 0;
                    if (last == null || (right && !lastRight) || (right == lastRight && position.maxX > last.maxX)) {
                        last = position;
                    }
                }
            }
            if (last != null) {
                last.pw = Math.max(1, last.pw + targetWidth - rowWidth);
                GroupPositionState state = GROUP_POSITION_STATES.get(last);
                if (noAvatar) {
                    if (last.leftSpanOffset > 0) {
                        last.leftSpanOffset = Math.max(0, targetWidth - last.pw);
                    }
                } else if (state != null && state.leftSpanOffset > 0) {
                    last.leftSpanOffset = Math.max(0, targetWidth - last.pw);
                }
            }
        }
    }

    private static void restoreGroupedLayout(MessageObject.GroupedMessages groupedMessages) {
        if (groupedMessages == null || groupedMessages.posArray == null || groupedMessages.posArray.isEmpty()) {
            return;
        }
        boolean restored = false;
        for (int i = 0, n = groupedMessages.posArray.size(); i < n; i++) {
            MessageObject.GroupedMessagePosition position = groupedMessages.posArray.get(i);
            if (position == null) {
                continue;
            }
            GroupPositionState state = GROUP_POSITION_STATES.remove(position);
            if (state != null) {
                state.restore(position);
                restored = true;
            }
        }
        if (restored) {
            groupedMessages.cachedWidthForCaption = -1;
        }
    }

    /** Loads the original channel post of a comments thread to show its signature in the embedded header. */
    public static final class CommentsPostAuthorLoader {
        private final TLRPC.Chat channel;
        private final int currentAccount;
        private final ArrayList<MessageObject> messages = new ArrayList<>();
        private ChatActivity chatActivity;
        private MessageObject originalMessage;

        public CommentsPostAuthorLoader(int account, TLRPC.Chat channel) {
            this.currentAccount = account;
            this.channel = channel;
        }

        public MessageObject getOriginalMessage() {
            return originalMessage;
        }

        public int load(int messageId, BooleanSupplier cancelled) {
            TLRPC.TL_channels_getMessages req = new TLRPC.TL_channels_getMessages();
            req.channel = MessagesController.getInputChannel(channel);
            req.id.add(messageId);
            return ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getInstance(currentAccount).doOnIdle(() -> onLoaded(cancelled, response, messageId))));
        }

        private void onLoaded(BooleanSupplier cancelled, TLObject response, int messageId) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                return;
            }
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                MessagesController.getInstance(currentAccount).putUsers(res.users, false);
                MessagesController.getInstance(currentAccount).putChats(res.chats, false);
                for (int i = 0, n = res.messages.size(); i < n; i++) {
                    TLRPC.Message message = res.messages.get(i);
                    if (message != null && message.id == messageId) {
                        originalMessage = new MessageObject(currentAccount, message, true, true);
                        break;
                    }
                }
            }
            apply();
        }

        public void setMessages(ArrayList<MessageObject> messageObjects, ChatActivity chatActivity) {
            messages.clear();
            if (messageObjects != null) {
                messages.addAll(messageObjects);
            }
            this.chatActivity = chatActivity;
            apply();
        }

        private void apply() {
            if (messages.isEmpty()) {
                return;
            }
            for (int i = 0, n = messages.size(); i < n; i++) {
                MessageObject messageObject = messages.get(i);
                registerCommentsPostContext(messageObject, channel, originalMessage);
                messageObject.forceUpdate = true;
            }
            if (originalMessage != null && chatActivity != null) {
                chatActivity.updateWidePostsCommentsAuthor(messages);
            }
        }
    }

    public static final class CommentsPostContext {
        final long channelId;
        String postAuthor;

        public CommentsPostContext(long channelId, String postAuthor) {
            this.channelId = channelId;
            this.postAuthor = postAuthor;
        }

        public void updatePostAuthor(String author) {
            if (author != null) {
                postAuthor = author;
            }
        }
    }

    public static final class GroupPositionState {
        final int width;
        final int spanSize;
        final int leftSpanOffset;
        private int appliedWidth;
        private int appliedSpanSize;
        private int appliedLeftSpanOffset;
        private int scaledForWidth;

        public GroupPositionState(MessageObject.GroupedMessagePosition position) {
            width = appliedWidth = position.pw;
            spanSize = appliedSpanSize = position.spanSize;
            leftSpanOffset = appliedLeftSpanOffset = position.leftSpanOffset;
        }

        public boolean apply(MessageObject.GroupedMessagePosition position) {
            boolean changed = appliedWidth != position.pw || appliedSpanSize != position.spanSize || appliedLeftSpanOffset != position.leftSpanOffset;
            appliedWidth = position.pw;
            appliedSpanSize = position.spanSize;
            appliedLeftSpanOffset = position.leftSpanOffset;
            return changed;
        }

        public void restore(MessageObject.GroupedMessagePosition position) {
            position.pw = width;
            position.spanSize = spanSize;
            position.leftSpanOffset = leftSpanOffset;
        }
    }
}
