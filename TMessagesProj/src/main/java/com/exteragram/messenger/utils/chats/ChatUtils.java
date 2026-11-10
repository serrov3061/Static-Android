package com.exteragram.messenger.utils.chats;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.Base64;

import androidx.core.content.FileProvider;

import com.exteragram.messenger.ExteraConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.WebFile;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChannelAdminLogActivity;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;
import org.telegram.ui.Components.TranscribeButton;
import org.telegram.ui.Components.VideoPlayer;
import org.telegram.ui.ProfileActivity;

/**
 * exteraGram ChatUtils (port, stage 3).
 * Differences from exteraGram: user/chat lookup by id through the third-party inline bot
 * (tgdb_search_bot) and requests to the exteraGram API bot are disabled - on the StaticGram
 * network those bots do not exist, so lookups only use the local cache.
 */
public class ChatUtils {

    private static SpannableStringBuilder channelIcon;
    private static SpannableStringBuilder editedIcon;

    public static final DispatchQueue utilsQueue = new DispatchQueue("utilsQueue");
    private static final ChatUtils[] Instance = new ChatUtils[16];
    private static final Object lockObject = new Object();
    private static final CharsetDecoder textDecoder = StandardCharsets.UTF_8.newDecoder();

    private final int selectedAccount;

    public ChatUtils(int account) {
        this.selectedAccount = account;
    }

    public static ChatUtils getInstance() {
        return getInstance(UserConfig.selectedAccount);
    }

    public static ChatUtils getInstance(int account) {
        ChatUtils local = Instance[account];
        if (local != null) {
            return local;
        }
        synchronized (lockObject) {
            local = Instance[account];
            if (local == null) {
                local = new ChatUtils(account);
                Instance[account] = local;
            }
        }
        return local;
    }

    public static long extractOwnerId(long id) {
        long ownerId = id >> 32;
        if (((id >> 16) & 255) == 63) {
            ownerId |= 2147483648L;
        }
        return ((id >> 24) & 255) != 0 ? ownerId + 4294967296L : ownerId;
    }

    public static CharSequence getEditedIcon() {
        if (editedIcon == null) {
            editedIcon = new SpannableStringBuilder("‍");
            ColoredImageSpan span = new ColoredImageSpan(Theme.chat_pencilIconDrawable);
            span.setTranslateX(-AndroidUtilities.dp(1.0f));
            editedIcon.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return editedIcon;
    }

    public static CharSequence getChannelIcon() {
        if (channelIcon == null) {
            channelIcon = new SpannableStringBuilder("‍");
            channelIcon.setSpan(new ColoredImageSpan(Theme.chat_channelIconDrawable), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return channelIcon;
    }

    public static boolean hasRestrictionReason(ArrayList<TLRPC.RestrictionReason> reasons, String reason) {
        if (reasons != null && !TextUtils.isEmpty(reason)) {
            for (int i = 0; i < reasons.size(); i++) {
                TLRPC.RestrictionReason r = reasons.get(i);
                if (r != null && reason.equals(r.reason)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isTermsRestrictedMessage(MessageObject messageObject) {
        return messageObject != null && messageObject.messageOwner != null && hasRestrictionReason(messageObject.messageOwner.restriction_reason, "terms");
    }

    public static boolean applyChannelPostContent(TLRPC.Message message, TLRPC.Message channelPost) {
        TLRPC.MessageFwdHeader fwd;
        if (message == null || channelPost == null || (fwd = message.fwd_from) == null || fwd.saved_from_msg_id != channelPost.id || !MessageObject.peersEqual(fwd.saved_from_peer, channelPost.peer_id)) {
            return false;
        }
        boolean changed = reactionsChanged(message.reactions, channelPost.reactions)
                || !TextUtils.equals(message.message, channelPost.message)
                || message.edit_date != channelPost.edit_date
                || message.edit_hide != channelPost.edit_hide
                || mediaChanged(message.media, channelPost.media)
                || entitiesChanged(message.entities, channelPost.entities);
        message.reactions = channelPost.reactions;
        message.message = channelPost.message;
        message.media = channelPost.media;
        message.entities = channelPost.entities;
        message.edit_date = channelPost.edit_date;
        message.edit_hide = channelPost.edit_hide;
        message.flags = (message.flags & (-1081985))
                | (message.media != null ? 512 : 0)
                | ((message.entities == null || message.entities.isEmpty()) ? 0 : 128)
                | (message.edit_date != 0 ? 32768 : 0)
                | (message.reactions != null ? 1048576 : 0);
        return changed;
    }

    private static boolean reactionsChanged(TLRPC.MessageReactions a, TLRPC.MessageReactions b) {
        if (a == b) {
            return false;
        }
        if (a == null || b == null) {
            return true;
        }
        int size = a.results == null ? 0 : a.results.size();
        if (size != (b.results == null ? 0 : b.results.size())) {
            return true;
        }
        for (int i = 0; i < size; i++) {
            TLRPC.ReactionCount ra = a.results.get(i);
            TLRPC.ReactionCount rb = b.results.get(i);
            if (ra == null || rb == null) {
                if (ra != rb) {
                    return true;
                }
            } else if (ra.count != rb.count || ra.chosen_order != rb.chosen_order || !ReactionsLayoutInBubble.equalsTLReaction(ra.reaction, rb.reaction)) {
                return true;
            }
        }
        return false;
    }

    private static boolean mediaChanged(TLRPC.MessageMedia a, TLRPC.MessageMedia b) {
        if (a == b) {
            return false;
        }
        return a == null || b == null || a.getClass() != b.getClass() || mediaIdentity(a) != mediaIdentity(b);
    }

    private static long mediaIdentity(TLRPC.MessageMedia media) {
        if (media.photo != null) {
            return media.photo.id;
        }
        if (media.document != null) {
            return media.document.id;
        }
        if (media.webpage != null) {
            return media.webpage.id;
        }
        if (media.game != null) {
            return media.game.id;
        }
        return media.id;
    }

    private static boolean entitiesChanged(ArrayList<TLRPC.MessageEntity> a, ArrayList<TLRPC.MessageEntity> b) {
        if (a == b) {
            return false;
        }
        int size = a == null ? 0 : a.size();
        if (size != (b == null ? 0 : b.size())) {
            return true;
        }
        for (int i = 0; i < size; i++) {
            TLRPC.MessageEntity ea = a.get(i);
            TLRPC.MessageEntity eb = b.get(i);
            if (ea == null || eb == null) {
                if (ea != eb) {
                    return true;
                }
            } else if (ea.getClass() != eb.getClass() || ea.offset != eb.offset || ea.length != eb.length || !TextUtils.equals(ea.url, eb.url)) {
                return true;
            }
        }
        return false;
    }

    public static String getDCName(int dc) {
        switch (dc) {
            case 1:
            case 3:
                return "Miami FL, USA";
            case 2:
            case 4:
                return "Amsterdam, NL";
            case 5:
                return "Singapore, SG";
            default:
                return null;
        }
    }

    private static boolean fileExists(String path) {
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        File file = new File(path);
        return file.exists() && file.isFile();
    }

    public CharSequence getMessageText(MessageObject messageObject, MessageObject.GroupedMessages group) {
        CharSequence text = null;
        int type = messageObject.type;
        if (type != MessageObject.TYPE_EMOJIS && type != MessageObject.TYPE_ANIMATED_STICKER && type != MessageObject.TYPE_STICKER) {
            text = getMessageCaption(messageObject, group);
            if (text == null && messageObject.isPoll()) {
                try {
                    TLRPC.Poll poll = ((TLRPC.TL_messageMediaPoll) messageObject.messageOwner.media).poll;
                    StringBuilder sb = new StringBuilder(poll.question.text);
                    sb.append("\n");
                    for (int i = 0; i < poll.answers.size(); i++) {
                        TLRPC.PollAnswer answer = poll.answers.get(i);
                        sb.append("\n🔘 ");
                        sb.append(answer.text == null ? "" : answer.text.text);
                    }
                    text = sb.toString();
                } catch (Exception ignore) {
                }
            }
            if (text == null && MessageObject.isMediaEmpty(messageObject.messageOwner)) {
                text = getMessageContent(messageObject);
            }
        }
        if (messageObject.translated || messageObject.isRestrictedMessage) {
            return null;
        }
        return text;
    }

    private CharSequence getMessageCaption(MessageObject messageObject, MessageObject.GroupedMessages group) {
        String restrictionReason = getMessagesController().getRestrictionReason(messageObject.messageOwner.restriction_reason);
        if (!TextUtils.isEmpty(restrictionReason)) {
            return restrictionReason;
        }
        if (messageObject.isVoiceTranscriptionOpen() && !TranscribeButton.isTranscribing(messageObject)) {
            return messageObject.getVoiceTranscription();
        }
        if (messageObject.caption != null) {
            return messageObject.caption;
        }
        if (group == null) {
            return null;
        }
        CharSequence caption = null;
        for (int i = 0; i < group.messages.size(); i++) {
            CharSequence c = group.messages.get(i).caption;
            if (c != null) {
                if (caption != null) {
                    return null;
                }
                caption = c;
            }
        }
        return caption;
    }

    private CharSequence getMessageContent(MessageObject messageObject) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String restrictionReason = getMessagesController().getRestrictionReason(messageObject.messageOwner.restriction_reason);
        if (!TextUtils.isEmpty(restrictionReason)) {
            sb.append(restrictionReason);
        } else if (messageObject.caption != null) {
            sb.append(messageObject.caption);
        } else if (messageObject.messageText != null) {
            sb.append(messageObject.messageText);
        }
        return sb.toString();
    }

    public ArrayList<TLRPC.MessageEntity> copyMessageEntities(ArrayList<TLRPC.MessageEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return null;
        }
        ArrayList<TLRPC.MessageEntity> result = new ArrayList<>();
        for (int i = 0; i < entities.size(); i++) {
            TLRPC.MessageEntity entity = entities.get(i);
            if (entity instanceof TLRPC.TL_messageEntityMentionName) {
                TLRPC.TL_inputMessageEntityMentionName mention = new TLRPC.TL_inputMessageEntityMentionName();
                mention.length = entity.length;
                mention.offset = entity.offset;
                mention.user_id = getMessagesController().getInputUser(((TLRPC.TL_messageEntityMentionName) entity).user_id);
                result.add(mention);
            } else {
                result.add(entity);
            }
        }
        return result;
    }

    public MessageObject getMessageForRepeat(MessageObject messageObject, MessageObject.GroupedMessages group) {
        if (group != null && !group.isDocuments) {
            return getTargetMessageObjectFromGroup(group);
        }
        TLRPC.MessageMedia media = messageObject.messageOwner.media;
        if (TextUtils.isEmpty(messageObject.messageOwner.message) && !messageObject.isAnyKindOfSticker() && (media == null || media instanceof TLRPC.TL_messageMediaEmpty || media instanceof TLRPC.TL_messageMediaWebPage)) {
            return null;
        }
        return messageObject;
    }

    public boolean hasMediaForRepeat(MessageObject messageObject) {
        if (messageObject.isAnyKindOfSticker()) {
            return false;
        }
        TLRPC.MessageMedia media = messageObject.messageOwner.media;
        return media != null && !(media instanceof TLRPC.TL_messageMediaEmpty) && !(media instanceof TLRPC.TL_messageMediaWebPage);
    }

    private MessageObject getTargetMessageObjectFromGroup(MessageObject.GroupedMessages group) {
        MessageObject target = null;
        for (int i = 0; i < group.messages.size(); i++) {
            MessageObject m = group.messages.get(i);
            if (!TextUtils.isEmpty(m.messageOwner.message)) {
                if (target != null) {
                    return null;
                }
                target = m;
            }
        }
        return target;
    }

    public String getTextFromCallback(byte[] data) {
        try {
            synchronized (textDecoder) {
                return textDecoder.decode(ByteBuffer.wrap(data)).toString();
            }
        } catch (CharacterCodingException e) {
            return Base64.encodeToString(data, Base64.NO_PADDING | Base64.NO_WRAP);
        }
    }

    public long getEmojiIdFrom(MessageObject messageObject, TLRPC.User user) {
        MessageObject reply;
        if (messageObject == null || messageObject.messageOwner == null || (reply = messageObject.replyMessageObject) == null || reply.messageOwner == null || reply.messageOwner.from_id == null) {
            return 0;
        }
        if (DialogObject.isEncryptedDialog(reply.getDialogId())) {
            if (reply.isOutOwner()) {
                user = getUserConfig().getCurrentUser();
            }
            return user != null ? UserObject.getEmojiId(user) : 0;
        }
        if (reply.isFromUser()) {
            TLRPC.User u = getMessagesController().getUser(reply.messageOwner.from_id.user_id);
            return u != null ? UserObject.getEmojiId(u) : 0;
        }
        if (reply.isFromChannel()) {
            TLRPC.Chat chat = getMessagesController().getChat(reply.messageOwner.from_id.channel_id);
            if (chat != null) {
                return ChatObject.getEmojiId(chat);
            }
        }
        return 0;
    }

    public TLRPC.InputStickerSet getSetFrom(MessageObject messageObject, TLRPC.User user) {
        return AnimatedEmojiDrawable.findStickerSet(selectedAccount, getEmojiIdFrom(messageObject, user));
    }

    public TLRPC.InputStickerSet getSetFrom(TLRPC.User user) {
        return AnimatedEmojiDrawable.findStickerSet(selectedAccount, UserObject.getProfileEmojiId(user));
    }

    public TLRPC.InputStickerSet getSetFrom(TLRPC.Chat chat) {
        return AnimatedEmojiDrawable.findStickerSet(selectedAccount, ChatObject.getProfileEmojiId(chat));
    }

    public String getDC(TLRPC.User user) {
        return getDC(user, null);
    }

    public String getDC(TLRPC.Chat chat) {
        return getDC(null, chat);
    }

    private int getUserProfileDC(TLRPC.User user) {
        TLRPC.UserFull userFull = getMessagesController().getUserFull(user.id);
        if (userFull != null && userFull.profile_photo != null && !(userFull.profile_photo instanceof TLRPC.TL_photoEmpty) && userFull.profile_photo.dc_id > 0) {
            return userFull.profile_photo.dc_id;
        }
        TLRPC.UserProfilePhoto photo = user.photo;
        if (photo != null && !photo.personal) {
            return photo.dc_id;
        }
        return -1;
    }

    public String getDC(TLRPC.User user, TLRPC.Chat chat) {
        int dc = getConnectionsManager().getCurrentDatacenterId();
        if (user != null) {
            if (!UserObject.isUserSelf(user) || dc == -1) {
                dc = getUserProfileDC(user);
            }
        } else if (chat != null) {
            dc = chat.photo != null ? chat.photo.dc_id : -1;
        } else {
            dc = 0;
        }
        if (dc == -1 || dc == 0) {
            return getDCName(0);
        }
        return String.format(Locale.ROOT, "DC%d, %s", dc, getDCName(dc));
    }

    public String getName(long dialogId) {
        String name = null;
        if (DialogObject.isEncryptedDialog(dialogId)) {
            TLRPC.EncryptedChat encryptedChat = getMessagesController().getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
            if (encryptedChat != null) {
                TLRPC.User user = getMessagesController().getUser(encryptedChat.user_id);
                if (user != null) {
                    name = ContactsController.formatName(user.first_name, user.last_name);
                }
            }
        } else if (DialogObject.isUserDialog(dialogId)) {
            TLRPC.User user = getMessagesController().getUser(dialogId);
            if (user != null) {
                name = ContactsController.formatName(user);
            }
        } else {
            TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
            if (chat != null) {
                name = chat.title;
            }
        }
        return dialogId == getUserConfig().getClientUserId() ? LocaleController.getString(R.string.SavedMessages) : name;
    }

    public Runnable searchUserById(Long userId, Utilities.Callback<TLRPC.User> callback) {
        return searchUserById(userId, callback, null);
    }

    /**
     * exteraGram looks up unknown users through the inline bot @tgdb_search_bot (Telegram network).
     * StaticGram: that bot is not available, only the local cache is used.
     */
    public Runnable searchUserById(Long userId, Utilities.Callback<TLRPC.User> callback, Utilities.Callback<Runnable> cancelCallback) {
        if (userId == null || userId == 0) {
            return null;
        }
        TLRPC.User user = getMessagesController().getUser(userId);
        if (user != null) {
            callback.run(user);
            return null;
        }
        callback.run(null);
        return null;
    }

    /**
     * exteraGram API bot request: an inline query to the API bot, the answer is results[0].send_message.message.
     * StaticGram: the bot is our in-network badge bot from app config (sg_badges_bot); without it the answer is null.
     */
    public void sendBotRequest(String query, boolean useCache, Utilities.Callback<String> callback) {
        String username = com.exteragram.messenger.badges.BadgesConfig.getBotUsername();
        if (TextUtils.isEmpty(username)) {
            callback.run(null);
            return;
        }
        TLObject cached = getMessagesController().getUserOrChat(username);
        if (cached instanceof TLRPC.User) {
            sendInlineBotRequest((TLRPC.User) cached, query, useCache, results -> processBotResponse(results, callback));
            return;
        }
        getMessagesController().getUserNameResolver().resolve(username, id -> {
            TLRPC.User bot = id != null && id > 0 ? getMessagesController().getUser(id) : null;
            if (bot == null) {
                callback.run(null);
                return;
            }
            sendInlineBotRequest(bot, query, useCache, results -> processBotResponse(results, callback));
        });
    }

    private void processBotResponse(TLRPC.messages_BotResults results, Utilities.Callback<String> callback) {
        if (results != null && !results.results.isEmpty()) {
            TLRPC.BotInlineMessage message = results.results.get(0).send_message;
            if (message != null && message.message != null) {
                callback.run(message.message);
                return;
            }
        }
        callback.run(null);
    }

    public Runnable sendInlineBotRequest(TLRPC.User bot, String query, boolean useCache, Utilities.Callback<TLRPC.messages_BotResults> callback) {
        return sendInlineBotRequest(bot, query, useCache, callback, null);
    }

    public Runnable sendInlineBotRequest(TLRPC.User bot, String query, boolean useCache, Utilities.Callback<TLRPC.messages_BotResults> callback, Utilities.Callback<Runnable> cancelCallback) {
        if (bot == null) {
            callback.run(null);
            return null;
        }
        final String key = "bot_inline_query_" + bot.id + "_" + query;
        RequestDelegate delegate = (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (useCache && (!(response instanceof TLRPC.messages_BotResults) || ((TLRPC.messages_BotResults) response).results.isEmpty())) {
                Runnable cancel = sendInlineBotRequest(bot, query, false, callback);
                if (cancelCallback != null) {
                    cancelCallback.run(cancel);
                }
                return;
            }
            if (response instanceof TLRPC.messages_BotResults) {
                TLRPC.messages_BotResults results = (TLRPC.messages_BotResults) response;
                if (!useCache && results.cache_time != 0) {
                    getMessageStorage().saveBotCache(key, results);
                }
                callback.run(results);
                return;
            }
            callback.run(null);
        });
        if (useCache) {
            getMessageStorage().getBotCache(key, delegate);
            return null;
        }
        TLRPC.TL_messages_getInlineBotResults req = new TLRPC.TL_messages_getInlineBotResults();
        req.query = query;
        req.bot = getMessagesController().getInputUser(bot);
        req.offset = "";
        req.peer = new TLRPC.TL_inputPeerEmpty();
        final int reqId = getConnectionsManager().sendRequest(req, delegate, ConnectionsManager.RequestFlagFailOnServerErrors);
        return () -> getConnectionsManager().cancelRequest(reqId, false);
    }

    public Runnable resolveUser(String username, long userId, Utilities.Callback<TLRPC.User> callback) {
        return getMessagesController().getUserNameResolver().resolve(username, id -> {
            if (id != null && id > 0 && id == userId) {
                callback.run(getMessagesController().getUser(userId));
            } else {
                callback.run(null);
            }
        });
    }

    /** exteraGram looks up unknown chats through @tgdb_search_bot; StaticGram uses only the local cache. */
    public void searchChatById(long chatId, Utilities.Callback<TLRPC.Chat> callback) {
        if (chatId == 0) {
            callback.run(null);
            return;
        }
        callback.run(getMessagesController().getChat(chatId));
    }

    public void resolveChannel(String username, Utilities.Callback<TLRPC.Chat> callback) {
        getMessagesController().getUserNameResolver().resolve(username, id -> {
            if (id != null && id < 0) {
                callback.run(getMessagesController().getChat(-id));
            } else {
                callback.run(null);
            }
        });
    }

    public MessagesController getMessagesController() {
        return MessagesController.getInstance(selectedAccount);
    }

    public MessagesStorage getMessageStorage() {
        return MessagesStorage.getInstance(selectedAccount);
    }

    public ConnectionsManager getConnectionsManager() {
        return ConnectionsManager.getInstance(selectedAccount);
    }

    public FileLoader getFileLoader() {
        return FileLoader.getInstance(selectedAccount);
    }

    public UserConfig getUserConfig() {
        return UserConfig.getInstance(selectedAccount);
    }

    public static void addFileToClipboard(File file, Runnable onDone) {
        try {
            Context context = ApplicationLoader.applicationContext;
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            Uri uri = FileProvider.getUriForFile(context, ApplicationLoader.getApplicationId() + ".provider", file);
            clipboard.setPrimaryClip(ClipData.newUri(context.getContentResolver(), "label", uri));
            if (onDone != null) {
                onDone.run();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public void addMessageToClipboard(MessageObject messageObject, Runnable onDone) {
        String path = getPathToMessage(messageObject);
        if (TextUtils.isEmpty(path)) {
            return;
        }
        addFileToClipboard(new File(path), onDone);
    }

    public String getPathToMessage(MessageObject messageObject) {
        if (messageObject == null) {
            return null;
        }
        FileLoader fileLoader = FileLoader.getInstance(messageObject.currentAccount);
        TLRPC.Message message = messageObject.messageOwner;
        if (message != null && !TextUtils.isEmpty(message.attachPath) && fileExists(message.attachPath)) {
            return message.attachPath;
        }
        if (message != null) {
            String path = fileLoader.getPathToMessage(message).toString();
            if (fileExists(path)) {
                return path;
            }
        }
        if (messageObject.getDocument() != null) {
            String path = fileLoader.getPathToAttach(messageObject.getDocument(), false).toString();
            if (fileExists(path)) {
                return path;
            }
            path = fileLoader.getPathToAttach(messageObject.getDocument(), true).toString();
            if (fileExists(path)) {
                return path;
            }
        }
        VideoPlayer.VideoUri cached = messageObject.cachedQuality;
        if (cached != null && cached.isCached() && cached.uri != null) {
            String path = cached.uri.getPath();
            if (fileExists(path)) {
                return path;
            }
        }
        if (messageObject.qualityToSave != null) {
            String path = fileLoader.getPathToAttach(messageObject.qualityToSave, null, false, true).toString();
            if (fileExists(path)) {
                return path;
            }
        }
        return null;
    }

    public boolean canSaveSticker(MessageObject messageObject) {
        return MessageObject.isStickerDocument(messageObject.getDocument()) || isEmoji(messageObject.getDocument());
    }

    public boolean isEmoji(TLRPC.Document document) {
        return MessageObject.isAnimatedEmoji(document) && !MessageObject.isAnimatedStickerDocument(document, false);
    }

    public void saveStickerToGallery(Activity activity, MessageObject messageObject, Utilities.Callback<Uri> callback) {
        saveStickerToGallery(activity, getPathToMessage(messageObject), messageObject.isVideoSticker(), callback);
    }

    public void saveStickerToGallery(Activity activity, TLRPC.Document document, Utilities.Callback<Uri> callback) {
        String path = getFileLoader().getPathToAttach(document, true).toString();
        if (new File(path).exists()) {
            saveStickerToGallery(activity, path, MessageObject.isVideoSticker(document), callback);
        }
    }

    public void saveStickerToGallery(Activity activity, String path, boolean video, Utilities.Callback<Uri> callback) {
        utilsQueue.postRunnable(() -> {
            if (TextUtils.isEmpty(path)) {
                return;
            }
            try {
                if (video) {
                    AndroidUtilities.runOnUIThread(() -> MediaController.saveFile(path, activity, 1, null, null, callback));
                    return;
                }
                Bitmap bitmap = BitmapFactory.decodeFile(path);
                if (bitmap != null) {
                    File file = new File(path.endsWith(".webp") ? path.replace(".webp", ".png") : path.concat(".png"));
                    FileOutputStream stream = new FileOutputStream(file);
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                    stream.close();
                    AndroidUtilities.runOnUIThread(() -> MediaController.saveFile(file.toString(), activity, 0, null, null, callback));
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void saveGifToGallery(Activity activity, TLRPC.Document document, TLRPC.BotInlineResult result, Utilities.Callback<Uri> callback) {
        String path = getGifPath(document, result);
        if (TextUtils.isEmpty(path)) {
            return;
        }
        utilsQueue.postRunnable(() -> AndroidUtilities.runOnUIThread(() -> MediaController.saveFile(path, activity, 1, null, null, callback)));
    }

    private String getGifPath(TLRPC.Document document, TLRPC.BotInlineResult result) {
        if (document != null) {
            String path = getExistingPath(getFileLoader().getPathToAttach(document, false));
            return path != null ? path : getExistingPath(getFileLoader().getPathToAttach(document, true));
        }
        if (result == null || result.content == null) {
            return null;
        }
        WebFile webFile = WebFile.createWithWebDocument(result.content);
        String path = getExistingPath(getFileLoader().getPathToAttach(webFile, false));
        return path != null ? path : getExistingPath(getFileLoader().getPathToAttach(webFile, true));
    }

    private String getExistingPath(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        return file.toString();
    }

    public boolean hasArchivedChats() {
        ArrayList<TLRPC.Dialog> archived = getMessagesController().dialogsByFolder.get(1);
        return archived != null && !archived.isEmpty();
    }

    public long getLikeDialog() {
        return ExteraConfig.getPreferences().getLong("channelToSave" + selectedAccount, getUserConfig().getClientUserId());
    }

    public void setLikeDialog(long dialogId) {
        ExteraConfig.getEditor().putLong("channelToSave" + selectedAccount, dialogId).apply();
    }

    public boolean shouldAddTimestamp(MessageObject messageObject, CharSequence text) {
        TLRPC.Message message = messageObject.messageOwner;
        if (message == null) {
            return false;
        }
        return (messageObject.currentEvent != null || message.action != null) && !TextUtils.isEmpty(text);
    }

    public CharSequence addTimestamp(CharSequence text, long date, Theme.ResourcesProvider resourcesProvider) {
        String time = LocaleController.getInstance().getFormatterDay().format(date * 1000);
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        ProfileActivity.ShowDrawable drawable = ChannelAdminLogActivity.findDrawable(text);
        if (drawable == null) {
            drawable = new ProfileActivity.ShowDrawable(time);
            drawable.textDrawable.setTypeface(AndroidUtilities.bold());
            drawable.textDrawable.setTextSize(AndroidUtilities.dp(10.0f));
            drawable.setTextColor(Theme.getThemePaint(Theme.key_paint_chatActionText, resourcesProvider).getColor());
            drawable.setBackgroundColor(0x1E000000);
        } else {
            drawable.textDrawable.setText(time, false);
        }
        drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        sb.append(" S");
        sb.setSpan(new ColoredImageSpan(drawable), sb.length() - 1, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }
}
