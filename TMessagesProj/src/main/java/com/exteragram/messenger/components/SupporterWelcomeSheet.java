package com.exteragram.messenger.components;

import android.app.Activity;
import android.os.Build;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;

import com.exteragram.messenger.badges.BadgesConfig;
import com.exteragram.messenger.badges.BadgesController;
import com.exteragram.messenger.components.supporter.SupporterBadgeLanding;
import com.exteragram.messenger.components.supporter.SupporterEnterFrom;
import com.exteragram.messenger.components.supporter.SupporterNumberView;
import com.exteragram.messenger.components.supporter.SupporterPerks;
import com.exteragram.messenger.components.supporter.SupporterSheet;
import com.exteragram.messenger.components.supporter.SupporterTransfer;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.FireworksOverlay;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.ProfileActivity;

/**
 * exteraGram 13.1.1 SupporterWelcomeSheet, «Your Perks» of the own supporter badge.
 * <ul>
 * <li>{@link #MODE_WELCOME}: shown once after the badge appears (SupporterWelcomeController): «Thank You for Your
 * Support!», the number rolls in, fireworks.</li>
 * <li>{@link #MODE_PERKS}: tapping the own badge (profile, chat, drawer) or «Your Perks» on the «exteraStatic badge»
 * screen: «Your Perks», «Badge #N — here's what you get:», «Transfer to Another Account».</li>
 * </ul>
 * StaticGram: the perks come from {@link SupporterPerks}; «Early Builds Channel» is app config
 * sg_badges_early_builds_url (exteraGram: remote config supporters_channel_link), «Now Playing» is not offered.
 */
public class SupporterWelcomeSheet extends SupporterSheet {

    public static final int MODE_WELCOME = 0;
    public static final int MODE_PERKS = 1;

    private FireworksOverlay fireworksOverlay;
    private SupporterNumberView numberView;

    public SupporterWelcomeSheet(BaseFragment fragment, int mode, Theme.ResourcesProvider resourcesProvider) {
        super(fragment, resourcesProvider);
        final Activity context = fragment.getParentActivity();
        final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        BadgesController controller = BadgesController.INSTANCE;
        Integer number = controller.getNumber(selfId);
        boolean developer = controller.isDeveloper(UserConfig.getInstance(currentAccount).getCurrentUser());
        boolean rollNumber = mode == MODE_WELCOME && number != null;
        if (number != null) {
            getArrowView().setNumber(BadgesController.formatNumber(number));
        }
        String title = LocaleController.getString(mode != MODE_WELCOME || developer ? R.string.DeveloperPerksTitle : R.string.SupporterWelcomeTitle);
        CharSequence subtitle;
        if (number != null && !rollNumber) {
            subtitle = AndroidUtilities.replaceTags(LocaleController.formatString(R.string.SupporterWelcomeInfo, BadgesController.formatNumber(number)));
        } else {
            subtitle = LocaleController.getString(R.string.SupporterWelcomeInfoNoNumber);
        }
        setContent(title, subtitle);
        if (rollNumber) {
            numberView = new SupporterNumberView(context);
            numberView.setNumber(BadgesController.formatNumber(number));
            numberView.setColors(getArrowView().color1, getArrowView().color2);
            addHeaderAccessory(numberView);
        }
        final String earlyBuildsUrl = BadgesConfig.getEarlyBuildsUrl();
        final boolean hasEarlyBuilds = !TextUtils.isEmpty(earlyBuildsUrl);
        SupporterPerks.addTo(context, this::addView, resourcesProvider, key -> {
            if (SupporterPerks.BADGE.equals(key)) {
                return () -> openOwnProfile(fragment, selfId, mode);
            } else if (SupporterPerks.EARLY_BUILDS.equals(key) && hasEarlyBuilds) {
                return () -> {
                    dismiss();
                    Browser.openUrl(context, earlyBuildsUrl);
                };
            }
            return null;
        });
        if (hasEarlyBuilds) {
            button.setText(LocaleController.getString(R.string.SupporterJoinChannel), false);
            button.setOnClickListener(v -> {
                dismiss();
                Browser.openUrl(context, earlyBuildsUrl);
            });
        } else {
            button.setText(LocaleController.getString(R.string.GotIt), false);
            button.setOnClickListener(v -> dismiss());
        }
        if (mode == MODE_PERKS && SupporterTransfer.canTransfer(currentAccount)) {
            addSecondaryButton(LocaleController.getString(R.string.SupporterTransfer), v ->
                    SupporterTransfer.start(fragment, container, v, resourcesProvider, this::dismiss));
        }
        if (mode == MODE_WELCOME) {
            fireworksOverlay = new FireworksOverlay(context);
            container.addView(fireworksOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
        commit();
    }

    private void openOwnProfile(BaseFragment fragment, long selfId, int mode) {
        if (fragment instanceof ProfileActivity && ((ProfileActivity) fragment).getDialogId() == selfId) {
            dismiss();
            return;
        }
        if (mode == MODE_WELCOME && !getArrowView().hasBadge() && SupporterBadgeLanding.prepare(selfId, getArrowView().getIconView(), 0.8f)) {
            getArrowView().setIconHidden(true);
        }
        dismiss();
        fragment.presentFragment(ProfileActivity.of(selfId));
    }

    @Override
    public void show() {
        super.show();
        if (numberView != null) {
            if (!getArrowView().hasBadge()) {
                getArrowView().iconView.startEnterAnimation(-360, 250L);
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (isDismissed()) {
                    return;
                }
                numberView.start(() -> {
                    try {
                        container.performHapticFeedback(Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.LONG_PRESS);
                    } catch (Exception ignore) {
                    }
                    if (fireworksOverlay != null) {
                        fireworksOverlay.start(true);
                    }
                });
            }, 250L);
        } else if (fireworksOverlay != null) {
            AndroidUtilities.runOnUIThread(() -> {
                if (isDismissed()) {
                    return;
                }
                try {
                    container.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                } catch (Exception ignore) {
                }
                fireworksOverlay.start(true);
            }, 200L);
        }
    }

    public static SupporterWelcomeSheet show(BaseFragment fragment, int mode) {
        return show(fragment, mode, fragment.getResourceProvider(), null, 0L);
    }

    public static SupporterWelcomeSheet show(BaseFragment fragment, int mode, Theme.ResourcesProvider resourcesProvider, SupporterEnterFrom enterFrom, long customBadgeId) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return null;
        }
        SupporterWelcomeSheet sheet = new SupporterWelcomeSheet(fragment, mode, resourcesProvider);
        sheet.setEnterFrom(enterFrom);
        if (customBadgeId != 0) {
            sheet.setBadge(customBadgeId);
        }
        return fragment.showDialog(sheet) != null ? sheet : null;
    }
}
