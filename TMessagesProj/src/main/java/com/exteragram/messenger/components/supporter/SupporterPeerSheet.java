package com.exteragram.messenger.components.supporter;

import android.app.Activity;
import android.text.TextUtils;
import android.view.View;

import com.exteragram.messenger.api.dto.BadgeDTO;
import com.exteragram.messenger.badges.BadgesConfig;
import com.exteragram.messenger.badges.BadgesController;
import com.exteragram.messenger.preferences.utils.SettingsRegistry;
import com.exteragram.messenger.utils.ExternalTelegram;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

/**
 * exteraGram 13.1.1 SupporterBottomSheet, peer branch: the read-only sheet about someone else's supporter badge
 * (title «X supports StaticGram», the badge caption, the arrow with their number or their custom emoji with a
 * «Badge #N» pill, the perks list without actions). exteraGram ends with «How to Get» (donation, proof to
 * @exteraOwner) and «Make a Donation»; StaticGram shows its own purchase steps (shop bot, prices from app
 * config) and «Buy Badge» / «Upgrade», or «Got it» when the viewer already has what the sheet offers.
 * (Our SupporterBottomSheet keeps the StaticGram «support / buy» sheet for the no-peer case.)
 */
public class SupporterPeerSheet extends SupporterSheet {

    public SupporterPeerSheet(BaseFragment fragment, TLObject peer, Theme.ResourcesProvider resourcesProvider) {
        super(fragment, resourcesProvider);
        Activity context = fragment.getParentActivity();
        BadgesController controller = BadgesController.INSTANCE;
        TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
        BadgeDTO customBadge = controller.hasCustomBadge(peer) ? controller.getBadge(peer) : null;
        if (customBadge != null) {
            String text = customBadge.getText();
            setBadge(customBadge.getDocumentId());
            setContent(formatOtherTitle(peer), null);
            addNumberPill(BadgesController.getNumber(peer));
            if (!TextUtils.isEmpty(text)) {
                addCaptionBubble(BadgesController.formatBadgeCaption(text, DialogObject.getName(peer), fragment, this::dismiss));
            }
            boolean viewerHasCustom = me != null && (controller.canChangeBadge(me) || controller.isDeveloper(me));
            setupButton(fragment, viewerHasCustom, true);
            commit();
            return;
        }
        BadgeDTO badge = controller.getBadge(peer);
        String text = badge != null ? badge.getText() : null;
        setContent(formatOtherTitle(peer), LocaleController.getString(R.string.SupporterOtherSubtitle));
        if (!TextUtils.isEmpty(text)) {
            addCaptionBubble(BadgesController.formatBadgeCaption(text, DialogObject.getName(peer), fragment, this::dismiss));
        }
        Integer number = BadgesController.getNumber(peer);
        if (number != null) {
            getArrowView().setNumber(BadgesController.formatNumber(number));
        }
        SupporterPerks.addTo(context, this::addView, resourcesProvider, null);
        boolean viewerIsSupporter = me != null && controller.isSupporter(me.id);
        setupButton(fragment, viewerIsSupporter, false);
        commit();
    }

    private void setupButton(BaseFragment fragment, boolean viewerHasIt, boolean custom) {
        String shopUrl = BadgesConfig.getShopUrl();
        if (viewerHasIt || TextUtils.isEmpty(shopUrl)) {
            button.setText(LocaleController.getString(R.string.GotIt), false);
            button.setOnClickListener(v -> dismiss());
            return;
        }
        addHowToGet(custom);
        boolean upgrade = custom && BadgesController.INSTANCE.hasBadge();
        button.setText(LocaleController.getString(upgrade ? R.string.ExteraBadgeUpgrade : R.string.ExteraBadgeBuy), false);
        button.setOnClickListener(v -> {
            dismiss();
            ExternalTelegram.openUrl(fragment.getParentActivity(), shopUrl);
        });
    }

    /**
     * «How to Get», StaticGram flow (no donation / proof of payment): 1) buy the badge in @StaticGramApp → Shop →
     * Badges, prices from app config sg_badges_price_* (BadgesConfig, as on the «exteraStatic badge» screen);
     * 2) custom badge: choose the emoji and caption by tapping the badge in your profile; standard: it appears
     * automatically.
     */
    private void addHowToGet(boolean custom) {
        addHeader(LocaleController.getString(R.string.SupporterHowToGet));
        String shopUrl = BadgesConfig.getShopUrl();
        CharSequence buyInfo = AndroidUtilities.replaceSingleTag(
                LocaleController.formatString(R.string.SgSupporterBuyStepInfo, BadgesConfig.getPriceSupporter(), BadgesConfig.getPriceCustom()),
                Theme.key_chat_messageLinkIn, 0, () -> {
                    if (!TextUtils.isEmpty(shopUrl)) {
                        dismiss();
                        ExternalTelegram.openUrl(fragment.getParentActivity(), shopUrl);
                    }
                }, resourcesProvider);
        addView(new SupporterFeatureCell(getContext(), R.drawable.menu_feature_paid, LocaleController.getString(R.string.SgSupporterBuyStep), buyInfo, resourcesProvider));
        if (custom) {
            addView(new SupporterFeatureCell(getContext(), R.drawable.menu_feature_reactions,
                    LocaleController.getString(R.string.SupporterCustomBadgeChoose), LocaleController.getString(R.string.SgSupporterCustomChooseInfo), resourcesProvider));
        } else {
            CharSequence step3 = AndroidUtilities.replaceSingleTag(LocaleController.getString(R.string.ExteraBadgeStep3Info), Theme.key_chat_messageLinkIn, 0, () -> {
                dismiss();
                SettingsRegistry.getInstance().handleLink(SettingsRegistry.BADGE_ALIAS, null);
            }, resourcesProvider);
            addView(new SupporterFeatureCell(getContext(), R.drawable.menu_feature_reactions, LocaleController.getString(R.string.ExteraBadgeStep3Title), step3, resourcesProvider));
        }
    }

    private CharSequence formatOtherTitle(TLObject peer) {
        return AndroidUtilities.replaceSingleLink(LocaleController.formatString(R.string.SupporterOtherTitle, DialogObject.getName(peer)), getThemedColor(Theme.key_featuredStickers_addButton));
    }

    public static SupporterPeerSheet show(BaseFragment fragment, TLObject peer, Theme.ResourcesProvider resourcesProvider, SupporterEnterFrom enterFrom) {
        if (fragment == null || fragment.getParentActivity() == null || peer == null) {
            return null;
        }
        SupporterPeerSheet sheet = new SupporterPeerSheet(fragment, peer, resourcesProvider);
        sheet.setEnterFrom(enterFrom);
        return fragment.showDialog(sheet) != null ? sheet : null;
    }
}
