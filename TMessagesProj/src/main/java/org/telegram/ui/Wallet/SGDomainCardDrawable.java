package org.telegram.ui.Wallet;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

/**
 * StaticGram: a local vector render of the .ton domain card, matching the server's flat red card
 * (gramsrv collectibles/metadata drawCard + domainTheme, 1000×1000 units): the #F55530→#E23E20
 * diagonal gradient (t = 0.4x + 0.6y), the white StaticGram mark on a translucent white rounded
 * square (320 units at y 150, radius 84) with the blue TON disc in its corner, the flat white pill
 * centred at y 640 with the name in #DC381A (96 → 40 units, fitted to 820) and the
 * ".ton домен StaticGram" caption. No 3D, shadows or highlights. Being vector, it stays crisp at
 * any size; it is the placeholder while the server card loads and the card of unminted domains.
 */
public class SGDomainCardDrawable extends Drawable {

    private static final float UNITS = 1000f;
    private static final float ICON_SIZE = 320, ICON_RADIUS = 84, ICON_Y = 150;
    private static final float PILL_CY = 640, PILL_MAX_W = 900, PILL_MAX_EM = 96, PILL_MIN_EM = 40, CAPTION_GAP = 70;
    private static final String CAPTION = ".ton домен StaticGram";
    private static final int TON_BLUE = 0xFF0098EA;

    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blue = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint caption = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final Path clip = new Path();
    private Drawable logo;
    private String name;
    private float radiusFraction = .08f;
    private boolean showCaption = true;
    private int alpha = 255;

    public SGDomainCardDrawable(String name) {
        this.name = SGTonDns.fullName(name == null ? "" : name);
        iconPaint.setColor(0x2EFFFFFF); // white 0.18
        white.setColor(0xFFFFFFFF);
        blue.setColor(TON_BLUE);
        text.setColor(0xFFDC381A);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(AndroidUtilities.bold());
        caption.setColor(0xBDFFFFFF); // white 0.74
        caption.setTextAlign(Paint.Align.CENTER);
        caption.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        try {
            logo = ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.staticgram_logo_mono);
            if (logo != null) {
                logo = logo.mutate();
                logo.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            }
        } catch (Exception ignore) {
            logo = null;
        }
    }

    public void setName(String name) {
        final String full = SGTonDns.fullName(name == null ? "" : name);
        if (full.equals(this.name)) return;
        this.name = full;
        invalidateSelf();
    }

    public String getName() {
        return name;
    }

    /** Corner radius as a fraction of the card side (0 = square, as the server PNG). */
    public SGDomainCardDrawable setRadiusFraction(float fraction) {
        radiusFraction = fraction;
        invalidateSelf();
        return this;
    }

    /** The ".ton домен StaticGram" line under the pill (on by default, as on the server card). */
    public SGDomainCardDrawable setShowCaption(boolean show) {
        showCaption = show;
        invalidateSelf();
        return this;
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        final float s = Math.min(bounds.width(), bounds.height());
        // t = (0.4x + 0.6y) / s: a linear gradient along (0.4, 0.6)·k with k = s / 0.52
        final float k = s / .52f;
        final float left = bounds.left + (bounds.width() - s) / 2f, top = bounds.top + (bounds.height() - s) / 2f;
        background.setShader(new LinearGradient(left, top, left + .4f * k, top + .6f * k,
                0xFFF55530, 0xFFE23E20, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        final Rect b = getBounds();
        final float w = b.width(), h = b.height();
        if (w <= 0 || h <= 0) return;
        final float s = Math.min(w, h);
        final float u = s / UNITS;
        final float left = b.left + (w - s) / 2f, top = b.top + (h - s) / 2f;

        canvas.save();
        rect.set(left, top, left + s, top + s);
        final float r = s * radiusFraction;
        if (r > 0) {
            clip.rewind();
            clip.addRoundRect(rect, r, r, Path.Direction.CW);
            canvas.clipPath(clip);
        }
        canvas.drawRect(rect, background);

        // the app icon: translucent white rounded square, the white StaticGram mark
        final float ix = left + (UNITS - ICON_SIZE) / 2f * u, iy = top + ICON_Y * u, is = ICON_SIZE * u;
        rect.set(ix, iy, ix + is, iy + is);
        canvas.drawRoundRect(rect, ICON_RADIUS * u, ICON_RADIUS * u, iconPaint);
        if (logo != null && logo.getIntrinsicWidth() > 0 && logo.getIntrinsicHeight() > 0) {
            final float box = is * .74f;
            final float aspect = logo.getIntrinsicWidth() / (float) logo.getIntrinsicHeight();
            final float lw = aspect >= 1 ? box : box * aspect, lh = aspect >= 1 ? box / aspect : box;
            final float cx = ix + is / 2f, cy = iy + is / 2f;
            logo.setBounds((int) (cx - lw / 2f), (int) (cy - lh / 2f), (int) (cx + lw / 2f), (int) (cy + lh / 2f));
            logo.setAlpha(alpha);
            logo.draw(canvas);
        }
        // the TON disc in the icon's corner
        final float tcx = ix + is - 14 * u, tcy = iy + is - 14 * u, trad = 64 * u;
        canvas.drawCircle(tcx, tcy, trad + 8 * u, white);
        canvas.drawCircle(tcx, tcy, trad, blue);
        drawTonMark(canvas, tcx - trad, tcy - trad, 2 * trad);

        // the flat white pill with the name in the card's red, auto-fitted
        final float padMin = 40;
        final float maxText = PILL_MAX_W - 2 * padMin;
        float size = PILL_MAX_EM;
        text.setTextSize(size * u);
        float tw = text.measureText(name) / u;
        while (tw > maxText && size > PILL_MIN_EM) {
            size -= 2;
            text.setTextSize(size * u);
            tw = text.measureText(name) / u;
        }
        String label = name;
        if (tw > maxText) {
            label = TextUtils.ellipsize(name, text, maxText * u, TextUtils.TruncateAt.END).toString();
            tw = text.measureText(label) / u;
        }
        final float padX = Math.max(padMin, Math.round(size * .62f));
        final float pillH = Math.round(size * 1.62f);
        final float pillW = Math.min(tw + 2 * padX, PILL_MAX_W);
        final float py = PILL_CY - pillH / 2f;
        rect.set(left + (UNITS - pillW) / 2f * u, top + py * u, left + (UNITS + pillW) / 2f * u, top + (py + pillH) * u);
        canvas.drawRoundRect(rect, pillH / 2f * u, pillH / 2f * u, white);
        final Paint.FontMetrics fm = text.getFontMetrics();
        canvas.drawText(label, rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2f, text);

        if (showCaption) {
            float cs = 44;
            caption.setTextSize(cs * u);
            while (caption.measureText(CAPTION) / u > UNITS - 140 && cs > 28) {
                cs -= 2;
                caption.setTextSize(cs * u);
            }
            final Paint.FontMetrics cfm = caption.getFontMetrics();
            final float cy = top + (py + pillH + CAPTION_GAP) * u;
            canvas.drawText(CAPTION, left + s / 2f, cy - (cfm.ascent + cfm.descent) / 2f, caption);
        }
        canvas.restore();
    }

    /** The TON symbol from its 56×56 logo (gramsrv metadata.tonMark), white with blue holes. */
    private void drawTonMark(Canvas canvas, float x, float y, float size) {
        final float k = size / 56f;
        path.rewind();
        path.moveTo(x + 37.5603f * k, y + 15.6277f * k);
        path.lineTo(x + 18.4386f * k, y + 15.6277f * k);
        path.cubicTo(x + 14.9228f * k, y + 15.6277f * k, x + 12.6944f * k, y + 19.4202f * k, x + 14.4632f * k, y + 22.4861f * k);
        path.lineTo(x + 26.2644f * k, y + 42.9409f * k);
        path.cubicTo(x + 27.0345f * k, y + 44.2765f * k, x + 28.9644f * k, y + 44.2765f * k, x + 29.7345f * k, y + 42.9409f * k);
        path.lineTo(x + 41.5381f * k, y + 22.4861f * k);
        path.cubicTo(x + 43.3045f * k, y + 19.4251f * k, x + 41.0761f * k, y + 15.6277f * k, x + 37.5627f * k, y + 15.6277f * k);
        path.close();
        canvas.drawPath(path, white);
        path.rewind();
        path.moveTo(x + 26.2524f * k, y + 36.8092f * k);
        path.lineTo(x + 23.6847f * k, y + 31.8327f * k);
        path.lineTo(x + 17.4833f * k, y + 20.7414f * k);
        path.cubicTo(x + 17.0742f * k, y + 20.0315f * k, x + 17.5795f * k, y + 19.1218f * k, x + 18.4362f * k, y + 19.1218f * k);
        path.lineTo(x + 26.2524f * k, y + 19.1218f * k);
        path.close();
        path.moveTo(x + 29.7417f * k, y + 36.8068f * k);
        path.lineTo(x + 32.3118f * k, y + 31.8351f * k);
        path.lineTo(x + 38.5108f * k, y + 20.739f * k);
        path.cubicTo(x + 38.9199f * k, y + 20.0291f * k, x + 38.4146f * k, y + 19.1194f * k, x + 37.5579f * k, y + 19.1194f * k);
        path.lineTo(x + 29.7417f * k, y + 19.1194f * k);
        path.close();
        canvas.drawPath(path, blue);
    }

    @Override
    public void setAlpha(int alpha) {
        if (this.alpha == alpha) return;
        this.alpha = alpha;
        background.setAlpha(alpha);
        iconPaint.setAlpha((int) (0x2E * alpha / 255f));
        white.setAlpha(alpha);
        blue.setAlpha(alpha);
        text.setAlpha(alpha);
        caption.setAlpha((int) (0xBD * alpha / 255f));
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return AndroidUtilities.dp(220);
    }

    @Override
    public int getIntrinsicHeight() {
        return AndroidUtilities.dp(220);
    }
}
