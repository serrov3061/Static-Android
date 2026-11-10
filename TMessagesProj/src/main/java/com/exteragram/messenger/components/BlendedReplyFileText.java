package com.exteragram.messenger.components;

import android.content.Context;
import android.graphics.Paint;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;

import com.exteragram.messenger.utils.chats.ChatUtils;
import com.exteragram.messenger.utils.text.LocaleUtils;

import java.util.ArrayList;
import java.util.EnumMap;

import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;

/**
 * exteraGram BlendedReplyFileText (port): reply preview "icon + file name/type" for media replies.
 * PLUGIN and BACKUP detection belong to later stages (plugins / settings export) and are disabled.
 */
public final class BlendedReplyFileText {

    public enum AttachType {
        MUSIC(R.drawable.filled_widget_music, null),
        GIFT(R.drawable.gift, R.string.ActionStarGift),
        VOICE(R.drawable.msg_filled_data_voice, R.string.Voice),
        ROUND_VIDEO(R.drawable.filled_profile_video_24, R.string.AttachRound),
        PHOTO(R.drawable.msg_filled_reply_image, R.string.StoryPhoto),
        VIDEO(R.drawable.filled_profile_video_24, R.string.StoryVideo),
        PLUGIN(R.drawable.plugins_filled, null),
        BACKUP(R.drawable.filled_profile_settings, null),
        ICON_PACK(R.drawable.stickers_filled, null),
        FILE(R.drawable.msg_filled_reply_files, null),
        UNKNOWN(R.drawable.msg_filled_reply_files, null);

        private final int iconRes;
        private final Integer stringRes;

        AttachType(int iconRes, Integer stringRes) {
            this.iconRes = iconRes;
            this.stringRes = stringRes;
        }

        public int getIconRes() {
            return iconRes;
        }

        public Integer getStringRes() {
            return stringRes;
        }
    }

    private final EnumMap<AttachType, BlendedReplyColorIconSpan> icons = new EnumMap<>(AttachType.class);

    private static boolean isImageMime(String mime) {
        return mime != null && mime.startsWith("image/");
    }

    private static boolean isVideoMime(String mime) {
        return mime != null && mime.startsWith("video/");
    }

    public static AttachType getType(MessageObject reply) {
        if (reply == null) {
            return AttachType.UNKNOWN;
        }
        TLRPC.Document document = reply.getDocument();
        String mime = document != null ? document.mime_type : null;
        boolean isFile = reply.type == MessageObject.TYPE_FILE;
        if (reply.type == MessageObject.TYPE_MUSIC) {
            return AttachType.MUSIC;
        }
        if (reply.isAnyGift()) {
            return AttachType.GIFT;
        }
        if (reply.type == MessageObject.TYPE_VOICE) {
            return AttachType.VOICE;
        }
        if (reply.type == MessageObject.TYPE_ROUND_VIDEO) {
            return AttachType.ROUND_VIDEO;
        }
        if (reply.type == MessageObject.TYPE_PHOTO || isFile && isImageMime(mime)) {
            return AttachType.PHOTO;
        }
        if (reply.type == MessageObject.TYPE_VIDEO || isFile && isVideoMime(mime)) {
            return AttachType.VIDEO;
        }
        if ("image/gif".equals(mime) || isVideoMime(mime)) {
            return AttachType.UNKNOWN;
        }
        if (isFile) {
            String path = ChatUtils.getInstance().getPathToMessage(reply);
            if (!TextUtils.isEmpty(path) && reply.getDocumentName() != null && path.endsWith(".icons")) {
                return AttachType.ICON_PACK;
            }
            return AttachType.FILE;
        }
        return AttachType.UNKNOWN;
    }

    public CharSequence build(Context context, MessageObject reply, AttachType type, Paint.FontMetricsInt fontMetrics) {
        BlendedReplyColorIconSpan icon = icons.get(type);
        if (icon == null) {
            icon = createIcon(context, type.getIconRes());
            icons.put(type, icon);
        }
        String name;
        if (type == AttachType.MUSIC) {
            name = reply.getMusicAuthor() + " – " + reply.getMusicTitle();
        } else if (type.getStringRes() != null) {
            name = LocaleController.getString(type.getStringRes());
        } else {
            name = reply.getDocumentName();
            if (TextUtils.isEmpty(name)) {
                name = LocaleController.getString(R.string.AttachDocument);
            }
        }
        SpannableStringBuilder sb = new SpannableStringBuilder("d ").append(Emoji.replaceEmoji(name, fontMetrics, false));
        sb.setSpan(icon, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new BlendedReplyColorSpan(), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (TextUtils.isEmpty(reply.caption)) {
            return sb;
        }
        return sb.append(", ").append(formatCaption(reply, reply.caption, fontMetrics));
    }

    private CharSequence formatCaption(MessageObject reply, CharSequence caption, Paint.FontMetricsInt fontMetrics) {
        String text = caption.toString();
        if (text.length() > 150) {
            text = text.substring(0, 150);
        }
        CharSequence result = Emoji.replaceEmoji(text.replace('\n', ' '), fontMetrics, true);
        if (reply.messageOwner == null || reply.messageOwner.entities == null) {
            return result;
        }
        ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>(reply.messageOwner.entities);
        LocaleUtils.parseCustomEmojis(result, entities);
        Spannable spannable = MessageObject.replaceAnimatedEmoji(result, entities, fontMetrics, true);
        MediaDataController.addTextStyleRuns(reply.messageOwner.entities, caption, spannable);
        return spannable;
    }

    private BlendedReplyColorIconSpan createIcon(Context context, int res) {
        BlendedReplyColorIconSpan span = new BlendedReplyColorIconSpan(ContextCompat.getDrawable(context, res).mutate());
        span.setScale(0.7f, 0.7f);
        return span;
    }
}
