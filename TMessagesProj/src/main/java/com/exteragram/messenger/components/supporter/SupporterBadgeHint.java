package com.exteragram.messenger.components.supporter;

import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.widget.FrameLayout;

import androidx.core.graphics.ColorUtils;

import com.exteragram.messenger.badges.BadgesController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.PeerColorActivity;
import org.telegram.ui.Stories.recorder.HintView2;

/**
 * exteraGram 13.1.1 SupporterBadgeHint: the «Arrow Badge #N» / «Badge #N» bubble above the profile name
 * pointing at the badge (like the collectible gift hint). Shown for any profile whose badge has a number.
 */
public class SupporterBadgeHint {

    private int appliedTextColor;
    private int backgroundColor;
    private boolean customBadge;
    private boolean held;
    private final Runnable hideHint = this::hide;
    private HintView2 hint;
    private Boolean hintVisible;
    private Integer number;
    private int textColor;
    private long userId;

    public static boolean hasNumber(TLRPC.User user) {
        return BadgesController.INSTANCE.hasBadge(user) && BadgesController.getNumber(user) != null;
    }

    public void set(FrameLayout container, int account, TLRPC.User user, Runnable onClick) {
        long id = user != null ? user.id : 0;
        Integer newNumber = user != null && hasNumber(user) ? BadgesController.getNumber(user) : null;
        boolean custom = BadgesController.INSTANCE.getCustomBadgeId(user) != 0;
        int background = resolveBackgroundColor(account, user);
        if (id == userId && custom == customBadge && background == backgroundColor && (newNumber == null ? number == null : newNumber.equals(number))) {
            return;
        }
        userId = id;
        number = newNumber;
        customBadge = custom;
        backgroundColor = background;
        final HintView2 old = hint;
        if (old != null) {
            old.setOnHiddenListener(() -> AndroidUtilities.removeFromParent(old));
            old.hide();
            hint = null;
        }
        if (container == null || newNumber == null) {
            return;
        }
        textColor = resolveTextColor(backgroundColor);
        hintVisible = null;
        hint = new HintView2(container.getContext(), HintView2.DIRECTION_BOTTOM);
        hint.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), AndroidUtilities.dp(2));
        hint.setFlicker(0.66f, Theme.multAlpha(textColor, 0.5f));
        container.addView(hint, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 24));
        hint.setTextSize(9.33f);
        hint.setTextTypeface(AndroidUtilities.bold());
        hint.setTextColor(appliedTextColor = textColor);
        hint.setText(LocaleController.formatString(customBadge ? R.string.SupporterBadgeNameCustom : R.string.SupporterBadgeName, BadgesController.formatNumber(newNumber)));
        hint.setDuration(-1);
        hint.setInnerPadding(5.66f, 2.66f, 5.66f, 2.66f);
        hint.setArrowSize(4, 2.66f);
        hint.setRoundingWithCornerEffect(false);
        hint.setRounding(16);
        hint.setBgColor(backgroundColor);
        if (onClick != null) {
            hint.setOnClickListener(v -> onClick.run());
        }
        if (!held) {
            showHint(hint);
        }
    }

    private void showHint(HintView2 view) {
        view.show();
        AndroidUtilities.cancelRunOnUIThread(hideHint);
        AndroidUtilities.runOnUIThread(hideHint, 6000);
    }

    /** Hides the hint while the badge lands into the name (SupporterBadgeLanding). */
    public void hold() {
        held = true;
        AndroidUtilities.cancelRunOnUIThread(hideHint);
        if (hint != null) {
            hint.hide(false);
        }
    }

    public void release() {
        if (held) {
            held = false;
            if (hint != null) {
                showHint(hint);
            }
        }
    }

    /** Follows the badge drawable of the name (called from ProfileActivity.updateCollectibleHint). */
    public void update(SimpleTextView nameView, Drawable badge, float expanded, boolean visible) {
        if (hint == null || nameView == null || badge == null) {
            return;
        }
        int x;
        if (nameView.getRightDrawable() == badge) {
            x = nameView.getRightDrawableX();
        } else if (nameView.getRightDrawable2() == badge || nameView.getRightDrawable3() == badge) {
            Rect bounds = badge.getBounds();
            x = bounds.isEmpty() ? 0 : bounds.centerX();
            if (bounds.isEmpty()) {
                visible = false;
            }
        } else {
            x = 0;
            visible = false;
        }
        visible &= x >= 0 && x <= nameView.getWidth();
        hint.setJointPx(0, -hint.getPaddingLeft() + nameView.getX() + x * nameView.getScaleX());
        hint.setTranslationY(-hint.getPaddingBottom() + nameView.getY() - AndroidUtilities.dp(24) + AndroidUtilities.lerp(AndroidUtilities.dp(6), -AndroidUtilities.dp(12), expanded));
        hint.setBgColor(ColorUtils.blendARGB(backgroundColor, 0x50000000, expanded));
        int color = ColorUtils.blendARGB(textColor, 0xFFFFFFFF, expanded);
        if (appliedTextColor != color) {
            hint.setTextColor(appliedTextColor = color);
            hint.invalidate();
        }
        if (hintVisible == null || hintVisible != visible) {
            hintVisible = visible;
            hint.animate().alpha(visible ? 1.0f : 0.0f).setInterpolator(CubicBezierInterpolator.EASE_OUT).setDuration(200).start();
        }
    }

    public void hide() {
        if (hint != null) {
            hint.hide(true);
        }
    }

    public static int resolveTextColor(int background) {
        return AndroidUtilities.computePerceivedBrightness(background) > 0.721f ? 0xFF000000 : 0xFFFFFFFF;
    }

    public static int resolveBackgroundColor(int account, TLRPC.User user) {
        if (user != null && user.emoji_status instanceof TLRPC.TL_emojiStatusCollectible) {
            TLRPC.TL_emojiStatusCollectible status = (TLRPC.TL_emojiStatusCollectible) user.emoji_status;
            return Theme.blendOver(status.center_color | 0xFF000000, Theme.multAlpha(status.pattern_color | 0xFF000000, 0.5f));
        }
        MessagesController.PeerColors peerColors = MessagesController.getInstance(account).profilePeerColors;
        MessagesController.PeerColor color = peerColors == null ? null : peerColors.getColor(UserObject.getProfileColorId(user));
        if (color != null) {
            int bgColor1 = color.getBgColor1(Theme.isCurrentThemeDark());
            int pattern = color.patternColor;
            if (pattern == 0) {
                pattern = PeerColorActivity.adaptProfileEmojiColor(bgColor1);
            }
            return Theme.blendOver(bgColor1, Theme.multAlpha(pattern | 0xFF000000, 0.5f));
        }
        return Theme.blendOver(Theme.getColor(Theme.key_actionBarDefault), Theme.multAlpha(Theme.getColor(Theme.key_actionBarDefaultTitle), 0.15f));
    }
}
