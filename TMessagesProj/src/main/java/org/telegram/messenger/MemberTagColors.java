package org.telegram.messenger;

import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.collection.LongSparseArray;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_sgtags;
import org.telegram.ui.ActionBar.Theme;

/**
 * StaticGram member tag colors.
 *
 * Default rule (no color picked): the tag of a participant with admin rights
 * (admin or owner) is red, the tag of a plain member is green. A color picked
 * in the tag sheet (sgtags.setTagColor) overrides the default for anyone,
 * admins included. Colors are stored on the server and fetched per chat
 * (sgtags.getTagColors); this class caches them in memory and in prefs.
 */
public class MemberTagColors {

    /** Picker palette (opaque ARGB). */
    public static final int[] PALETTE = {
        0xFFE53935, // red
        0xFFF57C00, // orange
        0xFFD4A20B, // amber
        0xFF40A920, // green
        0xFF00A3A3, // teal
        0xFF3390EC, // blue
        0xFF8E59D9, // purple
        0xFFE0569A, // pink
        0xFF8D99A6, // grey
    };

    private static final long RELOAD_INTERVAL_MS = 30_000;

    private static final MemberTagColors[] instances = new MemberTagColors[UserConfig.MAX_ACCOUNT_COUNT];

    public static MemberTagColors getInstance(int account) {
        MemberTagColors instance = instances[account];
        if (instance == null) {
            synchronized (MemberTagColors.class) {
                instance = instances[account];
                if (instance == null) {
                    instances[account] = instance = new MemberTagColors(account);
                }
            }
        }
        return instance;
    }

    private final int currentAccount;
    private final LongSparseArray<LongSparseArray<Integer>> colors = new LongSparseArray<>();
    private final LongSparseArray<Long> lastLoad = new LongSparseArray<>();

    private MemberTagColors(int account) {
        currentAccount = account;
    }

    /** The two halves of the "auto" swatch in the picker: admin with rights red, member tag gray. */
    public static int getDefaultColor(boolean admin, Theme.ResourcesProvider resourcesProvider) {
        return Theme.getColor(admin ? Theme.key_text_RedBold : Theme.key_chat_inAdminText, resourcesProvider);
    }

    /**
     * Default tag color: owner purple; admin with admin rights red; admin title granted without
     * any rights (a bought tag) green; a member tag ("add member tag", no admin role) gray.
     */
    public static int getDefaultColor(boolean owner, boolean admin, boolean adminWithoutRights, Theme.ResourcesProvider resourcesProvider) {
        if (owner) {
            return Theme.getColor(Theme.key_chat_tagCreator, resourcesProvider);
        }
        if (admin) {
            return Theme.getColor(adminWithoutRights ? Theme.key_chat_tagAdmin : Theme.key_text_RedBold, resourcesProvider);
        }
        return Theme.getColor(Theme.key_chat_inAdminText, resourcesProvider);
    }

    /** True if the user is a supergroup admin whose admin rights are all off (title only). */
    public static boolean adminWithoutRights(int account, long chatId, long userId) {
        if (chatId <= 0 || userId == 0) return false;
        final TLRPC.ChannelParticipant p = MessagesController.getInstance(account).getAdminInChannel(userId, chatId);
        if (!(p instanceof TLRPC.TL_channelParticipantAdmin)) return false;
        return !hasAnyRight(p.admin_rights);
    }

    public static boolean hasAnyRight(TLRPC.TL_chatAdminRights r) {
        if (r == null) return false;
        return r.change_info || r.post_messages || r.edit_messages || r.delete_messages || r.ban_users || r.invite_users
            || r.pin_messages || r.add_admins || r.manage_call || r.manage_topics || r.post_stories
            || r.edit_stories || r.delete_stories || r.manage_direct_messages || r.manage_ranks || r.manage_linked_peers
            || r.manage_welcome_messages;
    }

    /** The color a tag is drawn with: the picked color if any, else the default rule. chatId is positive. */
    public static int resolve(int account, long chatId, long userId, boolean owner, boolean admin, Theme.ResourcesProvider resourcesProvider) {
        final int custom = getInstance(account).getColor(chatId, userId);
        if (custom != 0) return custom;
        return getDefaultColor(owner, admin, admin && !owner && adminWithoutRights(account, chatId, userId), resourcesProvider);
    }

    /** Picked color (opaque ARGB) or 0 when none was picked. chatId is positive. */
    public int getColor(long chatId, long userId) {
        if (chatId <= 0 || userId == 0) return 0;
        final Integer color = getChat(chatId).get(userId);
        return color == null ? 0 : color;
    }

    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("sg_tag_colors" + currentAccount, 0);
    }

    private LongSparseArray<Integer> getChat(long chatId) {
        LongSparseArray<Integer> map = colors.get(chatId);
        if (map == null) {
            map = new LongSparseArray<>();
            try {
                final String saved = prefs().getString("c" + chatId, null);
                if (!TextUtils.isEmpty(saved)) {
                    for (String entry : saved.split(";")) {
                        final int sep = entry.indexOf(':');
                        if (sep <= 0) continue;
                        map.put(Long.parseLong(entry.substring(0, sep)), Integer.parseInt(entry.substring(sep + 1)));
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            colors.put(chatId, map);
        }
        return map;
    }

    private void save(long chatId, LongSparseArray<Integer> map) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < map.size(); ++i) {
            if (sb.length() > 0) sb.append(';');
            sb.append(map.keyAt(i)).append(':').append(map.valueAt(i));
        }
        final SharedPreferences.Editor editor = prefs().edit();
        if (sb.length() == 0) {
            editor.remove("c" + chatId);
        } else {
            editor.putString("c" + chatId, sb.toString());
        }
        editor.apply();
    }

    private static boolean sameColors(LongSparseArray<Integer> a, LongSparseArray<Integer> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); ++i) {
            final Integer other = b.get(a.keyAt(i));
            if (other == null || !other.equals(a.valueAt(i))) return false;
        }
        return true;
    }

    /** Fetches the chat colors from the server (throttled unless forced). Call on the UI thread. */
    public void load(long chatId, boolean force) {
        if (chatId <= 0) return;
        // Tags (and their colors) exist only in megagroups; the server answers
        // CHANNEL_INVALID for deleted/forbidden chats and an empty list for
        // broadcast channels, so skip those requests.
        final TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(chatId);
        if (chat == null || !chat.megagroup || chat instanceof TLRPC.TL_channelForbidden || chat instanceof TLRPC.TL_chatForbidden) return;
        final long now = SystemClock.elapsedRealtime();
        final Long last = lastLoad.get(chatId);
        if (!force && last != null && now - last < RELOAD_INTERVAL_MS) return;
        final TLRPC.InputPeer peer = MessagesController.getInstance(currentAccount).getInputPeer(-chatId);
        if (peer == null || peer instanceof TLRPC.TL_inputPeerEmpty) return;
        lastLoad.put(chatId, now);
        final TL_sgtags.TL_getTagColors req = new TL_sgtags.TL_getTagColors();
        req.peer = peer;
        ConnectionsManager.getInstance(currentAccount).sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            if (res == null) return;
            final LongSparseArray<Integer> map = new LongSparseArray<>();
            for (TL_sgtags.TL_tagColor item : res.colors) {
                map.put(item.user_id, 0xFF000000 | (item.color & 0xFFFFFF));
            }
            if (sameColors(getChat(chatId), map)) return;
            colors.put(chatId, map);
            save(chatId, map);
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.memberTagColorsUpdated, chatId);
        });
    }

    /** Applies a color locally (0 clears) after the server accepted it. */
    public void setLocal(long chatId, long userId, int color) {
        if (chatId <= 0 || userId == 0) return;
        final LongSparseArray<Integer> map = getChat(chatId);
        if (getColor(chatId, userId) == color) return;
        if (color == 0) {
            map.remove(userId);
        } else {
            map.put(userId, color);
        }
        save(chatId, map);
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.memberTagColorsUpdated, chatId);
    }

    /** Sends sgtags.setTagColor (color 0 clears); done gets null on success. */
    public void send(long chatId, TLRPC.User user, int color, Utilities.Callback<TLRPC.TL_error> done) {
        final TL_sgtags.TL_setTagColor req = new TL_sgtags.TL_setTagColor();
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(-chatId);
        req.participant = MessagesController.getInputPeer(user);
        req.color = color == 0 ? -1 : (color & 0xFFFFFF);
        ConnectionsManager.getInstance(currentAccount).sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            if (err == null) {
                setLocal(chatId, user.id, color == 0 ? 0 : (0xFF000000 | color));
            }
            if (done != null) {
                done.run(err);
            }
        });
    }
}
