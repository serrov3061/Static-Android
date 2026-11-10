package com.exteragram.messenger.badges;

import android.text.TextUtils;

import org.telegram.messenger.AppGlobalConfig;
import org.telegram.messenger.UserConfig;

/**
 * StaticGram badge settings from help.getAppConfig (see dexaudit/badges-contract.md):
 * sg_badges_api_url, sg_badges_bot, sg_badges_default_document_id, sg_badges_developer_document_id,
 * sg_badges_trusted_document_id, sg_badges_shop_url, sg_badges_early_builds_url. Empty/0 until the server provides them,
 * in which case badges are simply not shown.
 */
public final class BadgesConfig {

    private BadgesConfig() {
    }

    private static AppGlobalConfig config() {
        return AppGlobalConfig.getInstance(UserConfig.selectedAccount);
    }

    public static String getApiUrl() {
        try {
            String url = config().sgBadgesApiUrl.get();
            return TextUtils.isEmpty(url) ? null : url.trim();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Username (without @) of the in-network bot that accepts "badge &lt;documentId&gt; [text]" inline queries. */
    public static String getBotUsername() {
        try {
            String bot = config().sgBadgesBot.get();
            if (TextUtils.isEmpty(bot)) {
                return null;
            }
            bot = bot.trim();
            return bot.startsWith("@") ? bot.substring(1) : bot;
        } catch (Throwable t) {
            return null;
        }
    }

    public static String getShopUrl() {
        try {
            String url = config().sgBadgesShopUrl.get();
            return TextUtils.isEmpty(url) ? null : url.trim();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Link of the supporters' early builds channel (exteraGram 13.1.1 "supporters_channel_link"), from app config
     * sg_badges_early_builds_url. Null when absent: the «Early Builds» perk and the «Early Builds Channel» button are hidden.
     */
    public static String getEarlyBuildsUrl() {
        try {
            String url = config().sgBadgesEarlyBuildsUrl.get();
            return TextUtils.isEmpty(url) ? null : url.trim();
        } catch (Throwable t) {
            return null;
        }
    }

    /** app config sg_badges_transfer: the badge bot handles transfer_init / transfer_claim. Off until the server says so. */
    public static boolean isTransferEnabled() {
        try {
            return config().sgBadgesTransfer.get();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String price(AppGlobalConfig.ConfigString key, String fallback) {
        try {
            String value = key.get();
            return TextUtils.isEmpty(value) ? fallback : value.trim();
        } catch (Throwable t) {
            return fallback;
        }
    }

    public static String getPriceSupporter() {
        return price(config().sgBadgesPriceSupporter, "100 ₽ / 100 ⭐");
    }

    public static String getPriceCustom() {
        return price(config().sgBadgesPriceCustom, "300 ₽ / 300 ⭐");
    }

    public static String getPriceUpgrade() {
        return price(config().sgBadgesPriceUpgrade, "200 ₽");
    }

    public static long getDefaultDocumentId() {
        try {
            return config().sgBadgesDefaultDocumentId.get();
        } catch (Throwable t) {
            return 0;
        }
    }

    public static long getDeveloperDocumentId() {
        try {
            return config().sgBadgesDeveloperDocumentId.get();
        } catch (Throwable t) {
            return 0;
        }
    }

    public static long getTrustedDocumentId() {
        try {
            return config().sgBadgesTrustedDocumentId.get();
        } catch (Throwable t) {
            return 0;
        }
    }
}
