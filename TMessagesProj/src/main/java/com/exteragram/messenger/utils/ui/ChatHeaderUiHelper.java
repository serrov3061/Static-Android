package com.exteragram.messenger.utils.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.debug.DebugConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.ChatActivityTopPanelLayout;
import org.telegram.ui.Components.ChatAvatarContainer;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.ui.Components.chat.layouts.ChatActivityFadeView;

/**
 * Chat header helpers of exteraGram (ExteraConfig.newChatHeaderStyle, "Material 3" header):
 * bigger 46dp avatar without the glass middle pill, solid top fade, top panel gap.
 * With the setting off every method returns the stock Telegram values.
 */
public final class ChatHeaderUiHelper {

    private ChatHeaderUiHelper() {
    }

    /**
     * Material 3 chat header in effect. While the iOS-style header (ExteraConfig.iOSChatHeader) is on
     * the iOS geometry is kept exactly, so every M3 tweak is neutralised here; the only M3 trait left
     * is the hidden middle island, see {@link #isIOSHeaderPillHidden()}.
     */
    public static boolean isMaterial3ChatHeaderStyle() {
        return ExteraConfig.getNewChatHeaderStyle() && !ExteraConfig.getIOSChatHeader();
    }

    /**
     * iOS-style header with the Material 3 chat header option: the iOS layout is kept as is
     * (centred title/status, avatar on the right, marquee), only the middle glass island
     * behind the title is not drawn.
     */
    public static boolean isIOSHeaderPillHidden() {
        return ExteraConfig.getNewChatHeaderStyle() && ExteraConfig.getIOSChatHeader();
    }

    public static int getChatAvatarSizeDp() {
        return isMaterial3ChatHeaderStyle() ? 46 : 42;
    }

    /** Stock header draws the avatar 1px inside its 42dp slot. */
    public static int getAvatarInsetPx() {
        return isMaterial3ChatHeaderStyle() ? 0 : 1;
    }

    public static float getGlassPillExtraRightPaddingDp() {
        return isMaterial3ChatHeaderStyle() ? 0.0f : 6.0f;
    }

    public static int getAvatarSizePx(int sizeDp) {
        return AndroidUtilities.dp(sizeDp) - getAvatarInsetPx() * 2;
    }

    public static int getAvatarRadius(int sizeDp, boolean forum) {
        if (isMaterial3ChatHeaderStyle()) {
            return ExteraConfig.getAvatarCorners(sizeDp, false, forum);
        }
        return ExteraConfig.getAvatarCorners(getAvatarSizePx(sizeDp), true, forum);
    }

    public static int getChatFadeColorKey() {
        return DebugConfig.getChatFadeUseWhiteBackground() ? Theme.key_windowBackgroundWhite : Theme.key_windowBackgroundGray;
    }

    /** Glass mode for the header avatar container (+ 46dp avatar in the Material 3 style). */
    public static void setupGlassAvatarContainer(ChatAvatarContainer container) {
        container.setGlassMode();
        if (isMaterial3ChatHeaderStyle()) {
            container.setAvatarSizeInDp(46);
        }
    }

    public static void applyChatHeaderGlassStyle(ActionBar actionBar) {
        applyChatHeaderGlassStyle(actionBar, isMaterial3ChatHeaderStyle());
    }

    /** exteraGram: hides the glass middle pill and its shadow. */
    public static void applyChatHeaderGlassStyle(ActionBar actionBar, boolean material3) {
        if (material3) {
            actionBar.setDrawGlassMiddlePill(false);
            actionBar.setGlassShadowAlpha(0.0f);
        }
    }

    public static void setupChatTopFade(ChatActivityFadeView fadeView, ActionBar actionBar, int color, int actionBarHeight) {
        setupChatTopFade(fadeView, actionBar, color, actionBarHeight, isMaterial3ChatHeaderStyle());
    }

    public static void setupChatTopFade(ChatActivityFadeView fadeView, ActionBar actionBar, int color, int actionBarHeight, boolean material3) {
        fadeView.setFadeTopAlpha(actionBar.getVisibility() == View.VISIBLE ? 255 : 0);
        int zone = getChatTopFadeZone(actionBarHeight, material3);
        if (material3) {
            fadeView.setFadeZoneTop(getScaledChatTopFadeZone(actionBar, zone, true));
            fadeView.setTopFadeColor(color);
        } else {
            fadeView.setFadeZoneTop(zone);
        }
        fadeView.setFadeHeightTop(getChatTopFadeHeight(material3));
    }

    /** True when the scrimmed message (or its group) goes under the top fade. */
    public static boolean scrimReachesTopFade(ChatActivityFadeView fadeView, ViewGroup listView, View scrimView, MessageObject.GroupedMessages group, float scrimTop) {
        final float fadeZoneTop = fadeView.getFadeZoneTop();
        if (scrimTop >= fadeZoneTop) {
            return false;
        }
        for (int i = 0, n = listView.getChildCount(); i < n; i++) {
            final View child = listView.getChildAt(i);
            final ChatMessageCell cell = child instanceof ChatMessageCell ? (ChatMessageCell) child : null;
            final boolean isScrim = child == scrimView || group != null && cell != null && cell.getCurrentMessagesGroup() == group;
            if (isScrim && child.getAlpha() != 0.0f && (cell != null && cell.getTransitionParams().animateBackgroundBoundsInner || listView.getY() + child.getY() < fadeZoneTop)) {
                return true;
            }
        }
        if (group != null && group.transitionParams.cell != null) {
            final MessageObject.GroupedMessages.TransitionParams tp = group.transitionParams;
            float y = tp.top + tp.offsetTop;
            if (!tp.backgroundChangeBounds) {
                y += tp.cell.getTranslationY();
            }
            return listView.getY() + y < fadeZoneTop;
        }
        return false;
    }

    private static int getScaledChatTopFadeZone(ActionBar actionBar, int zone, boolean material3) {
        int height = actionBar.getMeasuredHeight();
        int baseZone = getChatTopFadeZone(height, material3);
        return (height == 0 || baseZone >= zone) ? zone : baseZone + Math.round((zone - baseZone) * 0.5f);
    }

    public static boolean isLightChatStatusBar(ActionBar actionBar, int color) {
        return isLightChatStatusBar(actionBar, color, isMaterial3ChatHeaderStyle());
    }

    public static boolean isLightChatStatusBar(ActionBar actionBar, int color, boolean material3) {
        if (!material3 || actionBar.isActionModeShowed()) {
            color = actionBar.getBackgroundColor();
        }
        return AndroidUtilities.computePerceivedBrightness(color) > 0.721f;
    }

    /**
     * Left margin (dp) of the avatar container inside the action bar.
     * Stock layout keeps {@code stockDp} (ChatActivity 52 / preview 0, ChannelAdminLogActivity 54).
     */
    public static int getAvatarContainerLeftMargin(boolean inPreviewMode, int stockDp) {
        if (!isMaterial3ChatHeaderStyle()) {
            return stockDp;
        }
        return inPreviewMode ? 4 : 57;
    }

    public static float getTopPanelActionBarGapOffset(ChatActivityTopPanelLayout topPanelLayout) {
        if (!isMaterial3ChatHeaderStyle() || topPanelLayout == null) {
            return 0.0f;
        }
        return AndroidUtilities.dp(4.0f) * topPanelLayout.getMetadata().getTotalVisibility();
    }

    public static float getFinalTopPanelHeight(float topPanelHeight, ChatActivityTopPanelLayout topPanelLayout) {
        return topPanelHeight + getTopPanelActionBarGapOffset(topPanelLayout);
    }

    /** Stock: ty - dp(5) - tabsTop * tabsShown. */
    public static float getTopPanelTranslationY(float ty, float topicTabsTop, float hashtagTabsShown) {
        return ty + AndroidUtilities.dp(isMaterial3ChatHeaderStyle() ? -1 : -5) - topicTabsTop * hashtagTabsShown;
    }

    public static int getChatTopFadeHeight(boolean material3) {
        return AndroidUtilities.dp(material3 ? 78.0f : 48.0f);
    }

    public static int getChatTopFadeZone(int actionBarHeight, boolean material3) {
        return material3 ? actionBarHeight + AndroidUtilities.dp(42.0f) : actionBarHeight;
    }

    /** Left radius of the glass middle pill so it follows the header avatar shape (12.10.6). */
    public static float getGlassPillLeftRadius(float maxRadius, boolean forum) {
        final boolean material3 = isMaterial3ChatHeaderStyle();
        final int avatarSizePx = getAvatarSizePx(getChatAvatarSizeDp());
        final float extra = material3 ? AndroidUtilities.dp(3.33f) : (AndroidUtilities.dp(46.0f) - avatarSizePx) / 2.0f;
        return Math.min(maxRadius, getAvatarRadius(getChatAvatarSizeDp(), forum) + extra);
    }

    /** Avatar -> profile transition parameters captured from the chat header (used by ProfileActivity in exteraGram). */
    public static final class ProfileTransitionState {
        private float avatarTranslation;
        private float avatarStartY = Float.NaN;
        private float avatarSizeDp = 42.0f;
        private float nameTranslationX = Float.NaN;
        private float nameTranslationY = Float.NaN;
        private float onlineTranslationX = Float.NaN;
        private float onlineTranslationY = Float.NaN;

        public void reset() {
            avatarTranslation = 0.0f;
            avatarStartY = Float.NaN;
            avatarSizeDp = 42.0f;
            nameTranslationX = Float.NaN;
            nameTranslationY = Float.NaN;
            onlineTranslationX = Float.NaN;
            onlineTranslationY = Float.NaN;
        }

        public void capture(ChatAvatarContainer container, ViewGroup parent, View nameView, View onlineView) {
            if (container == null || parent == null) {
                return;
            }
            final BackupImageView avatar = container.getAvatarImageView();
            if (avatar == null) {
                return;
            }
            avatarTranslation = ViewPositionWatcher.computeXCoordinateInParent(avatar, parent);
            avatarStartY = ViewPositionWatcher.computeYCoordinateInParent(avatar, parent);
            final int width = avatar.getMeasuredWidth() != 0 ? avatar.getMeasuredWidth() : avatar.getWidth();
            if (width > 0) {
                avatarSizeDp = width / AndroidUtilities.density;
            }
            if (container.getTitleTextView() != null && nameView != null) {
                nameTranslationX = ViewPositionWatcher.computeXCoordinateInParent(container.getTitleTextView(), parent) - getTransitionLayoutLeft(nameView);
                nameTranslationY = ViewPositionWatcher.computeYCoordinateInParent(container.getTitleTextView(), parent) - getTransitionLayoutTop(nameView);
            }
            final View subtitle = container.getSubtitleTextView();
            if (subtitle == null || onlineView == null) {
                return;
            }
            onlineTranslationX = ViewPositionWatcher.computeXCoordinateInParent(subtitle, parent) - getTransitionLayoutLeft(onlineView);
            onlineTranslationY = ViewPositionWatcher.computeYCoordinateInParent(subtitle, parent) - getTransitionLayoutTop(onlineView);
        }

        public float getAvatarTranslation() {
            return avatarTranslation;
        }

        public float getAvatarStartY(ActionBar actionBar) {
            if (!Float.isNaN(avatarStartY)) {
                return avatarStartY;
            }
            return (actionBar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0) + ActionBar.getCurrentActionBarHeight() / 2.0f - AndroidUtilities.dp(avatarSizeDp / 2.0f) + actionBar.getTranslationY();
        }

        public float getAvatarStartScale() {
            return avatarSizeDp / 100.0f;
        }

        public float getAvatarSizeDp() {
            return avatarSizeDp;
        }

        public float getNameTranslationX() {
            if (!Float.isNaN(nameTranslationX)) {
                return nameTranslationX;
            }
            return avatarTranslation - AndroidUtilities.dp(109.0f) + AndroidUtilities.dp(avatarSizeDp + 6.0f);
        }

        public float getNameTranslationY(ActionBar actionBar) {
            if (!Float.isNaN(nameTranslationY)) {
                return nameTranslationY;
            }
            return (float) Math.floor(getAvatarStartY(actionBar)) + AndroidUtilities.dp(isMaterial3ChatHeaderStyle() ? 1.66f : 1.3f);
        }

        public float getOnlineTranslationX() {
            if (!Float.isNaN(onlineTranslationX)) {
                return onlineTranslationX;
            }
            return getNameTranslationX();
        }

        public float getOnlineTranslationY(ActionBar actionBar) {
            if (!Float.isNaN(onlineTranslationY)) {
                return onlineTranslationY;
            }
            return (float) Math.floor(getAvatarStartY(actionBar)) + AndroidUtilities.dp(isMaterial3ChatHeaderStyle() ? 26.66f : 24.0f);
        }

        public void updateBadgePositionsFromCollapsedAvatar(View avatar, float scale, ImageView b1, ImageView b2, ImageView b3, ImageView b4) {
            final int size = AndroidUtilities.dp(avatarSizeDp);
            updateBadgePositions(avatar, size * ((scale * 100.0f) / avatarSizeDp) - size, b1, b2, b3, b4);
        }

        public void updateBadgePositionsFromExpandedAvatar(View avatar, float scale, ImageView b1, ImageView b2, ImageView b3, ImageView b4) {
            updateBadgePositions(avatar, (avatar.getMeasuredWidth() - AndroidUtilities.dp(avatarSizeDp)) * ((scale * 100.0f) / avatarSizeDp), b1, b2, b3, b4);
        }

        private void updateBadgePositions(View avatar, float offset, ImageView b1, ImageView b2, ImageView b3, ImageView b4) {
            if (b1 != null) {
                b1.setTranslationX(avatar.getX() + AndroidUtilities.dp(avatarSizeDp - 26.0f) + offset);
                b1.setTranslationY(avatar.getY() + AndroidUtilities.dp(-10.0f) + offset);
            }
            if (b2 != null) {
                b2.setTranslationX(avatar.getX() + AndroidUtilities.dp(avatarSizeDp - 14.0f) + offset);
                b2.setTranslationY(avatar.getY() + AndroidUtilities.dp(avatarSizeDp - 15.5f) + offset);
            }
            if (b3 != null) {
                b3.setTranslationX(avatar.getX() + AndroidUtilities.dp(avatarSizeDp - 14.0f) + offset);
                b3.setTranslationY(avatar.getY() + AndroidUtilities.dp(avatarSizeDp - 18.0f) + offset);
            }
            if (b4 != null) {
                b4.setTranslationX(avatar.getX() + AndroidUtilities.dp(avatarSizeDp - 14.0f) + offset);
                b4.setTranslationY(avatar.getY() + AndroidUtilities.dp(avatarSizeDp - 18.0f) + offset);
            }
        }

        private static int getTransitionLayoutLeft(View view) {
            final ViewGroup.LayoutParams lp = view.getLayoutParams();
            return lp instanceof ViewGroup.MarginLayoutParams ? ((ViewGroup.MarginLayoutParams) lp).leftMargin : view.getLeft();
        }

        private static int getTransitionLayoutTop(View view) {
            final ViewGroup.LayoutParams lp = view.getLayoutParams();
            return lp instanceof ViewGroup.MarginLayoutParams ? ((ViewGroup.MarginLayoutParams) lp).topMargin : view.getTop();
        }
    }
}
