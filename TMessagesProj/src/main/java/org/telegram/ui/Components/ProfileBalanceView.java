package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Wallet.WalletCardComponent;

/**
 * Telegram 13.1.1 profile balance pill, next to the music pill. StaticGram shows both card
 * balances: GRAM and Stars. Tapping it opens the card sheet (SGCardSheet).
 */
public class ProfileBalanceView extends ProfileMusicView {
    private final Drawable gramIcon;
    private final Drawable gramColorIcon;
    private final Drawable starIcon;
    private Text gramAmount, starsAmount;
    private final Paint chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path chevron = new Path();
    private float ejectProgress;

    public ProfileBalanceView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider);
        gramIcon = context.getResources().getDrawable(R.drawable.profile_balance_gram).mutate();
        gramColorIcon = context.getResources().getDrawable(R.drawable.profile_balance_gram_color).mutate();
        starIcon = context.getResources().getDrawable(R.drawable.star_small_inner).mutate();
        chevronPaint.setStyle(Paint.Style.STROKE);
        chevronPaint.setStrokeCap(Paint.Cap.ROUND);
        chevronPaint.setStrokeJoin(Paint.Join.ROUND);
        chevronPaint.setStrokeWidth(dpf2(1.16f));
        chevron.moveTo(0, -dpf2(3.33f));
        chevron.lineTo(dpf2(3.16f), 0);
        chevron.lineTo(0, dpf2(3.33f));
        setBalance(0, 0);
    }

    /** GRAM in nanoton, Stars as is. */
    public void setBalance(long nanograms, long stars) {
        final String gram = WalletCardComponent.formatGrams(nanograms);
        final String star = WalletCardComponent.formatStars(stars);
        gramAmount = new Text(gram, 11, AndroidUtilities.bold());
        starsAmount = new Text(star, 11, AndroidUtilities.bold());
        setContentDescription(getString(R.string.StarsBalance) + ": " + gram + " GRAM, " + star + " Stars");
        invalidate();
    }

    /** Room after an amount for its chevron, the end padding of a pill and the gap between pills. */
    private static final int CHEVRON_ROOM = 9, PILL_PAD = 8, GAP = 6;

    /** Content-relative x where the GRAM pill ends (its right padding included). */
    private float gramPillEnd() {
        return dp(13 + 2) + gramAmount.getWidth() + dp(CHEVRON_ROOM) + dp(PILL_PAD);
    }

    @Override
    protected void buildPillPath(Path path, android.graphics.RectF rect) {
        final float split = rect.left + dp(8) + gramPillEnd();
        final float r = rect.height() / 2f;
        path.addRoundRect(rect.left, rect.top, split, rect.bottom, r, r, Path.Direction.CW);
        path.addRoundRect(split + dp(GAP), rect.top, rect.right, rect.bottom, r, r, Path.Direction.CW);
    }

    @Override
    protected int getGlassPills(android.graphics.RectF rect, android.graphics.RectF out0, android.graphics.RectF out1) {
        // StaticGram: two glass pills on the glass profile, the same split as buildPillPath
        final float split = rect.left + dp(8) + gramPillEnd();
        out0.set(rect.left, rect.top, split, rect.bottom);
        out1.set(split + dp(GAP), rect.top, rect.right, rect.bottom);
        return 2;
    }

    private float lastTouchX = -1;
    private int downKind = -1;
    // StaticGram: each pill has its own press bounce — pressing GRAM must not press Stars too.
    private final ButtonBounce gramBounce = new ButtonBounce(this), starsBounce = new ButtonBounce(this);

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
            lastTouchX = event.getX();
            downKind = tappedKind();
        }
        final boolean pressed = super.dispatchTouchEvent(event);
        gramBounce.setPressed(pressed && downKind == 0);
        starsBounce.setPressed(pressed && downKind == 1);
        return pressed;
    }

    @Override
    protected float getPressScale() {
        return 1f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float s0 = gramBounce.getScale(0.04f), s1 = starsBounce.getScale(0.04f);
        final android.graphics.RectF r = getPillRect();
        if ((s0 == 1f && s1 == 1f) || r == null || r.width() <= 0) {
            super.onDraw(canvas);
            return;
        }
        // Draw the view twice, each copy clipped to one pill and scaled around that pill only.
        final float split = r.left + dp(8) + gramPillEnd() + dp(GAP) / 2f;
        final float cy = getHeight() / 2f;
        canvas.save();
        canvas.clipRect(0, 0, split, getHeight());
        canvas.scale(s0, s0, (r.left + split) / 2f, cy);
        super.onDraw(canvas);
        canvas.restore();
        canvas.save();
        canvas.clipRect(split, 0, getWidth(), getHeight());
        canvas.scale(s1, s1, (split + r.right) / 2f, cy);
        super.onDraw(canvas);
        canvas.restore();
    }

    /** Half-width of the dead band around the gap between the pills: a tap there opens neither card. */
    private static final int DEAD_ZONE = 14;

    /** Which pill was tapped last: 0 — GRAM, 1 — Stars, -1 — the middle (nothing). */
    public int tappedKind() {
        final android.graphics.RectF r = getPillRect();
        if (r == null || lastTouchX < 0) return 0;
        final float split = r.left + dp(8) + gramPillEnd() + dp(GAP) / 2f;
        if (Math.abs(lastTouchX - split) < dp(DEAD_ZONE)) return -1;
        return lastTouchX < split ? 0 : 1;
    }

    /**
     * Screen bounds of one pill (kind 0 — GRAM, 1 — Stars), with every transform of this view
     * and its parents applied (scale, translation), so an overlay window can grow out of it.
     * Returns false when the pill has not been laid out yet.
     */
    public boolean getChipBoundsOnScreen(int kind, android.graphics.RectF out) {
        final android.graphics.RectF r = getPillRect();
        if (r == null || r.width() <= 0 || r.height() <= 0 || !isAttachedToWindow()) return false;
        final float split = r.left + dp(8) + gramPillEnd();
        final float[] pts = kind == 1
                ? new float[] {Math.min(r.right, split + dp(GAP)), r.top, r.right, r.bottom}
                : new float[] {r.left, r.top, Math.min(r.right, split), r.bottom};
        android.view.View v = this;
        while (true) {
            v.getMatrix().mapPoints(pts);
            final float dx = v.getLeft() - v.getScrollX(), dy = v.getTop() - v.getScrollY();
            pts[0] += dx; pts[2] += dx; pts[1] += dy; pts[3] += dy;
            if (!(v.getParent() instanceof android.view.View)) break;
            v = (android.view.View) v.getParent();
        }
        final int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        final float ox = loc[0] - v.getLeft(), oy = loc[1] - v.getTop();
        out.set(Math.min(pts[0], pts[2]) + ox, Math.min(pts[1], pts[3]) + oy, Math.max(pts[0], pts[2]) + ox, Math.max(pts[1], pts[3]) + oy);
        return out.width() > 0 && out.height() > 0;
    }

    /** Pushes the content up out of the pill, as if the card is being ejected from it. */
    public void setEjectProgress(float progress) {
        if (ejectProgress == progress) return;
        ejectProgress = progress;
        invalidate();
    }

    @Override
    protected float getContentWidth(float maxWidth) {
        gramAmount.ellipsize(-1);
        starsAmount.ellipsize(-1);
        // StaticGram: no "Balance:" label, each pill is just the icon and the amount.
        final float icons = dp(13 + 2) + dp(CHEVRON_ROOM) + dp(PILL_PAD + GAP + PILL_PAD) + dp(13 + 2) + dp(CHEVRON_ROOM);
        return icons + gramAmount.getWidth() + starsAmount.getWidth();
    }

    @Override
    protected void drawContent(Canvas canvas, float alpha) {
        final float cy = getHeight() / 2f;
        canvas.translate(0, -dp(20) * ejectProgress);
        final int size = dp(13);
        final Drawable icon = hasProfileColor || isOnProfilePhoto() ? gramColorIcon : gramIcon;
        icon.setBounds(0, (int) cy - size / 2, size, (int) cy - size / 2 + size);
        icon.setAlpha((int) (255 * alpha));
        icon.draw(canvas);
        canvas.translate(size + dp(2), 0);
        gramAmount.draw(canvas, 0, cy, textColor, alpha);
        canvas.save();
        canvas.translate(gramAmount.getWidth() + dpf2(4.8f), cy);
        chevronPaint.setColor(Theme.multAlpha(textColor, 0.85f * alpha));
        canvas.drawPath(chevron, chevronPaint);
        canvas.restore();
        // StaticGram: GRAM and Stars are two separate pills with a gap between them.
        canvas.translate(gramAmount.getWidth() + dp(CHEVRON_ROOM) + dp(PILL_PAD + GAP + PILL_PAD), 0);
        starIcon.setBounds(0, (int) cy - size / 2, size, (int) cy - size / 2 + size);
        starIcon.setAlpha((int) (255 * alpha));
        starIcon.draw(canvas);
        canvas.translate(size + dp(2), 0);
        starsAmount.draw(canvas, 0, cy, textColor, alpha);
        canvas.translate(starsAmount.getWidth() + dpf2(4.8f), cy);
        chevronPaint.setColor(Theme.multAlpha(textColor, 0.85f * alpha));
        canvas.drawPath(chevron, chevronPaint);
    }
}
