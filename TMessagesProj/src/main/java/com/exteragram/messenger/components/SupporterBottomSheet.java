package com.exteragram.messenger.components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.exteragram.messenger.badges.BadgesConfig;
import com.exteragram.messenger.badges.BadgesController;
import com.exteragram.messenger.preferences.BadgePreferencesActivity;
import com.exteragram.messenger.utils.ExternalTelegram;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;

/**
 * exteraGram 12.10.6 SupporterBottomSheet layout (icon on a coloured circle, title, subtitle, three
 * FeatureCell steps, buttons), with StaticGram content: badges are sold by the shop bot
 * (sg_badges_shop_url), prices come from sg_badges_price_* (with defaults).
 */
public class SupporterBottomSheet extends BottomSheet {

    private final ArrayList<View> animated = new ArrayList<>();
    private View iconView;

    @SuppressLint("UseCompatLoadingForDrawables")
    public SupporterBottomSheet(BaseFragment fragment, Theme.ResourcesProvider resourcesProvider) {
        super(fragment.getParentActivity(), false, resourcesProvider);
        Context context = fragment.getParentActivity();
        fixNavigationBar();

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        ImageView icon = new ImageView(getContext());
        icon.setScaleType(ImageView.ScaleType.FIT_XY);
        icon.setPadding(AndroidUtilities.dp(11), AndroidUtilities.dp(11), AndroidUtilities.dp(11), AndroidUtilities.dp(11));
        icon.setImageResource(R.drawable.ic_sg_badge);
        icon.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        icon.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(80), Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider)));
        layout.addView(icon, LayoutHelper.createLinear(80, 80, Gravity.CENTER_HORIZONTAL, 0, 28, 0, 0));
        iconView = icon;

        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.ExteraBadgeSheetTitle));
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 22, 14, 22, 0));
        animated.add(title);

        TextView subtitle = new TextView(context);
        subtitle.setText(LocaleController.getString(R.string.ExteraBadgeSheetInfo));
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        subtitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 22, 8, 22, 0));
        animated.add(subtitle);

        String step1 = LocaleController.formatString(R.string.ExteraBadgeStep1Info,
                BadgesConfig.getPriceSupporter(), BadgesConfig.getPriceCustom(), BadgesConfig.getPriceUpgrade());
        FeatureCell cell1 = new FeatureCell(context, R.drawable.menu_feature_paid, LocaleController.getString(R.string.ExteraBadgeStep1Title), step1);
        layout.addView(cell1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 0, 0));
        animated.add(cell1);

        FeatureCell cell2 = new FeatureCell(context, R.drawable.menu_feature_wallpaper, LocaleController.getString(R.string.ExteraBadgeStep2Title), LocaleController.getString(R.string.ExteraBadgeStep2Info));
        layout.addView(cell2, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));
        animated.add(cell2);

        FeatureCell cell3 = new FeatureCell(context, R.drawable.menu_feature_reactions, LocaleController.getString(R.string.ExteraBadgeStep3Title), LocaleController.getString(R.string.ExteraBadgeStep3Info), true);
        layout.addView(cell3, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));
        animated.add(cell3);

        // primary action depends on what the current user already has
        boolean developer = BadgesController.INSTANCE.isDeveloper();
        boolean custom = BadgesController.INSTANCE.canChangeBadge();
        boolean hasBadge = BadgesController.INSTANCE.hasBadge();
        String shopUrl = BadgesConfig.getShopUrl();
        String primaryText = null;
        Runnable primaryAction = null;
        if (!developer) {
            if (custom) {
                primaryText = LocaleController.getString(R.string.ExteraBadgeSettings);
                primaryAction = () -> {
                    if (!(fragment instanceof BadgePreferencesActivity)) {
                        fragment.presentFragment(new BadgePreferencesActivity());
                    }
                };
            } else if (!TextUtils.isEmpty(shopUrl)) {
                primaryText = LocaleController.getString(hasBadge ? R.string.ExteraBadgeUpgrade : R.string.ExteraBadgeBuy);
                primaryAction = () -> ExternalTelegram.openUrl(fragment.getParentActivity(), shopUrl);
            }
        }
        if (primaryText != null) {
            ButtonWithCounterView primary = new ButtonWithCounterView(context, true, resourcesProvider);
            primary.setRound();
            primary.setText(primaryText, false);
            final Runnable action = primaryAction;
            primary.setOnClickListener(v -> {
                dismiss();
                action.run();
            });
            layout.addView(primary, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 14, 22, 14, 0));
            animated.add(primary);
        }

        ButtonWithCounterView close = new ButtonWithCounterView(context, primaryText == null, resourcesProvider);
        close.setRound();
        close.setText(LocaleController.getString(R.string.Close), false);
        close.setOnClickListener(v -> dismiss());
        layout.addView(close, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 14, primaryText == null ? 22 : 8, 14, 14));
        animated.add(close);

        ScrollView scrollView = new ScrollView(getContext());
        scrollView.addView(layout);
        setCustomView(scrollView);

        iconView.setScaleX(0f);
        iconView.setScaleY(0f);
        for (View v : animated) {
            v.setAlpha(0f);
            v.setTranslationY(AndroidUtilities.dp(14));
        }
        setOnShowListener(d -> playEnterAnimation());

        final int stateAtOpen = badgeState();
        com.exteragram.messenger.api.ApiController.syncNow(() -> {
            if (isShowing() && badgeState() != stateAtOpen) {
                // the server state differs from what the sheet was built for: rebuild it
                dismiss();
                showAlert(fragment, resourcesProvider);
            }
        });
    }

    private static int badgeState() {
        BadgesController c = BadgesController.INSTANCE;
        return (c.isDeveloper() ? 4 : 0) | (c.canChangeBadge() ? 2 : 0) | (c.hasBadge() ? 1 : 0);
    }

    private void playEnterAnimation() {
        AnimatorSet set = new AnimatorSet();
        ArrayList<Animator> list = new ArrayList<>();
        ObjectAnimator sx = ObjectAnimator.ofFloat(iconView, View.SCALE_X, 0f, 1f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(iconView, View.SCALE_Y, 0f, 1f);
        sx.setInterpolator(new OvershootInterpolator(1.6f));
        sy.setInterpolator(new OvershootInterpolator(1.6f));
        sx.setDuration(450);
        sy.setDuration(450);
        list.add(sx);
        list.add(sy);
        long delay = 120;
        for (View v : animated) {
            ObjectAnimator a = ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f);
            ObjectAnimator t = ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, AndroidUtilities.dp(14), 0f);
            a.setStartDelay(delay);
            t.setStartDelay(delay);
            a.setDuration(260);
            t.setDuration(260);
            a.setInterpolator(CubicBezierInterpolator.DEFAULT);
            t.setInterpolator(CubicBezierInterpolator.DEFAULT);
            list.add(a);
            list.add(t);
            delay += 60;
        }
        set.playTogether(list);
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                iconView.setScaleX(1f);
                iconView.setScaleY(1f);
                for (View v : animated) {
                    v.setAlpha(1f);
                    v.setTranslationY(0f);
                }
            }
        });
        set.start();
    }

    private class FeatureCell extends FrameLayout {

        FeatureCell(Context context, int iconRes, CharSequence title, CharSequence subtitle) {
            this(context, iconRes, title, subtitle, false);
        }

        FeatureCell(Context context, int iconRes, CharSequence title, CharSequence subtitle, boolean badgeLink) {
            super(context);
            boolean rtl = LocaleController.isRTL;

            ImageView image = new ImageView(getContext());
            android.graphics.drawable.Drawable drawable = ContextCompat.getDrawable(getContext(), iconRes).mutate();
            drawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider), PorterDuff.Mode.MULTIPLY));
            image.setImageDrawable(drawable);
            addView(image, LayoutHelper.createFrame(24, 24, rtl ? Gravity.RIGHT : Gravity.LEFT, rtl ? 0 : 27, 6, rtl ? 27 : 0, 0));

            TextView titleView = new TextView(getContext());
            titleView.setText(title);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
            titleView.setTypeface(AndroidUtilities.bold());
            addView(titleView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, rtl ? Gravity.RIGHT : Gravity.LEFT, rtl ? 27 : 68, 0, rtl ? 68 : 27, 0));

            LinkSpanDrawable.LinksTextView subtitleView = new LinkSpanDrawable.LinksTextView(getContext());
            if (badgeLink) {
                subtitle = AndroidUtilities.replaceSingleTag(subtitle.toString(), Theme.key_chat_messageLinkIn, 0, () -> {
                    dismiss();
                    com.exteragram.messenger.preferences.utils.SettingsRegistry.getInstance().handleLink(com.exteragram.messenger.preferences.utils.SettingsRegistry.BADGE_ALIAS, null);
                }, resourcesProvider);
            }
            subtitleView.setText(subtitle);
            subtitleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
            subtitleView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider));
            subtitleView.setLinkTextColor(Theme.getColor(Theme.key_chat_messageLinkIn, resourcesProvider));
            subtitleView.setLineSpacing(AndroidUtilities.dp(2), 1f);
            addView(subtitleView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, rtl ? Gravity.RIGHT : Gravity.LEFT, rtl ? 27 : 68, 20, rtl ? 68 : 27, 0));
        }
    }

    public static SupporterBottomSheet showAlert(BaseFragment fragment) {
        return showAlert(fragment, fragment.getResourceProvider());
    }

    public static BottomSheet showAlert(BaseFragment fragment, org.telegram.tgnet.TLObject peer, Theme.ResourcesProvider resourcesProvider) {
        return showAlert(fragment, peer, resourcesProvider, null);
    }

    /**
     * exteraGram 13.1.1 SupporterBottomSheet.showAlert(fragment, peer, resourcesProvider, enterFrom):
     * no peer (or the own user) and the current account is a supporter: «Your Perks» (SupporterWelcomeSheet,
     * with the own custom emoji in place of the arrow); another peer: the read-only supporter sheet
     * (SupporterPeerSheet); otherwise the StaticGram «support / buy» sheet.
     */
    public static BottomSheet showAlert(BaseFragment fragment, org.telegram.tgnet.TLObject peer, Theme.ResourcesProvider resourcesProvider,
                                        com.exteragram.messenger.components.supporter.SupporterEnterFrom enterFrom) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return null;
        }
        org.telegram.messenger.UserConfig userConfig = org.telegram.messenger.UserConfig.getInstance(fragment.getCurrentAccount());
        boolean self = peer == null || peer instanceof org.telegram.tgnet.TLRPC.User && ((org.telegram.tgnet.TLRPC.User) peer).id == userConfig.getClientUserId();
        if (self) {
            BadgesController controller = BadgesController.INSTANCE;
            if (controller.isSupporter(userConfig.getClientUserId())) {
                return SupporterWelcomeSheet.show(fragment, SupporterWelcomeSheet.MODE_PERKS, resourcesProvider, enterFrom, controller.getCustomBadgeId(userConfig.getCurrentUser()));
            }
            return showAlert(fragment, resourcesProvider);
        }
        return com.exteragram.messenger.components.supporter.SupporterPeerSheet.show(fragment, peer, resourcesProvider, enterFrom);
    }

    public static SupporterBottomSheet showAlert(BaseFragment fragment, Theme.ResourcesProvider resourcesProvider) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return null;
        }
        SupporterBottomSheet sheet = new SupporterBottomSheet(fragment, resourcesProvider);
        fragment.showDialog(sheet);
        return sheet;
    }
}
