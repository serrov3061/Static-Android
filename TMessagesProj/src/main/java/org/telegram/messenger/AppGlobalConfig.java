package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;
import org.telegram.utils.twidget.WidgetMedia;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class AppGlobalConfig {
    private final HashMap<String, ConfigInternal> map = new HashMap<>();

    public final ConfigInt starsPaidMessagesChannelAmountDefault = ofInt("stars_paid_messages_channel_amount_default", 10);

    public final ConfigInt starsSuggestedPostCommissionPermille = ofInt("stars_suggested_post_commission_permille", 850);
    public final ConfigInt starsSuggestedPostAmountMin = ofInt("stars_suggested_post_amount_min", 5);
    public final ConfigInt starsSuggestedPostAmountMax = ofInt("stars_suggested_post_amount_max", 100000);

    public final ConfigInt tonSuggestedPostCommissionPermille = ofInt("ton_suggested_post_commission_permille", 850);
    public final ConfigLong tonSuggestedPostAmountMin = ofLong("ton_suggested_post_amount_min", 10_000_000L); // 0.01 TON
    public final ConfigLong tonSuggestedPostAmountMax = ofLong("ton_suggested_post_amount_max", 10_000_000_000_000L); // 10000 TON

    public final ConfigTime starsSuggestedPostAgeMin = ofTime("stars_suggested_post_age_min", 86400, TimeUnit.SECONDS);
    public final ConfigTime starsSuggestedPostFutureMin = ofTime("stars_suggested_post_future_min", 300, TimeUnit.SECONDS);
    public final ConfigTime starsSuggestedPostFutureMax = ofTime("stars_suggested_post_future_max", 2678400, TimeUnit.SECONDS);

    public final ConfigDouble tonUsdRate = ofDouble("ton_usd_rate", 3);

    public final ConfigString starsRatingLearnMoreUrl = ofString("stars_rating_learnmore_url", "https://docs.staticgram.top/faq");
    public final ConfigBoolean needAgeVideoVerification = ofBoolean("need_age_video_verification", false);

    public final ConfigInt starsStarGiftResaleCommissionPermille = ofInt("stars_stargift_resale_commission_permille", 800);
    public final ConfigInt tonStarGiftResaleCommissionPermille = ofInt("ton_stargift_resale_commission_permille", 800);
    public final ConfigInt starsStarGiftResaleAmountMin = ofInt("stars_stargift_resale_amount_min", 125);
    public final ConfigInt starsStarGiftResaleAmountMax = ofInt("stars_stargift_resale_amount_max", 35000);
    public final ConfigLong tonStarGiftResaleAmountMin = ofLong("ton_stargift_resale_amount_min", 10_000_000L);
    public final ConfigLong tonStarGiftResaleAmountMax = ofLong("ton_stargift_resale_amount_max", 10_000_000_000_000L);

    public final ConfigInt stargiftsCollectionsLimit = ofInt("stargifts_collections_limit", 100);
    public final ConfigInt stargiftsCollectionGiftsLimit = ofInt("stargifts_collection_gifts_limit", 100);
    public final ConfigInt storiesAlbumsLimit = ofInt("stories_albums_limit", 100);
    public final ConfigInt storiesAlbumStoriesLimit = ofInt("stories_album_stories_limit", 100);

    public final ConfigTime messageTypingDraftTtl = ofTime("message_typing_draft_ttl", 30, TimeUnit.SECONDS);

    public final ConfigTime groupCallMessageTtl = ofTime("group_call_message_ttl", 10, TimeUnit.SECONDS);
    public final ConfigInt groupCallMessageLengthLimit = ofInt("group_call_message_length_limit", 128);

    public final ConfigInt contactNoteLengthLimit = ofInt("contact_note_length_limit", 128);

    public final ConfigInt passkeysAccountPasskeysMax = ofInt("passkeys_account_passkeys_max", 5);

    public final ConfigBoolean settingsDisplayPasskeys = ofBoolean("settings_display_passkeys", BuildVars.DEBUG_VERSION ? true : false);

    public final ConfigBoolean disableBlurInLightTheme = ofBoolean("android_disable_blur_in_light_theme", false);
    public final ConfigBoolean disableBlurInDarkTheme = ofBoolean("android_disable_blur_in_dark_theme", false);

    public final ConfigTime noForwardsRequestExpirePeriod = ofTime("no_forwards_request_expire_period", 86400, TimeUnit.SECONDS);
    public final ConfigTime pollClosePeriodMax = ofTime("poll_close_period_max", 86400 * 30, TimeUnit.SECONDS);

    public final ConfigString musicSearchUsername = ofString("music_search_username", null);

    public final ConfigInt pollAnswersMax = ofInt("poll_answers_max", 12);
    public final ConfigInt pollCountriesMax = ofInt("poll_countries_max", 12);
    public final ConfigInt pollAnswerLengthMax = ofInt("poll_answer_length_max", 100);
    public final ConfigInt pollQuestionLengthMax = ofInt("poll_question_length_max", 255);
    public final ConfigInt pollSolutionLengthMax = ofInt("poll_solution_length_max", 200);
    public final ConfigInt pollCaptionLengthMax = ofInt("poll_caption_length_max", 300);

    public final ConfigTime pollAnswerDeletePeriod = ofTime("poll_answer_delete_period", 300, TimeUnit.SECONDS);

    public final ConfigInt botsCreateLimitDefault = ofInt("bots_create_limit_default", 20);
    public final ConfigInt botsCreateLimitPremium = ofInt("bots_create_limit_premium", 40);
    public final ConfigInt botAdditionalUsernamesLimit = ofInt("bot_additional_usernames_limit", 2);

    public final ConfigString phoneCountryIso2 = ofString("phone_country_iso2", "en");

    public final ConfigInt aicomposeToneExamplesNum = ofInt("aicompose_tone_examples_num", 3);
    public final ConfigInt aicomposeToneTitleLengthMax = ofInt("aicompose_tone_title_length_max", 12);
    public final ConfigInt aicomposeTonePromptLengthMax = ofInt("aicompose_tone_prompt_length_max", 1024);
    public final ConfigInt aicomposeToneSavedLimitDefault = ofInt("aicompose_tone_saved_limit_default", 5);
    public final ConfigInt aicomposeToneSavedLimitPremium = ofInt("aicompose_tone_saved_limit_premium", 20);

    public final ConfigBoolean messagePrimaryEditedDate = ofBoolean("message_primary_edited_date", false);

    public final ConfigInt richMessageLengthLimit = ofInt("rich_message_length_limit", 32_768);
    public final ConfigInt richMessageMaxBlocks = ofInt("rich_message_max_blocks", 500);
    public final ConfigInt richMessageMaxDepth = ofInt("rich_message_max_depth", 16);
    public final ConfigInt richMessageMaxMedia = ofInt("rich_message_max_media", 50);
    public final ConfigInt richMessageMaxTableCols = ofInt("rich_message_max_table_cols", 20);
    public final ConfigString richMessagePosting = ofString("rich_message_posting", "premium");

    public final ConfigInt communityPeersLimit = ofInt("community_peers_limit", 100);
    public final ConfigInt communityBotPeersLimit = ofInt("community_bot_peers_limit", 100);

    public final ConfigInt messageLengthLimitDefault = ofInt("message_length_limit_default", 4096);
    public final ConfigInt messageLengthLimitPremium = ofInt("message_length_limit_premium", 8192);

    public final ConfigInt quickReplyMessagesLimit = ofInt("quick_reply_messages_limit", 20);
    public final ConfigInt ephemeralWelcomeMessagesMax = ofInt("ephemeral_welcome_messages_max", 5);

    // StaticGram: exteraStatic badges (dexaudit/badges-contract.md); document ids may come as strings
    public final ConfigString sgBadgesApiUrl = ofString("sg_badges_api_url", null);
    public final ConfigString sgBadgesBot = ofString("sg_badges_bot", null);
    public final ConfigString sgBadgesShopUrl = ofString("sg_badges_shop_url", null);
    public final ConfigString sgBadgesPriceSupporter = ofString("sg_badges_price_supporter", null);
    public final ConfigString sgBadgesPriceCustom = ofString("sg_badges_price_custom", null);
    public final ConfigString sgBadgesPriceUpgrade = ofString("sg_badges_price_upgrade", null);
    public final ConfigLong sgBadgesDefaultDocumentId = ofLong("sg_badges_default_document_id", 0);
    public final ConfigLong sgBadgesDeveloperDocumentId = ofLong("sg_badges_developer_document_id", 0);
    public final ConfigLong sgBadgesTrustedDocumentId = ofLong("sg_badges_trusted_document_id", 0);
    // supporter perks (exteraGram 13.1.1 «Early Builds Channel», remote key supporters_channel_link there); null/"" = hidden
    public final ConfigString sgBadgesEarlyBuildsUrl = ofString("sg_badges_early_builds_url", null);
    // the badge bot supports transfer_init / transfer_claim (exteraGram 13.1.1 SupporterTransfer); false = no transfer button
    public final ConfigBoolean sgBadgesTransfer = ofBoolean("sg_badges_transfer", false);

    // StaticGram: JSON array of positive channel ids (as strings) trusted as plugin sources
    public final ConfigIdList sgTrustedPluginChannels = ofIdList("sg_trusted_plugin_channels");
    // StaticGram plugin antivirus endpoint (PluginGuard); null = built-in default
    public final ConfigString sgPluginCheckUrl = ofString("sg_plugin_check_url", null);
    // StaticGram plugin catalog: index of the built-in repository (@PluginsBot); null = https://plugins.staticgram.top/catalog/index.json
    public final ConfigString sgPluginsCatalogUrl = ofString("sg_plugins_catalog_url", null);
    public final ConfigString sgPluginsCatalogChannel = ofString("sg_plugins_catalog_channel", null);

    public final ConfigBoolean roundVideoRecorder2Allowed = ofBoolean("round_video_recorder_2_allowed", true);
    public final ConfigBoolean audioRecorderV2Disabled = ofBoolean("android_audio_recorder_v2_disabled", false);
    public final ConfigBoolean roundVideoRecorderV2Disabled = ofBoolean("android_round_video_recorder_v2_disabled", false);

    public final ConfigBoolean starsSpendTopUpInvoiceDisabled = ofBoolean("stars_spend_topup_invoice_disabled", false);

    public final ConfigStringSet botAllowedSuffixes = ofStringSet("bot_allowed_suffixes", Collections.singleton("bot"));

    public final ConfigBoolean walletAvailable = ofBoolean("wallet_available", BuildVars.DEBUG_VERSION);
    public final ConfigBoolean walletPaidMessageRecipientMessageAmountCheck = ofBoolean("wallet_paid_message_recipient_message_amount_check", true);
    public final ConfigLong walletTransferMinNanos = ofLong("wallet_transfer_min_nanos", 100_000_000);
    public final ConfigLong walletGaslessMinNanos = ofLong("wallet_gasless_min_nanos", 100_000_000);
    public final ConfigStringSet walletCardBgGifts = ofStringSet("wallet_card_bg_gifts", Collections.emptySet());
    public final ConfigLongSet agentBotIds = ofLongSet("agent_bot_ids");

    // Kill switch for message widgets (messageMediaWidget): true and the client runs no widget code at all
    // (WidgetSandboxSession#disabledByConfig). Kept across restarts; turned back on only by an explicit false.
    public final ConfigBoolean widgetsDisabled = ofBoolean("widgets_disabled", false);

    /* * */

    public boolean apply(SharedPreferences.Editor editor, TLRPC.TL_jsonObject object) {
        boolean changed = false;

        for (int a = 0, N = object.value.size(); a < N; a++) {
            final TLRPC.TL_jsonObjectValue value = object.value.get(a);
            final ConfigInternal configHandler = map.get(value.key);
            if (configHandler == null) {
                continue;
            }

            changed |= configHandler.apply(editor, value.value);
        }

        if (widgetsDisabled.get()) {
            // The widgets running now stop at once; new ones never start.
            WidgetMedia.onKillSwitch();
        }

        return changed;
    }

    public void load(SharedPreferences preferences) {
        for (ConfigInternal configHandler : map.values()) {
            try {
                configHandler.load(preferences);
            } catch (ClassCastException e) {
                FileLog.e(e);
            }
        }
    }



    /* * */

    private interface ConfigInternal {
        boolean apply(SharedPreferences.Editor editor, TLRPC.JSONValue tlValue);
        void load(SharedPreferences preferences);
    }

    public static class ConfigInt {
        private final Internal handler;

        private ConfigInt(String name, int defaultValue) {
            this.handler = new Internal(name, defaultValue);
        }

        public int get() {
            return handler.value;
        }

        private static class Internal implements ConfigInternal {
            private final String name;
            private final int defaultValue;
            private int value;

            private Internal(String name, int defaultValue) {
                this.name = name;
                this.defaultValue = defaultValue;
            }

            @Override
            public boolean apply(SharedPreferences.Editor editor, TLRPC.JSONValue tlValue) {
                if (tlValue instanceof TLRPC.TL_jsonNumber) {
                    TLRPC.TL_jsonNumber num = (TLRPC.TL_jsonNumber) tlValue;
                    if (num.value != value) {
                        value = (int) num.value;
                        editor.putInt(name, value);
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void load(SharedPreferences preferences) {
                value = preferences.getInt(name, defaultValue);
            }
        }
    }

    public static class ConfigLong {
        private final Internal handler;

        private ConfigLong(String name, long defaultValue) {
            this.handler = new Internal(name, defaultValue);
        }

        public long get() {
            return handler.value;
        }

        private static class Internal implements ConfigInternal {
            private final String name;
            private final long defaultValue;
            private long value;

            private Internal(String name, long defaultValue) {
                this.name = name;
                this.defaultValue = defaultValue;
            }

            @Override
            public boolean apply(SharedPreferences.Editor editor, TLRPC.JSONValue tlValue) {
                if (tlValue instanceof TLRPC.TL_jsonNumber) {
                    TLRPC.TL_jsonNumber num = (TLRPC.TL_jsonNumber) tlValue;
                    if (num.value != value) {
                        value = (long) num.value;
                        editor.putLong(name, value);
                        return true;
                    }
                } else if (tlValue instanceof TLRPC.TL_jsonString) {
                    // StaticGram: 64-bit ids (e.g. document ids) do not fit a JSON double, so they may come as strings
                    try {
                        long parsed = Long.parseLong(((TLRPC.TL_jsonString) tlValue).value.trim());
                        if (parsed != value) {
                            value = parsed;
                            editor.putLong(name, value);
                            return true;
                        }
                    } catch (Exception ignore) {
                    }
                }
                return false;
            }

            @Override
            public void load(SharedPreferences preferences) {
                value = preferences.getLong(name, defaultValue);
            }
        }
    }

    public static class ConfigDouble {
        private final Internal handler;

        private ConfigDouble(String name, double defaultValue) {
            this.handler = new Internal(name, defaultValue);
        }

        public double get() {
            return handler.value;
        }

        private static class Internal implements ConfigInternal {
            private final String name;
            private final double defaultValue;
            private double value;

            private Internal(String name, double defaultValue) {
                this.name = name;
                this.defaultValue = defaultValue;
            }

            @Override
            public boolean apply(SharedPreferences.Editor editor, TLRPC.JSONValue tlValue) {
                if (tlValue instanceof TLRPC.TL_jsonNumber) {
                    TLRPC.TL_jsonNumber num = (TLRPC.TL_jsonNumber) tlValue;
                    if (num.value != value) {
                        value = num.value;
                        editor.putFloat(name, (float) value);
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void load(SharedPreferences preferences) {
                value = preferences.getFloat(name, (float) defaultValue);
            }
        }
    }

    public static class ConfigString {
        private final Internal handler;

        private ConfigString(String name, String defaultValue) {
            this.handler = new Internal(name, defaultValue);
        }

        public String get() {
            return handler.value;
        }

        public boolean is(String value) {
            return TextUtils.equals(get(), value);
        }

        private static class Internal implements ConfigInternal {
            private final String name;
            private final String defaultValue;
            private String value;

            private Internal(String name, String defaultValue) {
                this.name = name;
                this.defaultValue = defaultValue;
            }

            @Override
            public boolean apply(SharedPreferences.Editor editor, TLRPC.JSONValue tlValue) {
                if (tlValue instanceof TLRPC.TL_jsonString) {
                    TLRPC.TL_jsonString num = (TLRPC.TL_jsonString) tlValue;
                    if (!TextUtils.equals(num.value, value)) {
                        value = num.value;
                        editor.putString(name, value);
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void load(SharedPreferences preferences) {
                value = preferences.getString(name, defaultValue);
            }
        }
    }

    public static class ConfigIdList {
        private final Internal handler;

        private ConfigIdList(String name) {
            this.handler = new Internal(name);
        }

        public boolean contains(long id) {
            return handler.value.contains(id);
        }

        public java.util.Set<Long> get() {
            return handler.value;
        }

        private static class Internal implements ConfigInternal {
            private final String name;
            private volatile java.util.Set<Long> value = java.util.Collections.emptySet();

            private Internal(String name) {
                this.name = name;
            }

            private static void parseItem(String s, java.util.Set<Long> out) {
                if (s == null) return;
                s = s.trim();
                if (s.startsWith("-100")) s = s.substring(4);
                else if (s.startsWith("-")) s = s.substring(1);
                try {
                    long v = Long.parseLong(s);
                    if (v > 0) out.add(v);
                } catch (Exception ignore) {
                }
            }

            @Override
            public boolean apply(SharedPreferences.Editor editor, TLRPC.JSONValue tlValue) {
                java.util.Set<Long> parsed = new java.util.HashSet<>();
                if (tlValue instanceof TLRPC.TL_jsonArray) {
                    for (TLRPC.JSONValue v : ((TLRPC.TL_jsonArray) tlValue).value) {
                        if (v instanceof TLRPC.TL_jsonString) {
                            parseItem(((TLRPC.TL_jsonString) v).value, parsed);
                        } else if (v instanceof TLRPC.TL_jsonNumber) {
                            long n = (long) ((TLRPC.TL_jsonNumber) v).value;
                            if (n > 0) parsed.add(n);
                        }
                    }
                } else if (tlValue instanceof TLRPC.TL_jsonString) {
                    String raw = ((TLRPC.TL_jsonString) tlValue).value;
                    for (String part : raw.replaceAll("[\\[\\]\"\\s]", "").split(",")) {
                        parseItem(part, parsed);
                    }
                } else {
                    return false;
                }
                if (parsed.equals(value)) return false;
                value = parsed;
                StringBuilder sb = new StringBuilder();
                for (long id : parsed) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(id);
                }
                editor.putString(name, sb.toString());
                return true;
            }

            @Override
            public void load(SharedPreferences preferences) {
                java.util.Set<Long> parsed = new java.util.HashSet<>();
                String raw = preferences.getString(name, "");
                if (raw != null) {
                    for (String part : raw.split(",")) parseItem(part, parsed);
                }
                value = parsed;
            }
        }
    }

    public static class ConfigStringSet {
        private final Internal handler;

        private ConfigStringSet(String name, Set<String> defaultValue) {
            this.handler = new Internal(name, defaultValue);
        }

        public Set<String> get() {
            return handler.value;
        }

        private static class Internal implements ConfigInternal {
            private final String name;
            private final Set<String> defaultValue;
            private Set<String> value;

            private Internal(String name, Set<String> defaultValue) {
                this.name = name;
                this.defaultValue = defaultValue == null ? null : new HashSet<>(defaultValue);
            }

            @Override
            public boolean apply(SharedPreferences.Editor editor, TLRPC.JSONValue tlValue) {
                if (tlValue instanceof TLRPC.TL_jsonArray) {
                    TLRPC.TL_jsonArray array = (TLRPC.TL_jsonArray) tlValue;
                    HashSet<String> newValue = new HashSet<>();
                    for (int i = 0, N = array.value.size(); i < N; i++) {
                        TLRPC.JSONValue item = array.value.get(i);
                        if (item instanceof TLRPC.TL_jsonString) {
                            newValue.add(((TLRPC.TL_jsonString) item).value);
                        }
                    }
                    if (!newValue.equals(value)) {
                        value = newValue;
                        editor.putStringSet(name, new HashSet<>(value));
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void load(SharedPreferences preferences) {
                Set<String> stored = preferences.getStringSet(name, defaultValue);
                value = stored == null ? null : new HashSet<>(stored);
            }
        }
    }

    public static class ConfigLongSet {
        private final Internal handler;

        private ConfigLongSet(String name) {
            this.handler = new Internal(name);
        }

        public Set<Long> get() {
            return handler.value;
        }

        public boolean contains(long id) {
            return handler.value.contains(id);
        }

        private static class Internal implements ConfigInternal {
            private final String name;
            private Set<Long> value = Collections.emptySet();

            private Internal(String name) {
                this.name = name;
            }

            @Override
            public boolean apply(SharedPreferences.Editor editor, TLRPC.JSONValue tlValue) {
                if (tlValue instanceof TLRPC.TL_jsonArray) {
                    TLRPC.TL_jsonArray array = (TLRPC.TL_jsonArray) tlValue;
                    HashSet<Long> newValue = new HashSet<>();
                    for (int i = 0, N = array.value.size(); i < N; i++) {
                        TLRPC.JSONValue item = array.value.get(i);
                        if (item instanceof TLRPC.TL_jsonNumber) {
                            newValue.add((long) ((TLRPC.TL_jsonNumber) item).value);
                        } else if (item instanceof TLRPC.TL_jsonString) {
                            try {
                                newValue.add(Long.parseLong(((TLRPC.TL_jsonString) item).value));
                            } catch (NumberFormatException ignore) {}
                        }
                    }
                    if (!newValue.equals(value)) {
                        value = newValue;
                        HashSet<String> stored = new HashSet<>();
                        for (Long id : value) {
                            stored.add(Long.toString(id));
                        }
                        editor.putStringSet(name, stored);
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void load(SharedPreferences preferences) {
                Set<String> stored = preferences.getStringSet(name, null);
                HashSet<Long> newValue = new HashSet<>();
                if (stored != null) {
                    for (String s : stored) {
                        try {
                            newValue.add(Long.parseLong(s));
                        } catch (NumberFormatException ignore) {}
                    }
                }
                value = newValue;
            }
        }
    }

    public static class ConfigBoolean {
        private final Internal handler;

        private ConfigBoolean(String name, boolean defaultValue) {
            this.handler = new Internal(name, defaultValue);
        }

        public boolean get() {
            return handler.value;
        }

        private static class Internal implements ConfigInternal {
            private final String name;
            private final boolean defaultValue;
            private boolean value;

            private Internal(String name, boolean defaultValue) {
                this.name = name;
                this.defaultValue = defaultValue;
            }

            @Override
            public boolean apply(SharedPreferences.Editor editor, TLRPC.JSONValue tlValue) {
                if (tlValue instanceof TLRPC.TL_jsonBool) {
                    TLRPC.TL_jsonBool num = (TLRPC.TL_jsonBool) tlValue;
                    if (num.value != value) {
                        value = num.value;
                        editor.putBoolean(name, value);
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void load(SharedPreferences preferences) {
                value = preferences.getBoolean(name, defaultValue);
            }
        }
    }

    public static class ConfigTime {
        private final ConfigLong.Internal handler;
        private final TimeUnit timeUnit;

        private ConfigTime(String name, TimeUnit timeUnit, long defaultValue) {
            this.handler = new ConfigLong.Internal(name, defaultValue);
            this.timeUnit = timeUnit;
        }

        public long get(TimeUnit timeUnit) {
            return timeUnit.convert(handler.value, this.timeUnit);
        }
    }

    private ConfigInt ofInt(String name, int defaultValue) {
        ConfigInt configInt = new ConfigInt(name, defaultValue);
        map.put(name, configInt.handler);

        return configInt;
    }

    private ConfigLong ofLong(String name, long defaultValue) {
        ConfigLong configInt = new ConfigLong(name, defaultValue);
        map.put(name, configInt.handler);

        return configInt;
    }

    private ConfigDouble ofDouble(String name, double defaultValue) {
        ConfigDouble configDouble = new ConfigDouble(name, defaultValue);
        map.put(name, configDouble.handler);

        return configDouble;
    }

    private ConfigBoolean ofBoolean(String name, boolean defaultValue) {
        ConfigBoolean configBoolean = new ConfigBoolean(name, defaultValue);
        map.put(name, configBoolean.handler);

        return configBoolean;
    }

    private ConfigIdList ofIdList(String name) {
        ConfigIdList config = new ConfigIdList(name);
        map.put(name, config.handler);

        return config;
    }

    private ConfigString ofString(String name, String defaultValue) {
        ConfigString config = new ConfigString(name, defaultValue);
        map.put(name, config.handler);

        return config;
    }

    private ConfigLongSet ofLongSet(String name) {
        ConfigLongSet config = new ConfigLongSet(name);
        map.put(name, config.handler);

        return config;
    }

    private ConfigStringSet ofStringSet(String name, Set<String> defaultValue) {
        ConfigStringSet config = new ConfigStringSet(name, defaultValue);
        map.put(name, config.handler);

        return config;
    }

    private ConfigTime ofTime(String name, long defaultValue, TimeUnit timeUnit) {
        ConfigTime configInt = new ConfigTime(name, timeUnit, defaultValue);
        map.put(name, configInt.handler);

        return configInt;
    }

    public static AppGlobalConfig getInstance(int num) {
        return MessagesController.getInstance(num).config;
    }
}
