package com.exteragram.messenger.feed;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.Calendar;

/** exteraGram feed: helpers for feed rows in ChatActivity / ChatMessageCell. */
public final class FeedMessageUtils {

    private FeedMessageUtils() {
    }

    /** Double tap actions usable in the feed (DoubleTapUtils ids: reactions, reply-less ones). */
    public static boolean isAllowedDoubleTapAction(int action) {
        return action == 1 || action == 2 || action == 3 || action == 4 || action == 6 || action == 9;
    }

    /** ChatActivity message menu options allowed in the feed (OPTION_* ids, see filterAllowedOptions). */
    public static boolean isAllowedFeedOption(int option) {
        return option == 2 || option == 3 || option == 4 || option == 6 || option == 7 || option == 8 || option == 10 || option == 16
            || option == 22 || option == 25 || option == 36 || option == 115 || option == 200 || option == 203 || option == 206
            || option == 28 || option == 29;
    }

    public static boolean isAllowedSwipeAction(int action) {
        return action != 1 && isAllowedDoubleTapAction(action);
    }

    public static boolean isPostRow(MessageObject messageObject) {
        return messageObject != null && !messageObject.isDateObject && messageObject.type != MessageObject.TYPE_LOADING && !messageObject.isSponsored();
    }

    public static boolean isFeedMessage(MessageObject messageObject) {
        return messageObject != null && messageObject.searchType == FeedController.SEARCH_FEED;
    }

    /** Real (channel) id of a feed post, the plain id otherwise (exteraGram MessageObject.getFeedRealId). */
    public static int getFeedRealId(MessageObject messageObject) {
        return isFeedMessage(messageObject) ? messageObject.getRealId() : messageObject.getId();
    }

    public static TLRPC.Chat resolveDisplayChannel(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.searchType != FeedController.SEARCH_FEED || messageObject.preview || messageObject.isSponsored() || messageObject.type == MessageObject.TYPE_JOINED_CHANNEL) {
            return null;
        }
        TLRPC.Message message = messageObject.messageOwner;
        if (!message.post || message.action != null || message.peer_id == null || message.peer_id.channel_id == 0) {
            return null;
        }
        TLRPC.Chat chat = MessagesController.getInstance(messageObject.currentAccount).getChat(message.peer_id.channel_id);
        return ChatObject.isChannelAndNotMegaGroup(chat) ? chat : null;
    }

    public static boolean hasProfileSignature(MessageObject messageObject) {
        TLRPC.Chat chat = resolveDisplayChannel(messageObject);
        return chat != null && chat.signature_profiles;
    }

    public static boolean shouldMergePosts(MessageObject a, TLRPC.Chat chatA, MessageObject b, TLRPC.Chat chatB) {
        return chatA != null && chatB != null && chatA.id == chatB.id && a.contentType == b.contentType
            && !(a.messageOwner.reply_markup instanceof TLRPC.TL_replyInlineMarkup) && a.isOutOwner() == b.isOutOwner()
            && Math.abs(a.messageOwner.date - b.messageOwner.date) <= 300 && b.messageOwner.paid_message_stars <= 0
            && DialogObject.getPeerDialogId(a.messageOwner.guestchat_via_from) == DialogObject.getPeerDialogId(b.messageOwner.guestchat_via_from);
    }

    public static MessageObject createUnreadDivider(int account, int stableId) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.message = "";
        message.id = 0;
        MessageObject messageObject = new MessageObject(account, message, false, false);
        messageObject.type = MessageObject.TYPE_LOADING;
        messageObject.contentType = 2;
        messageObject.stableId = stableId;
        return messageObject;
    }

    public static MessageObject createDateHeader(int account, MessageObject source, int stableId) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.message = LocaleController.formatDateChat(source.messageOwner.date);
        message.id = 0;
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(source.messageOwner.date * 1000L);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        message.date = (int) (calendar.getTimeInMillis() / 1000);
        MessageObject messageObject = new MessageObject(account, message, false, false);
        messageObject.type = MessageObject.TYPE_DATE;
        messageObject.contentType = 1;
        messageObject.isDateObject = true;
        messageObject.stableId = stableId;
        return messageObject;
    }

    public static TLRPC.InputPeer getInputPeerForMessageRequest(MessagesController messagesController, long dialogId, boolean isFeed, MessageObject messageObject) {
        if (isFeed && messageObject != null) {
            dialogId = messageObject.getDialogId();
        }
        return messagesController.getInputPeer(dialogId);
    }

    public static boolean matchesPlaybackNotification(int account, MessageObject messageObject, int id) {
        if (messageObject == null) {
            return false;
        }
        if (messageObject.getId() == id) {
            return true;
        }
        FeedController controller = FeedController.peekInstance(account);
        if (controller == null) {
            return false;
        }
        long dialogId = controller.resolveRealDialogId(id);
        return dialogId != 0 && dialogId == messageObject.getDialogId() && controller.resolveRealMessageId(dialogId, id) == getFeedRealId(messageObject);
    }

    public static int getPlaybackScrollMessageId(boolean isFeed, long dialogId, MessageObject messageObject) {
        if (messageObject != null && messageObject.searchType == FeedController.SEARCH_FEED && !isFeed && messageObject.getDialogId() == dialogId) {
            return messageObject.getRealId();
        }
        return messageObject != null ? messageObject.getId() : 0;
    }

    /** A copy of a feed post with its real channel id, for forwarding / replying / statistics. */
    public static MessageObject getForwardingMessageObject(int account, boolean isFeed, MessageObject messageObject) {
        if (!isFeed || messageObject == null || messageObject.getId() == messageObject.getRealId()) {
            return messageObject;
        }
        TLRPC.TL_message copy = copyMessage(messageObject.messageOwner);
        copy.id = messageObject.getRealId();
        copy.realId = 0;
        copy.dialog_id = messageObject.getDialogId();
        MessageObject result = new MessageObject(account, copy, messageObject.replyMessageObject, null, null, null, null, false, true, 0, false, false, false);
        result.isPrimaryGroupMessage = messageObject.isPrimaryGroupMessage;
        result.localGroupId = messageObject.localGroupId;
        result.copyStableParams(messageObject);
        return result;
    }

    /** A feed copy (synthetic id) of an edited channel message, replacing the old row in the store. */
    public static MessageObject createReplacement(int account, long dialogId, MessageObject edited) {
        if (edited == null) {
            return null;
        }
        FeedController controller = FeedController.getInstance(account);
        MessageObject existing = controller.getMessage(dialogId, edited.getRealId());
        if (existing == null) {
            return null;
        }
        TLRPC.TL_message copy = copyMessage(edited.messageOwner);
        copy.id = existing.getId();
        copy.realId = existing.getRealId();
        copy.dialog_id = existing.getDialogId();
        MessageObject result = new MessageObject(account, copy, existing.replyMessageObject, null, null, null, null, true, true, 0, false, false, false, FeedController.SEARCH_FEED);
        result.isPrimaryGroupMessage = existing.isPrimaryGroupMessage;
        result.localGroupId = existing.localGroupId;
        result.copyStableParams(existing);
        controller.replaceMessage(existing, result);
        return result;
    }

    public static ArrayList<MessageObject> createReplacements(int account, long dialogId, ArrayList<MessageObject> edited) {
        ArrayList<MessageObject> result = new ArrayList<>();
        for (int i = 0; i < edited.size(); i++) {
            MessageObject replacement = createReplacement(account, dialogId, edited.get(i));
            if (replacement != null) {
                result.add(replacement);
            }
        }
        return result;
    }

    public static void filterAllowedOptions(ArrayList<CharSequence> items, ArrayList<Integer> options, ArrayList<Integer> icons) {
        for (int i = options.size() - 1; i >= 0; i--) {
            if (!isAllowedFeedOption(options.get(i))) {
                icons.remove(i);
                items.remove(i);
                options.remove(i);
            }
        }
    }

    public static void copyFeedPostLink(ChatActivity chatActivity, MessageObject messageObject) {
        if (chatActivity == null || messageObject == null) {
            return;
        }
        TLRPC.Chat chat = chatActivity.getMessagesController().getChat(-messageObject.getDialogId());
        if (!ChatObject.isChannel(chat)) {
            return;
        }
        TLRPC.TL_channels_exportMessageLink req = new TLRPC.TL_channels_exportMessageLink();
        req.id = messageObject.getRealId();
        req.channel = MessagesController.getInputChannel(chat);
        chatActivity.getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.TL_exportedMessageLink) {
                String link = ((TLRPC.TL_exportedMessageLink) response).link;
                if (AndroidUtilities.addToClipboard(link) && BulletinFactory.canShowBulletin(chatActivity)) {
                    BulletinFactory.of(chatActivity).createCopyLinkBulletin(link.contains("/c/")).show();
                }
            }
        }));
    }

    public static void copyTranslationState(MessageObject from, MessageObject to) {
        if (from == null || to == null || from == to || from.messageOwner == null || to.messageOwner == null) {
            return;
        }
        TLRPC.Message src = from.messageOwner;
        TLRPC.Message dst = to.messageOwner;
        dst.translatedText = src.translatedText;
        dst.translatedToLanguage = src.translatedToLanguage;
        dst.translatedVoiceTranscription = src.translatedVoiceTranscription;
        dst.translatedPoll = src.translatedPoll;
        dst.translatedRichMessage = src.translatedRichMessage;
        dst.summaryText = src.summaryText;
        dst.summarizedOpen = src.summarizedOpen;
        dst.translatedSummaryText = src.translatedSummaryText;
        dst.translatedSummaryLanguage = src.translatedSummaryLanguage;
    }

    private static TLRPC.TL_message copyMessage(TLRPC.Message message) {
        TLRPC.TL_message copy = new TLRPC.TL_message();
        copy.id = message.id;
        copy.from_id = message.from_id;
        copy.from_boosts_applied = message.from_boosts_applied;
        copy.peer_id = message.peer_id;
        copy.saved_peer_id = message.saved_peer_id;
        copy.date = message.date;
        copy.expire_date = message.expire_date;
        copy.action = message.action;
        copy.message = message.message;
        copy.media = message.media;
        copy.flags = message.flags;
        copy.flags2 = message.flags2;
        copy.mentioned = message.mentioned;
        copy.media_unread = message.media_unread;
        copy.out = message.out;
        copy.unread = message.unread;
        copy.entities = message.entities;
        copy.via_bot_name = message.via_bot_name;
        copy.reply_markup = message.reply_markup;
        copy.views = message.views;
        copy.forwards = message.forwards;
        copy.replies = message.replies;
        copy.edit_date = message.edit_date;
        copy.silent = message.silent;
        copy.post = message.post;
        copy.from_scheduled = message.from_scheduled;
        copy.legacy = message.legacy;
        copy.reactions_are_possible = message.reactions_are_possible;
        copy.edit_hide = message.edit_hide;
        copy.pinned = message.pinned;
        copy.fwd_from = message.fwd_from;
        copy.via_bot_id = message.via_bot_id;
        copy.via_business_bot_id = message.via_business_bot_id;
        copy.reply_to = message.reply_to;
        copy.post_author = message.post_author;
        copy.grouped_id = message.grouped_id;
        copy.reactions = message.reactions;
        copy.restriction_reason = message.restriction_reason;
        copy.ttl_period = message.ttl_period;
        copy.quick_reply_shortcut_id = message.quick_reply_shortcut_id;
        copy.effect = message.effect;
        copy.noforwards = message.noforwards;
        copy.invert_media = message.invert_media;
        copy.offline = message.offline;
        copy.video_processing_pending = message.video_processing_pending;
        copy.factcheck = message.factcheck;
        copy.paid_message_stars = message.paid_message_stars;
        copy.suggested_post = message.suggested_post;
        copy.summary_from_language = message.summary_from_language;
        copy.from_rank = message.from_rank;
        copy.guestchat_via_from = message.guestchat_via_from;
        copy.rich_message = message.rich_message;
        copy.send_state = message.send_state;
        copy.fwd_msg_id = message.fwd_msg_id;
        copy.params = message.params;
        copy.random_id = message.random_id;
        copy.local_id = message.local_id;
        copy.attachPath = message.attachPath;
        copy.dialog_id = message.dialog_id;
        copy.ttl = message.ttl;
        copy.destroyTime = message.destroyTime;
        copy.destroyTimeMillis = message.destroyTimeMillis;
        copy.layer = message.layer;
        copy.seq_in = message.seq_in;
        copy.seq_out = message.seq_out;
        copy.with_my_score = message.with_my_score;
        copy.replyMessage = message.replyMessage;
        copy.reqId = message.reqId;
        copy.realId = message.realId;
        copy.stickerVerified = message.stickerVerified;
        copy.isThreadMessage = message.isThreadMessage;
        copy.voiceTranscription = message.voiceTranscription;
        copy.voiceTranscriptionOpen = message.voiceTranscriptionOpen;
        copy.voiceTranscriptionRated = message.voiceTranscriptionRated;
        copy.voiceTranscriptionFinal = message.voiceTranscriptionFinal;
        copy.voiceTranscriptionForce = message.voiceTranscriptionForce;
        copy.voiceTranscriptionId = message.voiceTranscriptionId;
        copy.premiumEffectWasPlayed = message.premiumEffectWasPlayed;
        copy.originalLanguage = message.originalLanguage;
        copy.translatedToLanguage = message.translatedToLanguage;
        copy.translatedText = message.translatedText;
        copy.translatedRichMessage = message.translatedRichMessage;
        copy.translatedVoiceTranscription = message.translatedVoiceTranscription;
        copy.translatedPoll = message.translatedPoll;
        copy.replyStory = message.replyStory;
        copy.quick_reply_shortcut = message.quick_reply_shortcut;
        copy.summarizedOpen = message.summarizedOpen;
        copy.summaryText = message.summaryText;
        copy.translatedSummaryLanguage = message.translatedSummaryLanguage;
        copy.translatedSummaryText = message.translatedSummaryText;
        return copy;
    }
}
