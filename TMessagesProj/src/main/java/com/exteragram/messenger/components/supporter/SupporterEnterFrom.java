package com.exteragram.messenger.components.supporter;

import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.AnimatedEmojiDrawable;

/**
 * exteraGram 13.1.1 SupporterEnterFrom: where the tapped badge is on screen, so the supporter sheet can
 * fly it into its header (and the landing animation can fly it back into the name).
 * StaticGram: our badge sits in the profile name's rightDrawable2 (StaticGram drawable order) and in
 * ChatMessageCell.currentNameBadgeDrawable; their centre is taken from the bounds set when drawn.
 */
public final class SupporterEnterFrom {

    public final Drawable drawable;
    public final float scale;
    public final float screenX;
    public final float screenY;
    public final View view;

    private SupporterEnterFrom(View view, Drawable drawable, float x, float y) {
        this.view = view;
        this.drawable = drawable;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        this.scale = view.getScaleX();
        this.screenX = location[0] + x * scale;
        this.screenY = location[1] + y * view.getScaleY();
    }

    /** exteraGram: the emoji status / badge slot of a message name. StaticGram: the badge drawable of the name. */
    public static SupporterEnterFrom of(ChatMessageCell cell) {
        if (cell == null) {
            return null;
        }
        AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable badge = cell.currentNameBadgeDrawable;
        if (badge != null && !badge.getBounds().isEmpty()) {
            Rect bounds = badge.getBounds();
            return new SupporterEnterFrom(cell, badge, bounds.centerX(), bounds.centerY());
        }
        AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable status = cell.currentNameStatusDrawable;
        if (status == null) {
            return null;
        }
        return new SupporterEnterFrom(cell, status, cell.getNameStatusX(), cell.getNameStatusY());
    }

    /** exteraGram: the secondary (emoji status) slot of a message name, when it is drawn. */
    public static SupporterEnterFrom ofSecondary(ChatMessageCell cell) {
        if (cell == null) {
            return null;
        }
        AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable drawable = cell.currentNameBadgeDrawable;
        if (drawable == null || drawable.getBounds().isEmpty()) {
            drawable = cell.currentNameEmojiStatusDrawable;
        }
        if (drawable == null || drawable.getBounds().isEmpty()) {
            return null;
        }
        return new SupporterEnterFrom(cell, drawable, drawable.getBounds().centerX(), drawable.getBounds().centerY());
    }

    public static SupporterEnterFrom of(SimpleTextView textView, Drawable drawable) {
        if (textView == null || drawable == null) {
            return null;
        }
        if (textView.getRightDrawable() == drawable) {
            return new SupporterEnterFrom(textView, drawable, textView.rightDrawableX, textView.rightDrawableY);
        }
        if ((textView.getRightDrawable2() == drawable || textView.getRightDrawable3() == drawable) && !drawable.getBounds().isEmpty()) {
            Rect bounds = drawable.getBounds();
            return new SupporterEnterFrom(textView, drawable, bounds.centerX(), bounds.centerY());
        }
        return null;
    }

    public void invalidate() {
        if (view instanceof ChatMessageCell) {
            ((ChatMessageCell) view).invalidateOutbounds();
        } else {
            view.invalidate();
        }
    }
}
