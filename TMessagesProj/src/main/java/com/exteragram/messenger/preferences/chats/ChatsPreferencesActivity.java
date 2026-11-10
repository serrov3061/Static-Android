package com.exteragram.messenger.preferences.chats;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.view.View;
import androidx.core.content.ContextCompat;
import androidx.core.util.Consumer;
import com.exteragram.messenger.CameraType;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.StickerTimeMode;
import com.exteragram.messenger.VideoMessagesCamera;
import com.exteragram.messenger.ai.AiController;
import com.exteragram.messenger.ai.ui.activities.AiPreferencesActivity;
import com.exteragram.messenger.camera.CameraXSession;
import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.preferences.SwitchGroup;
import com.exteragram.messenger.preferences.chats.components.DoubleTapCell;
import com.exteragram.messenger.preferences.chats.components.MessagesPreviewCell;
import com.exteragram.messenger.preferences.chats.components.SliderPreviewCell;
import com.exteragram.messenger.preferences.chats.components.StickerShapeCell;
import com.exteragram.messenger.preferences.components.AltSeekbar;
import com.exteragram.messenger.speech.VoiceRecognitionController;
import com.exteragram.messenger.speech.ui.RecognitionModelDialogs;
import com.exteragram.messenger.translator.TranslatorUtils;
import com.exteragram.messenger.utils.ExtensionsKt;
import com.exteragram.messenger.utils.chats.DoubleTapUtils;
import com.exteragram.messenger.utils.chats.SwipeAction;
import com.exteragram.messenger.utils.ui.PopupUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import kotlin.Unit;
import kotlin.collections.CollectionsKt;
import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlin.jvm.internal.Intrinsics;
import kotlin.ranges.RangesKt;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Stories.recorder.DualCameraView;
import org.telegram.ui.ThemeActivity;

public final class ChatsPreferencesActivity extends BasePreferencesActivity {
    private static final List<String> languageCodes = java.util.Arrays.asList(new String[]{"none", "en", "es", "zh", "hi", "fa", "fr", "ru", "pt", "de", "ja", "ko", "it", "uk", "gu", "pl", "nl", "tr", "vi", "cs", "uz", "eo", "kk", "tg", "ca"});
    private final SwitchGroup aiFeatures;
    private CharSequence[] bottomButtonOptions;
    private CharSequence[] forwardSheetStyleOptions; // exteraStatic
    private final SwitchGroup cameraSettings;
    private CharSequence[] cameraTypeOptions;
    private CharSequence[] doubleTapActions;
    private DoubleTapCell doubleTapCell;
    private CharSequence[] doubleTapOutActions;
    private CharSequence[] doubleTapSeekDurationOptions;
    private final SwitchGroup hideReactions;
    private final SwitchGroup messageMenu;
    private MessagesPreviewCell messagesPreviewCell;
    private final SwitchGroup pauseOnMinimize;
    private final SwitchGroup quickTransitions;
    private CharSequence[] recognitionLanguageOptions;
    private final SwitchGroup replyElements;
    private ActionBarMenuItem resetItem;
    private StickerShapeCell stickerShapeCell;
    private SliderPreviewCell stickerSizeCell;
    private CharSequence[] stickerTimeModes;
    private CharSequence[] videoMessagesCameraOptions;
    private final SwitchGroup widePosts;

    public static final /* synthetic */ class WhenMappings {
        public static final /* synthetic */ int[] $EnumSwitchMapping$0;

        static {
            int[] iArr = new int[ChatsItem.values().length];
            try {
                iArr[ChatsItem.STICKER_TIME.ordinal()] = 1;
            } catch (NoSuchFieldError unused) {
            }
            try {
                iArr[ChatsItem.REPLY_ELEMENTS.ordinal()] = 2;
            } catch (NoSuchFieldError unused2) {
            }
            try {
                iArr[ChatsItem.REPLY_COLORS.ordinal()] = 3;
            } catch (NoSuchFieldError unused3) {
            }
            try {
                iArr[ChatsItem.REPLY_EMOJI.ordinal()] = 4;
            } catch (NoSuchFieldError unused4) {
            }
            try {
                iArr[ChatsItem.REPLY_BACKGROUND.ordinal()] = 5;
            } catch (NoSuchFieldError unused5) {
            }
            try {
                iArr[ChatsItem.AI.ordinal()] = 6;
            } catch (NoSuchFieldError unused6) {
            }
            try {
                iArr[ChatsItem.CHAT_SETTINGS.ordinal()] = 7;
            } catch (NoSuchFieldError unused7) {
            }
            try {
                iArr[ChatsItem.UNLIMITED_RECENT_STICKERS.ordinal()] = 8;
            } catch (NoSuchFieldError unused8) {
            }
            try {
                iArr[ChatsItem.HIDE_REACTIONS.ordinal()] = 9;
            } catch (NoSuchFieldError unused9) {
            }
            try {
                iArr[ChatsItem.CHANNELS.ordinal()] = 10;
            } catch (NoSuchFieldError unused10) {
            }
            try {
                iArr[ChatsItem.GROUPS.ordinal()] = 11;
            } catch (NoSuchFieldError unused11) {
            }
            try {
                iArr[ChatsItem.PRIVATE_CHATS.ordinal()] = 12;
            } catch (NoSuchFieldError unused12) {
            }
            try {
                iArr[ChatsItem.DOUBLE_TAP_ACTION.ordinal()] = 13;
            } catch (NoSuchFieldError unused13) {
            }
            try {
                iArr[ChatsItem.DOUBLE_TAP_ACTION_OUT_OWNER.ordinal()] = 14;
            } catch (NoSuchFieldError unused14) {
            }
            try {
                iArr[ChatsItem.SWIPE_ACTIONS.ordinal()] = 15;
            } catch (NoSuchFieldError unused15) {
            }
            try {
                iArr[ChatsItem.BOTTOM_BUTTON.ordinal()] = 16;
            } catch (NoSuchFieldError unused16) {
            }
            try {
                iArr[ChatsItem.WIDE_POSTS.ordinal()] = 17;
            } catch (NoSuchFieldError unused17) {
            }
            try {
                iArr[ChatsItem.WIDE_POSTS_FEED.ordinal()] = 18;
            } catch (NoSuchFieldError unused18) {
            }
            try {
                iArr[ChatsItem.WIDE_POSTS_CHANNELS.ordinal()] = 19;
            } catch (NoSuchFieldError unused19) {
            }
            try {
                iArr[ChatsItem.AI_FEATURES.ordinal()] = 20;
            } catch (NoSuchFieldError unused20) {
            }
            try {
                iArr[ChatsItem.AI_FEATURES_EDITOR.ordinal()] = 21;
            } catch (NoSuchFieldError unused21) {
            }
            try {
                iArr[ChatsItem.AI_FEATURES_SUMMARIES.ordinal()] = 22;
            } catch (NoSuchFieldError unused22) {
            }
            try {
                iArr[ChatsItem.AI_FEATURES_INSTANT_VIEW_SUMMARIES.ordinal()] = 23;
            } catch (NoSuchFieldError unused23) {
            }
            try {
                iArr[ChatsItem.ADMIN_SHORTCUTS.ordinal()] = 24;
            } catch (NoSuchFieldError unused24) {
            }
            try {
                iArr[ChatsItem.QUICK_TRANSITIONS.ordinal()] = 25;
            } catch (NoSuchFieldError unused25) {
            }
            try {
                iArr[ChatsItem.QUICK_TRANSITION_FOR_CHANNELS.ordinal()] = 26;
            } catch (NoSuchFieldError unused26) {
            }
            try {
                iArr[ChatsItem.QUICK_TRANSITION_FOR_TOPICS.ordinal()] = 27;
            } catch (NoSuchFieldError unused27) {
            }
            try {
                iArr[ChatsItem.DISABLE_GREETING_STICKER.ordinal()] = 28;
            } catch (NoSuchFieldError unused28) {
            }
            try {
                iArr[ChatsItem.HIDE_KEYBOARD_ON_SCROLL.ordinal()] = 29;
            } catch (NoSuchFieldError unused29) {
            }
            try {
                iArr[ChatsItem.ADD_COMMA_AFTER_MENTION.ordinal()] = 30;
            } catch (NoSuchFieldError unused30) {
            }
            try {
                iArr[ChatsItem.INLINE_MATH_RESULT.ordinal()] = 31;
            } catch (NoSuchFieldError unused31) {
            }
            try {
                iArr[ChatsItem.HIDE_SEND_AS_PEER.ordinal()] = 32;
            } catch (NoSuchFieldError unused32) {
            }
            try {
                iArr[ChatsItem.REPLACE_EDITED_WITH_ICON.ordinal()] = 33;
            } catch (NoSuchFieldError unused33) {
            }
            try {
                iArr[ChatsItem.SHOW_ONLINE_STATUS.ordinal()] = 34;
            } catch (NoSuchFieldError unused34) {
            }
            try {
                iArr[ChatsItem.SHOW_FORWARDS_COUNT.ordinal()] = 35;
            } catch (NoSuchFieldError unused35) {
            }
            try {
                iArr[ChatsItem.REMOVE_MESSAGE_TAIL.ordinal()] = 36;
            } catch (NoSuchFieldError unused36) {
            }
            try {
                iArr[ChatsItem.SHOW_RESULTS_BEFORE_VOTING.ordinal()] = 37;
            } catch (NoSuchFieldError unused37) {
            }
            try {
                iArr[ChatsItem.HIDE_SHARE_BUTTON.ordinal()] = 38;
            } catch (NoSuchFieldError unused38) {
            }
            try {
                iArr[ChatsItem.MESSAGE_MENU.ordinal()] = 39;
            } catch (NoSuchFieldError unused39) {
            }
            try {
                iArr[ChatsItem.COPY_PHOTO.ordinal()] = 40;
            } catch (NoSuchFieldError unused40) {
            }
            try {
                iArr[ChatsItem.SAVE.ordinal()] = 41;
            } catch (NoSuchFieldError unused41) {
            }
            try {
                iArr[ChatsItem.REPEAT.ordinal()] = 42;
            } catch (NoSuchFieldError unused42) {
            }
            try {
                iArr[ChatsItem.CLEAR.ordinal()] = 43;
            } catch (NoSuchFieldError unused43) {
            }
            try {
                iArr[ChatsItem.HISTORY.ordinal()] = 44;
            } catch (NoSuchFieldError unused44) {
            }
            try {
                iArr[ChatsItem.REPORT.ordinal()] = 45;
            } catch (NoSuchFieldError unused45) {
            }
            try {
                iArr[ChatsItem.GENERATE.ordinal()] = 46;
            } catch (NoSuchFieldError unused46) {
            }
            try {
                iArr[ChatsItem.DETAILS.ordinal()] = 47;
            } catch (NoSuchFieldError unused47) {
            }
            try {
                iArr[ChatsItem.GROUP_MESSAGE_MENU.ordinal()] = 48;
            } catch (NoSuchFieldError unused48) {
            }
            try {
                iArr[ChatsItem.SPEECH_RECOGNITION_LANGUAGE.ordinal()] = 49;
            } catch (NoSuchFieldError unused49) {
            }
            try {
                iArr[ChatsItem.POST_PROCESSING_WITH_AI.ordinal()] = 50;
            } catch (NoSuchFieldError unused50) {
            }
            try {
                iArr[ChatsItem.DELETE_RECOGNITION_MODEL.ordinal()] = 51;
            } catch (NoSuchFieldError unused51) {
            }
            try {
                iArr[ChatsItem.CAMERA_TYPE.ordinal()] = 52;
            } catch (NoSuchFieldError unused52) {
            }
            try {
                iArr[ChatsItem.CAMERA_SETTINGS.ordinal()] = 53;
            } catch (NoSuchFieldError unused53) {
            }
            try {
                iArr[ChatsItem.DUAL_CAMERA.ordinal()] = 54;
            } catch (NoSuchFieldError unused54) {
            }
            try {
                iArr[ChatsItem.EXTENDED_FRAMES_PER_SECOND.ordinal()] = 55;
            } catch (NoSuchFieldError unused55) {
            }
            try {
                iArr[ChatsItem.CAMERA_STABILIZATION.ordinal()] = 56;
            } catch (NoSuchFieldError unused56) {
            }
            try {
                iArr[ChatsItem.CAMERA_MIRROR_MODE.ordinal()] = 57;
            } catch (NoSuchFieldError unused57) {
            }
            try {
                iArr[ChatsItem.START_WITH_WIDE_ANGLE_CAMERA.ordinal()] = 58;
            } catch (NoSuchFieldError unused58) {
            }
            try {
                iArr[ChatsItem.VIDEO_MESSAGES_CAMERA.ordinal()] = 59;
            } catch (NoSuchFieldError unused59) {
            }
            try {
                iArr[ChatsItem.REMEMBER_LAST_USED_CAMERA.ordinal()] = 60;
            } catch (NoSuchFieldError unused60) {
            }
            try {
                iArr[ChatsItem.ZOOM_SLIDER.ordinal()] = 61;
            } catch (NoSuchFieldError unused61) {
            }
            try {
                iArr[ChatsItem.STATIC_ZOOM.ordinal()] = 62;
            } catch (NoSuchFieldError unused62) {
            }
            try {
                iArr[ChatsItem.ALWAYS_SEND_IN_HD.ordinal()] = 63;
            } catch (NoSuchFieldError unused63) {
            }
            try {
                iArr[ChatsItem.HIDE_CAMERA_TILE.ordinal()] = 64;
            } catch (NoSuchFieldError unused64) {
            }
            try {
                iArr[ChatsItem.DOUBLE_TAP_SEEK_DURATION.ordinal()] = 65;
            } catch (NoSuchFieldError unused65) {
            }
            try {
                iArr[ChatsItem.PREFER_ORIGINAL_QUALITY.ordinal()] = 66;
            } catch (NoSuchFieldError unused66) {
            }
            try {
                iArr[ChatsItem.SWIPE_TO_PIP.ordinal()] = 67;
            } catch (NoSuchFieldError unused67) {
            }
            try {
                iArr[ChatsItem.UNMUTE_WITH_VOLUME_BUTTONS.ordinal()] = 68;
            } catch (NoSuchFieldError unused68) {
            }
            try {
                iArr[ChatsItem.PAUSE_ON_MINIMIZE.ordinal()] = 69;
            } catch (NoSuchFieldError unused69) {
            }
            try {
                iArr[ChatsItem.PAUSE_ON_MINIMIZE_VIDEO.ordinal()] = 70;
            } catch (NoSuchFieldError unused70) {
            }
            try {
                iArr[ChatsItem.PAUSE_ON_MINIMIZE_VOICE.ordinal()] = 71;
            } catch (NoSuchFieldError unused71) {
            }
            try {
                iArr[ChatsItem.PAUSE_ON_MINIMIZE_ROUND.ordinal()] = 72;
            } catch (NoSuchFieldError unused72) {
            }
            try {
                iArr[ChatsItem.IOS_INPUT_FIELD.ordinal()] = 73;
            } catch (NoSuchFieldError unused73) {
            }
            try {
                iArr[ChatsItem.IOS_CHAT_HEADER.ordinal()] = 74;
            } catch (NoSuchFieldError unused74) {
            }
            try {
                iArr[ChatsItem.FORWARD_SHEET_STYLE.ordinal()] = 75;
            } catch (NoSuchFieldError unused75) {
            }
            $EnumSwitchMapping$0 = iArr;
        }
    }

    public ChatsPreferencesActivity() {
        int i = 40;
        DefaultConstructorMarker defaultConstructorMarker = null;
        CharSequence charSequence = null;
        SwitchGroup switchGroup = new SwitchGroup(this, ChatsItem.REPLY_ELEMENTS, R.string.RepliesTitle, "replyElements");
        switchGroup.onChanged(this::updateReplySettings);
        switchGroup.add(ChatsItem.REPLY_COLORS, R.string.BackgroundColors, () -> ExteraConfig.getReplyColors(), value -> {
            ExteraConfig.setReplyColors(value);
        });
        switchGroup.add(ChatsItem.REPLY_EMOJI, R.string.Emoji, () -> ExteraConfig.getReplyEmoji(), value -> {
            ExteraConfig.setReplyEmoji(value);
        });
        switchGroup.add(ChatsItem.REPLY_BACKGROUND, R.string.ReplyBackground, () -> ExteraConfig.getReplyBackground(), value -> {
            ExteraConfig.setReplyBackground(value);
        });
        this.replyElements = switchGroup;
        SwitchGroup switchGroup2 = new SwitchGroup(this, ChatsItem.HIDE_REACTIONS, R.string.HideReactions, "hideReactions");
        switchGroup2.onChanged(this::updateReplySettings);
        switchGroup2.add(ChatsItem.CHANNELS, R.string.ChannelsTab, () -> ExteraConfig.getHideReactionsInChannels(), value -> {
            ExteraConfig.setHideReactionsInChannels(value);
        });
        switchGroup2.add(ChatsItem.GROUPS, R.string.SaveToGalleryGroups, () -> ExteraConfig.getHideReactionsInGroups(), value -> {
            ExteraConfig.setHideReactionsInGroups(value);
        });
        switchGroup2.add(ChatsItem.PRIVATE_CHATS, R.string.PrivateChats, () -> ExteraConfig.getHideReactionsInPrivateChats(), value -> {
            ExteraConfig.setHideReactionsInPrivateChats(value);
        });
        this.hideReactions = switchGroup2;
        SwitchGroup switchGroup3 = new SwitchGroup(this, ChatsItem.WIDE_POSTS, R.string.WidePosts, "widePosts");
        switchGroup3.onChanged(() -> ChatsPreferencesActivity.m1673$r8$lambda$DhYH9Loy50Ur_v1N0SzJKvErw(ChatsPreferencesActivity.this));
        switchGroup3.add(ChatsItem.WIDE_POSTS_FEED, R.string.Feed, () -> ExteraConfig.getWidePostsInFeed(), value -> {
            ExteraConfig.setWidePostsInFeed(value);
        });
        switchGroup3.add(ChatsItem.WIDE_POSTS_CHANNELS, R.string.ChannelsTab, () -> ExteraConfig.getWidePostsInChannels(), value -> {
            ExteraConfig.setWidePostsInChannels(value);
        });
        this.widePosts = switchGroup3;
        SwitchGroup switchGroup4 = new SwitchGroup(this, ChatsItem.AI_FEATURES, R.string.AIFeatures, "aiFeatures");
        switchGroup4.onChanged(() -> ChatsPreferencesActivity.$r8$lambda$aa1yxM530VFz38JNCenQ6qK8Bvw(ChatsPreferencesActivity.this));
        switchGroup4.add(ChatsItem.AI_FEATURES_EDITOR, R.string.AIFeaturesEditor, () -> ExteraConfig.getTelegramAiEditor(), value -> {
            ExteraConfig.setTelegramAiEditor(value);
        });
        switchGroup4.add(ChatsItem.AI_FEATURES_SUMMARIES, R.string.AIFeaturesSummaries, () -> ExteraConfig.getTelegramAiSummaries(), value -> {
            ExteraConfig.setTelegramAiSummaries(value);
        });
        switchGroup4.add(ChatsItem.AI_FEATURES_INSTANT_VIEW_SUMMARIES, R.string.AIFeaturesInstantViewSummaries, () -> ExteraConfig.getTelegramAiInstantViewSummaries(), value -> {
            ExteraConfig.setTelegramAiInstantViewSummaries(value);
        });
        this.aiFeatures = switchGroup4;
        SwitchGroup switchGroup5 = new SwitchGroup(this, ChatsItem.QUICK_TRANSITIONS, R.string.QuickTransitions, "quickTransitions");
        switchGroup5.add(ChatsItem.QUICK_TRANSITION_FOR_CHANNELS, R.string.FilterChannels, () -> ExteraConfig.getQuickTransitionForChannels(), value -> {
            ExteraConfig.setQuickTransitionForChannels(value);
        });
        switchGroup5.add(ChatsItem.QUICK_TRANSITION_FOR_TOPICS, R.string.Topics, () -> ExteraConfig.getQuickTransitionForTopics(), value -> {
            ExteraConfig.setQuickTransitionForTopics(value);
        });
        this.quickTransitions = switchGroup5;
        SwitchGroup switchGroup6 = new SwitchGroup(this, ChatsItem.MESSAGE_MENU, R.string.MessageMenu, "messageMenu");
        switchGroup6.onChanged(() -> ChatsPreferencesActivity.$r8$lambda$aTj7CImeY3w9zA5fuudmfkAIdbA(ChatsPreferencesActivity.this));
        switchGroup6.add(ChatsItem.COPY_PHOTO, R.string.CopyPhoto, () -> ExteraConfig.getShowCopyPhotoButton(), value -> {
            ExteraConfig.setShowCopyPhotoButton(value);
        });
        switchGroup6.add(ChatsItem.SAVE, R.string.Save, () -> ExteraConfig.getShowSaveMessageButton(), value -> {
            ExteraConfig.setShowSaveMessageButton(value);
        });
        switchGroup6.add(ChatsItem.REPEAT, R.string.Repeat, () -> ExteraConfig.getShowRepeatMessageButton(), value -> {
            ExteraConfig.setShowRepeatMessageButton(value);
        });
        switchGroup6.add(ChatsItem.CLEAR, R.string.Clear, () -> ExteraConfig.getShowClearButton(), value -> {
            ExteraConfig.setShowClearButton(value);
        });
        switchGroup6.add(ChatsItem.HISTORY, R.string.MessageHistory, () -> ExteraConfig.getShowHistoryButton(), value -> {
            ExteraConfig.setShowHistoryButton(value);
        });
        switchGroup6.add(ChatsItem.REPORT, R.string.ReportChat, () -> ExteraConfig.getShowReportButton(), value -> {
            ExteraConfig.setShowReportButton(value);
        });
        switchGroup6.addIf(() -> AiController.canUseAI(), ChatsItem.GENERATE, R.string.Generate, () -> ExteraConfig.getShowGenerateButton(), value -> {
            ExteraConfig.setShowGenerateButton(value);
        });
        switchGroup6.add(ChatsItem.DETAILS, R.string.Details, () -> ExteraConfig.getShowDetailsButton(), value -> {
            ExteraConfig.setShowDetailsButton(value);
        });
        this.messageMenu = switchGroup6;
        SwitchGroup switchGroup7 = new SwitchGroup(this, ChatsItem.CAMERA_SETTINGS, R.string.ExtendedSettings, "cameraSettings");
        switchGroup7.addIf(() -> ChatsPreferencesActivity.this.isSeamlessSwitchingAvailable(), ChatsItem.DUAL_CAMERA, R.string.SeamlessSwitching, () -> DualCameraView.roundDualAvailableStatic(ChatsPreferencesActivity.this.getContext()), z -> {
            ChatsPreferencesActivity.$r8$lambda$SIECvH1woGtCA1yKWk5reWi9U30(z);
        });
        switchGroup7.add(ChatsItem.EXTENDED_FRAMES_PER_SECOND, R.string.ExtendedFramesPerSecond, () -> ExteraConfig.getExtendedFramesPerSecond(), value -> {
            ExteraConfig.setExtendedFramesPerSecond(value);
        });
        switchGroup7.add(ChatsItem.CAMERA_STABILIZATION, R.string.CameraStabilization, () -> ExteraConfig.getCameraStabilization(), value -> {
            ExteraConfig.setCameraStabilization(value);
        });
        switchGroup7.addIf(() -> ChatsPreferencesActivity.m1672$r8$lambda$3bAEwhc4N0xTYjnM3bIRxyA8OI(), ChatsItem.CAMERA_MIRROR_MODE, R.string.CameraMirrorMode, () -> ExteraConfig.getCameraMirrorMode(), value -> {
            ExteraConfig.setCameraMirrorMode(value);
        });
        switchGroup7.addIf(() -> ChatsPreferencesActivity.$r8$lambda$FaZY3S7n_AQyadXG2RJ9uhDMbLM(), ChatsItem.START_WITH_WIDE_ANGLE_CAMERA, R.string.StartWithWideAngleCamera, () -> ExteraConfig.getStartWithWideAngleCamera(), value -> {
            ExteraConfig.setStartWithWideAngleCamera(value);
        });
        switchGroup7.markNew("Camera-ExtendedSettings-StartWithWideAngle");
        this.cameraSettings = switchGroup7;
        SwitchGroup switchGroup8 = new SwitchGroup(this, ChatsItem.PAUSE_ON_MINIMIZE, R.string.PauseOnMinimize, "pauseOnMinimize");
        switchGroup8.add(ChatsItem.PAUSE_ON_MINIMIZE_VIDEO, R.string.PauseOnMinimizeVideo, () -> ExteraConfig.getPauseOnMinimizeVideo(), value -> {
            ExteraConfig.setPauseOnMinimizeVideo(value);
        });
        switchGroup8.add(ChatsItem.PAUSE_ON_MINIMIZE_VOICE, R.string.PauseOnMinimizeVoice, () -> ExteraConfig.getPauseOnMinimizeVoice(), value -> {
            ExteraConfig.setPauseOnMinimizeVoice(value);
        });
        switchGroup8.add(ChatsItem.PAUSE_ON_MINIMIZE_ROUND, R.string.PauseOnMinimizeRound, () -> ExteraConfig.getPauseOnMinimizeRound(), value -> {
            ExteraConfig.setPauseOnMinimizeRound(value);
        });
        this.pauseOnMinimize = switchGroup8;
    }

        public enum ChatsItem {
        STICKER_SIZE,
        STICKER_TIME,
        REPLY_ELEMENTS,
        REPLY_COLORS,
        REPLY_EMOJI,
        REPLY_BACKGROUND,
        STICKER_SHAPE,
        AI,
        CHAT_SETTINGS,
        UNLIMITED_RECENT_STICKERS,
        HIDE_REACTIONS,
        DOUBLE_TAP,
        DOUBLE_TAP_ACTION,
        DOUBLE_TAP_ACTION_OUT_OWNER,
        SWIPE_ACTIONS,
        BOTTOM_BUTTON,
        WIDE_POSTS,
        WIDE_POSTS_FEED,
        WIDE_POSTS_CHANNELS,
        AI_FEATURES,
        AI_FEATURES_EDITOR,
        AI_FEATURES_SUMMARIES,
        AI_FEATURES_INSTANT_VIEW_SUMMARIES,
        ADMIN_SHORTCUTS,
        QUICK_TRANSITIONS,
        QUICK_TRANSITION_FOR_CHANNELS,
        QUICK_TRANSITION_FOR_TOPICS,
        DISABLE_GREETING_STICKER,
        HIDE_KEYBOARD_ON_SCROLL,
        ADD_COMMA_AFTER_MENTION,
        INLINE_MATH_RESULT,
        HIDE_SEND_AS_PEER,
        MESSAGES_PREVIEW,
        REMOVE_MESSAGE_TAIL,
        REPLACE_EDITED_WITH_ICON,
        SHOW_ONLINE_STATUS,
        HIDE_SHARE_BUTTON,
        SHOW_RESULTS_BEFORE_VOTING,
        MESSAGE_MENU,
        COPY_PHOTO,
        SAVE,
        REPEAT,
        CLEAR,
        HISTORY,
        REPORT,
        GENERATE,
        DETAILS,
        GROUP_MESSAGE_MENU,
        GROUPS,
        CHANNELS,
        PRIVATE_CHATS,
        SPEECH_RECOGNITION_LANGUAGE,
        POST_PROCESSING_WITH_AI,
        DELETE_RECOGNITION_MODEL,
        CAMERA_TYPE,
        CAMERA_SETTINGS,
        DUAL_CAMERA,
        EXTENDED_FRAMES_PER_SECOND,
        CAMERA_STABILIZATION,
        CAMERA_MIRROR_MODE,
        VIDEO_MESSAGES_CAMERA,
        REMEMBER_LAST_USED_CAMERA,
        START_WITH_WIDE_ANGLE_CAMERA,
        ZOOM_SLIDER,
        STATIC_ZOOM,
        ALWAYS_SEND_IN_HD,
        HIDE_CAMERA_TILE,
        DOUBLE_TAP_SEEK_DURATION,
        PREFER_ORIGINAL_QUALITY,
        SWIPE_TO_PIP,
        UNMUTE_WITH_VOLUME_BUTTONS,
        PAUSE_ON_MINIMIZE,
        PAUSE_ON_MINIMIZE_VIDEO,
        PAUSE_ON_MINIMIZE_VOICE,
        PAUSE_ON_MINIMIZE_ROUND,
        SHOW_FORWARDS_COUNT,
        IOS_INPUT_FIELD,
        IOS_CHAT_HEADER,
        FORWARD_SHEET_STYLE,
        INLINE_CURRENCIES, // exteraGram 13.1.1 inline results
        INLINE_UNITS,
        INLINE_TIME_ZONES,
        INLINE_DIGIT_GROUPING;

        public static java.util.List<ChatsItem> getEntries() {
            return java.util.Arrays.asList(values());
        }

        private final int id = ordinal() + 1;

        private ChatsItem() {
        }

        public final int getId() {
            return this.id;
        }
    }

    /* renamed from: $r8$lambda$DhYH9Loy50Ur_v1N0SzJ-KvEr-w, reason: not valid java name */
    public static Unit m1673$r8$lambda$DhYH9Loy50Ur_v1N0SzJKvErw(ChatsPreferencesActivity chatsPreferencesActivity) {
        chatsPreferencesActivity.parentLayout.rebuildFragments(AndroidUtilities.isTablet() ? 1 : 0);
        return Unit.INSTANCE;
    }

    public static Unit $r8$lambda$aa1yxM530VFz38JNCenQ6qK8Bvw(ChatsPreferencesActivity chatsPreferencesActivity) {
        chatsPreferencesActivity.parentLayout.rebuildFragments(0);
        return Unit.INSTANCE;
    }

    public static Unit $r8$lambda$aTj7CImeY3w9zA5fuudmfkAIdbA(ChatsPreferencesActivity chatsPreferencesActivity) {
        chatsPreferencesActivity.parentLayout.rebuildFragments(0);
        return Unit.INSTANCE;
    }

    public static void $r8$lambda$SIECvH1woGtCA1yKWk5reWi9U30(boolean z) {
        SharedPreferences.Editor editorEdit = MessagesController.getGlobalMainSettings().edit();
        editorEdit.putBoolean("rounddual_available", z);
        editorEdit.apply();
    }

    /* renamed from: $r8$lambda$3bAEwhc4N0xTYjnM3bIRxyA-8OI, reason: not valid java name */
    public static boolean m1672$r8$lambda$3bAEwhc4N0xTYjnM3bIRxyA8OI() {
        return ExteraConfig.getCameraType() != CameraType.CAMERA_2;
    }

    public static boolean $r8$lambda$FaZY3S7n_AQyadXG2RJ9uhDMbLM() {
        return ExteraConfig.getCameraType() == CameraType.CAMERA_X;
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    public void initializeOptionStrings() {
        this.stickerTimeModes = new CharSequence[]{LocaleController.getString(R.string.Default), LocaleController.getString(R.string.StickerTimeSide), LocaleController.getString(R.string.StickerTimeHidden)};
        this.doubleTapActions = DoubleTapUtils.getDoubleTapActions(false);
        this.doubleTapOutActions = DoubleTapUtils.getDoubleTapActions(true);
        this.forwardSheetStyleOptions = new CharSequence[]{LocaleController.getString(R.string.ForwardSheetStyleStandard), LocaleController.getString(R.string.ForwardSheetStyleIOS)};
        this.bottomButtonOptions = new CharSequence[]{LocaleController.getString(R.string.Hide), LocaleController.getString(R.string.ChannelMuteNoCaps), LocaleController.getString(R.string.ChannelDiscussNoCaps)};
        this.videoMessagesCameraOptions = new CharSequence[]{LocaleController.getString(R.string.VideoMessagesCameraFront), LocaleController.getString(R.string.VideoMessagesCameraRear), LocaleController.getString(R.string.VideoMessagesCameraAsk)};
        this.doubleTapSeekDurationOptions = new CharSequence[]{LocaleController.formatPluralString("Seconds", 5, new Object[0]), LocaleController.formatPluralString("Seconds", 10, new Object[0]), LocaleController.formatPluralString("Seconds", 15, new Object[0]), LocaleController.formatPluralString("Seconds", 30, new Object[0])};
        this.cameraTypeOptions = new CharSequence[]{"Camera 1", "Camera 2", "Camera X"};
        List<String> list = languageCodes;
        ArrayList arrayList = new ArrayList(CollectionsKt.collectionSizeOrDefault(list, 10));
        Iterator it = list.iterator();
        while (it.hasNext()) {
            arrayList.add(RecognitionModelDialogs.getRecognitionLanguageOption((String) it.next()));
        }
        this.recognitionLanguageOptions = (CharSequence[]) arrayList.toArray(new CharSequence[0]);
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity, org.telegram.ui.ActionBar.BaseFragment
    public View createView(Context context) {
        SliderPreviewCell sliderPreviewCell = new SliderPreviewCell(this.parentLayout, context, ChatsItem.STICKER_SIZE.getId(), 4, 20, ExteraConfig.getStickerSize(), LocaleController.getString(R.string.StickerSize), LocaleController.getString(R.string.StickerSizeLeft), LocaleController.getString(R.string.StickerSizeRight), false);
        sliderPreviewCell.setListener(new SliderPreviewCell.OnSliderChangedListener() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda0
            @Override // com.exteragram.messenger.preferences.chats.components.SliderPreviewCell.OnSliderChangedListener
            public final void onChanged(float f) {
                ChatsPreferencesActivity.$r8$lambda$Zyay2o4VkHEizG1V7GsE0WSPGh4(ChatsPreferencesActivity.this, f);
            }
        });
        this.stickerSizeCell = sliderPreviewCell;
        this.stickerShapeCell = new StickerShapeCell(context) { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity.createView.2
            @Override // com.exteragram.messenger.preferences.chats.components.StickerShapeCell
            public void updateStickerPreview() {
                ChatsPreferencesActivity.this.parentLayout.rebuildFragments(0);
                SliderPreviewCell sliderPreviewCell2 = ChatsPreferencesActivity.this.stickerSizeCell;
                if (sliderPreviewCell2 != null) {
                    sliderPreviewCell2.invalidate();
                }
            }
        };
        this.doubleTapCell = new DoubleTapCell(context);
        this.messagesPreviewCell = new MessagesPreviewCell(context, this.parentLayout, 1);
        View viewCreateView = super.createView(context);
        ActionBarMenuItem actionBarMenuItemAddItem = this.actionBar.createMenu().addItem(0, R.drawable.msg_reset);
        actionBarMenuItemAddItem.setContentDescription(LocaleController.getString(R.string.Reset));
        actionBarMenuItemAddItem.setVisibility(ExteraConfig.getStickerSize() == 12.0f ? 8 : 0);
        actionBarMenuItemAddItem.setTag(null);
        actionBarMenuItemAddItem.setOnClickListener(new View.OnClickListener() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda1
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ChatsPreferencesActivity.m1675$r8$lambda$KS8V5vv1M6Glb4i2Hv8jWzQKs(ChatsPreferencesActivity.this, view);
            }
        });
        this.resetItem = actionBarMenuItemAddItem;
        return viewCreateView;
    }

    public static void $r8$lambda$Zyay2o4VkHEizG1V7GsE0WSPGh4(ChatsPreferencesActivity chatsPreferencesActivity, float f) {
        ExteraConfig.setStickerSize(f);
        ActionBarMenuItem actionBarMenuItem = chatsPreferencesActivity.resetItem;
        if (actionBarMenuItem == null || actionBarMenuItem.getVisibility() == 0) {
            return;
        }
        AndroidUtilities.updateViewVisibilityAnimated(actionBarMenuItem, true, 0.5f, true);
    }

    /* renamed from: $r8$lambda$KS8V5vv1M6-Glb4i2Hv8jWz-QKs, reason: not valid java name */
    public static void m1675$r8$lambda$KS8V5vv1M6Glb4i2Hv8jWzQKs(final ChatsPreferencesActivity chatsPreferencesActivity, View view) {
        AndroidUtilities.updateViewVisibilityAnimated(view, false, 0.5f, true);
        ValueAnimator valueAnimatorOfFloat = ValueAnimator.ofFloat(ExteraConfig.getStickerSize(), 12.0f);
        valueAnimatorOfFloat.setDuration(200L);
        valueAnimatorOfFloat.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda11
            @Override // android.animation.ValueAnimator.AnimatorUpdateListener
            public final void onAnimationUpdate(ValueAnimator valueAnimator) {
                ChatsPreferencesActivity.$r8$lambda$Sf9ddikJ8W132h_hhZTZGz2P0lc(chatsPreferencesActivity, valueAnimator);
            }
        });
        valueAnimatorOfFloat.start();
    }

    public static void $r8$lambda$Sf9ddikJ8W132h_hhZTZGz2P0lc(ChatsPreferencesActivity chatsPreferencesActivity, ValueAnimator valueAnimator) {
        AltSeekbar altSeekbar;
        float fFloatValue = ((Float) valueAnimator.getAnimatedValue()).floatValue();
        ExteraConfig.setStickerSize(fFloatValue);
        SliderPreviewCell sliderPreviewCell = chatsPreferencesActivity.stickerSizeCell;
        if (sliderPreviewCell != null && (altSeekbar = sliderPreviewCell.seekBar) != null) {
            altSeekbar.setProgress(fFloatValue);
        }
        SliderPreviewCell sliderPreviewCell2 = chatsPreferencesActivity.stickerSizeCell;
        if (sliderPreviewCell2 != null) {
            sliderPreviewCell2.invalidate();
        }
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    public String getTitle() {
        return LocaleController.getString(R.string.SearchAllChatsShort);
    }

    private final CharSequence swipeActionsValue() {
        List<SwipeAction> listEnabled = SwipeAction.enabled();
        if (listEnabled.isEmpty()) {
            return LocaleController.getString(R.string.Disable);
        }
        SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder();
        int iCoerceAtMost = RangesKt.coerceAtMost(listEnabled.size(), 5);
        for (int i = 0; i < iCoerceAtMost; i++) {
            if (i > 0) {
                spannableStringBuilder.append(' ');
            }
            Drawable drawable = ContextCompat.getDrawable(getContext(), listEnabled.get(i).iconRes);
            if (drawable != null) {
                int length = spannableStringBuilder.length();
                spannableStringBuilder.append('*');
                ColoredImageSpan coloredImageSpan = new ColoredImageSpan(drawable.mutate());
                coloredImageSpan.setSize(AndroidUtilities.dp(18.0f));
                spannableStringBuilder.setSpan(coloredImageSpan, length, spannableStringBuilder.length(), 33);
            }
        }
        if (listEnabled.size() > iCoerceAtMost) {
            spannableStringBuilder.append((CharSequence) " +").append((CharSequence) String.valueOf(listEnabled.size() - iCoerceAtMost));
        }
        return spannableStringBuilder;
    }

    /* JADX WARN: Removed duplicated region for block: B:22:0x0442  */
    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean z;
        items.add(UItem.asCustom(ChatsItem.STICKER_SIZE.getId(), this.stickerSizeCell).setLinkAlias("stickerSize", this));
        ChatsItem chatsItem = ChatsItem.STICKER_TIME;
        String string = LocaleController.getString(R.string.StickerTimeMode);
        CharSequence[] charSequenceArr = this.stickerTimeModes;
        if (charSequenceArr == null) {
            charSequenceArr = null;
        }
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) chatsItem, (CharSequence) string, 0, charSequenceArr[ExteraConfig.getStickerTimeMode().ordinal()], (CharSequence) null, (Boolean) null, false, "stickerTime", false, false, (Boolean) null, 1908, (Object) null));
        this.replyElements.fill(items);
        items.add(UItem.asShadow());
        items.add(UItem.asHeader(LocaleController.getString(R.string.StickerShape)));
        items.add(UItem.asCustom(ChatsItem.STICKER_SHAPE.getId(), this.stickerShapeCell).setLinkAlias("stickerShape", this));
        items.add(UItem.asShadow());
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.AI, (CharSequence) LocaleController.getString(R.string.AIChat), R.drawable.ai_chat, (CharSequence) null, (CharSequence) LocaleController.getString(R.string.AIChatInfo), (Boolean) null, false, "aiChat", false, false, (Boolean) null, 1896, (Object) null));
        // exteraGram 13.1.1: inline results got their own screen with documentation
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.INLINE_MATH_RESULT, (CharSequence) LocaleController.getString(R.string.InlineMathResult), R.drawable.msg_calculator, (CharSequence) null, (CharSequence) LocaleController.getString(R.string.InlineMathResultInfo), (Boolean) null, false, "inlineMathResult", false, false, (Boolean) null, 1896, (Object) null));
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.CHAT_SETTINGS, (CharSequence) LocaleController.getString(R.string.ChatSettings), R.drawable.msg_discussion, (CharSequence) null, (CharSequence) LocaleController.getString(R.string.ChatSettingsInfo), (Boolean) null, false, "chatSettings", false, false, (Boolean) null, 1832, (Object) null));
        items.add(UItem.asShadow());
        items.add(UItem.asHeader(LocaleController.getString(R.string.StickersName)));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.UNLIMITED_RECENT_STICKERS, (CharSequence) LocaleController.getString(R.string.UnlimitedRecentStickers), ExteraConfig.getUnlimitedRecentStickers(), (CharSequence) null, 0, false, "unlimitedRecentStickers", false, 0, 440, (Object) null));
        this.hideReactions.fill(items);
        items.add(UItem.asShadow(LocaleController.getString(R.string.HideReactionsInfo)));
        items.add(UItem.asHeader(LocaleController.getString(R.string.Gestures)));
        items.add(UItem.asCustom(ChatsItem.DOUBLE_TAP.getId(), this.doubleTapCell));
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.DOUBLE_TAP_ACTION, (CharSequence) LocaleController.getString(R.string.DoubleTapIncoming), 0, DoubleTapUtils.getDoubleTapActionLabel(ExteraConfig.getDoubleTapAction(), false), (CharSequence) null, (Boolean) null, false, "doubleTapIncoming", false, false, (Boolean) null, 1908, (Object) null));
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.DOUBLE_TAP_ACTION_OUT_OWNER, (CharSequence) LocaleController.getString(R.string.DoubleTapOutgoing), 0, DoubleTapUtils.getDoubleTapActionLabel(ExteraConfig.getDoubleTapActionOutOwner(), true), (CharSequence) null, (Boolean) null, false, "doubleTapOutgoing", false, false, (Boolean) null, 1908, (Object) null));
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.SWIPE_ACTIONS, (CharSequence) LocaleController.getString(R.string.SwipeActions), 0, swipeActionsValue(), (CharSequence) null, (Boolean) null, false, "swipeActions", false, false, (Boolean) null, 1908, (Object) null));
        items.add(UItem.asShadow(LocaleController.getString(R.string.DoubleTapInfo)));
        items.add(UItem.asHeader(LocaleController.getString(R.string.MainTabsChats)));
        ChatsItem chatsItem2 = ChatsItem.BOTTOM_BUTTON;
        String string2 = LocaleController.getString(R.string.BottomButton);
        CharSequence[] charSequenceArr2 = this.bottomButtonOptions;
        if (charSequenceArr2 == null) {
            charSequenceArr2 = null;
        }
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) chatsItem2, (CharSequence) string2, 0, charSequenceArr2[ExteraConfig.getBottomButton()], (CharSequence) null, (Boolean) null, false, "bottomButton", false, false, (Boolean) null, 1908, (Object) null));
        this.widePosts.fill(items);
        this.aiFeatures.fill(items);
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.ADMIN_SHORTCUTS, (CharSequence) LocaleController.getString(R.string.AdminShortcuts), ExteraConfig.getQuickAdminShortcuts(), (CharSequence) null, 0, false, "adminShortcuts", false, 0, 440, (Object) null));
        this.quickTransitions.fill(items);
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.DISABLE_GREETING_STICKER, (CharSequence) LocaleController.getString(R.string.DisableGreetingSticker), ExteraConfig.getDisableGreetingSticker(), (CharSequence) null, 0, false, "disableGreetingSticker", false, 0, 440, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.HIDE_KEYBOARD_ON_SCROLL, (CharSequence) LocaleController.getString(R.string.HideKeyboardOnScroll), ExteraConfig.getHideKeyboardOnScroll(), (CharSequence) null, 0, false, "hideKeyboardOnScroll", false, 0, 440, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.ADD_COMMA_AFTER_MENTION, (CharSequence) LocaleController.getString(R.string.AddCommaAfterMention), ExteraConfig.getAddCommaAfterMention(), (CharSequence) null, 0, false, "addCommaAfterMention", false, 0, 440, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.IOS_CHAT_HEADER, (CharSequence) LocaleController.getString(R.string.IOSChatHeader), ExteraConfig.getIOSChatHeader(), (CharSequence) LocaleController.getString(R.string.IOSChatHeaderHint), 0, false, "iosChatHeader", false, 0, 432, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.IOS_INPUT_FIELD, (CharSequence) LocaleController.getString(R.string.IOSInputField), ExteraConfig.getIOSInputField(), (CharSequence) LocaleController.getString(R.string.IOSInputFieldHint), 0, false, "iosInputField", false, 0, 432, (Object) null));
        // exteraStatic: forward/share sheet look (Standard / iOS = Cherrygram)
        CharSequence[] fwdSheetOptions = this.forwardSheetStyleOptions;
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.FORWARD_SHEET_STYLE, (CharSequence) LocaleController.getString(R.string.ForwardSheetStyle), 0, fwdSheetOptions == null ? null : fwdSheetOptions[Math.max(0, Math.min(fwdSheetOptions.length - 1, ExteraConfig.getForwardSheetStyle()))], (CharSequence) null, (Boolean) null, false, "forwardSheetStyle", false, false, (Boolean) null, 1908, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.HIDE_SEND_AS_PEER, (CharSequence) LocaleController.getString(R.string.HideSendAsPeer), ExteraConfig.getHideSendAsPeer(), (CharSequence) null, 0, false, "hideSendAsPeer", false, 0, 440, (Object) null));
        items.add(UItem.asShadow(LocaleController.getString(R.string.HideSendAsPeerInfo)));
        items.add(UItem.asHeader(LocaleController.getString(R.string.MessagesChartTitle)));
        items.add(UItem.asCustom(ChatsItem.MESSAGES_PREVIEW.getId(), this.messagesPreviewCell));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.REMOVE_MESSAGE_TAIL, (CharSequence) LocaleController.getString(R.string.RemoveMessageTail), ExteraConfig.getRemoveMessageTail(), (CharSequence) null, 0, false, "removeMessageTail", false, 0, 440, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.REPLACE_EDITED_WITH_ICON, (CharSequence) LocaleController.formatString(R.string.ReplaceEditedWithIcon, LocaleController.getString(R.string.EditedMessage)), ExteraConfig.getReplaceEditedWithIcon(), (CharSequence) null, 0, false, "replaceEditedWithIcon", false, 0, 440, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.SHOW_ONLINE_STATUS, (CharSequence) LocaleController.getString(R.string.ShowOnlineStatus), ExteraConfig.getShowOnlineStatus(), (CharSequence) null, 0, false, "showOnlineStatus", false, 0, 440, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.SHOW_FORWARDS_COUNT, (CharSequence) LocaleController.getString(R.string.ShowForwardsCount), ExteraConfig.getShowForwardsCount(), (CharSequence) null, 0, false, "showForwardsCount", false, 0, 440, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.HIDE_SHARE_BUTTON, (CharSequence) LocaleController.formatString(R.string.HideShareButton, LocaleController.getString(R.string.ShareFile)), ExteraConfig.getHideShareButton(), (CharSequence) null, 0, false, "hideShareButton", false, 0, 440, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.SHOW_RESULTS_BEFORE_VOTING, (CharSequence) LocaleController.getString(R.string.ShowPollResultsBeforeVoting), ExteraConfig.getShowResultsBeforeVoting(), (CharSequence) LocaleController.getString(R.string.ShowPollResultsBeforeVotingHint), 0, false, "showResultsBeforeVoting", false, 0, 432, (Object) null));
        this.messageMenu.fill(items);
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.GROUP_MESSAGE_MENU, (CharSequence) LocaleController.getString(R.string.GroupMessageMenu), ExteraConfig.getGroupMessageMenu(), (CharSequence) null, 0, false, "groupMessageMenu", false, 0, 440, (Object) null));
        items.add(UItem.asShadow(LocaleController.getString(R.string.GroupMessageMenuInfo)));
        // exteraGram: on-device recognition needs libvosk (ARM only); hide the section where it cannot run
        if (VoiceRecognitionController.isAvailable() || !getDownloadedRecognitionModels().isEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.PremiumPreviewVoiceToText)));
            items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.SPEECH_RECOGNITION_LANGUAGE, (CharSequence) LocaleController.getString(R.string.RecognitionLanguage), 0, (CharSequence) TranslatorUtils.getLanguageTitleSystem(ExteraConfig.getRecognitionLanguage()), (CharSequence) null, (Boolean) null, false, "recognitionLanguage", false, false, (Boolean) null, 1908, (Object) null));
            ExtensionsKt.addIf(items, AiController.canUseAI() && VoiceRecognitionController.isCustomRecognitionEnabled(), BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.POST_PROCESSING_WITH_AI, (CharSequence) LocaleController.getString(R.string.PostProcessingWithAi), ExteraConfig.getPostprocessingWithAi(), (CharSequence) LocaleController.getString(R.string.PostProcessingWithAiInfo), 0, false, "postprocessingWithAi", false, 0, 432, (Object) null));
            ExtensionsKt.addIf(items, !getDownloadedRecognitionModels().isEmpty(), BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.DELETE_RECOGNITION_MODEL, (CharSequence) LocaleController.getString(R.string.DeleteRecognitionModel), R.drawable.msg_delete, (CharSequence) null, (CharSequence) null, (Boolean) null, false, "deleteRecognitionModel", true, false, (Boolean) null, 1656, (Object) null));
            items.add(UItem.asShadow(LocaleController.getString(R.string.RecognitionInfo)));
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.VoipCamera)));
        ChatsItem chatsItem3 = ChatsItem.CAMERA_TYPE;
        String string3 = LocaleController.getString(R.string.CameraType);
        CharSequence[] charSequenceArr3 = this.cameraTypeOptions;
        if (charSequenceArr3 == null) {
            charSequenceArr3 = null;
        }
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) chatsItem3, (CharSequence) string3, 0, charSequenceArr3[ExteraConfig.getCameraType().ordinal()], (CharSequence) null, (Boolean) null, false, "cameraType", false, false, (Boolean) null, 1908, (Object) null));
        if (ExteraConfig.getCameraType() != CameraType.CAMERA_1) {
            this.cameraSettings.fill(items);
            if (isSeamlessSwitchingAvailable()) {
                z = false;
            } else {
                SharedPreferences.Editor editorEdit = MessagesController.getGlobalMainSettings().edit();
                z = false;
                editorEdit.putBoolean("rounddual_available", false);
                editorEdit.apply();
            }
        }
        ChatsItem chatsItem4 = ChatsItem.VIDEO_MESSAGES_CAMERA;
        String string4 = LocaleController.getString(R.string.VideoMessagesCamera);
        CharSequence[] charSequenceArr4 = this.videoMessagesCameraOptions;
        if (charSequenceArr4 == null) {
            charSequenceArr4 = null;
        }
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) chatsItem4, (CharSequence) string4, 0, charSequenceArr4[ExteraConfig.getVideoMessagesCamera().ordinal()], (CharSequence) null, (Boolean) null, false, "videoMessagesCamera", false, false, (Boolean) null, 1908, (Object) null));
        z = ExteraConfig.getVideoMessagesCamera() != VideoMessagesCamera.ASK;
        ExtensionsKt.addIf(items, z, BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.REMEMBER_LAST_USED_CAMERA, (CharSequence) LocaleController.getString(R.string.RememberLastUsedCamera), ExteraConfig.getRememberLastUsedCamera(), (CharSequence) LocaleController.getString(R.string.RememberLastUsedCameraInfo), 0, false, "rememberLastUsedCamera", false, 0, 432, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.ZOOM_SLIDER, (CharSequence) LocaleController.getString(R.string.ZoomSlider), ExteraConfig.getZoomSlider(), (CharSequence) LocaleController.getString(R.string.ZoomSliderInfo), 0, false, "zoomSlider", false, 0, 432, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.STATIC_ZOOM, (CharSequence) LocaleController.getString(R.string.StaticZoom), ExteraConfig.getStaticZoom(), (CharSequence) null, 0, false, "staticZoom", false, 0, 440, (Object) null));
        items.add(UItem.asShadow(LocaleController.getString(R.string.StaticZoomInfo)));
        items.add(UItem.asHeader(LocaleController.getString(R.string.AutoDownloadPhotos)));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.ALWAYS_SEND_IN_HD, (CharSequence) LocaleController.getString(R.string.AlwaysSendInHD), ExteraConfig.getAlwaysSendInHD(), (CharSequence) null, 0, false, "alwaysSendInHD", false, 0, 440, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.HIDE_CAMERA_TILE, (CharSequence) LocaleController.getString(R.string.HideCameraTile), ExteraConfig.getHideCameraTile(), (CharSequence) null, 0, false, "hideCameraTile", false, 0, 440, (Object) null));
        items.add(UItem.asShadow(LocaleController.getString(R.string.HideCameraTileInfo)));
        items.add(UItem.asHeader(LocaleController.getString(R.string.AutoDownloadVideos)));
        ChatsItem chatsItem5 = ChatsItem.DOUBLE_TAP_SEEK_DURATION;
        String string5 = LocaleController.getString(R.string.DoubleTapSeekDuration);
        CharSequence[] charSequenceArr5 = this.doubleTapSeekDurationOptions;
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) chatsItem5, (CharSequence) string5, 0, (charSequenceArr5 == null ? null : charSequenceArr5)[ExteraConfig.getDoubleTapSeekDuration()], (CharSequence) null, (Boolean) null, false, "doubleTapSeekDuration", false, false, (Boolean) null, 1908, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.PREFER_ORIGINAL_QUALITY, (CharSequence) LocaleController.getString(R.string.PreferOriginalQuality), ExteraConfig.getPreferOriginalQuality(), (CharSequence) null, 0, false, "preferOriginalQuality", false, 0, 440, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.SWIPE_TO_PIP, (CharSequence) LocaleController.getString(R.string.SwipeToPip), ExteraConfig.getSwipeToPip(), (CharSequence) null, 0, false, "swipeToPip", false, 0, 440, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) ChatsItem.UNMUTE_WITH_VOLUME_BUTTONS, (CharSequence) LocaleController.getString(R.string.UnmuteWithVolumeButtons), ExteraConfig.getUnmuteWithVolumeButtons(), (CharSequence) LocaleController.getString(R.string.UnmuteWithVolumeButtonsInfo), 0, false, "unmuteWithVolumeButtons", false, 0, 432, (Object) null));
        this.pauseOnMinimize.fill(items);
        items.add(UItem.asShadow(LocaleController.getString(R.string.PauseOnMinimizeInfo)));
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    public void onClick(UItem item, View view, int position, float x, float y) {
        int i = item.id;
        if (i <= 0 || i > ChatsItem.getEntries().size()) {
            return;
        }
        final ChatsItem chatsItem = ChatsItem.getEntries().get(item.id - 1);
        if (chatsItem == ChatsItem.INLINE_MATH_RESULT) { // exteraGram 13.1.1: the inline results screen
            presentFragment(new InlineResultsPreferencesActivity());
            return;
        }
        switch (WhenMappings.$EnumSwitchMapping$0[chatsItem.ordinal()]) {
            case 1:
                CharSequence[] charSequenceArr = this.stickerTimeModes;
                if (charSequenceArr == null) {
                    charSequenceArr = null;
                }
                showListDialog(item, charSequenceArr, LocaleController.getString(R.string.StickerTimeMode), ExteraConfig.getStickerTimeMode().ordinal(), new PopupUtils.OnItemClickListener() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda12
                    @Override // com.exteragram.messenger.utils.ui.PopupUtils.OnItemClickListener
                    public final void onClick(int i2) {
                        ChatsPreferencesActivity.$r8$lambda$FWitcGe3P3jEExkXLxXfYkSVZ4s(ChatsPreferencesActivity.this, i2);
                    }
                });
                break;
            case 2:
            case 3:
            case 4:
            case 5:
                this.replyElements.onClick(item);
                break;
            case 6:
                presentFragment(new AiPreferencesActivity());
                break;
            case 7:
                presentFragment(new ThemeActivity(0));
                break;
            case 8:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda23
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setUnlimitedRecentStickers(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 9:
            case 10:
            case 11:
            case 12:
                this.hideReactions.onClick(item);
                break;
            case 13:
                CharSequence[] charSequenceArr2 = this.doubleTapActions;
                BasePreferencesActivity.showListDialog$default(this, item, charSequenceArr2 == null ? null : charSequenceArr2, DoubleTapUtils.getDoubleTapIcons(false), LocaleController.getString(R.string.DoubleTapIncoming), ExteraConfig.getDoubleTapAction(), new PopupUtils.OnItemClickListener() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda34
                    @Override // com.exteragram.messenger.utils.ui.PopupUtils.OnItemClickListener
                    public final void onClick(int i2) {
                        ChatsPreferencesActivity.$r8$lambda$oohFinv130Q8KjzojqiDzlgwZUQ(ChatsPreferencesActivity.this, i2);
                    }
                }, false, false, 192, null);
                break;
            case 14:
                CharSequence[] charSequenceArr3 = this.doubleTapOutActions;
                BasePreferencesActivity.showListDialog$default(this, item, charSequenceArr3 == null ? null : charSequenceArr3, DoubleTapUtils.getDoubleTapIcons(true), LocaleController.getString(R.string.DoubleTapOutgoing), ExteraConfig.getDoubleTapActionOutOwner(), new PopupUtils.OnItemClickListener() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda35
                    @Override // com.exteragram.messenger.utils.ui.PopupUtils.OnItemClickListener
                    public final void onClick(int i2) {
                        ChatsPreferencesActivity.$r8$lambda$JUuCcMDTxW8ABK84fpnnPE2XeIY(ChatsPreferencesActivity.this, i2);
                    }
                }, false, false, 192, null);
                break;
            case 15:
                presentFragment(new SwipeActionsPreferencesActivity());
                break;
            case 16:
                CharSequence[] charSequenceArr4 = this.bottomButtonOptions;
                if (charSequenceArr4 == null) {
                    charSequenceArr4 = null;
                }
                showListDialog(item, charSequenceArr4, LocaleController.getString(R.string.BottomButton), ExteraConfig.getBottomButton(), new PopupUtils.OnItemClickListener() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda36
                    @Override // com.exteragram.messenger.utils.ui.PopupUtils.OnItemClickListener
                    public final void onClick(int i2) {
                        ChatsPreferencesActivity.m1679$r8$lambda$mvxOJVz3LNxAun88VdgUhdHUg(ChatsPreferencesActivity.this, i2);
                    }
                });
                break;
            case 17:
            case 18:
            case 19:
                this.widePosts.onClick(item);
                break;
            case 20:
            case 21:
            case 22:
            case 23:
                this.aiFeatures.onClick(item);
                break;
            case 24:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda37
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setQuickAdminShortcuts(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 25:
            case 26:
            case 27:
                this.quickTransitions.onClick(item);
                break;
            case 28:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda38
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setDisableGreetingSticker(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 29:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda39
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setHideKeyboardOnScroll(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 30:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda40
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setAddCommaAfterMention(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 31:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda41
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setInlineMathResult(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 75:
                showListDialog(item, this.forwardSheetStyleOptions, LocaleController.getString(R.string.ForwardSheetStyle), ExteraConfig.getForwardSheetStyle(), i2 -> ExteraConfig.setForwardSheetStyle(i2));
                break;
            case 74:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() {
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setIOSChatHeader(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 73:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() {
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setIOSInputField(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 32:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda13
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setHideSendAsPeer(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 33:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda14
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setReplaceEditedWithIcon(((Boolean) obj).booleanValue());
                    }
                });
                MessagesPreviewCell messagesPreviewCell = this.messagesPreviewCell;
                if (messagesPreviewCell != null) {
                    messagesPreviewCell.refreshMessages();
                    break;
                }
                break;
            case 34:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda15
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setShowOnlineStatus(((Boolean) obj).booleanValue());
                    }
                });
                MessagesPreviewCell messagesPreviewCell2 = this.messagesPreviewCell;
                if (messagesPreviewCell2 != null) {
                    messagesPreviewCell2.refreshMessages();
                    break;
                }
                break;
            case 35:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda16
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setShowForwardsCount(((Boolean) obj).booleanValue());
                    }
                });
                MessagesPreviewCell messagesPreviewCell3 = this.messagesPreviewCell;
                if (messagesPreviewCell3 != null) {
                    messagesPreviewCell3.refreshMessages();
                }
                this.parentLayout.rebuildFragments(AndroidUtilities.isTablet() ? 1 : 0);
                break;
            case 36:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda17
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setRemoveMessageTail(((Boolean) obj).booleanValue());
                    }
                });
                Theme.chat_msgInDrawable = null;
                Theme.createChatResources(getParentActivity(), false);
                MessagesPreviewCell messagesPreviewCell4 = this.messagesPreviewCell;
                if (messagesPreviewCell4 != null) {
                    messagesPreviewCell4.refreshMessages();
                    break;
                }
                break;
            case 37:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda18
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setShowResultsBeforeVoting(((Boolean) obj).booleanValue());
                    }
                });
                this.parentLayout.rebuildFragments(0);
                break;
            case 38:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda19
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setHideShareButton(((Boolean) obj).booleanValue());
                    }
                });
                MessagesPreviewCell messagesPreviewCell5 = this.messagesPreviewCell;
                if (messagesPreviewCell5 != null) {
                    messagesPreviewCell5.refreshMessages();
                    break;
                }
                break;
            case 39:
            case 40:
            case 41:
            case 42:
            case 43:
            case 44:
            case 45:
            case 46:
            case 47:
                this.messageMenu.onClick(item);
                break;
            case 48:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda20
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setGroupMessageMenu(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 49:
                handleSpeechRecognitionLanguageClick(item);
                break;
            case 50:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda21
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setPostprocessingWithAi(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 51:
                handleDeleteRecognitionModelClick();
                break;
            case 52:
                CharSequence[] charSequenceArr5 = this.cameraTypeOptions;
                if (charSequenceArr5 == null) {
                    charSequenceArr5 = null;
                }
                showListDialog(item, charSequenceArr5, LocaleController.getString(R.string.CameraType), ExteraConfig.getCameraType().ordinal(), new PopupUtils.OnItemClickListener() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda22
                    @Override // com.exteragram.messenger.utils.ui.PopupUtils.OnItemClickListener
                    public final void onClick(int i2) {
                        ExteraConfig.setCameraType(CameraType.getEntries().get(i2));
                    }
                });
                break;
            case 53:
            case 54:
            case 55:
            case 56:
            case 57:
            case 58:
                this.cameraSettings.onClick(item);
                break;
            case 59:
                CharSequence[] charSequenceArr6 = this.videoMessagesCameraOptions;
                if (charSequenceArr6 == null) {
                    charSequenceArr6 = null;
                }
                showListDialog(item, charSequenceArr6, LocaleController.getString(R.string.VideoMessagesCamera), ExteraConfig.getVideoMessagesCamera().ordinal(), new PopupUtils.OnItemClickListener() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda24
                    @Override // com.exteragram.messenger.utils.ui.PopupUtils.OnItemClickListener
                    public final void onClick(int i2) {
                        ExteraConfig.setVideoMessagesCamera(VideoMessagesCamera.getEntries().get(i2));
                    }
                });
                break;
            case 60:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda25
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setRememberLastUsedCamera(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 61:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda26
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setZoomSlider(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 62:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda27
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setStaticZoom(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 63:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda28
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setAlwaysSendInHD(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 64:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda29
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setHideCameraTile(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 65:
                CharSequence[] charSequenceArr7 = this.doubleTapSeekDurationOptions;
                if (charSequenceArr7 == null) {
                    charSequenceArr7 = null;
                }
                showListDialog(item, charSequenceArr7, LocaleController.getString(R.string.DoubleTapSeekDuration), ExteraConfig.getDoubleTapSeekDuration(), new PopupUtils.OnItemClickListener() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda30
                    @Override // com.exteragram.messenger.utils.ui.PopupUtils.OnItemClickListener
                    public final void onClick(int i2) {
                        ExteraConfig.setDoubleTapSeekDuration(i2);
                    }
                });
                break;
            case 66:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda31
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setPreferOriginalQuality(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 67:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda32
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setSwipeToPip(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 68:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda33
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setUnmuteWithVolumeButtons(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 69:
            case 70:
            case 71:
            case 72:
                this.pauseOnMinimize.onClick(item);
                break;
        }
    }

    public static void $r8$lambda$FWitcGe3P3jEExkXLxXfYkSVZ4s(ChatsPreferencesActivity chatsPreferencesActivity, int i) {
        ExteraConfig.setStickerTimeMode(StickerTimeMode.getEntries().get(i));
        SliderPreviewCell sliderPreviewCell = chatsPreferencesActivity.stickerSizeCell;
        if (sliderPreviewCell != null) {
            sliderPreviewCell.invalidate();
        }
        chatsPreferencesActivity.parentLayout.rebuildFragments(0);
    }

    public static void $r8$lambda$oohFinv130Q8KjzojqiDzlgwZUQ(ChatsPreferencesActivity chatsPreferencesActivity, int i) {
        ExteraConfig.setDoubleTapAction(i);
        chatsPreferencesActivity.handleDoubleTapActionButtonClick(false);
    }

    public static void $r8$lambda$JUuCcMDTxW8ABK84fpnnPE2XeIY(ChatsPreferencesActivity chatsPreferencesActivity, int i) {
        ExteraConfig.setDoubleTapActionOutOwner(i);
        chatsPreferencesActivity.handleDoubleTapActionButtonClick(true);
    }

    /* renamed from: $r8$lambda$mvxO-JVz3LNxAun88Vdg-UhdHUg, reason: not valid java name */
    public static void m1679$r8$lambda$mvxOJVz3LNxAun88VdgUhdHUg(ChatsPreferencesActivity chatsPreferencesActivity, int i) {
        ExteraConfig.setBottomButton(i);
        chatsPreferencesActivity.parentLayout.rebuildFragments(0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void updateReplySettings() {
        SliderPreviewCell sliderPreviewCell = this.stickerSizeCell;
        if (sliderPreviewCell != null) {
            sliderPreviewCell.invalidate();
        }
        this.parentLayout.rebuildFragments(0);
    }

    private final DoubleTapCell handleDoubleTapActionButtonClick(boolean outOwner) {
        DoubleTapCell doubleTapCell = this.doubleTapCell;
        if (doubleTapCell == null) {
            return null;
        }
        doubleTapCell.updateIcons(outOwner ? 2 : 1, true);
        doubleTapCell.invalidate();
        return doubleTapCell;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final boolean isSeamlessSwitchingAvailable() {
        if (ExteraConfig.getCameraType() == CameraType.CAMERA_X && CameraXSession.isSeamlessSwitchingAvailable(getContext())) {
            return true;
        }
        return ExteraConfig.getCameraType() == CameraType.CAMERA_2 && DualCameraView.dualAvailableStatic(getContext());
    }

    private final void handleSpeechRecognitionLanguageClick(final UItem item) {
        CharSequence[] charSequenceArr = this.recognitionLanguageOptions;
        if (charSequenceArr == null) {
            charSequenceArr = null;
        }
        PopupUtils.showDialog(charSequenceArr, null, LocaleController.getString(R.string.RecognitionLanguage), languageCodes.indexOf(ExteraConfig.getRecognitionLanguage()), getContext(), new PopupUtils.OnItemClickListener() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda42
            @Override // com.exteragram.messenger.utils.ui.PopupUtils.OnItemClickListener
            public final void onClick(int i) {
                ChatsPreferencesActivity.$r8$lambda$4KvpqlaYRwOjBXeSe628W1EL_Hc(ChatsPreferencesActivity.this, item, i);
            }
        });
    }

    public static void $r8$lambda$4KvpqlaYRwOjBXeSe628W1EL_Hc(final ChatsPreferencesActivity chatsPreferencesActivity, final UItem uItem, int i) {
        Object next;
        String recognitionLanguage = ExteraConfig.getRecognitionLanguage();
        final String str = languageCodes.get(i);
        if (Intrinsics.areEqual(recognitionLanguage, str)) {
            return;
        }
        Runnable runnable = new Runnable() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda44
            @Override // java.lang.Runnable
            public final void run() {
                ChatsPreferencesActivity.m1677$r8$lambda$cefdfm2DsK_hsuAL4lE7qv2ngo(str, chatsPreferencesActivity, uItem);
            }
        };
        if (!Intrinsics.areEqual(str, "none")) {
            List<VoiceRecognitionController.RecognitionModel> downloadedRecognitionModels = chatsPreferencesActivity.getDownloadedRecognitionModels();
            if (!(downloadedRecognitionModels instanceof Collection) || !downloadedRecognitionModels.isEmpty()) {
                Iterator it = downloadedRecognitionModels.iterator();
                while (it.hasNext()) {
                    if (Intrinsics.areEqual(((VoiceRecognitionController.RecognitionModel) it.next()).getLanguage(), str)) {
                        runnable.run();
                        return;
                    }
                }
            }
            Iterator it2 = VoiceRecognitionController.getInstance().listAvailableModels("vosk").iterator();
            while (true) {
                if (!it2.hasNext()) {
                    next = null;
                    break;
                } else {
                    next = it2.next();
                    if (Intrinsics.areEqual(((VoiceRecognitionController.RecognitionModel) next).getLanguage(), str)) {
                        break;
                    }
                }
            }
            VoiceRecognitionController.RecognitionModel recognitionModel = (VoiceRecognitionController.RecognitionModel) next;
            if (recognitionModel == null) {
                return;
            }
            RecognitionModelDialogs.showDownloadDialog(chatsPreferencesActivity, str, recognitionModel, runnable);
            return;
        }
        runnable.run();
    }

    /* renamed from: $r8$lambda$cefdfm2DsK_hsuAL4lE-7qv2ngo, reason: not valid java name */
    public static void m1677$r8$lambda$cefdfm2DsK_hsuAL4lE7qv2ngo(String str, ChatsPreferencesActivity chatsPreferencesActivity, UItem uItem) {
        ExteraConfig.setRecognitionLanguage(str);
        View viewFindViewByItemId = chatsPreferencesActivity.getListView$TMessagesProj().findViewByItemId(uItem.id);
        if (viewFindViewByItemId instanceof TextCell) {
            ((TextCell) viewFindViewByItemId).setValue(TranslatorUtils.getLanguageTitleSystem(str), true);
        }
        chatsPreferencesActivity.getListView$TMessagesProj().adapter.update(true);
    }

    private final List<VoiceRecognitionController.RecognitionModel> getDownloadedRecognitionModels() {
        return VoiceRecognitionController.getInstance().listDownloadedModels("vosk");
    }

    private final void handleDeleteRecognitionModelClick() {
        RecognitionModelDialogs.showDeleteFlow(this, getDownloadedRecognitionModels(), new Utilities.Callback() { // from class: com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity$$ExternalSyntheticLambda43
            @Override // org.telegram.messenger.Utilities.Callback
            public final void run(Object obj) {
                ChatsPreferencesActivity.$r8$lambda$Z6TQ1sAjTbJhiJ9r9nzpIdA6Di4(ChatsPreferencesActivity.this, (VoiceRecognitionController.RecognitionModel) obj);
            }
        });
    }

    public static void $r8$lambda$Z6TQ1sAjTbJhiJ9r9nzpIdA6Di4(ChatsPreferencesActivity chatsPreferencesActivity, VoiceRecognitionController.RecognitionModel recognitionModel) {
        if (Intrinsics.areEqual(ExteraConfig.getRecognitionLanguage(), recognitionModel.getLanguage())) {
            ExteraConfig.setRecognitionLanguage("none");
        }
        chatsPreferencesActivity.getListView$TMessagesProj().adapter.update(true);
    }
}
