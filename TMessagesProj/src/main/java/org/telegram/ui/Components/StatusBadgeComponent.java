package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Premium.PremiumGradient;

public class StatusBadgeComponent {

    private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable statusDrawable;
    private Drawable verifiedDrawable;
    // exteraGram: with a badge the component returns a row: [emoji status / premium star] [badge] [verified]
    private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable badgeDrawable;
    private RowDrawable rowDrawable = new RowDrawable();
    private Drawable currentDrawable;

    public StatusBadgeComponent(View parentView) {
        this(parentView, 18);
    }

    public StatusBadgeComponent(View parentView, int sizeDp) {
        statusDrawable = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(parentView, AndroidUtilities.dp(sizeDp));
        badgeDrawable = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(parentView, AndroidUtilities.dp(sizeDp));
        currentDrawable = statusDrawable;
    }

    public Drawable updateDrawable(TLObject object, int colorFilter, boolean animated) {
        if (object instanceof TLRPC.User) {
            return updateDrawable((TLRPC.User) object, null, colorFilter, animated);
        } else if (object instanceof TLRPC.Chat) {
            return updateDrawable(null, (TLRPC.Chat) object, colorFilter, animated);
        }
        return updateDrawable(null, null, colorFilter, animated);
    }

    public Drawable updateDrawable(TLRPC.User user, TLRPC.Chat chat, int colorFilter, boolean animated) {
        com.exteragram.messenger.api.dto.BadgeDTO badge = com.exteragram.messenger.badges.BadgesController.INSTANCE.getBadgeOrMark(user != null ? user : chat);
        if (badge != null) {
            return currentDrawable = updateBadgeRow(user, chat, badge, colorFilter, animated);
        }
        badgeDrawable.set((Drawable) null, animated);
        currentDrawable = statusDrawable;
        if (chat != null && chat.verified) {
            statusDrawable.set(verifiedDrawable = (verifiedDrawable == null ? new CombinedDrawable(Theme.dialogs_verifiedDrawable, Theme.dialogs_verifiedCheckDrawable) : verifiedDrawable), animated);
            statusDrawable.setColor(null);
        } else if (chat != null && DialogObject.getEmojiStatusDocumentId(chat.emoji_status) != 0) {
            statusDrawable.set(DialogObject.getEmojiStatusDocumentId(chat.emoji_status), animated);
            statusDrawable.setColor(colorFilter);
        } else if (user != null && user.verified) {
            statusDrawable.set(verifiedDrawable = (verifiedDrawable == null ? new CombinedDrawable(Theme.dialogs_verifiedDrawable, Theme.dialogs_verifiedCheckDrawable) : verifiedDrawable), animated);
            statusDrawable.setColor(null);
        } else if (user != null && DialogObject.getEmojiStatusDocumentId(user.emoji_status) != 0) {
            statusDrawable.set(DialogObject.getEmojiStatusDocumentId(user.emoji_status), animated);
            statusDrawable.setColor(colorFilter);
        } else if (user != null && user.premium) {
            statusDrawable.set(PremiumGradient.getInstance().premiumStarDrawableMini, animated);
            statusDrawable.setColor(colorFilter);
        } else {
            statusDrawable.set((Drawable) null, animated);
            statusDrawable.setColor(null);
        }
        return statusDrawable;
    }

    /** exteraGram: name [emoji status / premium star] [badge] [verified] - nothing is dropped. */
    private Drawable updateBadgeRow(TLRPC.User user, TLRPC.Chat chat, com.exteragram.messenger.api.dto.BadgeDTO badge, int colorFilter, boolean animated) {
        final boolean verified = chat != null ? chat.verified : user != null && user.verified;
        final long statusId = chat != null ? DialogObject.getEmojiStatusDocumentId(chat.emoji_status) : user != null ? DialogObject.getEmojiStatusDocumentId(user.emoji_status) : 0;
        Drawable status = null;
        if (statusId != 0) {
            statusDrawable.set(statusId, animated);
            statusDrawable.setColor(colorFilter);
            status = statusDrawable;
        } else if (chat == null && user != null && user.premium) {
            statusDrawable.set(PremiumGradient.getInstance().premiumStarDrawableMini, animated);
            statusDrawable.setColor(colorFilter);
            status = statusDrawable;
        } else {
            statusDrawable.set((Drawable) null, animated);
            statusDrawable.setColor(null);
        }
        badgeDrawable.set(badge.getDocumentId(), animated);
        badgeDrawable.setParticles(true, animated);
        badgeDrawable.setColor(colorFilter);
        if (verified && verifiedDrawable == null) {
            verifiedDrawable = new CombinedDrawable(Theme.dialogs_verifiedDrawable, Theme.dialogs_verifiedCheckDrawable);
        }
        final Drawable last = verified ? verifiedDrawable : null;
        if (!rowDrawable.holds(status, badgeDrawable, last)) {
            // a new instance, so a SimpleTextView re-measures the name for the changed width
            rowDrawable.set(null, null, null);
            rowDrawable = new RowDrawable();
        }
        rowDrawable.set(status, badgeDrawable, last);
        return rowDrawable;
    }

    public Drawable getDrawable() {
        return currentDrawable;
    }

    public void onAttachedToWindow() {
        statusDrawable.attach();
        badgeDrawable.attach();
    }

    public void onDetachedFromWindow() {
        statusDrawable.detach();
        badgeDrawable.detach();
    }

    /** Draws up to three drawables in a row, vertically centred, 4dp apart. */
    private static class RowDrawable extends Drawable implements Drawable.Callback {
        private final Drawable[] items = new Drawable[3];
        private final int gap = AndroidUtilities.dp(4);

        boolean holds(Drawable a, Drawable b, Drawable c) {
            return items[0] == a && items[1] == b && items[2] == c;
        }

        void set(Drawable a, Drawable b, Drawable c) {
            final Drawable[] next = { a, b, c };
            for (int i = 0; i < 3; i++) {
                if (items[i] != next[i]) {
                    if (items[i] != null && items[i].getCallback() == this) {
                        items[i].setCallback(null);
                    }
                    items[i] = next[i];
                    if (items[i] != null) {
                        items[i].setCallback(this);
                    }
                }
            }
            invalidateSelf();
        }

        @Override
        public int getIntrinsicWidth() {
            int w = 0;
            for (Drawable d : items) {
                if (d != null) {
                    w += (w > 0 ? gap : 0) + d.getIntrinsicWidth();
                }
            }
            return w;
        }

        @Override
        public int getIntrinsicHeight() {
            int h = 0;
            for (Drawable d : items) {
                if (d != null) {
                    h = Math.max(h, d.getIntrinsicHeight());
                }
            }
            return h;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            final android.graphics.Rect b = getBounds();
            int x = b.left;
            for (Drawable d : items) {
                if (d == null) {
                    continue;
                }
                final int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
                final int y = b.centerY() - h / 2;
                d.setBounds(x, y, x + w, y + h);
                d.draw(canvas);
                x += w + gap;
            }
        }

        @Override
        public void setAlpha(int alpha) {
            for (Drawable d : items) {
                if (d != null) {
                    d.setAlpha(alpha);
                }
            }
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSPARENT;
        }

        @Override
        public void invalidateDrawable(@NonNull Drawable who) {
            invalidateSelf();
        }

        @Override
        public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
            scheduleSelf(what, when);
        }

        @Override
        public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
            unscheduleSelf(what);
        }
    }
}
