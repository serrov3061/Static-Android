package com.exteragram.messenger.utils.chats;

import androidx.collection.LongSparseArray;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.TabCounterMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import kotlin.Unit;
import kotlin.collections.CollectionsKt;
import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlin.jvm.internal.Intrinsics;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.support.LongSparseIntArray;
import org.telegram.tgnet.TLRPC;

public final class FolderCounters {

    /* renamed from: Companion, reason: from kotlin metadata */
    public static final Companion INSTANCE = new Companion(null);
    private static final FolderCounters[] instances = new FolderCounters[16];
    private static final Object[] lockObjects;
    private final int account;
    private HashMap<Integer, Integer> filterCounts;
    private int mainCount;
    private volatile boolean recountScheduled;

    public /* synthetic */ FolderCounters(int i, DefaultConstructorMarker defaultConstructorMarker) {
        this(i);
    }

    public static final FolderCounters getInstance(int i) {
        return INSTANCE.getInstance(i);
    }

    private FolderCounters(int i) {
        this.account = i;
        this.filterCounts = new HashMap<>();
    }

    public static final class UnreadDialog {
        private final boolean archived;
        private final long id;
        private final int typeFlags;
        private final int weight;
        private final boolean muted;
        private final TLRPC.Chat chat;

        public UnreadDialog(long j, int i, int i2, boolean z, boolean muted, TLRPC.Chat chat) {
            this.id = j;
            this.typeFlags = i;
            this.weight = i2;
            this.archived = z;
            this.muted = muted;
            this.chat = chat;
        }

        public final long getId() {
            return this.id;
        }

        public final int getTypeFlags() {
            return this.typeFlags;
        }

        public final int getWeight() {
            return this.weight;
        }

        public final boolean getArchived() {
            return this.archived;
        }

        public final boolean getMuted() {
            return this.muted;
        }

        public final TLRPC.Chat getChat() {
            return this.chat;
        }
    }

    public final int getMainUnreadCount() {
        return INSTANCE.isUnmutedOnly() ? this.mainCount : MessagesStorage.getInstance(this.account).getMainUnreadCount();
    }

    public final int getUnreadCount(MessagesController.DialogFilter filter) {
        // exteraGram: folders with a role restriction are always counted locally
        if (!INSTANCE.isUnmutedOnly() && FolderRoles.getInstance(this.account).getMode(filter.id) == FolderRoles.MODE_NONE) {
            return filter.unreadCount;
        }
        Integer num = this.filterCounts.get(Integer.valueOf(filter.id));
        if (num != null) {
            return num.intValue();
        }
        return 0;
    }

    public final void update(List<? extends MessagesController.DialogFilter> filters, LongSparseArray<TLRPC.User> usersDict, LongSparseArray<TLRPC.User> encUsersDict, LongSparseIntArray encryptedChatsByUsersCount, LongSparseArray<TLRPC.Chat> chatsDict, LongSparseArray<Boolean> mutedDialogs, LongSparseArray<Boolean> archivedDialogs, LongSparseArray<Integer> dialogsWithMentions) {
        try {
            updateUnsafe(filters, usersDict, encUsersDict, encryptedChatsByUsersCount, chatsDict, mutedDialogs, archivedDialogs, dialogsWithMentions);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void updateUnsafe(List<? extends MessagesController.DialogFilter> filters, LongSparseArray<TLRPC.User> usersDict, LongSparseArray<TLRPC.User> encUsersDict, LongSparseIntArray encryptedChatsByUsersCount, LongSparseArray<TLRPC.Chat> chatsDict, LongSparseArray<Boolean> mutedDialogs, LongSparseArray<Boolean> archivedDialogs, LongSparseArray<Integer> dialogsWithMentions) {
        boolean unmutedOnly = INSTANCE.isUnmutedOnly();
        FolderRoles roles = FolderRoles.getInstance(this.account);
        if (!unmutedOnly && !roles.hasAny()) {
            return;
        }
        if (filters == null || usersDict == null || encUsersDict == null || encryptedChatsByUsersCount == null || chatsDict == null || mutedDialogs == null || archivedDialogs == null || dialogsWithMentions == null) {
            return;
        }
        HashMap<Long, Boolean> forumMutes = unmutedOnly ? loadForumMutes() : new HashMap<>();
        ArrayList<UnreadDialog> dialogs = new ArrayList<>();
        for (int i = 0, size = usersDict.size(); i < size; i++) {
            TLRPC.User user = usersDict.valueAt(i);
            if (user == null) {
                continue;
            }
            int type;
            if (ChatObject.isUserCollapsedInCommunity(chatsDict, user)) {
                type = MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS;
            } else if (user.bot) {
                type = MessagesController.DIALOG_FILTER_FLAG_BOTS;
            } else if (user.self || user.contact) {
                type = MessagesController.DIALOG_FILTER_FLAG_CONTACTS;
            } else {
                type = MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS;
            }
            Boolean forumMuted = forumMutes.get(user.id);
            boolean muted = forumMuted != null ? forumMuted : mutedDialogs.containsKey(user.id);
            dialogs.add(new UnreadDialog(user.id, type, 1, archivedDialogs.containsKey(user.id), muted, null));
        }
        for (int i = 0, size = encUsersDict.size(); i < size; i++) {
            TLRPC.User user = encUsersDict.valueAt(i);
            if (user == null) {
                continue;
            }
            int count = encryptedChatsByUsersCount.get(user.id, 0);
            if (count != 0) {
                int type = user.self || user.contact ? MessagesController.DIALOG_FILTER_FLAG_CONTACTS : MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS;
                dialogs.add(new UnreadDialog(user.id, type, count, archivedDialogs.containsKey(user.id), mutedDialogs.containsKey(user.id), null));
            }
        }
        for (int i = 0, size = chatsDict.size(); i < size; i++) {
            TLRPC.Chat chat = chatsDict.valueAt(i);
            if (chat == null || ChatObject.isCommunity(chat)) {
                continue;
            }
            long did = -chat.id;
            int type;
            if (ChatObject.isChatCollapsedInCommunity(chatsDict, chat)) {
                type = MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS;
            } else {
                type = !ChatObject.isChannel(chat) || chat.megagroup ? MessagesController.DIALOG_FILTER_FLAG_GROUPS : MessagesController.DIALOG_FILTER_FLAG_CHANNELS;
            }
            Boolean forumMuted = forumMutes.get(did);
            boolean muted = forumMuted != null ? forumMuted : mutedDialogs.containsKey(did) && !dialogsWithMentions.containsKey(did);
            dialogs.add(new UnreadDialog(did, type, 1, archivedDialogs.containsKey(did), muted, chat));
        }
        final int mainCount = unmutedOnly ? count(dialogs, MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS | MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_ARCHIVED, null, true) : 0;
        final HashMap<Integer, Integer> counts = new HashMap<>();
        for (MessagesController.DialogFilter filter : filters) {
            if (filter == null) {
                continue;
            }
            if (unmutedOnly || roles.getMode(filter.id) != FolderRoles.MODE_NONE) {
                counts.put(filter.id, count(dialogs, filter.flags, filter, unmutedOnly));
            }
        }
        AndroidUtilities.runOnUIThread(() -> {
            this.mainCount = mainCount;
            this.filterCounts = counts;
        });
    }

    public static void $r8$lambda$70DlWAvopMmbZjuP5Rkp7m_mrV0(FolderCounters folderCounters, int i, HashMap map) {
        folderCounters.mainCount = i;
        folderCounters.filterCounts = map;
    }

    public final void scheduleRecount() {
        if (!INSTANCE.isUnmutedOnly() && !FolderRoles.getInstance(this.account).hasAny() || this.recountScheduled) {
            return;
        }
        this.recountScheduled = true;
        final MessagesStorage messagesStorage = MessagesStorage.getInstance(this.account);
        messagesStorage.getStorageQueue().postRunnable(() -> FolderCounters.$r8$lambda$a7yR4wl_zPymeOD2mbHLE9R84bk(FolderCounters.this, messagesStorage), 500L);
    }

    public static void $r8$lambda$a7yR4wl_zPymeOD2mbHLE9R84bk(FolderCounters folderCounters, MessagesStorage messagesStorage) {
        folderCounters.recountScheduled = false;
        messagesStorage.resetAllUnreadCounters(false);
    }

    /* JADX WARN: Removed duplicated region for block: B:20:0x005d  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
    private final HashMap<Long, Boolean> loadForumMutes() {
        MessagesStorage messagesStorage = MessagesStorage.getInstance(this.account);
        MessagesController messagesController = MessagesController.getInstance(this.account);
        HashMap<Long, Boolean> map = new HashMap<>();
        SQLiteCursor sQLiteCursorQueryFinalized = null;
        try {
            try {
                sQLiteCursorQueryFinalized = messagesStorage.getDatabase().queryFinalized("SELECT did, topic_id, unread_mentions FROM topics WHERE unread_count > 0 OR unread_mentions > 0", new Object[0]);
                while (sQLiteCursorQueryFinalized.next()) {
                    long jLongValue = sQLiteCursorQueryFinalized.longValue(0);
                    if (!Intrinsics.areEqual(map.get(Long.valueOf(jLongValue)), Boolean.FALSE) && messagesStorage.isForum(jLongValue, 13)) {
                        Long lValueOf = Long.valueOf(jLongValue);
                        if (sQLiteCursorQueryFinalized.intValue(2) == 0) {
                            boolean z = true;
                            if (!messagesController.isDialogMuted(jLongValue, sQLiteCursorQueryFinalized.longValue(1))) {
                                z = false;
                            }
                            map.put(lValueOf, Boolean.valueOf(z));
                        }
                    }
                }
                sQLiteCursorQueryFinalized.dispose();
                return map;
            } catch (Exception e) {
                FileLog.e(e);
                if (sQLiteCursorQueryFinalized != null) {
                    sQLiteCursorQueryFinalized.dispose();
                }
                return map;
            }
        } catch (Throwable th) {
            if (sQLiteCursorQueryFinalized != null) {
                sQLiteCursorQueryFinalized.dispose();
            }
            throw th;
        }
    }

    private final int count(List<UnreadDialog> dialogs, int flags, MessagesController.DialogFilter filter, boolean unmutedOnly) {
        HashSet<Long> always = filter != null && filter.alwaysShow != null ? new HashSet<>(filter.alwaysShow) : null;
        HashSet<Long> never = filter != null && filter.neverShow != null ? new HashSet<>(filter.neverShow) : null;
        FolderRoles roles = FolderRoles.getInstance(this.account);
        int weight = 0;
        for (UnreadDialog dialog : dialogs) {
            if (unmutedOnly && dialog.getMuted()) {
                continue;
            }
            if (never != null && never.contains(dialog.getId())) {
                continue;
            }
            if (always == null || !always.contains(dialog.getId())) {
                if (dialog.getArchived() && (MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_ARCHIVED & flags) != 0) {
                    continue;
                }
                if (dialog.getMuted() && (MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_MUTED & flags) != 0) {
                    continue;
                }
                if (filter != null && dialog.getChat() != null && !roles.allows(filter.id, dialog.getChat())) {
                    continue;
                }
                if ((dialog.getTypeFlags() & flags) != dialog.getTypeFlags()) {
                    continue;
                }
            }
            weight += dialog.getWeight();
        }
        return weight;
    }

    public static final class Companion {
        public /* synthetic */ Companion(DefaultConstructorMarker defaultConstructorMarker) {
            this();
        }

        private Companion() {
        }

        public final FolderCounters getInstance(int num) {
            FolderCounters folderCounters;
            FolderCounters folderCounters2 = FolderCounters.instances[num];
            if (folderCounters2 != null) {
                return folderCounters2;
            }
            synchronized (FolderCounters.lockObjects[num]) {
                try {
                    folderCounters = FolderCounters.instances[num];
                    if (folderCounters == null) {
                        folderCounters = new FolderCounters(num, null);
                        FolderCounters.instances[num] = folderCounters;
                    }
                    Unit unit = Unit.INSTANCE;
                } catch (Throwable th) {
                    throw th;
                }
            }
            return folderCounters;
        }

        public final void recountAll() {
            if (isUnmutedOnly()) {
                for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
                    if (UserConfig.getInstance(i).isClientActivated()) {
                        final MessagesStorage messagesStorage = MessagesStorage.getInstance(i);
                        messagesStorage.getStorageQueue().postRunnable(new Runnable() { // from class: com.exteragram.messenger.utils.chats.FolderCounters$Companion$$ExternalSyntheticLambda0
                            @Override // java.lang.Runnable
                            public final void run() {
                                messagesStorage.resetAllUnreadCounters(false);
                            }
                        });
                    }
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public final boolean isUnmutedOnly() {
            return ExteraConfig.getTabCounterMode() == TabCounterMode.UNMUTED;
        }
    }

    static {
        Object[] objArr = new Object[16];
        for (int i = 0; i < 16; i++) {
            objArr[i] = new Object();
        }
        lockObjects = objArr;
    }
}
