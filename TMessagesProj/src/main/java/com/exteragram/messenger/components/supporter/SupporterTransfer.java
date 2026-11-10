package com.exteragram.messenger.components.supporter;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.api.ApiController;
import com.exteragram.messenger.badges.BadgesConfig;
import com.exteragram.messenger.badges.BadgesController;
import com.exteragram.messenger.utils.chats.ChatUtils;
import com.exteragram.messenger.utils.ui.AccountsUiHelper;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * exteraGram 13.1.1 SupporterTransfer: moves the supporter badge to another account logged in on this device.
 * Protocol (inline queries to the badge bot, answer = results[0].send_message.message):
 * <ol>
 * <li>the source account sends {@code transfer_init}; the bot answers {@code token:<T>}, {@code cooldown:<unixtime>}
 * or an error;</li>
 * <li>the target account sends {@code transfer_claim <T>}; the bot answers {@code ok}, {@code target_not_eligible}
 * (the target already has a badge) or an error;</li>
 * <li>on {@code ok} both profiles are re-read (ApiController.refreshProfiles).</li>
 * </ol>
 * «Another account…» opens the bot with {@code ?start=transfer} (exteraGram: the API bot; StaticGram: sg_badges_bot).
 * StaticGram: the account popup is built here (exteraGram's AccountsUiHelper.menu() builder is not ported).
 */
public abstract class SupporterTransfer {

    public static boolean canTransfer(int account) {
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        if (user == null) {
            return false;
        }
        BadgesController controller = BadgesController.INSTANCE;
        return controller.isSupporter(user.id) && !controller.isDeveloper(user) && BadgesConfig.isTransferEnabled() && !TextUtils.isEmpty(BadgesConfig.getBotUsername());
    }

    public static void start(BaseFragment fragment, ViewGroup container, View anchor, Theme.ResourcesProvider resourcesProvider, Runnable dismiss) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final int currentAccount = fragment.getCurrentAccount();
        List<Integer> accounts = AccountsUiHelper.activated(a -> a != currentAccount
                && UserConfig.getInstance(a).getCurrentUser() != null
                && !BadgesController.INSTANCE.isSupporter(UserConfig.getInstance(a).getClientUserId()));
        // from the bottom: the nearest account to the button comes first
        java.util.Collections.reverse(accounts);
        ItemOptions options = ItemOptions.makeOptions(container, resourcesProvider, anchor, false, true);
        options.add(R.drawable.msg_link2, LocaleController.getString(R.string.SupporterTransferOtherAccount), () -> {
            dismiss.run();
            String bot = BadgesConfig.getBotUsername();
            if (!TextUtils.isEmpty(bot)) {
                // StaticGram: the badge bot lives only in our network, a t.me link would leave to Telegram ("no such username")
                Browser.openUrl(activity, "https://staticgram.top/" + bot + "?start=transfer");
            }
        });
        if (!accounts.isEmpty()) {
            options.addGap();
            for (int i = 0; i < accounts.size(); i++) {
                final int account = accounts.get(i);
                LinearLayout row = accountRow(activity, resourcesProvider, account, false, i == accounts.size() - 1);
                row.setOnClickListener(v -> {
                    options.dismiss();
                    dismiss.run();
                    confirm(fragment, currentAccount, account, resourcesProvider);
                });
                options.addView(row, LayoutHelper.createLinear(230, 48));
            }
        }
        options.setBlur(true).translate(0, -AndroidUtilities.dp(4)).setDismissOnMoveOutside(true);
        options.setGravity(Gravity.CENTER_HORIZONTAL);
        options.show();
    }

    /** exteraGram AccountsUiHelper.row(): avatar + name of an account. */
    private static LinearLayout accountRow(Context context, Theme.ResourcesProvider resourcesProvider, int account, boolean top, boolean bottom) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), 0, 0));
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(user);
        FrameLayout avatarContainer = new FrameLayout(context);
        layout.addView(avatarContainer, LayoutHelper.createLinear(34, 34, Gravity.CENTER_VERTICAL, 12, 0, 0, 0));
        BackupImageView imageView = new BackupImageView(context);
        imageView.setRoundRadius(ExteraConfig.getAvatarCorners(32));
        imageView.getImageReceiver().setCurrentAccount(account);
        imageView.setForUserOrChat(user, avatarDrawable);
        avatarContainer.addView(imageView, LayoutHelper.createFrame(32, 32, Gravity.CENTER, 1, 1, 1, 1));
        TextView textView = new TextView(context);
        NotificationCenter.listenEmojiLoading(textView);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        textView.setText(Emoji.replaceEmoji(UserObject.getUserName(user), textView.getPaint().getFontMetricsInt(), false));
        textView.setMaxLines(2);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        layout.addView(textView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1.0f, Gravity.CENTER_VERTICAL, 13, 0, 14, 0));
        return layout;
    }

    private static void confirm(BaseFragment fragment, int fromAccount, int toAccount, Theme.ResourcesProvider resourcesProvider) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        TLRPC.User target = UserConfig.getInstance(toAccount).getCurrentUser();
        Integer number = BadgesController.INSTANCE.getNumber(UserConfig.getInstance(fromAccount).getClientUserId());
        String numberText = number != null ? BadgesController.formatNumber(number) : "";
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.SupporterTransferTitle));
        builder.setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.SupporterTransferConfirm, numberText, UserObject.getUserName(target))));
        builder.setPositiveButton(LocaleController.getString(R.string.Gift2TransferDo), (dialog, which) -> transfer(fragment, fromAccount, toAccount, resourcesProvider));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        fragment.showDialog(dialog);
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold, resourcesProvider));
        }
    }

    private static void transfer(BaseFragment fragment, int fromAccount, int toAccount, Theme.ResourcesProvider resourcesProvider) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        final AlertDialog progress = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER, resourcesProvider);
        progress.setCanCancel(false);
        progress.showDelayed(300);
        ChatUtils.getInstance(fromAccount).sendBotRequest("transfer_init", false, initResult -> AndroidUtilities.runOnUIThread(() -> {
            if (initResult == null || !initResult.startsWith("token:")) {
                progress.dismiss();
                showResult(fragment, initResult);
                return;
            }
            String token = initResult.substring(6);
            ChatUtils.getInstance(toAccount).sendBotRequest("transfer_claim " + token, false, claimResult -> AndroidUtilities.runOnUIThread(() -> {
                progress.dismiss();
                if (!"ok".equals(claimResult)) {
                    showResult(fragment, claimResult);
                    return;
                }
                ApiController.refreshProfiles(UserConfig.getInstance(fromAccount).getClientUserId(), UserConfig.getInstance(toAccount).getClientUserId());
                if (fragment.isFinished) {
                    return;
                }
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check,
                        AndroidUtilities.replaceTags(LocaleController.formatString(R.string.SupporterTransferDone, UserObject.getUserName(UserConfig.getInstance(toAccount).getCurrentUser())))).show();
            }));
        }));
    }

    private static void showResult(BaseFragment fragment, String result) {
        if (fragment.isFinished) {
            return;
        }
        long until = result != null && result.startsWith("cooldown:") ? Utilities.parseLong(result.substring(9)) : 0;
        CharSequence text;
        if (until > 0) {
            text = AndroidUtilities.replaceTags(LocaleController.formatString(R.string.SupporterTransferCooldown,
                    DateFormat.getDateInstance(DateFormat.LONG, LocaleController.getInstance().getCurrentLocale()).format(new Date(until * 1000))));
        } else if ("target_not_eligible".equals(result)) {
            text = LocaleController.getString(R.string.SupporterTransferTargetHasBadge);
        } else {
            text = LocaleController.getString(R.string.SupporterTransferFailed);
        }
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, text).show();
    }
}
