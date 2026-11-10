package org.telegram.ui.Wallet;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.style.ReplacementSpan;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.Components.AnimatedEmojiDrawable;

/**
 * StaticGram: the GRAM sign in money text as the GRAM custom emoji (document 6007983438294949171),
 * drawn through AnimatedEmojiDrawable (loaded on demand, frames invalidate the host view). Until
 * the document is loaded the flat wallet_gram_small sign stands in. Both are painted in the text
 * colour of the line (a button's text colour in «Купить за»), the emoji being a one-colour template.
 */
public class SGGramIconSpan extends ReplacementSpan {

    public static final long GRAM_EMOJI_DOCUMENT_ID = 6007983438294949171L;

    private final AnimatedEmojiDrawable emoji;
    private final Drawable fallback;
    private final int size;
    private final boolean tint;
    private int lastColor;
    private PorterDuffColorFilter filter;

    private static final java.util.WeakHashMap<View, SGGramIconSpan> tinted = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<View, SGGramIconSpan> plain = new java.util.WeakHashMap<>();

    /** One span per host view (texts are rebound often; the host gets one attach listener). */
    public static SGGramIconSpan of(@Nullable View host, int sizePx, boolean tint) {
        if (host == null) return new SGGramIconSpan(null, sizePx, tint);
        final java.util.WeakHashMap<View, SGGramIconSpan> cache = tint ? tinted : plain;
        SGGramIconSpan span = cache.get(host);
        if (span == null || span.size != sizePx) {
            span = new SGGramIconSpan(host, sizePx, tint);
            cache.put(host, span);
        }
        return span;
    }

    public SGGramIconSpan(@Nullable View host, int sizePx, boolean tint) {
        this.size = sizePx;
        // The GRAM emoji is a one-colour template: it is always painted in the text colour (black on
        // dark rows otherwise), in rows as in buttons, in both themes.
        this.tint = true;
        emoji = AnimatedEmojiDrawable.make(UserConfig.selectedAccount, AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES, GRAM_EMOJI_DOCUMENT_ID);
        Drawable d = null;
        try {
            d = ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.wallet_gram_small);
            if (d != null) d = d.mutate();
        } catch (Exception ignore) {
        }
        fallback = d;
        if (host != null) attach(host);
    }

    /** Ties the emoji to the view that draws this text: it loads and animates while that view is attached. */
    private void attach(View host) {
        if (host.isAttachedToWindow()) emoji.addView(host);
        host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View v) {
                emoji.addView(v);
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View v) {
                emoji.removeView(v);
            }
        });
    }

    private boolean emojiReady() {
        final ImageReceiver receiver = emoji.getImageReceiver();
        return receiver != null && receiver.hasBitmapImage();
    }

    @Override
    public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm) {
        if (fm != null) {
            final Paint.FontMetricsInt p = paint.getFontMetricsInt();
            fm.ascent = p.ascent;
            fm.descent = p.descent;
            fm.top = p.top;
            fm.bottom = p.bottom;
        }
        return size;
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, @NonNull Paint paint) {
        final Paint.FontMetricsInt fm = paint.getFontMetricsInt();
        final int cy = y + (fm.ascent + fm.descent) / 2;
        final int left = (int) x, t = cy - size / 2;
        final int color = paint.getColor();
        if (tint && (filter == null || color != lastColor)) {
            lastColor = color;
            filter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
        final Drawable d = emojiReady() || fallback == null ? emoji : fallback;
        d.setBounds(left, t, left + size, t + size);
        // The emoji drawable is shared (AnimatedEmojiDrawable.make caches it): set or clear the filter every time.
        if (tint) d.setColorFilter(filter);
        else d.setColorFilter(d == fallback ? new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) : null);
        d.setAlpha(paint.getAlpha());
        d.draw(canvas);
    }
}
