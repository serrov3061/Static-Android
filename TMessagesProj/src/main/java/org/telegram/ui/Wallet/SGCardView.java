package org.telegram.ui.Wallet;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.widget.FrameLayout;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.FileLog;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_sgcard;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.Components.LayoutHelper;

/** A StaticGram card: the 13.1.1 2D card face with the card's background gift and stickers. */
@SuppressLint("ViewConstructor")
public class SGCardView extends FrameLayout {

    public final WalletCardComponent card;
    public final SGCardStickers stickers;
    private final int currentAccount;
    private TLRPC.User user;
    private TL_sgcard.TL_card data;

    public SGCardView(Context context, int currentAccount, boolean editable) {
        super(context);
        this.currentAccount = currentAccount;
        setClipChildren(false);
        setClipToPadding(false);
        card = new WalletCardComponent(context);
        addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        stickers = new SGCardStickers(context, currentAccount, card, editable);
        addView(stickers, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // The sticker layer is MATCH_PARENT: under an AT_MOST parent it grew this view to the
        // whole available height and pushed the editor's picker off screen. Size to the card.
        card.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        final int h = card.getMeasuredHeight() + getPaddingTop() + getPaddingBottom();
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }

    public void bind(TLRPC.User user, TL_sgcard.TL_card c) {
        this.user = user;
        this.data = c;
        final int kind = c == null ? TL_sgcard.KIND_GRAM : c.kind;
        card.setSgCard(currentAccount, user, kind, c == null ? 0 : c.balance);
        stickers.setCard(c);
    }

    /** Updates only the balance, keeping stickers being edited. */
    public void setBalance(long balance) {
        if (data != null) data.balance = balance;
        card.setSgCard(currentAccount, user, getKind(), balance);
        stickers.requestSettle();
    }

    public int getKind() {
        return data == null ? TL_sgcard.KIND_GRAM : data.kind;
    }

    public TL_sgcard.TL_card getData() {
        return data;
    }

    public TLRPC.User getUser() {
        return user;
    }

    /**
     * Renders the card on a soft backdrop for sending as a photo. The card should be on screen
     * (images loaded); its live tilt is drawn as it is now.
     */
    public Bitmap renderImage() {
        final int w = card.getWidth(), h = card.getHeight();
        if (w <= 0 || h <= 0) return null;
        try {
            final float scale = Math.max(1f, 1280f / w);
            final int pad = dp(28);
            final int bw = Math.round((w + pad * 2) * scale), bh = Math.round((h + pad * 2) * scale);
            final Bitmap bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(bitmap);
            int tone = getKind() == TL_sgcard.KIND_STARS ? 0xFF3A2405 : 0xFF0B1E3A;
            final TL_stars.StarGift bg = stickers.getBackgroundGift();
            if (bg != null && bg.attributes != null) {
                for (TL_stars.StarGiftAttribute a : bg.attributes) {
                    if (a instanceof TL_stars.starGiftAttributeBackdrop) {
                        tone = ColorUtils.blendARGB(((TL_stars.starGiftAttributeBackdrop) a).edge_color | 0xFF000000, 0xFF000000, .55f);
                    }
                }
            }
            final Paint bgPaint = new Paint();
            bgPaint.setShader(new LinearGradient(0, 0, bw, bh, ColorUtils.blendARGB(tone, 0xFFFFFFFF, .12f), tone, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, bw, bh, bgPaint);
            canvas.scale(scale, scale);
            canvas.translate(pad - card.getLeft(), pad - card.getTop());
            draw(canvas);
            return bitmap;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    public void dispose() {
        stickers.dispose();
    }
}
