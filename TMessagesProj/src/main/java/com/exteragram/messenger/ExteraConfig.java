package com.exteragram.messenger;

import android.content.SharedPreferences;
import android.util.Pair;

import com.exteragram.messenger.backup.PreferencesUtils;
import com.exteragram.messenger.config.BasePref;
import com.exteragram.messenger.config.BooleanPref;
import com.exteragram.messenger.config.BottomNavigationBar;
import com.exteragram.messenger.config.EnumPref;
import com.exteragram.messenger.config.FloatPref;
import com.exteragram.messenger.config.IntegerPref;
import com.exteragram.messenger.config.LongPref;
import com.exteragram.messenger.config.NullableStringPref;
import com.exteragram.messenger.config.PrefClassesKt;
import com.exteragram.messenger.config.SanitizedIntegerPref;
import com.exteragram.messenger.config.StringPref;
import com.exteragram.messenger.config.StringSetPref;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.translator.TranslatorUtils;
import com.exteragram.messenger.utils.network.RemoteUtils;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.web.SearchEngine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * exteraGram settings storage (SharedPreferences "exteraconfig").
 * Java port of exteraGram 12.10.6 ExteraConfig.kt: same keys, defaults and types.
 * Accessors keep the Kotlin names (getX/setX) so the org.telegram hooks can be ported verbatim.
 */
public final class ExteraConfig {

    private static final Gson GSON = new Gson();
    private static final SharedPreferences preferences = PreferencesUtils.getPreferences("exteraconfig");
    private static final SharedPreferences.Editor editor = preferences.edit();
    private static final Object sync = new Object();
    private static final AtomicBoolean servicesStarted = new AtomicBoolean(false);
    private static boolean configLoaded;
    private static SharedPreferences.OnSharedPreferenceChangeListener apiBotListener;
    private static volatile Pair<Long, String> currentApiBot;

    private static ArrayList<String> doNotMarkAsNew = new ArrayList<>();
    private static HashMap<String, Long> newFeaturesShowedAt = new HashMap<>();
    private static ArrayList<String> iconPacksLayout = new ArrayList<>();
    private static ArrayList<String> iconPacksHidden = new ArrayList<>();
    private static ArrayList<Integer> mainMenuLayout = new ArrayList<>();
    private static ArrayList<Integer> mainMenuHiddenItems = new ArrayList<>();
    private static boolean pluginsEngine;

    private static final SearchEngine yandexSearchEngine = new SearchEngine("Yandex", /* homepage (12.10.6 SearchEngine field) "https://mini.ya.ru/", */ "https://ya.ru/search/?text=", "https://suggestqueries.google.com/complete/search?client=chrome&q=", "https://yandex.ru/legal/confidential");

    private static final IntegerPref translationProvider = new IntegerPref(0, "translationProvider");
    private static final BooleanPref disableNumberRounding = new BooleanPref(false, "disableNumberRounding");
    private static final BooleanPref formatTimeWithSeconds = new BooleanPref(false, "formatTimeWithSeconds");
    private static final BooleanPref relativeLastSeen = new BooleanPref(false, "relativeLastSeen");
    private static final BooleanPref inAppVibration = new BooleanPref(true, "inAppVibration");
    private static final BooleanPref disableNotificationDelay = new BooleanPref(false, "disableNotificationDelay");
    private static final BooleanPref filterZalgo = new BooleanPref(true, "filterZalgo");
    private static final IntegerPref downloadSpeedBoost = new IntegerPref(0, "downloadSpeedBoost");
    private static final BooleanPref uploadSpeedBoost = new BooleanPref(false, "uploadSpeedBoost");
    private static final BooleanPref hidePhoneNumber = new BooleanPref(false, "hidePhoneNumber");
    private static final IntegerPref showIdAndDc = new IntegerPref(1, "showIdAndDc");
    private static final BooleanPref hideArchiveFolder = new BooleanPref(false, "hideArchiveFolder");
    private static final BooleanPref archiveOnPull = new BooleanPref(false, "archiveOnPull");
    private static final BooleanPref disableUnarchiveSwipe = new BooleanPref(true, "disableUnarchiveSwipe");
    private static final IntegerPref doNotUseProxy = new IntegerPref(0, "doNotUseProxy");
    private static final StringPref customSavePath = new StringPref("StaticGram", "customSavePath");
    private static final EnumPref<IconPackType> iconPack = new EnumPref<>(IconPackType.DEFAULT, "iconPack");
    private static final NullableStringPref editingIconPackId = new NullableStringPref(null, "editingIconPackId");
    private static final FloatPref avatarCorners = new FloatPref(28.0f, "avatarCorners");
    private static final BooleanPref singleCornerRadius = new BooleanPref(false, "singleCornerRadius");
    private static final EnumPref<DividerStyle> dividerStyle = new EnumPref<>(DividerStyle.LINE, "dividerStyle");
    private static final BooleanPref forceSnow = new BooleanPref(false, "forceSnow");
    private static final BooleanPref hideActionBarStatus = new BooleanPref(false, "hideActionBarStatus");
    private static final BooleanPref centerTitle = new BooleanPref(false, "centerTitle");
    private static final BooleanPref hideStories = new BooleanPref(false, "hideStories");
    private static final BooleanPref hideFloatingButton = new BooleanPref(false, "hideFloatingButton");
    private static final BooleanPref hideDialogsSearchBar = new BooleanPref(false, "hideDialogsSearchBar");
    private static final BooleanPref senderMiniAvatars = new BooleanPref(true, "senderMiniAvatars");
    private static final IntegerPref titleText = new IntegerPref(0, "titleText");
    private static final EnumPref<TabIconsMode> tabIcons = new EnumPref<>(TabIconsMode.TITLES_ONLY, "tabIcons");
    private static final EnumPref<TabCounterMode> tabCounterMode = new EnumPref<>(TabCounterMode.ALL, "tabCounterMode");
    private static final BooleanPref hideAllChats = new BooleanPref(false, "hideAllChats");
    private static final BooleanPref squareFab = new BooleanPref(true, "squareFab");
    private static final FloatPref sectionRadius = new FloatPref(20.0f, "sectionRadius");
    private static final BooleanPref sectionsSeparatedHeadersPreference = new BooleanPref(true, "sectionsSeparatedHeaders");
    private static final BooleanPref newLoadingStyle = new BooleanPref(true, "newLoadingStyle");
    private static final BooleanPref newSliderStyle = new BooleanPref(true, "newSliderStyle");
    private static final BooleanPref newSwitchStyle = new BooleanPref(true, "newSwitchStyle");
    private static final BooleanPref newChatHeaderStyle = new BooleanPref(false, "newChatHeaderStyle");
    private static final BooleanPref newNavigationBarStyle = new BooleanPref(false, "newNavigationBarStyle");
    private static final BooleanPref newFabStyle = new BooleanPref(false, "newFabStyle");
    private static final IntegerPref tabletMode = new IntegerPref(0, "tabletMode");
    private static final BooleanPref useSystemFonts = new BooleanPref(true, "useSystemFonts");
    private static final BooleanPref gooeyAvatarAnimation = new BooleanPref(true, "gooeyAvatarAnimation");
    private static final BooleanPref customThemes = new BooleanPref(true, "customThemes");
    private static final FloatPref predictiveBackIntensity = new FloatPref(1.0f, "predictiveBackIntensity");
    // StaticGram: exteraGram defaults to SPRING; until the ported spring/AOSP transitions are checked on a device
    // the default stays Telegram's own animation (DEFAULT). SPRING/AOSP are selectable in Navigation settings.
    private static final EnumPref<TransitionAnimation> transitionAnimation = new EnumPref<>(TransitionAnimation.DEFAULT, "transitionAnimation");
    private static final EnumPref<GlassOutlineStyle> glassOutlineStyle = new EnumPref<>(GlassOutlineStyle.GLARE, "glassOutlineStyle");
    private static final BooleanPref glassMessageMenu = new BooleanPref(true, "glassMessageMenu");
    private static final BooleanPref forceBlur = new BooleanPref(false, "forceBlur");
    private static final IntegerPref eventType = new IntegerPref(0, "eventType");
    private static final BooleanPref navigationDrawer = new BooleanPref(false, "navigationDrawer");
    private static final BooleanPref immersiveDrawerAnimation = new BooleanPref(false, "immersiveDrawerAnimation");
    // StaticGram: back gesture style — Telegram (screen slides right, default) or the exteraGram card
    private static final BooleanPref telegramPredictiveBack = new BooleanPref(true, "sgTelegramPredictiveBack");
    // StaticGram: darken the screen behind while swiping back / sliding (off by default)
    private static final BooleanPref backScrim = new BooleanPref(false, "sgBackScrim");
    private static final BooleanPref showFeedTab = new BooleanPref(false, "showFeedTab");
    private static final BooleanPref showFeedUnreadCounter = new BooleanPref(true, "showFeedUnreadCounter");
    private static final FloatPref stickerSize = new FloatPref(12.0f, "stickerSize");
    private static final EnumPref<StickerTimeMode> stickerTimeMode = new EnumPref<>(StickerTimeMode.DEFAULT, "stickerTimeMode");
    private static final BooleanPref replyColors = new BooleanPref(true, "replyColors");
    private static final BooleanPref replyEmoji = new BooleanPref(true, "replyEmoji");
    private static final BooleanPref replyBackground = new BooleanPref(true, "replyBackground");
    private static final IntegerPref stickerShape = new IntegerPref(1, "stickerShape");
    private static final BooleanPref unlimitedRecentStickers = new BooleanPref(false, "unlimitedRecentStickers");
    private static final BooleanPref hideReactionsInPrivateChats = new BooleanPref(false, "hideReactionsInPrivateChats");
    private static final BooleanPref hideReactionsInChannels = new BooleanPref(false, "hideReactionsInChannels");
    private static final BooleanPref hideReactionsInGroups = new BooleanPref(false, "hideReactionsInGroups");
    private static final SanitizedIntegerPref doubleTapAction = new SanitizedIntegerPref(1, "doubleTapAction");
    private static final SanitizedIntegerPref doubleTapActionOutOwner = new SanitizedIntegerPref(1, "doubleTapActionOutOwner");
    private static final StringPref swipeActions = new StringPref("2", "swipeActions");
    private static final BooleanPref swipeActionsLoop = new BooleanPref(false, "swipeActionsLoop");
    private static final BooleanPref swipeActionsReversed = new BooleanPref(false, "swipeActionsReversed");
    private static final IntegerPref bottomButton = new IntegerPref(2, "bottomButton");
    private static final BooleanPref widePostsInFeed = new BooleanPref(true, "widePostsInFeed");
    private static final BooleanPref widePostsInChannels = new BooleanPref(false, "widePostsInChannels");
    private static final BooleanPref telegramAiEditor = new BooleanPref(true, "telegramAiEditor");
    private static final BooleanPref telegramAiSummaries = new BooleanPref(true, "telegramAiSummaries");
    private static final BooleanPref telegramAiInstantViewSummaries = new BooleanPref(true, "telegramAiInstantViewSummaries");
    private static final BooleanPref quickAdminShortcuts = new BooleanPref(true, "quickAdminShortcuts");
    private static final BooleanPref quickTransitionForChannels = new BooleanPref(true, "quickTransitionForChannels");
    private static final BooleanPref quickTransitionForTopics = new BooleanPref(true, "quickTransitionForTopics");
    private static final BooleanPref disableGreetingSticker = new BooleanPref(false, "disableGreetingSticker");
    private static final BooleanPref hideKeyboardOnScroll = new BooleanPref(false, "hideKeyboardOnScroll");
    private static final BooleanPref addCommaAfterMention = new BooleanPref(true, "addCommaAfterMention");
    private static final BooleanPref inlineMathResult = new BooleanPref(true, "inlineMathResult");
    // exteraGram 13.1.1
    private static final BooleanPref newProfileActionsStyle = new BooleanPref(false, "newProfileActionsStyle");
    private static final BooleanPref newPlayerStyle = new BooleanPref(false, "newPlayerStyle");
    private static final BooleanPref playerCoverColors = new BooleanPref(false, "playerCoverColors");
    private static final BooleanPref playerMiniCoverColors = new BooleanPref(false, "playerMiniCoverColors");
    private static final BooleanPref lyricsOnline = new BooleanPref(false, "lyricsOnline");
    private static final IntegerPref lyricsProgress = new IntegerPref(1, "lyricsProgress");
    private static final BooleanPref playerMotionCovers = new BooleanPref(false, "playerMotionCovers");
    private static final BooleanPref playerSpotifyCanvas = new BooleanPref(false, "playerSpotifyCanvas");
    private static final BooleanPref playerRhythmBackground = new BooleanPref(true, "playerRhythmBackground");
    private static final BooleanPref playerParticles = new BooleanPref(false, "playerParticles");
    private static final BooleanPref playerSpeedPitch = new BooleanPref(false, "playerSpeedPitch");
    private static final IntegerPref playerMode = new IntegerPref(0, "playerMode");
    private static final FloatPref tabletLeftFraction = new FloatPref(0.35f, "tabletLeftFraction");
    private static final BooleanPref hideNavigationBarLabels = new BooleanPref(false, "hideNavigationBarLabels");
    private static final BooleanPref inlineResults = new BooleanPref(true, "inlineResults");
    private static final BooleanPref inlineCurrencyRates = new BooleanPref(true, "inlineCurrencyRates");
    private static final BooleanPref inlineUnitConversion = new BooleanPref(true, "inlineUnitConversion");
    private static final BooleanPref inlineTimeZones = new BooleanPref(false, "inlineTimeZones");
    private static final StringPref inlineTargetCurrency = new StringPref("", "inlineTargetCurrency");
    private static final EnumPref<AngleUnit> inlineAngleUnit = new EnumPref<>(AngleUnit.DEGREES, "inlineAngleUnit");
    private static final EnumPref<MeasureSystem> inlineMeasureSystem = new EnumPref<>(MeasureSystem.AUTO, "inlineMeasureSystem");
    private static final BooleanPref inlineDigitGrouping = new BooleanPref(true, "inlineDigitGrouping");
    private static final BooleanPref pluginsStaticAnalysis = new BooleanPref(true, "pluginsStaticAnalysis");
    private static final BooleanPref disableMarkdown = new BooleanPref(false, "disableMarkdown");
    private static final BooleanPref hideSendAsPeer = new BooleanPref(false, "hideSendAsPeer");
    /** Cherrygram port: iOS-style message input field and chat footer (side glass bubbles). Off by default. */
    private static final BooleanPref iOSInputField = new BooleanPref(false, "iOSInputField");
    private static final IntegerPref forwardSheetStyle = new IntegerPref(0, "forwardSheetStyle"); // exteraStatic: 0 = standard, 1 = iOS (Cherrygram)
    /** Cherrygram port: iOS-style chat header (centered title pill, avatar circle on the right). Off by default. */
    private static final BooleanPref iOSChatHeader = new BooleanPref(false, "iOSChatHeader");
    private static final BooleanPref removeMessageTail = new BooleanPref(true, "removeMessageTail");
    private static final BooleanPref replaceEditedWithIcon = new BooleanPref(true, "replaceEditedWithIcon");
    private static final BooleanPref showOnlineStatus = new BooleanPref(false, "showOnlineStatus");
    private static final BooleanPref showForwardsCount = new BooleanPref(false, "showForwardsCount");
    private static final BooleanPref hideShareButton = new BooleanPref(true, "hideShareButton");
    private static final BooleanPref showResultsBeforeVoting = new BooleanPref(false, "showResultsBeforeVoting");
    private static final BooleanPref showCopyPhotoButton = new BooleanPref(true, "showCopyPhotoButton");
    private static final BooleanPref showSaveMessageButton = new BooleanPref(false, "showSaveMessageButton");
    private static final BooleanPref showRepeatMessageButton = new BooleanPref(false, "showRepeatMessageButton");
    private static final BooleanPref showClearButton = new BooleanPref(true, "showClearButton");
    private static final BooleanPref showHistoryButton = new BooleanPref(false, "showHistoryButton");
    private static final BooleanPref showReportButton = new BooleanPref(true, "showReportButton");
    private static final BooleanPref showGenerateButton = new BooleanPref(true, "showGenerateButton");
    private static final BooleanPref showDetailsButton = new BooleanPref(false, "showDetailsButton");
    private static final BooleanPref groupMessageMenu = new BooleanPref(true, "groupMessageMenu");
    private static final StringPref recognitionLanguage = new StringPref("none", "recognitionLanguage");
    private static final BooleanPref postprocessingWithAi = new BooleanPref(false, "postprocessingWithAi");
    private static final EnumPref<CameraType> cameraType = new EnumPref<>(SharedConfig.getDevicePerformanceClass() == 2 ? CameraType.CAMERA_X : CameraType.CAMERA_1, "cameraType");
    private static final BooleanPref extendedFramesPerSecond = new BooleanPref(false, "extendedFramesPerSecond");
    private static final BooleanPref cameraStabilization = new BooleanPref(false, "cameraStabilization");
    private static final BooleanPref cameraMirrorMode = new BooleanPref(true, "cameraMirrorMode");
    private static final EnumPref<VideoMessagesCamera> videoMessagesCamera = new EnumPref<>(VideoMessagesCamera.FRONT, "videoMessagesCamera");
    private static final BooleanPref rememberLastUsedCamera = new BooleanPref(false, "rememberLastUsedCamera");
    private static final BooleanPref startWithWideAngleCamera = new BooleanPref(false, "startWithWideAngleCamera");
    private static final BooleanPref zoomSlider = new BooleanPref(true, "zoomSlider");
    private static final BooleanPref staticZoom = new BooleanPref(false, "staticZoom");
    private static final BooleanPref alwaysSendInHD = new BooleanPref(true, "alwaysSendInHD");
    private static final BooleanPref hideCameraTile = new BooleanPref(false, "hideCameraTile");
    private static final IntegerPref doubleTapSeekDuration = new IntegerPref(1, "doubleTapSeekDuration");
    private static final BooleanPref preferOriginalQuality = new BooleanPref(false, "preferOriginalQuality");
    private static final BooleanPref swipeToPip = new BooleanPref(false, "swipeToPip");
    private static final BooleanPref unmuteWithVolumeButtons = new BooleanPref(false, "unmuteWithVolumeButtons");
    private static final BooleanPref pauseOnMinimizeVideo = new BooleanPref(true, "pauseOnMinimizeVideo");
    private static final BooleanPref pauseOnMinimizeVoice = new BooleanPref(false, "pauseOnMinimizeVoice");
    private static final BooleanPref pauseOnMinimizeRound = new BooleanPref(false, "pauseOnMinimizeRound");
    private static final BooleanPref useGoogleCrashlytics = new BooleanPref(BuildVars.isBetaApp(), "useGoogleCrashlytics");
    private static final BooleanPref useGoogleAnalytics = new BooleanPref(false, "useGoogleAnalytics");
    private static final BooleanPref enableAdBlock = new BooleanPref(false, "enableAdBlock");
    private static final LongPref updateScheduleTimestamp = new LongPref(0L, "updateScheduleTimestamp");
    private static final LongPref sdkUpdateScheduleTimestamp = new LongPref(0L, "sdkUpdateScheduleTimestamp");
    private static final StringPref targetLang = new StringPref("app", "targetLang");
    private static final FloatPref flashWarmth = new FloatPref(0.5f, "flashWarmth");
    private static final FloatPref flashIntensity = new FloatPref(1.0f, "flashIntensity");
    private static final BooleanPref pluginsDevMode = new BooleanPref(false, "pluginsDevMode");
    private static final BooleanPref pluginsSafeModePref = new BooleanPref(false, "pluginsSafeMode");
    /** exteraGram API: plugins read ExteraConfig.pluginsSafeMode as a static field (ZwyLib). Kept in sync with the pref. */
    public static boolean pluginsSafeMode = pluginsSafeModePref.get();
    private static final BooleanPref pluginsCompactView = new BooleanPref(false, "pluginsCompactView");
    private static final BooleanPref pluginsPySdkAutoUpdate = new BooleanPref(false, "pluginsPySdkAutoUpdate");
    private static final BooleanPref pluginsPySdkBetaVersions = new BooleanPref(false, "pluginsPySdkBetaVersions");
    private static final BooleanPref pluginsDisableArtOpts = new BooleanPref(false, "pluginsDisableArtOpts");
    private static final BooleanPref pluginsUnknownSources = new BooleanPref(false, "pluginsUnknownSources");
    private static final StringSetPref pinnedPlugins = new StringSetPref(java.util.Collections.<String>emptySet(), "pinnedPlugins");
    private static final BooleanPref useSystemIconShape = new BooleanPref(true, "useSystemIconShape");

    private ExteraConfig() {
    }

    public static Gson getGSON() {
        return GSON;
    }

    public static SharedPreferences getPreferences() {
        return preferences;
    }

    public static SharedPreferences.Editor getEditor() {
        return editor;
    }

    public static PreferencesUtils.BackupItem[] getBackupKeys() {
        return PrefClassesKt.getRegisteredKeys().toArray(new PreferencesUtils.BackupItem[0]);
    }

    private static void ensureApiBotListener() {
        if (apiBotListener != null) {
            return;
        }
        try {
            RemoteUtils.initCached();
            SharedPreferences sharedPreferences = RemoteUtils.sharedPreferences;
            if (sharedPreferences == null) {
                return;
            }
            SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> {
                if ("extera_api_bot".equals(key)) {
                    currentApiBot = null;
                }
            };
            sharedPreferences.registerOnSharedPreferenceChangeListener(listener);
            apiBotListener = listener;
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static Pair<Long, String> getApiBotInfo() {
        Pair<Long, String> pair = currentApiBot;
        if (pair != null) {
            return pair;
        }
        synchronized (sync) {
            if (currentApiBot != null) {
                return currentApiBot;
            }
            ensureApiBotListener();
            String value = RemoteUtils.getStringConfigValue("extera_api_bot", "8083294286:exteraAuthBot");
            if (value != null) {
                try {
                    String[] parts = value.split(":", 2);
                    if (parts.length == 2) {
                        currentApiBot = new Pair<>(Long.parseLong(parts[0]), parts[1]);
                        return currentApiBot;
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            currentApiBot = new Pair<>(8083294286L, "exteraAuthBot");
            return currentApiBot;
        }
    }

    // ----- delegated preferences (generated from ExteraConfig.kt) -----

    public static int getTranslationProvider() {
        return translationProvider.get();
    }

    public static void setTranslationProvider(int value) {
        translationProvider.set(value);
    }

    public static boolean getDisableNumberRounding() {
        return disableNumberRounding.get();
    }

    public static void setDisableNumberRounding(boolean value) {
        disableNumberRounding.set(value);
    }

    public static boolean getFormatTimeWithSeconds() {
        return formatTimeWithSeconds.get();
    }

    public static void setFormatTimeWithSeconds(boolean value) {
        formatTimeWithSeconds.set(value);
    }

    public static boolean getRelativeLastSeen() {
        return relativeLastSeen.get();
    }

    public static void setRelativeLastSeen(boolean value) {
        relativeLastSeen.set(value);
    }

    public static boolean getInAppVibration() {
        return inAppVibration.get();
    }

    public static void setInAppVibration(boolean value) {
        inAppVibration.set(value);
    }

    public static boolean getDisableNotificationDelay() {
        return disableNotificationDelay.get();
    }

    public static void setDisableNotificationDelay(boolean value) {
        disableNotificationDelay.set(value);
    }

    public static boolean getFilterZalgo() {
        return filterZalgo.get();
    }

    public static void setFilterZalgo(boolean value) {
        filterZalgo.set(value);
    }

    public static int getDownloadSpeedBoost() {
        return downloadSpeedBoost.get();
    }

    public static void setDownloadSpeedBoost(int value) {
        downloadSpeedBoost.set(value);
    }

    public static boolean getUploadSpeedBoost() {
        return uploadSpeedBoost.get();
    }

    public static void setUploadSpeedBoost(boolean value) {
        uploadSpeedBoost.set(value);
    }

    public static boolean getHidePhoneNumber() {
        return hidePhoneNumber.get();
    }

    public static void setHidePhoneNumber(boolean value) {
        hidePhoneNumber.set(value);
    }

    public static int getShowIdAndDc() {
        return showIdAndDc.get();
    }

    public static void setShowIdAndDc(int value) {
        showIdAndDc.set(value);
    }

    public static boolean getHideArchiveFolder() {
        return hideArchiveFolder.get();
    }

    public static void setHideArchiveFolder(boolean value) {
        hideArchiveFolder.set(value);
    }

    public static boolean getArchiveOnPull() {
        return archiveOnPull.get();
    }

    public static void setArchiveOnPull(boolean value) {
        archiveOnPull.set(value);
    }

    public static boolean getDisableUnarchiveSwipe() {
        return disableUnarchiveSwipe.get();
    }

    public static void setDisableUnarchiveSwipe(boolean value) {
        disableUnarchiveSwipe.set(value);
    }

    public static int getDoNotUseProxy() {
        return doNotUseProxy.get();
    }

    public static void setDoNotUseProxy(int value) {
        doNotUseProxy.set(value);
    }

    public static String getCustomSavePath() {
        return customSavePath.get();
    }

    /** exteraGram: subfolder for saved media (Pictures/&lt;path&gt;, Downloads/&lt;path&gt;...), root when empty. */
    public static java.io.File getSaveDir(java.io.File base) {
        String path = getCustomSavePath();
        return android.text.TextUtils.isEmpty(path) ? base : new java.io.File(base, path);
    }

    public static java.io.File getSaveDir(String base) {
        return getSaveDir(new java.io.File(base));
    }

    public static void setCustomSavePath(String value) {
        customSavePath.set(value);
    }

    public static IconPackType getIconPack() {
        return iconPack.get();
    }

    public static void setIconPack(IconPackType value) {
        iconPack.set(value);
    }

    public static String getEditingIconPackId() {
        return editingIconPackId.get();
    }

    public static void setEditingIconPackId(String value) {
        editingIconPackId.set(value);
    }

    public static float getAvatarCorners() {
        return avatarCorners.get();
    }

    public static void setAvatarCorners(float value) {
        avatarCorners.set(value);
    }

    public static boolean getSingleCornerRadius() {
        return singleCornerRadius.get();
    }

    public static void setSingleCornerRadius(boolean value) {
        singleCornerRadius.set(value);
    }

    public static DividerStyle getDividerStyle() {
        return dividerStyle.get();
    }

    public static void setDividerStyle(DividerStyle value) {
        dividerStyle.set(value);
    }

    public static boolean getForceSnow() {
        return forceSnow.get();
    }

    public static void setForceSnow(boolean value) {
        forceSnow.set(value);
    }

    public static boolean getHideActionBarStatus() {
        return hideActionBarStatus.get();
    }

    public static void setHideActionBarStatus(boolean value) {
        hideActionBarStatus.set(value);
    }

    public static boolean getCenterTitle() {
        return centerTitle.get();
    }

    public static void setCenterTitle(boolean value) {
        centerTitle.set(value);
    }

    public static boolean getHideStories() {
        return hideStories.get();
    }

    public static void setHideStories(boolean value) {
        hideStories.set(value);
    }

    public static boolean getHideFloatingButton() {
        return hideFloatingButton.get();
    }

    public static void setHideFloatingButton(boolean value) {
        hideFloatingButton.set(value);
    }

    public static boolean getHideDialogsSearchBar() {
        return hideDialogsSearchBar.get();
    }

    public static void setHideDialogsSearchBar(boolean value) {
        hideDialogsSearchBar.set(value);
    }

    public static boolean getSenderMiniAvatars() {
        return senderMiniAvatars.get();
    }

    public static void setSenderMiniAvatars(boolean value) {
        senderMiniAvatars.set(value);
    }

    public static int getTitleText() {
        return titleText.get();
    }

    public static void setTitleText(int value) {
        titleText.set(value);
    }

    public static TabIconsMode getTabIcons() {
        return tabIcons.get();
    }

    public static void setTabIcons(TabIconsMode value) {
        tabIcons.set(value);
    }

    public static TabCounterMode getTabCounterMode() {
        return tabCounterMode.get();
    }

    public static void setTabCounterMode(TabCounterMode value) {
        tabCounterMode.set(value);
    }

    public static boolean getHideAllChats() {
        return hideAllChats.get();
    }

    public static void setHideAllChats(boolean value) {
        hideAllChats.set(value);
    }

    public static boolean getSquareFab() {
        return squareFab.get();
    }

    public static void setSquareFab(boolean value) {
        squareFab.set(value);
    }

    public static float getSectionRadius() {
        return sectionRadius.get();
    }

    public static void setSectionRadius(float value) {
        sectionRadius.set(value);
    }

    private static boolean getSectionsSeparatedHeadersPreference() {
        return sectionsSeparatedHeadersPreference.get();
    }

    private static void setSectionsSeparatedHeadersPreference(boolean value) {
        sectionsSeparatedHeadersPreference.set(value);
    }

    public static boolean getNewLoadingStyle() {
        return newLoadingStyle.get();
    }

    public static void setNewLoadingStyle(boolean value) {
        newLoadingStyle.set(value);
    }

    public static boolean getNewSliderStyle() {
        return newSliderStyle.get();
    }

    public static void setNewSliderStyle(boolean value) {
        newSliderStyle.set(value);
    }

    public static boolean getNewSwitchStyle() {
        return newSwitchStyle.get();
    }

    public static void setNewSwitchStyle(boolean value) {
        newSwitchStyle.set(value);
    }

    public static boolean getNewChatHeaderStyle() {
        return newChatHeaderStyle.get();
    }

    public static void setNewChatHeaderStyle(boolean value) {
        newChatHeaderStyle.set(value);
    }

    public static boolean getNewNavigationBarStyle() {
        return newNavigationBarStyle.get();
    }

    public static void setNewNavigationBarStyle(boolean value) {
        newNavigationBarStyle.set(value);
    }

    public static boolean getNewFabStyle() {
        return newFabStyle.get();
    }

    public static void setNewFabStyle(boolean value) {
        newFabStyle.set(value);
    }

    public static int getTabletMode() {
        return tabletMode.get();
    }

    public static void setTabletMode(int value) {
        tabletMode.set(value);
    }

    public static boolean getUseSystemFonts() {
        return useSystemFonts.get();
    }

    public static void setUseSystemFonts(boolean value) {
        useSystemFonts.set(value);
    }

    public static boolean getGooeyAvatarAnimation() {
        return gooeyAvatarAnimation.get();
    }

    public static void setGooeyAvatarAnimation(boolean value) {
        gooeyAvatarAnimation.set(value);
    }

    public static boolean getCustomThemes() {
        return customThemes.get();
    }

    public static void setCustomThemes(boolean value) {
        customThemes.set(value);
    }

    public static float getPredictiveBackIntensity() {
        return predictiveBackIntensity.get();
    }

    public static void setPredictiveBackIntensity(float value) {
        predictiveBackIntensity.set(value);
    }

    public static TransitionAnimation getTransitionAnimation() {
        return transitionAnimation.get();
    }

    public static void setTransitionAnimation(TransitionAnimation value) {
        transitionAnimation.set(value);
    }

    public static GlassOutlineStyle getGlassOutlineStyle() {
        return glassOutlineStyle.get();
    }

    public static void setGlassOutlineStyle(GlassOutlineStyle value) {
        glassOutlineStyle.set(value);
    }

    public static boolean getGlassMessageMenu() {
        return glassMessageMenu.get();
    }

    public static void setGlassMessageMenu(boolean value) {
        glassMessageMenu.set(value);
    }

    public static boolean getForceBlur() {
        return forceBlur.get();
    }

    public static void setForceBlur(boolean value) {
        forceBlur.set(value);
    }

    public static int getEventType() {
        return eventType.get();
    }

    public static void setEventType(int value) {
        eventType.set(value);
    }

    public static boolean getNavigationDrawer() {
        return navigationDrawer.get();
    }

    public static void setNavigationDrawer(boolean value) {
        navigationDrawer.set(value);
    }

    public static boolean getImmersiveDrawerAnimation() {
        return immersiveDrawerAnimation.get();
    }

    public static boolean getTelegramPredictiveBack() {
        return telegramPredictiveBack.get();
    }

    public static void setTelegramPredictiveBack(boolean value) {
        telegramPredictiveBack.set(value);
    }

    public static boolean getBackScrim() {
        return backScrim.get();
    }

    public static void setBackScrim(boolean value) {
        backScrim.set(value);
    }

    public static void setImmersiveDrawerAnimation(boolean value) {
        immersiveDrawerAnimation.set(value);
    }

    public static boolean getShowFeedTab() {
        return showFeedTab.get();
    }

    public static void setShowFeedTab(boolean value) {
        showFeedTab.set(value);
    }

    public static boolean getShowFeedUnreadCounter() {
        return showFeedUnreadCounter.get();
    }

    public static void setShowFeedUnreadCounter(boolean value) {
        showFeedUnreadCounter.set(value);
    }

    public static float getStickerSize() {
        return stickerSize.get();
    }

    public static void setStickerSize(float value) {
        stickerSize.set(value);
    }

    public static StickerTimeMode getStickerTimeMode() {
        return stickerTimeMode.get();
    }

    public static void setStickerTimeMode(StickerTimeMode value) {
        stickerTimeMode.set(value);
    }

    public static boolean getReplyColors() {
        return replyColors.get();
    }

    public static void setReplyColors(boolean value) {
        replyColors.set(value);
    }

    public static boolean getReplyEmoji() {
        return replyEmoji.get();
    }

    public static void setReplyEmoji(boolean value) {
        replyEmoji.set(value);
    }

    public static boolean getReplyBackground() {
        return replyBackground.get();
    }

    public static void setReplyBackground(boolean value) {
        replyBackground.set(value);
    }

    public static int getStickerShape() {
        return stickerShape.get();
    }

    public static void setStickerShape(int value) {
        stickerShape.set(value);
    }

    public static boolean getUnlimitedRecentStickers() {
        return unlimitedRecentStickers.get();
    }

    public static void setUnlimitedRecentStickers(boolean value) {
        unlimitedRecentStickers.set(value);
    }

    public static boolean getHideReactionsInPrivateChats() {
        return hideReactionsInPrivateChats.get();
    }

    public static void setHideReactionsInPrivateChats(boolean value) {
        hideReactionsInPrivateChats.set(value);
    }

    public static boolean getHideReactionsInChannels() {
        return hideReactionsInChannels.get();
    }

    public static void setHideReactionsInChannels(boolean value) {
        hideReactionsInChannels.set(value);
    }

    public static boolean getHideReactionsInGroups() {
        return hideReactionsInGroups.get();
    }

    public static void setHideReactionsInGroups(boolean value) {
        hideReactionsInGroups.set(value);
    }

    public static int getDoubleTapAction() {
        return doubleTapAction.get();
    }

    public static void setDoubleTapAction(int value) {
        doubleTapAction.set(value);
    }

    public static int getDoubleTapActionOutOwner() {
        return doubleTapActionOutOwner.get();
    }

    public static void setDoubleTapActionOutOwner(int value) {
        doubleTapActionOutOwner.set(value);
    }

    public static String getSwipeActions() {
        return swipeActions.get();
    }

    public static void setSwipeActions(String value) {
        swipeActions.set(value);
    }

    public static boolean getSwipeActionsLoop() {
        return swipeActionsLoop.get();
    }

    public static void setSwipeActionsLoop(boolean value) {
        swipeActionsLoop.set(value);
    }

    public static boolean getSwipeActionsReversed() {
        return swipeActionsReversed.get();
    }

    public static void setSwipeActionsReversed(boolean value) {
        swipeActionsReversed.set(value);
    }

    public static int getBottomButton() {
        return bottomButton.get();
    }

    public static void setBottomButton(int value) {
        bottomButton.set(value);
    }

    public static boolean getWidePostsInFeed() {
        return widePostsInFeed.get();
    }

    public static void setWidePostsInFeed(boolean value) {
        widePostsInFeed.set(value);
    }

    public static boolean getWidePostsInChannels() {
        return widePostsInChannels.get();
    }

    public static void setWidePostsInChannels(boolean value) {
        widePostsInChannels.set(value);
    }

    public static boolean getTelegramAiEditor() {
        return telegramAiEditor.get();
    }

    public static void setTelegramAiEditor(boolean value) {
        telegramAiEditor.set(value);
    }

    public static boolean getTelegramAiSummaries() {
        return telegramAiSummaries.get();
    }

    public static void setTelegramAiSummaries(boolean value) {
        telegramAiSummaries.set(value);
    }

    public static boolean getTelegramAiInstantViewSummaries() {
        return telegramAiInstantViewSummaries.get();
    }

    public static void setTelegramAiInstantViewSummaries(boolean value) {
        telegramAiInstantViewSummaries.set(value);
    }

    public static boolean getQuickAdminShortcuts() {
        return quickAdminShortcuts.get();
    }

    public static void setQuickAdminShortcuts(boolean value) {
        quickAdminShortcuts.set(value);
    }

    public static boolean getQuickTransitionForChannels() {
        return quickTransitionForChannels.get();
    }

    public static void setQuickTransitionForChannels(boolean value) {
        quickTransitionForChannels.set(value);
    }

    public static boolean getQuickTransitionForTopics() {
        return quickTransitionForTopics.get();
    }

    public static void setQuickTransitionForTopics(boolean value) {
        quickTransitionForTopics.set(value);
    }

    public static boolean getDisableGreetingSticker() {
        return disableGreetingSticker.get();
    }

    public static void setDisableGreetingSticker(boolean value) {
        disableGreetingSticker.set(value);
    }

    public static boolean getHideKeyboardOnScroll() {
        return hideKeyboardOnScroll.get();
    }

    public static void setHideKeyboardOnScroll(boolean value) {
        hideKeyboardOnScroll.set(value);
    }

    public static boolean getAddCommaAfterMention() {
        return addCommaAfterMention.get();
    }

    public static void setAddCommaAfterMention(boolean value) {
        addCommaAfterMention.set(value);
    }

    public static boolean getInlineMathResult() {
        return inlineMathResult.get();
    }

    public static void setInlineMathResult(boolean value) {
        inlineMathResult.set(value);
    }

    public static boolean getDisableMarkdown() {
        return disableMarkdown.get();
    }

    public static void setDisableMarkdown(boolean value) {
        disableMarkdown.set(value);
    }

    public static boolean getIOSChatHeader() {
        return iOSChatHeader.get();
    }

    public static void setIOSChatHeader(boolean value) {
        iOSChatHeader.set(value);
    }

    public static boolean getIOSInputField() {
        return iOSInputField.get();
    }

    public static void setIOSInputField(boolean value) {
        iOSInputField.set(value);
    }

    public static final int FORWARD_SHEET_STANDARD = 0;
    public static final int FORWARD_SHEET_IOS = 1;

    public static int getForwardSheetStyle() {
        return forwardSheetStyle.get();
    }

    public static void setForwardSheetStyle(int value) {
        forwardSheetStyle.set(value);
    }

    public static boolean isForwardSheetIOS() {
        return forwardSheetStyle.get() == FORWARD_SHEET_IOS;
    }

    public static boolean getHideSendAsPeer() {
        return hideSendAsPeer.get();
    }

    public static void setHideSendAsPeer(boolean value) {
        hideSendAsPeer.set(value);
    }

    public static boolean getRemoveMessageTail() {
        return removeMessageTail.get();
    }

    public static void setRemoveMessageTail(boolean value) {
        removeMessageTail.set(value);
    }

    public static boolean getReplaceEditedWithIcon() {
        return replaceEditedWithIcon.get();
    }

    public static void setReplaceEditedWithIcon(boolean value) {
        replaceEditedWithIcon.set(value);
    }

    public static boolean getShowOnlineStatus() {
        return showOnlineStatus.get();
    }

    public static void setShowOnlineStatus(boolean value) {
        showOnlineStatus.set(value);
    }

    public static boolean getShowForwardsCount() {
        return showForwardsCount.get();
    }

    public static void setShowForwardsCount(boolean value) {
        showForwardsCount.set(value);
    }

    public static boolean getHideShareButton() {
        return hideShareButton.get();
    }

    public static void setHideShareButton(boolean value) {
        hideShareButton.set(value);
    }

    public static boolean getShowResultsBeforeVoting() {
        return showResultsBeforeVoting.get();
    }

    public static void setShowResultsBeforeVoting(boolean value) {
        showResultsBeforeVoting.set(value);
    }

    public static boolean getShowCopyPhotoButton() {
        return showCopyPhotoButton.get();
    }

    public static void setShowCopyPhotoButton(boolean value) {
        showCopyPhotoButton.set(value);
    }

    public static boolean getShowSaveMessageButton() {
        return showSaveMessageButton.get();
    }

    public static void setShowSaveMessageButton(boolean value) {
        showSaveMessageButton.set(value);
    }

    public static boolean getShowRepeatMessageButton() {
        return showRepeatMessageButton.get();
    }

    public static void setShowRepeatMessageButton(boolean value) {
        showRepeatMessageButton.set(value);
    }

    public static boolean getShowClearButton() {
        return showClearButton.get();
    }

    public static void setShowClearButton(boolean value) {
        showClearButton.set(value);
    }

    public static boolean getShowHistoryButton() {
        return showHistoryButton.get();
    }

    public static void setShowHistoryButton(boolean value) {
        showHistoryButton.set(value);
    }

    public static boolean getShowReportButton() {
        return showReportButton.get();
    }

    public static void setShowReportButton(boolean value) {
        showReportButton.set(value);
    }

    public static boolean getShowGenerateButton() {
        return showGenerateButton.get();
    }

    public static void setShowGenerateButton(boolean value) {
        showGenerateButton.set(value);
    }

    public static boolean getShowDetailsButton() {
        return showDetailsButton.get();
    }

    public static void setShowDetailsButton(boolean value) {
        showDetailsButton.set(value);
    }

    public static boolean getGroupMessageMenu() {
        return groupMessageMenu.get();
    }

    public static void setGroupMessageMenu(boolean value) {
        groupMessageMenu.set(value);
    }

    public static String getRecognitionLanguage() {
        return recognitionLanguage.get();
    }

    public static void setRecognitionLanguage(String value) {
        recognitionLanguage.set(value);
    }

    public static boolean getPostprocessingWithAi() {
        return postprocessingWithAi.get();
    }

    public static void setPostprocessingWithAi(boolean value) {
        postprocessingWithAi.set(value);
    }

    public static CameraType getCameraType() {
        return cameraType.get();
    }

    public static void setCameraType(CameraType value) {
        cameraType.set(value);
    }

    public static boolean getExtendedFramesPerSecond() {
        return extendedFramesPerSecond.get();
    }

    public static void setExtendedFramesPerSecond(boolean value) {
        extendedFramesPerSecond.set(value);
    }

    public static boolean getCameraStabilization() {
        return cameraStabilization.get();
    }

    public static void setCameraStabilization(boolean value) {
        cameraStabilization.set(value);
    }

    public static boolean getCameraMirrorMode() {
        return cameraMirrorMode.get();
    }

    public static void setCameraMirrorMode(boolean value) {
        cameraMirrorMode.set(value);
    }

    public static VideoMessagesCamera getVideoMessagesCamera() {
        return videoMessagesCamera.get();
    }

    public static void setVideoMessagesCamera(VideoMessagesCamera value) {
        videoMessagesCamera.set(value);
    }

    public static boolean getRememberLastUsedCamera() {
        return rememberLastUsedCamera.get();
    }

    public static void setRememberLastUsedCamera(boolean value) {
        rememberLastUsedCamera.set(value);
    }

    public static boolean getStartWithWideAngleCamera() {
        return startWithWideAngleCamera.get();
    }

    public static void setStartWithWideAngleCamera(boolean value) {
        startWithWideAngleCamera.set(value);
    }

    public static boolean getZoomSlider() {
        return zoomSlider.get();
    }

    public static void setZoomSlider(boolean value) {
        zoomSlider.set(value);
    }

    public static boolean getStaticZoom() {
        return staticZoom.get();
    }

    public static void setStaticZoom(boolean value) {
        staticZoom.set(value);
    }

    public static boolean getAlwaysSendInHD() {
        return alwaysSendInHD.get();
    }

    public static void setAlwaysSendInHD(boolean value) {
        alwaysSendInHD.set(value);
    }

    public static boolean getHideCameraTile() {
        return hideCameraTile.get();
    }

    public static void setHideCameraTile(boolean value) {
        hideCameraTile.set(value);
    }

    public static int getDoubleTapSeekDuration() {
        return doubleTapSeekDuration.get();
    }

    public static void setDoubleTapSeekDuration(int value) {
        doubleTapSeekDuration.set(value);
    }

    public static boolean getPreferOriginalQuality() {
        return preferOriginalQuality.get();
    }

    public static void setPreferOriginalQuality(boolean value) {
        preferOriginalQuality.set(value);
    }

    public static boolean getSwipeToPip() {
        return swipeToPip.get();
    }

    public static void setSwipeToPip(boolean value) {
        swipeToPip.set(value);
    }

    public static boolean getUnmuteWithVolumeButtons() {
        return unmuteWithVolumeButtons.get();
    }

    public static void setUnmuteWithVolumeButtons(boolean value) {
        unmuteWithVolumeButtons.set(value);
    }

    public static boolean getPauseOnMinimizeVideo() {
        return pauseOnMinimizeVideo.get();
    }

    public static void setPauseOnMinimizeVideo(boolean value) {
        pauseOnMinimizeVideo.set(value);
    }

    public static boolean getPauseOnMinimizeVoice() {
        return pauseOnMinimizeVoice.get();
    }

    public static void setPauseOnMinimizeVoice(boolean value) {
        pauseOnMinimizeVoice.set(value);
    }

    public static boolean getPauseOnMinimizeRound() {
        return pauseOnMinimizeRound.get();
    }

    public static void setPauseOnMinimizeRound(boolean value) {
        pauseOnMinimizeRound.set(value);
    }

    public static boolean getUseGoogleCrashlytics() {
        return useGoogleCrashlytics.get();
    }

    public static void setUseGoogleCrashlytics(boolean value) {
        useGoogleCrashlytics.set(value);
    }

    public static boolean getUseGoogleAnalytics() {
        return useGoogleAnalytics.get();
    }

    public static void setUseGoogleAnalytics(boolean value) {
        useGoogleAnalytics.set(value);
    }

    public static boolean getEnableAdBlock() {
        return enableAdBlock.get();
    }

    public static void setEnableAdBlock(boolean value) {
        enableAdBlock.set(value);
    }

    public static long getUpdateScheduleTimestamp() {
        return updateScheduleTimestamp.get();
    }

    public static void setUpdateScheduleTimestamp(long value) {
        updateScheduleTimestamp.set(value);
    }

    public static long getSdkUpdateScheduleTimestamp() {
        return sdkUpdateScheduleTimestamp.get();
    }

    public static void setSdkUpdateScheduleTimestamp(long value) {
        sdkUpdateScheduleTimestamp.set(value);
    }

    public static String getTargetLang() {
        return targetLang.get();
    }

    public static void setTargetLang(String value) {
        targetLang.set(value);
    }

    public static float getFlashWarmth() {
        return flashWarmth.get();
    }

    public static void setFlashWarmth(float value) {
        flashWarmth.set(value);
    }

    public static float getFlashIntensity() {
        return flashIntensity.get();
    }

    public static void setFlashIntensity(float value) {
        flashIntensity.set(value);
    }

    public static boolean getPluginsDevMode() {
        return pluginsDevMode.get();
    }

    public static void setPluginsDevMode(boolean value) {
        pluginsDevMode.set(value);
    }

    public static boolean getPluginsSafeMode() {
        return pluginsSafeMode = pluginsSafeModePref.get();
    }

    public static void setPluginsSafeMode(boolean value) {
        pluginsSafeModePref.set(value);
        pluginsSafeMode = value;
    }

    public static boolean getPluginsCompactView() {
        return pluginsCompactView.get();
    }

    public static void setPluginsCompactView(boolean value) {
        pluginsCompactView.set(value);
    }

    public static boolean getPluginsPySdkAutoUpdate() {
        return pluginsPySdkAutoUpdate.get();
    }

    public static void setPluginsPySdkAutoUpdate(boolean value) {
        pluginsPySdkAutoUpdate.set(value);
    }

    public static boolean getPluginsPySdkBetaVersions() {
        return pluginsPySdkBetaVersions.get();
    }

    public static void setPluginsPySdkBetaVersions(boolean value) {
        pluginsPySdkBetaVersions.set(value);
    }

    public static boolean getPluginsDisableArtOpts() {
        return pluginsDisableArtOpts.get();
    }

    public static void setPluginsDisableArtOpts(boolean value) {
        pluginsDisableArtOpts.set(value);
    }

    public static boolean getPluginsUnknownSources() {
        return pluginsUnknownSources.get();
    }

    public static void setPluginsUnknownSources(boolean value) {
        pluginsUnknownSources.set(value);
    }

    public static Set<String> getPinnedPlugins() {
        return pinnedPlugins.get();
    }

    public static void setPinnedPlugins(Set<String> value) {
        pinnedPlugins.set(value);
    }

    public static boolean getUseSystemIconShape() {
        return useSystemIconShape.get();
    }

    public static void setUseSystemIconShape(boolean value) {
        useSystemIconShape.set(value);
    }

    // ----- derived / non-delegated values -----

    public static boolean isProxyDisabledOn(ProxyDisableCondition condition) {
        return (condition.getFlag() & getDoNotUseProxy()) != 0;
    }

    public static void setProxyDisabledOn(ProxyDisableCondition condition, boolean disabled) {
        setDoNotUseProxy(disabled ? (condition.getFlag() | getDoNotUseProxy()) : (~condition.getFlag() & getDoNotUseProxy()));
    }

    public static ArrayList<String> getDoNotMarkAsNew() {
        return doNotMarkAsNew;
    }

    public static HashMap<String, Long> getNewFeaturesShowedAt() {
        return newFeaturesShowedAt;
    }

    public static ArrayList<String> getIconPacksLayout() {
        return iconPacksLayout;
    }

    public static ArrayList<String> getIconPacksHidden() {
        return iconPacksHidden;
    }

    public static boolean getSectionsSeparatedHeaders() {
        return getDividerStyle() == DividerStyle.SEGMENTS || getSectionsSeparatedHeadersPreference();
    }

    public static void setSectionsSeparatedHeaders(boolean value) {
        if (getDividerStyle() == DividerStyle.SEGMENTS) {
            value = true;
        }
        setSectionsSeparatedHeadersPreference(value);
    }

    public static boolean getSpringAnimations() {
        return getTransitionAnimation() == TransitionAnimation.SPRING;
    }

    public static boolean getAospTransitions() {
        return getTransitionAnimation() == TransitionAnimation.AOSP;
    }

    public static boolean getSpringSwipeback() {
        return getTransitionAnimation() != TransitionAnimation.DEFAULT;
    }

    public static ArrayList<Integer> getMainMenuLayout() {
        return mainMenuLayout;
    }

    public static ArrayList<Integer> getMainMenuHiddenItems() {
        return mainMenuHiddenItems;
    }

    public static SearchEngine getYandexSearchEngine() {
        return yandexSearchEngine;
    }

    public static boolean getPluginsEngine() {
        return pluginsEngine;
    }

    public static void setPluginsEngine(boolean value) {
        pluginsEngine = value;
    }

    // ----- lifecycle -----

    public static void init() {
        loadConfig();
        if (servicesStarted.compareAndSet(false, true)) {
            // Services (ApiController, RemoteUtils, PluginsController, AdBlockManager, IconManager)
            // are started by the stages that port them; see EXTERA_PORT.md.
            ExteraServices.start();
        }
    }

    private static void migrateProxyConditions() {
        if (preferences.contains("doNotUseProxyWithVpn")) {
            if (preferences.getBoolean("doNotUseProxyWithVpn", false)) {
                setProxyDisabledOn(ProxyDisableCondition.VPN, true);
            }
            editor.remove("doNotUseProxyWithVpn").apply();
        }
    }

    private static void migrateTelegramAiInstantViewSummaries() {
        if (preferences.contains("telegramAiInstantViewSummaries")) {
            return;
        }
        setTelegramAiInstantViewSummaries(getTelegramAiSummaries());
    }

    private static void migrateTransitionAnimation() {
        if (preferences.contains("springAnimations") || preferences.contains("aospTransitions")) {
            TransitionAnimation animation;
            if (preferences.getBoolean("aospTransitions", false)) {
                animation = TransitionAnimation.AOSP;
            } else {
                animation = preferences.getBoolean("springAnimations", true) ? TransitionAnimation.SPRING : TransitionAnimation.DEFAULT;
            }
            setTransitionAnimation(animation);
            editor.remove("springAnimations").remove("aospTransitions").apply();
        }
    }

    private static void migrateStickerTimeMode() {
        if (preferences.contains("hideStickerTime")) {
            if (preferences.getBoolean("hideStickerTime", false)) {
                setStickerTimeMode(StickerTimeMode.HIDDEN);
            }
            editor.remove("hideStickerTime").apply();
        }
    }

    private static void migrateTabCounterMode() {
        if (preferences.contains("tabCounter")) {
            if (!preferences.getBoolean("tabCounter", true)) {
                setTabCounterMode(TabCounterMode.HIDDEN);
            }
            editor.remove("tabCounter").apply();
        }
    }

    public static void loadConfig() {
        synchronized (sync) {
            if (configLoaded) {
                return;
            }
            BottomNavigationBar.setMode(preferences.getInt("bottomNavigationBarMode", 0));

            String layoutJson = preferences.getString("iconPacksLayout", null);
            String hiddenJson = preferences.getString("iconPacksHidden", null);
            String[] basePacks = {"base.default", "base.solar", "base.remix"};
            if (layoutJson != null) {
                iconPacksLayout = GSON.fromJson(layoutJson, new TypeToken<ArrayList<String>>() {}.getType());
                iconPacksHidden = hiddenJson != null ? GSON.fromJson(hiddenJson, new TypeToken<ArrayList<String>>() {}.getType()) : new ArrayList<>();
                if (iconPacksLayout == null) {
                    iconPacksLayout = new ArrayList<>();
                }
                if (iconPacksHidden == null) {
                    iconPacksHidden = new ArrayList<>();
                }
                boolean changed = false;
                String base = null;
                Iterator<String> it = iconPacksLayout.iterator();
                while (it.hasNext()) {
                    String next = it.next();
                    if (next.startsWith("base.")) {
                        if (base == null) {
                            base = next;
                        } else {
                            it.remove();
                            changed = true;
                        }
                    }
                }
                if (base == null) {
                    iconPacksLayout.add("base.default");
                    changed = true;
                }
                if (changed) {
                    saveIconPacksLayout();
                }
            } else {
                iconPacksLayout = new ArrayList<>();
                iconPacksHidden = new ArrayList<>();
                for (int i = 0; i < basePacks.length; i++) {
                    (i == getIconPack().ordinal() ? iconPacksLayout : iconPacksHidden).add(basePacks[i]);
                }
                saveIconPacksLayout();
            }
            boolean addedBase = false;
            for (String pack : basePacks) {
                if (!iconPacksLayout.contains(pack) && !iconPacksHidden.contains(pack)) {
                    iconPacksHidden.add(pack);
                    addedBase = true;
                }
            }
            if (addedBase) {
                saveIconPacksLayout();
            }

            ArrayList<String> notNew = null;
            String notNewJson = preferences.getString("doNotMarkAsNew", null);
            if (notNewJson != null) {
                notNew = GSON.fromJson(notNewJson, new TypeToken<ArrayList<String>>() {}.getType());
            }
            doNotMarkAsNew = notNew != null ? notNew : new ArrayList<>();

            HashMap<String, Long> showedAt = null;
            String showedAtJson = preferences.getString("newFeaturesShowedAt", null);
            if (showedAtJson != null) {
                showedAt = GSON.fromJson(showedAtJson, new TypeToken<HashMap<String, Long>>() {}.getType());
            }
            newFeaturesShowedAt = showedAt != null ? showedAt : new HashMap<>();

            String menuJson = preferences.getString("mainMenuLayout", null);
            String menuHiddenJson = preferences.getString("mainMenuHiddenItems", null);
            if (menuJson != null) {
                mainMenuLayout = GSON.fromJson(menuJson, new TypeToken<ArrayList<Integer>>() {}.getType());
                mainMenuHiddenItems = menuHiddenJson != null ? GSON.fromJson(menuHiddenJson, new TypeToken<ArrayList<Integer>>() {}.getType()) : new ArrayList<>();
                if (mainMenuLayout == null) {
                    mainMenuLayout = new ArrayList<>();
                }
                if (mainMenuHiddenItems == null) {
                    mainMenuHiddenItems = new ArrayList<>();
                }
            } else {
                mainMenuLayout = new ArrayList<>();
                mainMenuHiddenItems = new ArrayList<>();
                mainMenuLayout.addAll(getDefaultMainMenuLayout());
                for (MainMenuItem item : MainMenuItem.values()) {
                    if (item != MainMenuItem.DIVIDER && !mainMenuLayout.contains(item.getId()) && (item != MainMenuItem.PLUGINS || PluginsController.isPluginEngineSupported())) {
                        mainMenuHiddenItems.add(item.getId());
                    }
                }
                saveMainMenuLayout();
            }
            if (!PluginsController.isPluginEngineSupported()) {
                pluginsEngine = false;
                if (preferences.getBoolean("pluginsEngine", false)) {
                    editor.putBoolean("pluginsEngine", false).apply();
                }
                Integer id = MainMenuItem.PLUGINS.getId();
                mainMenuLayout.remove(id);
                mainMenuHiddenItems.remove(id);
            } else {
                pluginsEngine = preferences.getBoolean("pluginsEngine", false);
                Integer id = MainMenuItem.PLUGINS.getId();
                if (!mainMenuLayout.contains(id) && !mainMenuHiddenItems.contains(id)) {
                    mainMenuHiddenItems.add(id);
                }
            }
            Integer feedId = MainMenuItem.FEED.getId();
            if (!mainMenuLayout.contains(feedId) && !mainMenuHiddenItems.contains(feedId)) {
                mainMenuLayout.add(feedId);
                saveMainMenuLayout();
            }
            mainMenuLayout.removeAll(new HashSet<>(mainMenuHiddenItems));
            ensureSettingsVisibility();
            sanitizeMenu();
            migrateProxyConditions();
            migrateTelegramAiInstantViewSummaries();
            migrateStickerTimeMode();
            migrateTransitionAnimation();
            migrateTabCounterMode();
            TranslatorUtils.ensureTargetLanguageCompatibleWithProvider();
            configLoaded = true;
        }
    }

    public static void reloadConfig() {
        synchronized (sync) {
            configLoaded = false;
            for (BasePref<?> pref : PrefClassesKt.getAllDelegates()) {
                pref.invalidate();
            }
            loadConfig();
        }
    }

    public static void saveMainMenuLayout() {
        editor.putString("mainMenuLayout", GSON.toJson(mainMenuLayout)).putString("mainMenuHiddenItems", GSON.toJson(mainMenuHiddenItems)).apply();
    }

    public static void saveIconPacksLayout() {
        String base = null;
        for (String pack : iconPacksLayout) {
            if (pack.startsWith("base.")) {
                base = pack;
                break;
            }
        }
        IconPackType type;
        if ("base.solar".equals(base)) {
            type = IconPackType.SOLAR;
        } else if ("base.remix".equals(base)) {
            type = IconPackType.REMIX;
        } else {
            type = IconPackType.DEFAULT;
        }
        setIconPack(type);
        editor.putString("iconPacksLayout", GSON.toJson(iconPacksLayout)).putString("iconPacksHidden", GSON.toJson(iconPacksHidden)).apply();
    }

    public static ArrayList<Integer> getDefaultMainMenuLayout() {
        ArrayList<Integer> list = new ArrayList<>();
        list.add(MainMenuItem.ARCHIVE.getId());
        if (BottomNavigationBar.hidden()) {
            list.add(MainMenuItem.PROFILE.getId());
        }
        list.add(MainMenuItem.NEW_GROUP.getId());
        if (BottomNavigationBar.hidden()) {
            list.add(MainMenuItem.CONTACTS.getId());
        }
        list.add(MainMenuItem.SAVED.getId());
        list.add(MainMenuItem.FEED.getId());
        list.add(MainMenuItem.BOTS.getId());
        if (BottomNavigationBar.hidden()) {
            list.add(MainMenuItem.SETTINGS.getId());
        }
        return list;
    }

    public static void ensureSettingsVisibility() {
        Integer id = MainMenuItem.SETTINGS.getId();
        if (BottomNavigationBar.hidden() && !mainMenuLayout.contains(id)) {
            mainMenuHiddenItems.remove(id);
            mainMenuLayout.add(id);
            saveMainMenuLayout();
        }
    }

    private static boolean isUnknownMenuItem(Integer id) {
        return id != MainMenuItem.DIVIDER.getId() && MainMenuItem.getById(id) == null;
    }

    public static void sanitizeMenu() {
        boolean changed = mainMenuLayout.removeIf(ExteraConfig::isUnknownMenuItem);
        changed |= mainMenuHiddenItems.removeIf(ExteraConfig::isUnknownMenuItem);
        for (MainMenuItem item : MainMenuItem.values()) {
            if (item == MainMenuItem.DIVIDER || (item == MainMenuItem.PLUGINS && !PluginsController.isPluginEngineSupported())) {
                continue;
            }
            if (!mainMenuLayout.contains(item.getId()) && !mainMenuHiddenItems.contains(item.getId())) {
                mainMenuHiddenItems.add(item.getId());
                changed = true;
            }
        }
        if (changed) {
            saveMainMenuLayout();
        }
    }

    // ----- avatar corners -----

    public static int getAvatarCorners(float size) {
        return getAvatarCorners(size, false, false);
    }

    public static int getAvatarCorners(float size, boolean px) {
        return getAvatarCorners(size, px, false);
    }

    public static int getAvatarCorners(float size, boolean px, boolean forum) {
        return getAvatarCorners(size, px, forum ? AvatarCornerType.FORUM : AvatarCornerType.DEFAULT);
    }

    public static int getAvatarCorners(float size, boolean px, AvatarCornerType type) {
        if (getAvatarCorners() == 0.0f) {
            return 0;
        }
        float radius = (getAvatarCorners() * size) / 56.0f;
        if (!px) {
            radius = AndroidUtilities.dp(radius);
        }
        if (!getSingleCornerRadius()) {
            switch (type) {
                case DEFAULT:
                    break;
                case FORUM:
                    radius = (((int) radius) * 42) >> 6;
                    break;
                case COMMUNITY:
                    radius = (radius * 40.0f) / 72.0f;
                    break;
            }
        }
        return Math.max((int) Math.ceil(radius), 0);
    }

    public static float getAvatarSquareness() {
        return Math.max(0.0f, Math.min(1.0f, 1.0f - (getAvatarCorners() / 28.0f)));
    }

    public static int getOnlineDotOuterRadius() {
        return AndroidUtilities.dp((getAvatarSquareness() * 2.0f) + 7.0f);
    }

    public static int getOnlineDotInnerRadius() {
        return AndroidUtilities.dp(getAvatarSquareness() + 5.0f);
    }

    public static float getOnlineDotOffset(float circleOffset, float squareOffset) {
        return circleOffset + ((((float) (squareOffset / Math.sqrt(2.0d))) - circleOffset) * getAvatarSquareness());
    }

    public static int getSectionRadiusDp() {
        return Math.round(sanitizeSectionRadius(getSectionRadius()));
    }

    private static float sanitizeSectionRadius(float value) {
        if (Math.abs(value) <= Float.MAX_VALUE) {
            return Math.max(0.0f, Math.min(28.0f, value));
        }
        return 20.0f;
    }

    // ----- logging -----

    private static SharedPreferences systemConfigPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("systemConfig", 0);
    }

    public static void toggleLogging() {
        setLogging(!BuildVars.LOGS_ENABLED);
    }

    public static boolean getLogging() {
        return systemConfigPrefs().getBoolean("logsEnabled", false);
    }

    public static void setLogging(boolean enabled) {
        BuildVars.LOGS_ENABLED = enabled;
        systemConfigPrefs().edit().putBoolean("logsEnabled", enabled).apply();
        if (!enabled) {
            FileLog.cleanupLogs();
        }
    }

    public static String getCurrentLangName() {
        return TranslatorUtils.getTargetLanguageTitle();
    }

    public static int getDoubleTapSeekDurationMillis() {
        int duration = getDoubleTapSeekDuration();
        if (duration == 0 || duration == 1 || duration == 2) {
            return (duration + 1) * 5000;
        }
        return 30000;
    }
    // ---- exteraGram 13.1.1
    public static boolean getNewProfileActionsStyle() {
        return newProfileActionsStyle.get();
    }

    public static void setNewProfileActionsStyle(boolean value) {
        newProfileActionsStyle.set(value);
    }

    public static boolean getNewPlayerStyle() {
        return newPlayerStyle.get();
    }

    public static void setNewPlayerStyle(boolean value) {
        newPlayerStyle.set(value);
    }

    public static boolean getPlayerCoverColors() {
        return playerCoverColors.get();
    }

    public static void setPlayerCoverColors(boolean value) {
        playerCoverColors.set(value);
    }

    public static boolean getPlayerMiniCoverColors() {
        return playerMiniCoverColors.get();
    }

    public static void setPlayerMiniCoverColors(boolean value) {
        playerMiniCoverColors.set(value);
    }

    public static boolean getLyricsOnline() {
        return lyricsOnline.get();
    }

    public static void setLyricsOnline(boolean value) {
        lyricsOnline.set(value);
    }

    public static int getLyricsProgress() {
        return lyricsProgress.get();
    }

    public static void setLyricsProgress(int value) {
        lyricsProgress.set(value);
    }

    public static boolean getPlayerMotionCovers() {
        return playerMotionCovers.get();
    }

    public static void setPlayerMotionCovers(boolean value) {
        playerMotionCovers.set(value);
    }

    public static boolean getPlayerSpotifyCanvas() {
        return playerSpotifyCanvas.get();
    }

    public static void setPlayerSpotifyCanvas(boolean value) {
        playerSpotifyCanvas.set(value);
    }

    public static boolean getPlayerRhythmBackground() {
        return playerRhythmBackground.get();
    }

    public static void setPlayerRhythmBackground(boolean value) {
        playerRhythmBackground.set(value);
    }

    public static boolean getPlayerParticles() {
        return playerParticles.get();
    }

    public static void setPlayerParticles(boolean value) {
        playerParticles.set(value);
    }

    public static boolean getPlayerSpeedPitch() {
        return playerSpeedPitch.get();
    }

    public static void setPlayerSpeedPitch(boolean value) {
        playerSpeedPitch.set(value);
    }

    public static int getPlayerMode() {
        return playerMode.get();
    }

    public static void setPlayerMode(int value) {
        playerMode.set(value);
    }

    public static float getTabletLeftFraction() {
        return tabletLeftFraction.get();
    }

    public static void setTabletLeftFraction(float value) {
        tabletLeftFraction.set(value);
    }

    public static boolean getHideNavigationBarLabels() {
        return hideNavigationBarLabels.get();
    }

    public static void setHideNavigationBarLabels(boolean value) {
        hideNavigationBarLabels.set(value);
    }

    public static boolean getInlineResults() {
        return inlineResults.get();
    }

    public static void setInlineResults(boolean value) {
        inlineResults.set(value);
    }

    public static boolean getInlineCurrencyRates() {
        return inlineCurrencyRates.get();
    }

    public static void setInlineCurrencyRates(boolean value) {
        inlineCurrencyRates.set(value);
    }

    public static boolean getInlineUnitConversion() {
        return inlineUnitConversion.get();
    }

    public static void setInlineUnitConversion(boolean value) {
        inlineUnitConversion.set(value);
    }

    public static boolean getInlineTimeZones() {
        return inlineTimeZones.get();
    }

    public static void setInlineTimeZones(boolean value) {
        inlineTimeZones.set(value);
    }

    public static String getInlineTargetCurrency() {
        return inlineTargetCurrency.get();
    }

    public static void setInlineTargetCurrency(String value) {
        inlineTargetCurrency.set(value);
    }

    public static AngleUnit getInlineAngleUnit() {
        return inlineAngleUnit.get();
    }

    public static void setInlineAngleUnit(AngleUnit value) {
        inlineAngleUnit.set(value);
    }

    public static MeasureSystem getInlineMeasureSystem() {
        return inlineMeasureSystem.get();
    }

    public static void setInlineMeasureSystem(MeasureSystem value) {
        inlineMeasureSystem.set(value);
    }

    public static boolean getInlineDigitGrouping() {
        return inlineDigitGrouping.get();
    }

    public static void setInlineDigitGrouping(boolean value) {
        inlineDigitGrouping.set(value);
    }

    public static boolean getPluginsStaticAnalysis() {
        return pluginsStaticAnalysis.get();
    }

    public static void setPluginsStaticAnalysis(boolean value) {
        pluginsStaticAnalysis.set(value);
    }
}
