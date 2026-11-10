package com.exteragram.messenger.reactions;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.text.Spanned;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import kotlin.collections.ArraysKt;
import kotlin.collections.CollectionsKt;
import kotlin.comparisons.ComparisonsKt;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.internal.ArrayIteratorKt;
import kotlin.jvm.internal.Intrinsics;
import kotlin.text.MatchResult;
import kotlin.text.Regex;
import kotlin.text.StringsKt;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AnimatedEmojiSpan;

public final class ReactionsEditHelper {
    private static final Regex EMOJI_LINK = new Regex("tg://emoji\\?id=(\\d+)");
    private final int account;
    private final List<TLRPC.TL_availableReaction> availableReactions;
    private final long chatId;

    /* JADX WARN: Multi-variable type inference failed */
    public ReactionsEditHelper(int i, long j, List<? extends TLRPC.TL_availableReaction> list) {
        this.account = i;
        this.chatId = j;
        this.availableReactions = (List) list;
    }
    public static final /* data */ class Backup {
        private final boolean paid;
        private final List<Long> reactionIds;

        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Backup)) {
                return false;
            }
            Backup backup = (Backup) other;
            return Intrinsics.areEqual(this.reactionIds, backup.reactionIds) && this.paid == backup.paid;
        }

        public int hashCode() {
            return (this.reactionIds.hashCode() * 31) + Boolean.hashCode(this.paid);
        }

        public String toString() {
            return "Backup(reactionIds=" + this.reactionIds + ", paid=" + this.paid + ")";
        }

        public Backup(List<Long> list, boolean z) {
            this.reactionIds = list;
            this.paid = z;
        }

        public final boolean getPaid() {
            return this.paid;
        }

        public final List<Long> getReactionIds() {
            return this.reactionIds;
        }
    }

    private final String getBackupKey() {
        return "chat_reactions_backup_" + this.chatId;
    }

    private final ClipboardManager getClipboard() {
        Object systemService = ApplicationLoader.applicationContext.getSystemService("clipboard");
        if (systemService instanceof ClipboardManager) {
            return (ClipboardManager) systemService;
        }
        return null;
    }

    public final boolean getHasClipboard() {
        ClipboardManager clipboard = getClipboard();
        return clipboard != null && clipboard.hasPrimaryClip();
    }

    public final TLRPC.TL_availableReaction findAvailableReaction(long documentId) {
        Object next;
        Iterator it = this.availableReactions.iterator();
        while (it.hasNext()) {
            next = it.next();
            if (((TLRPC.TL_availableReaction) next).activate_animation.id == documentId) {
                return (TLRPC.TL_availableReaction) next;
            }
        }
        next = null;
        return (TLRPC.TL_availableReaction) next;
    }

    public final void saveBackup(List<Long> selectedIds, boolean paid) {
        List arrayList = new ArrayList();
        for (Object obj : selectedIds) {
            if (((Number) obj).longValue() != -1) {
                arrayList.add(obj);
            }
        }
        if (arrayList.isEmpty()) {
            return;
        }
        if (paid) {
            arrayList = CollectionsKt.plus((Collection) CollectionsKt.listOf(-1L), (Iterable) arrayList);
        }
        String strJoinToString$default = CollectionsKt.joinToString(arrayList, ",", "", "", -1, "...", null);
        SharedPreferences.Editor editorEdit = MessagesController.getMainSettings(this.account).edit();
        editorEdit.putString(getBackupKey(), strJoinToString$default);
        editorEdit.apply();
    }

    public final Backup loadBackup(int limit) {
        String string = MessagesController.getMainSettings(this.account).getString(getBackupKey(), null);
        if (string != null) {
            int i = 0;
            List listSplit$default = StringsKt.split((CharSequence) string, new char[]{','}, false, 0);
            if (listSplit$default != null) {
                ArrayList arrayList = new ArrayList();
                Iterator it = listSplit$default.iterator();
                while (it.hasNext()) {
                    Long longOrNull = StringsKt.toLongOrNull((String) it.next());
                    if (longOrNull == null || longOrNull.longValue() == 0) {
                        longOrNull = null;
                    }
                    if (longOrNull != null) {
                        arrayList.add(longOrNull);
                    }
                }
                ArrayList arrayList2 = new ArrayList();
                int size = arrayList.size();
                while (i < size) {
                    Object obj = arrayList.get(i);
                    i++;
                    if (((Number) obj).longValue() != -1) {
                        arrayList2.add(obj);
                    }
                }
                return new Backup(CollectionsKt.take(arrayList2, limit), arrayList.contains(-1L));
            }
        }
        return null;
    }

    public final void copyReactions(final Spanned text, int selectionStart, int selectionEnd) {
        if (text == null || selectionStart == selectionEnd) {
            return;
        }
        AnimatedEmojiSpan[] animatedEmojiSpanArr = (AnimatedEmojiSpan[]) text.getSpans(Math.min(selectionStart, selectionEnd), Math.max(selectionStart, selectionEnd), AnimatedEmojiSpan.class);
        if (animatedEmojiSpanArr.length == 0) {
            return;
        }
        String strJoinToString$default = CollectionsKt.joinToString(ArraysKt.sortedWith(animatedEmojiSpanArr, new Comparator() { // from class: com.exteragram.messenger.reactions.ReactionsEditHelper$copyReactions$$inlined$sortedBy$1
            /* JADX WARN: Multi-variable type inference failed */
            @Override // java.util.Comparator
            public final int compare(Object t, Object t2) {
                return ComparisonsKt.compareValues(Integer.valueOf(text.getSpanStart((AnimatedEmojiSpan) t)), Integer.valueOf(text.getSpanStart((AnimatedEmojiSpan) t2)));
            }
        }), " ", "", "", -1, "...", new Function1() { // from class: com.exteragram.messenger.reactions.ReactionsEditHelper$$ExternalSyntheticLambda0
            @Override // kotlin.jvm.functions.Function1
            public final Object invoke(Object obj) {
                return ReactionsEditHelper.this.reactionToText(((AnimatedEmojiSpan) obj).documentId);
            }
        });
        ClipboardManager clipboard = getClipboard();
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("reactions", strJoinToString$default));
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final String reactionToText(long documentId) {
        String str;
        if (documentId == -1) {
            return "⭐";
        }
        TLRPC.TL_availableReaction tL_availableReactionFindAvailableReaction = findAvailableReaction(documentId);
        if (tL_availableReactionFindAvailableReaction != null && (str = tL_availableReactionFindAvailableReaction.reaction) != null) {
            return str;
        }
        return "tg://emoji?id=" + documentId;
    }

    public final List<AnimatedEmojiSpan> parseClipboard() {
        ClipData primaryClip;
        Object next;
        ClipData.Item itemAt;
        ClipboardManager clipboard = getClipboard();
        if (clipboard == null || (primaryClip = clipboard.getPrimaryClip()) == null || primaryClip.getItemCount() <= 0) {
            primaryClip = null;
        }
        CharSequence charSequenceCoerceToText = (primaryClip == null || (itemAt = primaryClip.getItemAt(0)) == null) ? null : itemAt.coerceToText(ApplicationLoader.applicationContext);
        if (charSequenceCoerceToText == null || charSequenceCoerceToText.length() == 0) {
            return new ArrayList();
        }
        TreeMap treeMap = new TreeMap();
        TreeMap treeMap2 = new TreeMap();
        if (charSequenceCoerceToText instanceof Spanned) {
            Spanned spanned = (Spanned) charSequenceCoerceToText;
            Iterator it = ArrayIteratorKt.iterator(spanned.getSpans(0, spanned.length(), AnimatedEmojiSpan.class));
            while (it.hasNext()) {
                AnimatedEmojiSpan animatedEmojiSpan = (AnimatedEmojiSpan) it.next();
                int spanStart = spanned.getSpanStart(animatedEmojiSpan);
                treeMap2.put(Integer.valueOf(spanStart), Integer.valueOf(spanned.getSpanEnd(animatedEmojiSpan)));
                treeMap.put(Integer.valueOf(spanStart), new AnimatedEmojiSpan(animatedEmojiSpan.documentId, (Paint.FontMetricsInt) null));
            }
        }
        for (MatchResult matchResult : kotlin.sequences.SequencesKt.toList(EMOJI_LINK.findAll(charSequenceCoerceToText, 0))) {
            Long longOrNull = StringsKt.toLongOrNull(matchResult.getGroupValues().get(1));
            if (longOrNull != null) {
                if (longOrNull.longValue() == 0) {
                    longOrNull = null;
                }
                if (longOrNull != null) {
                    treeMap.put(Integer.valueOf(matchResult.getRange().getFirst()), new AnimatedEmojiSpan(longOrNull.longValue(), (Paint.FontMetricsInt) null));
                }
            }
        }
        for (Emoji.EmojiSpanRange emojiSpanRange : Emoji.parseEmojis(charSequenceCoerceToText)) {
            Map.Entry entryFloorEntry = treeMap2.floorEntry(Integer.valueOf(emojiSpanRange.start));
            if (entryFloorEntry == null || emojiSpanRange.start >= ((Number) entryFloorEntry.getValue()).intValue()) {
                String strReplace$default = StringsKt.replace(emojiSpanRange.code.toString(), "️", "", false);
                if (Intrinsics.areEqual(strReplace$default, "⭐")) {
                    treeMap.put(Integer.valueOf(emojiSpanRange.start), new AnimatedEmojiSpan(-1L, (Paint.FontMetricsInt) null));
                } else {
                    Iterator it2 = this.availableReactions.iterator();
                    do {
                        if (!it2.hasNext()) {
                            next = null;
                            break;
                        }
                        next = it2.next();
                    } while (!Intrinsics.areEqual(StringsKt.replace(((TLRPC.TL_availableReaction) next).reaction, "️", "", false), strReplace$default));
                    TLRPC.TL_availableReaction tL_availableReaction = (TLRPC.TL_availableReaction) next;
                    if (tL_availableReaction != null) {
                        treeMap.put(Integer.valueOf(emojiSpanRange.start), new AnimatedEmojiSpan(tL_availableReaction.activate_animation, (Paint.FontMetricsInt) null));
                    }
                }
            }
        }
        return CollectionsKt.toMutableList(treeMap.values());
    }
}
