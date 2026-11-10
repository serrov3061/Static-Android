package com.exteragram.messenger.feed;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.ItemOptions;

import java.util.ArrayList;

/** exteraGram feed: avatar menu of a feed post (open channel, hide from feed, leave). */
public final class FeedChannelActions {

    private FeedChannelActions() {
    }

    public static boolean canLeave(TLRPC.Chat chat) {
        return chat != null && !chat.creator && !ChatObject.isNotInChat(chat);
    }

    public static void showAvatarMenu(ChatActivity chatActivity, ChatMessageCell cell, TLRPC.Chat chat, Runnable openChannel, Runnable onLeft, Utilities.Callback<ArrayList<Integer>> onRowsDeleted) {
        if (chatActivity == null || cell == null || chat == null) {
            return;
        }
        ItemOptions.makeOptions(chatActivity, cell)
            .add(chat.broadcast ? R.drawable.msg_channel : R.drawable.msg_discussion, LocaleController.getString(chat.broadcast ? R.string.OpenChannel2 : R.string.OpenGroup2), openChannel)
            .add(R.drawable.menu_hide_gift, LocaleController.getString(R.string.FeedHideChannel), () -> chatActivity.hideFeedChannelWithUndo(-chat.id, chat.title))
            .addIf(canLeave(chat), R.drawable.msg_leave, LocaleController.getString(chat.broadcast ? R.string.LeaveChannelMenu : R.string.LeaveMegaMenu), true, () -> leaveChannel(chatActivity, chat, onLeft, onRowsDeleted))
            .setDrawScrim(false)
            .setGravity(android.view.Gravity.LEFT)
            .forceBottom(true)
            .show();
    }

    public static void leaveChannel(BaseFragment fragment, TLRPC.Chat chat, Runnable onLeft, Utilities.Callback<ArrayList<Integer>> onRowsDeleted) {
        if (fragment == null || chat == null || fragment.getParentActivity() == null) {
            return;
        }
        AlertsCreator.createClearOrDeleteDialogAlert(fragment, false, chat, null, false, true, false, false, revoke -> {
            long dialogId = -chat.id;
            if (ChatObject.isNotInChat(chat)) {
                fragment.getMessagesController().deleteDialog(dialogId, 0, revoke);
            } else {
                fragment.getMessagesController().deleteParticipantFromChat(chat.id, fragment.getMessagesController().getUser(fragment.getUserConfig().getClientUserId()), null, revoke, revoke);
            }
            ArrayList<Integer> deleted = FeedController.getInstance(fragment.getCurrentAccount()).deleteHistory(dialogId, Integer.MAX_VALUE);
            if (onRowsDeleted != null) {
                onRowsDeleted.run(deleted);
            }
            if (onLeft != null) {
                onLeft.run();
            }
        });
    }
}
