package com.exteragram.messenger.utils.chats;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_keyboard;
import org.telegram.ui.Components.AnimatedEmojiDrawable;

import java.util.ArrayList;

/**
 * exteraGram: emoji packs used by a message outside of its text (custom emoji reactions and
 * custom emoji icons of inline buttons), shown in the "message contains emoji" button.
 */
public final class MessageEmojiPacks {

    private MessageEmojiPacks() {
    }

    public static ArrayList<TLRPC.InputStickerSet> collect(int account, MessageObject messageObject) {
        ArrayList<TLRPC.InputStickerSet> sets = new ArrayList<>();
        if (messageObject == null || messageObject.messageOwner == null) {
            return sets;
        }
        TLRPC.Message message = messageObject.messageOwner;
        if (message.reactions != null && message.reactions.results != null) {
            for (int i = 0; i < message.reactions.results.size(); i++) {
                TLRPC.ReactionCount count = message.reactions.results.get(i);
                if (count != null && count.reaction instanceof TLRPC.TL_reactionCustomEmoji) {
                    add(sets, AnimatedEmojiDrawable.findStickerSet(account, ((TLRPC.TL_reactionCustomEmoji) count.reaction).document_id));
                }
            }
        }
        if (message.reply_markup instanceof TLRPC.TL_replyInlineMarkup) {
            ArrayList<TL_keyboard.KeyboardInlineButtonRow> rows = ((TLRPC.TL_replyInlineMarkup) message.reply_markup).rows;
            for (int i = 0; i < rows.size(); i++) {
                ArrayList<TL_keyboard.KeyboardInlineButton> buttons = rows.get(i).buttons;
                for (int j = 0; j < buttons.size(); j++) {
                    TL_keyboard.KeyboardButtonStyle style = buttons.get(j).style;
                    if (style != null && style.icon != 0) {
                        add(sets, AnimatedEmojiDrawable.findStickerSet(account, style.icon));
                    }
                }
            }
        }
        return sets;
    }

    public static void addAll(ArrayList<TLRPC.InputStickerSet> to, ArrayList<TLRPC.InputStickerSet> from) {
        for (int i = 0; i < from.size(); i++) {
            add(to, from.get(i));
        }
    }

    private static void add(ArrayList<TLRPC.InputStickerSet> sets, TLRPC.InputStickerSet set) {
        if (set == null) {
            return;
        }
        for (int i = 0; i < sets.size(); i++) {
            if (sets.get(i).id == set.id) {
                return;
            }
        }
        sets.add(set);
    }
}
