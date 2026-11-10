package org.telegram.ui.Wallet;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_sgcard;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieDrawable;

import java.util.ArrayList;

/**
 * Stickers and the gift background of a StaticGram card, drawn on the card face like
 * Telegram 13.1.1's WalletGiftStickers: white-outlined sticker art with a soft shadow and
 * a sheen that follows the tilt, the gift pattern behind them and the backdrop colours of
 * the background gift applied to the whole card (WalletGiftPalette).
 *
 * In edit mode this view is an overlay above the card that takes the touches: tap selects,
 * one finger moves, the side handles (or two fingers) scale and rotate, dragging a sticker
 * off the card removes it.
 *
 * With an overlay ({@link #createOverlay}) and a {@link CellSource}, the 13.1.1 board motion is
 * used: a sticker picked in the grid is tossed from its cell onto the card (lift, arc, peel curl,
 * slap, squash and sparks), a removed one flies back home into its cell, and a sticker dragged
 * off the card can be dropped into the trash bin.
 */
@SuppressLint("ViewConstructor")
public class SGCardStickers extends View {

    public interface Listener {
        void onChanged();

        void onSelectionChanged(boolean selected);
    }

    /** The editor's picker grid: where a sticker flies from and back to (13.1.1 WalletGiftCell). */
    public interface CellSource {
        /** The visible picker cell of this gift or sticker, or null. */
        View cellFor(TL_stars.StarGift gift, TLRPC.Document document);

        /** Share of the cell width taken by the art, as in 13.1.1 artShare(). */
        float artShare(View cell);

        /** The image shown in the cell, used until the card's own copy has loaded. */
        ImageReceiver imageOf(View cell);

        /** A sticker left the grid or came back home: cells update their "on card" state. */
        void onCellsChanged();
    }

    // 13.1.1 board states (WalletGiftStickers): on the card, flying onto it, flying back into
    // its cell, falling into the trash bin, dragged above everything.
    private static final int CARD = 0, FLIGHT = 1, HOME = 2, TRASH = 3, DRAG = 4, SHRINK = 5;
    private static final int MESH = 14;
    private CellSource cells;
    private View overlay;
    private final Matrix ovFace = new Matrix(), ovInverse = new Matrix();
    private final Matrix tileMatrix = new Matrix(), overlayMatrix = new Matrix();
    private float ovScale = 1;
    private final float[] mesh = new float[(MESH + 1) * (MESH + 1) * 2];
    private final int[] meshColors = new int[(MESH + 1) * (MESH + 1)];
    private final Spring bin = new Spring(0, 260, .78f), binHover = new Spring(0, 420, .72f);
    private final Spring trashFrame = new Spring(0, 120, 1);
    private boolean trashClosing, overBin;
    private RLottieDrawable trashDrawable;
    private final Paint trashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint binText = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The gift whose colours spread once it touches the card (13.1.1 pendingStyle). */
    private Item pendingReveal;

    /** Default sticker width as a share of the card width (13.1.1: SHARE). */
    public static final float DEFAULT_SCALE = .231f;
    private static final float MIN_SCALE = .05f, MAX_SCALE = .6f;

    private final int currentAccount;
    private final WalletCardComponent card;
    private final boolean editable;
    private final View patternLayer, cardLayer;
    private final ArrayList<Item> items = new ArrayList<>();
    /** Removed stickers that are still shrinking away (13.1.1 trash / home animations). */
    private final ArrayList<Item> leaving = new ArrayList<>();
    // 13.1.1 WalletGiftStickers motion: the card squashes and tilts when a sticker lands on it,
    // sparks and a ring spread from the spot.
    private final Spring squash = new Spring(1, 420, .38f);
    private final Spring kickH = new Spring(0, 170, .42f), kickV = new Spring(0, 170, .42f);
    private final ArrayList<Spark> sparks = new ArrayList<>();
    private final ArrayList<Ripple> ripples = new ArrayList<>();
    private final Path sparkPath = new Path();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long lastFrame;
    private Listener listener;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint plainPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint erasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LinearGradient sheen = new LinearGradient(0, 0, 1, 0,
            new int[]{0x00FFFFFF, 0x00FFFFFF, 0x14FFFFFF, 0x00FFFFFF, 0x00FFFFFF},
            new float[]{0f, .36f, .5f, .64f, 1f}, Shader.TileMode.CLAMP);
    private final Matrix sheenMatrix = new Matrix();
    private final Paint selectionDash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF(), stickerBounds = new RectF(), plainRect = new RectF();
    private final Path path = new Path();
    private final Matrix face = new Matrix(), inverse = new Matrix();
    private float width, height;
    private float rotX, rotY;
    private final boolean lowEnd = org.telegram.messenger.SharedConfig.getDevicePerformanceClass()
            == org.telegram.messenger.SharedConfig.PERFORMANCE_CLASS_LOW;
    private boolean disposed;

    // Background gift (the card colours) with the 13.1.1 circular reveal.
    private Background shownBackground, oldBackground;
    private float reveal = 1, revealX = .5f, revealY = .5f;
    private boolean fadeReveal;
    private ValueAnimator revealAnimator;

    // Editing
    private Item selected, touched;
    private int mode;
    private static final int MODE_NONE = 0, MODE_MOVE = 1, MODE_KNOB = 2, MODE_PINCH = 3;
    private float downX, downY, grabDx, grabDy;
    private boolean moved;
    private float knobAngle, knobDistance, knobScale, knobRotation;
    private float pinchDistance, pinchAngle, pinchScale, pinchRotation;
    private boolean snapped;

    private final ViewTreeObserver.OnPreDrawListener rotationSync = () -> {
        final float x = cardRotationX(), y = cardRotationY();
        if (x != rotX || y != rotY) {
            rotX = x;
            rotY = y;
            invalidateCard();
        }
        return true;
    };

    public SGCardStickers(Context context, int currentAccount, WalletCardComponent card, boolean editable) {
        super(context);
        this.currentAccount = currentAccount;
        this.card = card;
        this.editable = editable;
        sheenPaint.setShader(sheen);
        sheenPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        erasePaint.setColor(Color.BLACK);
        erasePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        line.setStyle(Paint.Style.STROKE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        patternLayer = new View(context) {
            @Override
            protected void onDraw(Canvas canvas) {
                drawPatterns(canvas);
            }
        };
        cardLayer = new View(context) {
            @Override
            protected void onDraw(Canvas canvas) {
                if (pendingSettle && geometry() && card.getStickerObstacles().get(0).width() > 0) {
                    // Layouts saved before the no-overlap rule (or a balance that grew) are
                    // moved off the text once the card is measured.
                    pendingSettle = false;
                    for (Item item : items) settle(item, false);
                }
                drawOnCard(canvas);
            }

            @Override
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                getViewTreeObserver().addOnPreDrawListener(rotationSync);
                attachImages(true);
            }

            @Override
            protected void onDetachedFromWindow() {
                getViewTreeObserver().removeOnPreDrawListener(rotationSync);
                attachImages(false);
                super.onDetachedFromWindow();
            }

            @Override
            protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                super.onSizeChanged(w, h, oldw, oldh);
                width = w;
                height = h;
            }
        };
        patternLayer.setTag(WalletCard2DView.BACK_PATTERN);
        card.getFrontFace().addView(patternLayer, 0, LayoutHelper.createFrame(-1, -1));
        card.getFrontFace().addView(cardLayer, card.getStickerLayerIndex(), LayoutHelper.createFrame(-1, -1));
        applyStyle();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setCellSource(CellSource cells) {
        this.cells = cells;
    }

    /**
     * A layer over the whole editor (added by it above everything, no touches) where stickers
     * fly between the grid and the card, are dragged off the card and fall into the bin.
     */
    public View createOverlay(Context context) {
        overlay = new View(context) {
            @Override
            protected void onDraw(Canvas canvas) {
                drawOverlay(canvas);
            }
        };
        overlay.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        binText.setTypeface(AndroidUtilities.bold());
        return overlay;
    }

    /** Whether the gift or sticker is out of its grid cell: on the card, or still flying home. */
    public boolean isOut(TL_stars.StarGift gift, TLRPC.Document document) {
        if (contains(gift, document)) return true;
        for (Item item : leaving) {
            if (item.state == HOME && item.matches(gift, document)) return true;
        }
        return false;
    }

    private float cardRotationX() {
        return card.getCardRotationX();
    }

    private float cardRotationY() {
        return card.getCardRotationY();
    }

    // ---------------------------------------------------------------- model

    private final class Background {
        final TL_stars.StarGift gift;
        final TL_stars.starGiftAttributeBackdrop backdrop;
        final ImageReceiver pattern = new ImageReceiver(patternLayer);

        Background(TL_stars.StarGift gift, TL_stars.starGiftAttributeBackdrop stockBackdrop) {
            this.gift = gift;
            TL_stars.starGiftAttributeBackdrop b = stockBackdrop;
            if (gift != null && gift.attributes != null) {
                for (TL_stars.StarGiftAttribute attr : gift.attributes) {
                    if (attr instanceof TL_stars.starGiftAttributeBackdrop) {
                        b = (TL_stars.starGiftAttributeBackdrop) attr;
                    } else if (attr instanceof TL_stars.starGiftAttributePattern) {
                        pattern.setImage(ImageLocation.getForDocument(((TL_stars.starGiftAttributePattern) attr).document), "60_60_firstframe", null, null, gift, 1);
                    }
                }
            }
            backdrop = b;
            pattern.setCurrentAccount(currentAccount);
            pattern.setDelegate((receiver, set, thumb, memCache) -> patternLayer.invalidate());
            if (cardLayer.isAttachedToWindow()) pattern.onAttachedToWindow();
        }
    }

    public final class Item {
        public final TL_stars.StarGift gift;
        public final TLRPC.Document document;
        public float x = .5f, y = .5f, scale = DEFAULT_SCALE, rotation;
        final Spring appear = new Spring(1, 260, 1);
        final Spring lift = new Spring(0, 380, .82f), press = new Spring(0, 520, .62f);
        final Spring impact = new Spring(0, 240, 1), bump = new Spring(1, 420, .5f);
        // 13.1.1 flight: position in face pixels, the toss arc, the scale from the cell's art to
        // the card (fly), the peel curl, the white border fading in, the turn to its angle.
        final Spring fx = new Spring(0, 150, .82f), fy = new Spring(0, 150, .82f);
        final Spring arcX = new Spring(0, 150, .82f), arcY = new Spring(0, 150, .82f);
        final Spring fly = new Spring(1, 300, .5f), curl = new Spring(0, 30, 1);
        final Spring border = new Spring(1, 300, 1), spin = new Spring(0, 700, .9f);
        final Spring[] springs = {appear, lift, press, impact, bump, fx, fy, arcX, arcY, fly, curl, border, spin};
        /** Waits to touch the card: the landing squashes the card (flewIn adds sparks). */
        boolean landing, flewIn, artShown;
        int state = CARD;
        boolean tossed, contact, ring, slapped;
        float curlX, curlY = 1;
        // Home / trash flights, in overlay pixels.
        float homeT, homeX, homeY, homeN, homeAngle, homeDuration;
        View homeCell;
        /** The grid cell's image, drawn while this sticker's own copy is loading. */
        ImageReceiver fallback;
        final ImageReceiver image = new ImageReceiver(cardLayer) {
            @Override
            public void invalidate() {
                super.invalidate();
                invalidateCard();
            }
        };
        Bitmap art, plain, shadow;
        Canvas plainCanvas;
        int[] outlineInput, outlineOutput, outlineAlpha, outlineLine;
        boolean outlining, outlineStale;
        Bitmap lastBitmap;

        Item(TL_stars.StarGift gift, TLRPC.Document document) {
            this.gift = gift;
            this.document = document;
            image.setCurrentAccount(currentAccount);
            image.setAllowStartLottieAnimation(false);
            image.setAutoRepeat(1);
            image.setDelegate((receiver, set, thumb, memCache) -> invalidateCard());
            final TLRPC.Document doc = gift != null ? gift.getDocument() : document;
            if (doc != null) {
                image.setImage(ImageLocation.getForDocument(doc), "120_120_nolimit", null, null, gift != null ? gift : document, 1);
            }
            if (cardLayer.isAttachedToWindow()) image.onAttachedToWindow();
        }

        public String key() {
            if (gift != null) return "g" + gift.slug;
            return document == null ? "" : "d" + document.id;
        }

        boolean matches(TL_stars.StarGift g, TLRPC.Document d) {
            return g != null && gift != null && g.slug != null && g.slug.equals(gift.slug)
                    || d != null && document != null && d.id == document.id;
        }

        /** The outlined art, or the plain art while the outline is being built. */
        Bitmap drawable() {
            final Bitmap b = bitmap();
            return b != null ? b : plain;
        }

        /** Sticker art with the 13.1.1 white outline; rebuilt off the UI thread when the image changes. */
        Bitmap bitmap() {
            Bitmap source = image.getBitmap();
            if (source != null && !source.isRecycled()) fallback = null;
            else if (fallback != null) source = fallback.getBitmap();
            if (source == null || source.isRecycled()) return art;
            if (plain != null && lastBitmap == source) return art;
            lastBitmap = source;
            final int n = WalletStickerOutline.SIZE, padding = WalletStickerOutline.PADDING;
            if (plain == null) {
                plain = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
                plainCanvas = new Canvas(plain);
            }
            plain.eraseColor(0);
            plainRect.set(padding, padding, n - padding, n - padding);
            plainCanvas.drawBitmap(source, null, plainRect, plainPaint);
            if (outlining) outlineStale = true;
            else outline();
            return art;
        }

        private void outline() {
            final int n = WalletStickerOutline.SIZE;
            if (outlineInput == null) {
                outlineInput = new int[n * n];
                outlineOutput = new int[n * n];
                outlineAlpha = new int[n * n];
                outlineLine = new int[WalletStickerOutline.lineBufferSize()];
            }
            plain.getPixels(outlineInput, 0, n, 0, 0, n, n);
            outlining = true;
            outlineStale = false;
            final boolean needShadow = shadow == null;
            final int[] input = outlineInput, output = outlineOutput, alpha = outlineAlpha, line = outlineLine;
            WalletStickerOutline.queue().postRunnable(() -> {
                WalletStickerOutline.compose(input, output, alpha, line);
                final Bitmap newShadow = needShadow && WalletStickerOutline.covered(output) ? WalletStickerOutline.shadow(output) : null;
                AndroidUtilities.runOnUIThread(() -> {
                    outlining = false;
                    if (disposed) return;
                    if (art == null) {
                        art = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
                        // 13.1.1: art that loads while the card is visible fades/scales in.
                        if (!artShown && card.isShown() && card.getAlpha() > 0 && appear.target > 0) {
                            appear.set(0).to(1);
                            wake();
                        }
                        artShown = true;
                    }
                    art.setPixels(output, 0, n, 0, 0, n, n);
                    if (newShadow != null && shadow == null) shadow = newShadow;
                    if (outlineStale) outline();
                    invalidateCard();
                });
            });
        }
    }

    private void attachImages(boolean attach) {
        for (Item item : items) {
            if (attach) item.image.onAttachedToWindow();
            else item.image.onDetachedFromWindow();
        }
        if (!attach) {
            for (Item item : leaving) item.image.onDetachedFromWindow();
            leaving.clear();
            cardLayer.removeCallbacks(tick);
            lastFrame = 0;
        }
        for (Background b : new Background[]{shownBackground, oldBackground}) {
            if (b == null) continue;
            if (attach) b.pattern.onAttachedToWindow();
            else b.pattern.onDetachedFromWindow();
        }
    }

    /** Shows a card as the server returned it. */
    public void setCard(TL_sgcard.TL_card c) {
        for (Item item : items) item.image.onDetachedFromWindow();
        items.clear();
        selected = null;
        if (c != null) {
            for (TL_sgcard.TL_sticker s : c.stickers) {
                if (s.gift == null && s.document == null) continue;
                final Item item = new Item(s.gift, s.document);
                item.x = (float) s.x;
                item.y = (float) s.y;
                item.scale = (float) s.scale;
                item.rotation = (float) s.rotation;
                items.add(item);
            }
        }
        setBackgroundGift(c == null ? null : c.background, false);
        ensureBackgroundSticker(false);
        pendingSettle = true;
        invalidateCard();
    }

    /** Set when stickers must be moved off the text on the next draw (after layout). */
    private boolean pendingSettle;

    /** The balance text changed width: move stickers off it again. */
    public void requestSettle() {
        pendingSettle = true;
        invalidateCard();
    }

    public TL_stars.StarGift getBackgroundGift() {
        return shownBackground == null ? null : shownBackground.gift;
    }

    public void setBackgroundGift(TL_stars.StarGift gift, boolean animated) {
        final TL_stars.StarGift current = getBackgroundGift();
        if (shownBackground != null && (current == gift || current != null && gift != null && current.slug != null && current.slug.equals(gift.slug))) {
            return;
        }
        final Background next = new Background(gift, WalletCardComponent.defaultBackdrop(card.getCardKind()));
        if (oldBackground != null && oldBackground != shownBackground) oldBackground.pattern.onDetachedFromWindow();
        oldBackground = shownBackground;
        shownBackground = next;
        if (revealAnimator != null) revealAnimator.cancel();
        revealing = false;
        if (!animated || oldBackground == null) {
            reveal = 1;
            oldBackground = next;
            applyStyle();
            patternLayer.invalidate();
            return;
        }
        fadeReveal = gift == null;
        revealX = .5f;
        revealY = .5f;
        pendingReveal = null;
        final boolean tossed = gift != null && !contains(gift, null);
        final Item onCard = gift == null ? null : ensureBackgroundSticker(true);
        if (onCard != null) {
            // 13.1.1: the colours spread from the gift itself.
            revealX = onCard.x;
            revealY = onCard.y;
        }
        // Until the reveal starts the card keeps the old colours (progress 0).
        reveal = 0;
        applyStyle();
        patternLayer.invalidate();
        if (tossed && onCard != null && (onCard.state == FLIGHT || onCard.landing)) {
            // A gift thrown onto the card first lands, then its colours spread (13.1.1 pendingStyle).
            pendingReveal = onCard;
        } else {
            startReveal();
        }
        changed();
    }

    /** 13.1.1 startReveal(): the palette spreads from (revealX, revealY), or fades for none. */
    private void startReveal() {
        pendingReveal = null;
        if (revealAnimator != null) revealAnimator.cancel();
        // Advanced by the board tick like 13.1.1 (reveal += dt / 1.2s, 0.45s for a fade).
        reveal = 0;
        revealing = true;
        applyStyle();
        patternLayer.invalidate();
        wake();
    }

    private boolean revealing;

    /**
     * In 13.1.1 the background gift is always one of the stickers on the card (applying the
     * colour throws the gift onto it), so the gift itself is seen on its backdrop.
     */
    private Item ensureBackgroundSticker(boolean animated) {
        final TL_stars.StarGift gift = getBackgroundGift();
        if (gift == null || gift.getDocument() == null) return null;
        for (Item item : items) {
            if (item.gift != null && gift.slug != null && gift.slug.equals(item.gift.slug)) return item;
        }
        if (items.size() >= TL_sgcard.MAX_STICKERS) return null;
        if (animated) {
            return add(gift, null) ? items.get(items.size() - 1) : null;
        }
        final Item item = new Item(gift, null);
        final float[] spot = freeSpot();
        item.x = spot[0];
        item.y = spot[1];
        item.rotation = (float) (Math.random() * 16 - 8);
        items.add(0, item);
        return item;
    }

    private boolean isBackground(Item item) {
        final TL_stars.StarGift bg = getBackgroundGift();
        return bg != null && item.gift != null && bg.slug != null && bg.slug.equals(item.gift.slug);
    }

    /** 13.1.1 applyStyle(): palette of the old and new backdrop plus the reveal progress. */
    private void applyStyle() {
        final TL_stars.starGiftAttributeBackdrop fromB = oldBackground == null ? WalletCardComponent.defaultBackdrop(card.getCardKind()) : oldBackground.backdrop;
        final TL_stars.starGiftAttributeBackdrop toB = shownBackground == null ? WalletCardComponent.defaultBackdrop(card.getCardKind()) : shownBackground.backdrop;
        final float[] from = WalletGiftPalette.of(fromB);
        final float[] to = WalletGiftPalette.of(toB);
        final float p = fadeReveal ? reveal * reveal * (3 - 2 * reveal) : 1 - (float) Math.pow(1 - reveal, 4);
        final float[] style = new float[18];
        final float[] transition = {from[0], from[1], from[2], to[0], to[1], to[2], revealX, revealY, p, fadeReveal ? 1 : 0};
        System.arraycopy(transition, 0, style, 0, 10);
        WalletGiftPalette.putBackdrop(style, 0, fromB);
        WalletGiftPalette.putBackdrop(style, 1, toB);
        card.setStickerStyle(style);
    }

    public ArrayList<Item> getItems() {
        return items;
    }

    public boolean contains(TL_stars.StarGift gift, TLRPC.Document document) {
        for (Item item : items) {
            if (gift != null && item.gift != null && gift.slug != null && gift.slug.equals(item.gift.slug)) return true;
            if (document != null && item.document != null && item.document.id == document.id) return true;
        }
        return false;
    }

    /** Adds a sticker in a free spot. Returns false when the card already holds the maximum. */
    public boolean add(TL_stars.StarGift gift, TLRPC.Document document) {
        if (items.size() >= TL_sgcard.MAX_STICKERS) return false;
        final Item item = new Item(gift, document);
        final float[] spot = freeSpot();
        item.x = spot[0];
        item.y = spot[1];
        item.rotation = (float) (Math.random() * 24 - 12);
        items.add(item);
        settle(item, false);
        selected = item;
        if (!toss(item, cells == null ? null : cells.cellFor(gift, document))) {
            // No grid cell to start from: the sticker drops onto the card from above.
            item.appear.set(0).to(1);
            item.lift.set(1.4f).with(150, .82f).to(0);
            item.bump.set(1.25f).to(1);
            item.landing = true;
            item.flewIn = true;
        }
        wake();
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (listener != null) listener.onSelectionChanged(true);
        changed();
        return true;
    }

    public void remove(TL_stars.StarGift gift, TLRPC.Document document) {
        for (int i = items.size() - 1; i >= 0; i--) {
            final Item item = items.get(i);
            if (item.matches(gift, document)) {
                removeItem(item, HOME);
            }
        }
    }

    public void removeSelected() {
        if (selected != null) removeItem(selected, HOME);
    }

    private void removeItem(Item item) {
        removeItem(item, SHRINK);
    }

    /** Takes a sticker off the card: home into its cell (13.1.1 home()), into the bin, or shrinking away. */
    private void removeItem(Item item, int how) {
        items.remove(item);
        item.landing = false;
        if (pendingReveal == item) pendingReveal = null;
        if (how == HOME && !startHome(item)) how = SHRINK;
        if (how == TRASH && !startTrash(item)) how = SHRINK;
        if (how == SHRINK) {
            // It shrinks away instead of vanishing.
            item.state = SHRINK;
            item.appear.with(420, 1).to(0);
            item.press.to(0);
            item.lift.to(.6f);
        }
        leaving.add(item);
        wake();
        if (isBackground(item)) setBackgroundGift(null, true);
        if (selected == item) {
            selected = null;
            if (listener != null) listener.onSelectionChanged(false);
        }
        invalidateCard();
        changed();
    }

    public void bringSelectedToFront() {
        if (selected == null) return;
        bringToFront(selected);
        invalidateCard();
        changed();
    }

    public boolean hasSelection() {
        return selected != null;
    }

    /** Spreads new stickers over the card, away from the balance and the existing ones. */
    private float[] freeSpot() {
        if (geometry()) {
            // Like 13.1.1 fitting(): the spot that keeps clear of the text and the QR chip and
            // is the farthest from the stickers already on the card.
            final ArrayList<RectF> obstacles = card.getStickerObstacles();
            final float pad = dp(3);
            for (RectF r : obstacles) if (!r.isEmpty()) r.inset(-pad, -pad);
            final float half = width * DEFAULT_SCALE * .42f;
            float bestScore = -Float.MAX_VALUE;
            float[] best = null;
            for (float y = half; y <= height - half; y += dp(6)) {
                for (float x = half; x <= width - half; x += dp(6)) {
                    if (!isFree(x, y, half, obstacles)) continue;
                    float nearest = Math.min(width, height);
                    for (Item item : items) {
                        nearest = Math.min(nearest, dist(x, y, item.x * width, item.y * height) - width * item.scale * .42f);
                    }
                    final float score = nearest - .05f * dist(x, y, width * .6f, height * .6f);
                    if (score > bestScore) {
                        bestScore = score;
                        best = new float[]{x / width, y / height};
                    }
                }
            }
            if (best != null) return best;
        }
        final float[][] candidates = {
                {.72f, .3f}, {.5f, .55f}, {.3f, .72f}, {.8f, .72f}, {.5f, .25f}, {.2f, .3f},
                {.62f, .75f}, {.35f, .45f}, {.85f, .45f}, {.15f, .6f}, {.45f, .8f}, {.65f, .5f}
        };
        float[] best = candidates[0];
        float bestScore = -1;
        for (float[] c : candidates) {
            float nearest = 10;
            for (Item item : items) {
                nearest = Math.min(nearest, (float) Math.hypot((c[0] - item.x) * 1.64f, c[1] - item.y));
            }
            if (nearest > bestScore) {
                bestScore = nearest;
                best = c;
            }
        }
        return new float[]{best[0], best[1]};
    }

    /**
     * StaticGram: a sticker never covers the balance, the holder name, the card number or the
     * QR chip. If it does, it moves to the nearest free spot (shrinking a little when the card
     * has none), animated from where it was dropped.
     */
    private void settle(Item s) {
        settle(s, true);
    }

    private void settle(Item s, boolean animate) {
        if (s == null || !geometry()) return;
        final ArrayList<RectF> obstacles = card.getStickerObstacles();
        final float pad = dp(3);
        for (RectF r : obstacles) {
            if (!r.isEmpty()) r.inset(-pad, -pad);
        }
        float scale = s.scale;
        for (int attempt = 0; attempt < 5; attempt++) {
            final float half = width * scale * .42f;
            final float[] spot = nearestFree(s.x * width, s.y * height, half, obstacles);
            if (spot != null) {
                if (animate) {
                    moveTo(s, spot[0] / width, spot[1] / height, scale);
                } else {
                    s.x = spot[0] / width;
                    s.y = spot[1] / height;
                    s.scale = scale;
                }
                return;
            }
            scale = Math.max(MIN_SCALE, scale * .85f);
        }
    }

    private float[] nearestFree(float cx, float cy, float half, ArrayList<RectF> obstacles) {
        if (isFree(cx, cy, half, obstacles)) return new float[]{cx, cy};
        final float step = dp(4);
        for (int ring = 1; ring <= 80; ring++) {
            float bestX = 0, bestY = 0;
            boolean found = false;
            for (int a = 0; a < 360; a += 10) {
                final float x = cx + (float) Math.cos(Math.toRadians(a)) * ring * step;
                final float y = cy + (float) Math.sin(Math.toRadians(a)) * ring * step;
                if (x < half || x > width - half || y < half || y > height - half) continue;
                if (isFree(x, y, half, obstacles)) {
                    bestX = x;
                    bestY = y;
                    found = true;
                    break;
                }
            }
            if (found) return new float[]{bestX, bestY};
        }
        return null;
    }

    private final RectF settleRect = new RectF();

    private boolean isFree(float cx, float cy, float half, ArrayList<RectF> obstacles) {
        settleRect.set(cx - half, cy - half, cx + half, cy + half);
        for (RectF r : obstacles) {
            if (!r.isEmpty() && RectF.intersects(settleRect, r)) return false;
        }
        return true;
    }

    private void moveTo(Item s, float x, float y, float scale) {
        final float fx = s.x, fy = s.y, fs = s.scale;
        if (Math.abs(fx - x) < 1e-4 && Math.abs(fy - y) < 1e-4 && Math.abs(fs - scale) < 1e-4) return;
        final ValueAnimator a = ValueAnimator.ofFloat(0, 1);
        a.setDuration(260);
        a.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        a.addUpdateListener(v -> {
            final float t = (float) v.getAnimatedValue();
            s.x = fx + (x - fx) * t;
            s.y = fy + (y - fy) * t;
            s.scale = fs + (scale - fs) * t;
            invalidateCard();
        });
        a.start();
        s.x = x;
        s.y = y;
        s.scale = scale;
        a.setCurrentFraction(0);
    }

    /** Stickers in the order they are drawn, as sgcard.inputSticker. */
    public ArrayList<TL_sgcard.TL_inputSticker> toInput() {
        final ArrayList<TL_sgcard.TL_inputSticker> list = new ArrayList<>();
        for (Item item : items) {
            final TL_sgcard.TL_inputSticker in = new TL_sgcard.TL_inputSticker();
            if (item.gift != null) {
                in.gift_slug = item.gift.slug;
            } else if (item.document != null) {
                final TLRPC.TL_inputDocument d = new TLRPC.TL_inputDocument();
                d.id = item.document.id;
                d.access_hash = item.document.access_hash;
                d.file_reference = item.document.file_reference == null ? new byte[0] : item.document.file_reference;
                in.document = d;
            } else {
                continue;
            }
            in.x = clamp(item.x, 0, 1);
            in.y = clamp(item.y, 0, 1);
            in.scale = clamp(item.scale, MIN_SCALE, 1);
            in.rotation = angle(item.rotation);
            list.add(in);
        }
        return list;
    }

    private void changed() {
        if (listener != null) listener.onChanged();
    }

    private void invalidateCard() {
        cardLayer.invalidate();
        if (editable) invalidate();
        if (overlay != null) overlay.invalidate();
    }

    public void dispose() {
        disposed = true;
        cardLayer.removeCallbacks(tick);
        if (revealAnimator != null) revealAnimator.cancel();
        attachImages(false);
    }

    // ---------------------------------------------------------------- motion (13.1.1)

    // ---------------------------------------------------------------- board (13.1.1 grid <-> card)

    /** Maps face pixels into the overlay; false while the card or the overlay is not laid out. */
    private boolean overlayGeometry() {
        if (overlay == null || !overlay.isAttachedToWindow() || overlay.getWidth() <= 0) return false;
        width = card.getFrontFace().getWidth();
        height = card.getFrontFace().getHeight();
        if (width <= 0 || height <= 0 || !card.getFrontFaceTransform(overlay, ovFace) || !ovFace.invert(ovInverse)) return false;
        ovScale = Math.max(.01f, ovFace.mapRadius(1));
        return true;
    }

    /** The cell's bounds in overlay pixels (13.1.1 tile()), or null when it is not on screen. */
    private RectF tileRect(View cell) {
        if (cell == null || overlay == null || !cell.isAttachedToWindow() || !cell.isShown() || cell.getWidth() <= 0) return null;
        WalletCardComponent.transformToRoot(cell, tileMatrix);
        WalletCardComponent.transformToRoot(overlay, overlayMatrix);
        if (!overlayMatrix.invert(overlayMatrix)) return null;
        tileMatrix.postConcat(overlayMatrix);
        final RectF r = new RectF(0, 0, cell.getWidth(), cell.getHeight());
        tileMatrix.mapRect(r);
        if (r.bottom <= 0 || r.top >= overlay.getHeight() || r.right <= 0 || r.left >= overlay.getWidth()) return null;
        return r;
    }

    /** 13.1.1 toss() + place(..., tossed): the sticker leaves its cell and is thrown onto the card. */
    private boolean toss(Item s, View cell) {
        if (cells == null || !geometry() || !overlayGeometry()) return false;
        final RectF tile = tileRect(cell);
        if (tile == null) return false;
        final float[] from = {tile.centerX(), tile.centerY()};
        ovInverse.mapPoints(from);
        final float tileArt = tile.width() / ovScale * cells.artShare(cell);
        final float tx = s.x * width, ty = s.y * height;
        s.fallback = cells.imageOf(cell);
        s.artShown = true;
        s.state = FLIGHT;
        s.tossed = s.flewIn = true;
        s.contact = s.ring = s.slapped = false;
        s.landing = false;
        s.appear.set(1);
        s.bump.set(1);
        s.fly.with(300, .5f).set(tileArt / Math.max(1, width * s.scale)).to(1);
        s.border.with(300, 1).set(0).to(1);
        s.lift.with(110, .9f).set(1).to(0);
        s.press.set(0);
        s.spin.with(700, .9f).set(0).to(s.rotation);
        s.fx.with(150, .82f).set(from[0]).to(tx);
        s.fy.with(150, .82f).set(from[1]).to(ty);
        final float dx = tx - from[0], dy = ty - from[1], len = Math.max(1, (float) Math.hypot(dx, dy));
        s.curlX = len > dp(6) ? dx / len : 0;
        s.curlY = len > dp(6) ? dy / len : 1;
        s.curl.with(30, 1).set(.9f).to(0);
        final float bow = Math.min(dp(110), len * .32f), side = dx >= 0 ? -1 : 1;
        s.arcX.set(0).velocity = -dy / len * bow * side * 20;
        s.arcY.set(0).velocity = (dx / len * bow * side - bow * .5f) * 20;
        return true;
    }

    /** Where a sticker is drawn now, in overlay pixels: {x, y, size, angle}. */
    private float[] overlayPose(Item s) {
        final float[] p;
        float n = width * s.scale * s.bump.value * s.fly.value;
        if (s.state == FLIGHT) {
            p = new float[]{s.fx.value + s.arcX.value, s.fy.value + s.arcY.value};
        } else {
            p = new float[]{s.x * width, s.y * height};
        }
        ovFace.mapPoints(p);
        n *= ovScale;
        return new float[]{p[0], p[1], n, s.state == FLIGHT ? s.spin.value : s.rotation};
    }

    /** 13.1.1 home(): the sticker flies back along an arc into its cell and peels into it. */
    private boolean startHome(Item s) {
        if (cells == null || !geometry() || !overlayGeometry()) return false;
        final View cell = cells.cellFor(s.gift, s.document);
        final RectF tile = tileRect(cell);
        if (tile == null) return false;
        final float[] pose = overlayPose(s);
        s.state = HOME;
        s.homeCell = cell;
        s.homeT = 0;
        s.homeX = pose[0];
        s.homeY = pose[1];
        s.homeN = pose[2];
        s.homeAngle = angle(pose[3]);
        s.homeDuration = .44f;
        s.press.set(0);
        s.curl.set(0);
        final float dx = tile.centerX() - s.homeX, dy = tile.centerY() - s.homeY, len = (float) Math.hypot(dx, dy);
        s.curlX = len > dp(6) ? dx / len : 0;
        s.curlY = len > dp(6) ? dy / len : 1;
        cell.animate().cancel();
        return true;
    }

    /** 13.1.1 TRASH: the sticker drops into the bin and the lid closes. */
    private boolean startTrash(Item s) {
        if (!geometry() || !overlayGeometry()) return false;
        final float[] pose = overlayPose(s);
        s.state = TRASH;
        s.homeT = 0;
        s.homeX = pose[0];
        s.homeY = pose[1];
        s.homeN = pose[2];
        s.homeAngle = angle(pose[3]);
        s.homeDuration = .3f;
        trashClosing = true;
        trashFrame.set(Math.max(30, trashFrame.value));
        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        return true;
    }

    private float binY() {
        return overlay.getHeight() - dp(64) - AndroidUtilities.navigationBarHeight;
    }

    private float binOpenWidth() {
        binText.setTextSize(dp(15));
        return dp(72) + binText.measureText(LocaleController.getString(R.string.Remove));
    }

    private float binIconX() {
        return overlay.getWidth() / 2f - binOpenWidth() / 2 + dp(30);
    }

    private void drawBin(Canvas canvas) {
        final float show = bin.value;
        if (show < .01f) return;
        if (trashDrawable == null) {
            trashDrawable = new RLottieDrawable(R.raw.wallet_gift_trash, "wallet_gift_trash_sg", dp(42), dp(42), true, null);
            trashDrawable.setMasterParent(overlay);
        }
        final float hover = Math.max(0, Math.min(1.2f, binHover.value)), h = clamp01(hover);
        final float w = dp(56) + (binOpenWidth() - dp(56)) * hover;
        canvas.save();
        canvas.translate(overlay.getWidth() / 2f, binY() + (1 - show) * dp(90));
        final float scale = .6f + .4f * show;
        canvas.scale(scale, scale);
        paint.setColor(androidx.core.graphics.ColorUtils.blendARGB(Theme.getColor(Theme.key_windowBackgroundWhite), 0xFFE5484D, h));
        paint.setAlpha((int) (255 * clamp01(show)));
        rect.set(-w / 2, -dp(28), w / 2, dp(28));
        canvas.drawRoundRect(rect, dp(28), dp(28), paint);
        final float iconX = (-w / 2 + dp(30)) * h, half = dp(21);
        trashDrawable.setCurrentFrame(Math.round(trashFrame.value), false);
        trashDrawable.setBounds((int) (iconX - half), (int) -half, (int) (iconX + half), (int) half);
        trashPaint.setColorFilter(new PorterDuffColorFilter(
                androidx.core.graphics.ColorUtils.blendARGB(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), Color.WHITE, h), PorterDuff.Mode.SRC_IN));
        trashPaint.setAlpha((int) (255 * clamp01(show)));
        final int layer = canvas.saveLayer(iconX - half, -half, iconX + half, half, trashPaint);
        trashDrawable.draw(canvas);
        canvas.restoreToCount(layer);
        if (h > .02f) {
            binText.setColor(Color.WHITE);
            binText.setAlpha((int) (255 * clamp01((h - .4f) / .6f)));
            final Paint.FontMetrics metrics = binText.getFontMetrics();
            canvas.save();
            canvas.clipRect(iconX + dp(22), -dp(28), w / 2 - dp(16), dp(28));
            canvas.drawText(LocaleController.getString(R.string.Remove), iconX + dp(22), -(metrics.ascent + metrics.descent) / 2, binText);
            canvas.restore();
        }
        paint.setColor(Color.WHITE);
        paint.setAlpha(255);
        canvas.restore();
    }

    /** 13.1.1 onDraw() of the board: everything that is above the card and the grid. */
    private void drawOverlay(Canvas canvas) {
        if (!overlayGeometry()) return;
        drawBin(canvas);
        paint.setShader(null);
        paint.setColorFilter(null);
        paint.setAlpha(255);
        overlayList.clear();
        overlayList.addAll(leaving);
        overlayList.addAll(items);
        for (Item s : overlayList) {
            float x, y, n, rotation, lift = clamp01(s.lift.value), b = clamp01(s.border.value);
            if (s.state == FLIGHT && s.tossed && s.lift.value > .3f) {
                final float[] pose = overlayPose(s);
                x = pose[0];
                y = pose[1];
                n = pose[2] * (1 + lift * .15f);
                rotation = pose[3];
            } else if (s.state == DRAG) {
                final float[] pose = overlayPose(s);
                x = pose[0];
                y = pose[1] - lift * dp(4);
                n = pose[2] * (1 + .04f * s.press.value);
                rotation = pose[3];
            } else if (s.state == HOME || s.state == TRASH) {
                final float t = s.homeT, e = 1 - (1 - t) * (1 - t);
                final RectF tile = s.state == HOME ? tileRect(s.homeCell) : null;
                rotation = s.homeAngle * (1 - e);
                if (s.state == TRASH) {
                    x = lerp(s.homeX, binIconX(), e);
                    y = lerp(s.homeY, binY(), e) - (float) Math.sin(Math.PI * e) * dp(30);
                    n = s.homeN * (1 - e * e) + dp(38) * e * (1 - e);
                    lift = .6f * (1 - e);
                } else if (tile == null) {
                    x = s.homeX;
                    y = s.homeY;
                    n = s.homeN * (1 - e);
                } else {
                    final float arc = (float) Math.sin(Math.PI * e);
                    final float bow = Math.min(dp(90), dist(tile.centerX(), tile.centerY(), s.homeX, s.homeY) * .28f);
                    x = lerp(s.homeX, tile.centerX(), e);
                    y = lerp(s.homeY, tile.centerY(), e) - 2 * (1 - e) * e * bow;
                    final float rest = tile.width() * (cells == null ? .77f : cells.artShare(s.homeCell));
                    n = lerp(s.homeN, rest, e) * (1 + .05f * arc);
                    lift = .8f * arc;
                    b = clamp01(1 - (e - .35f) / .4f);
                }
            } else {
                continue;
            }
            final Bitmap art = s.bitmap(), plain = s.plain;
            if (art == null && plain == null || n < 1) continue;
            canvas.save();
            canvas.rotate(rotation, x, y);
            drawShadow(canvas, s, x, y + dp(1.5f) + lift * dp(5), n * (1 + lift * .04f), lift, 0, b);
            canvas.restore();
            if (s.state == HOME && s.homeT > .6f) {
                // The last part of the way home it peels down into the cell.
                final float k = clamp01((s.homeT - .6f) / .4f);
                final float c = .5f * .9f * (1 - k * k * (3 - 2 * k));
                final float bend = .55f * (1 - .6f * clamp01(-s.curlY));
                if ((b < 1 || art == null) && plain != null) {
                    paint.setAlpha((int) (255 * (art == null ? 1 : 1 - b)));
                    drawCurl(canvas, plain, x, y, n, c, bend, rotation, s.curlX, s.curlY);
                }
                if (b > 0 && art != null) {
                    paint.setAlpha((int) (255 * b));
                    drawCurl(canvas, art, x, y, n, c, bend, rotation, s.curlX, s.curlY);
                }
                paint.setAlpha(255);
                continue;
            }
            canvas.save();
            canvas.rotate(rotation, x, y);
            rect.set(x - n / 2, y - n / 2, x + n / 2, y + n / 2);
            if ((b < 1 || art == null) && plain != null) {
                paint.setAlpha((int) (255 * (art == null ? 1 : 1 - b)));
                canvas.drawBitmap(plain, null, rect, paint);
            }
            if (b > 0 && art != null) {
                paint.setAlpha((int) (255 * b));
                canvas.drawBitmap(art, null, rect, paint);
            }
            paint.setAlpha(255);
            canvas.restore();
        }
    }

    private final ArrayList<Item> overlayList = new ArrayList<>();

    /** 13.1.1 drawCurl(): the art bent along (curlX, curlY) like a peeled sticker, on a mesh. */
    private void drawCurl(Canvas canvas, Bitmap bitmap, float cx, float cy, float n, float curl, float bendScale, float rotation, float curlX, float curlY) {
        final float contact = -n / 2 + n * curl, r = n * .3f, eye = n * 3.2f, maxAngle = .72f * bendScale, arc = r * maxAngle;
        final float cos = (float) Math.cos(Math.toRadians(rotation)), sin = (float) Math.sin(Math.toRadians(rotation));
        int k = 0;
        for (int row = 0; row <= MESH; row++) {
            for (int col = 0; col <= MESH; col++) {
                final float ax = (col / (float) MESH - .5f) * n, ay = (row / (float) MESH - .5f) * n;
                final float lx = ax * cos - ay * sin, ly = ax * sin + ay * cos, p = lx * curlX + ly * curlY, q = -lx * curlY + ly * curlX;
                float along = p, z = 0, shade = 1;
                final float d = contact - p;
                if (d > 0) {
                    final float phi = Math.min(maxAngle, d / r), rest = Math.max(0, d - arc);
                    along = contact - r * (float) Math.sin(phi) - rest * (float) Math.cos(phi);
                    z = r * (1 - (float) Math.cos(phi)) + rest * (float) Math.sin(phi);
                    shade = .62f + .38f * (float) Math.cos(phi);
                }
                final float perspective = eye / (eye - z);
                mesh[k * 2] = cx + (along * curlX - q * curlY) * perspective;
                mesh[k * 2 + 1] = cy + (along * curlY + q * curlX) * perspective - z * .15f;
                final int c = (int) (255 * shade);
                meshColors[k++] = Color.rgb(c, c, c);
            }
        }
        canvas.drawBitmapMesh(bitmap, MESH, MESH, mesh, 0, meshColors, 0, paint);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    static final class Spring {
        float value, target, velocity, stiffness, damping;

        Spring(float v, float k, float d) {
            value = target = v;
            stiffness = k;
            damping = d;
        }

        Spring set(float v) {
            value = target = v;
            velocity = 0;
            return this;
        }

        Spring to(float v) {
            target = v;
            return this;
        }

        Spring with(float k, float d) {
            stiffness = k;
            damping = d;
            return this;
        }

        boolean step(float dt) {
            final float friction = 2 * damping * (float) Math.sqrt(stiffness);
            for (float left = dt; left > 0; ) {
                final float h = Math.min(left, 1f / 480);
                velocity += (-stiffness * (value - target) - friction * velocity) * h;
                value += velocity * h;
                left -= h;
            }
            final boolean moving = Math.abs(value - target) > .002f || Math.abs(velocity) > .02f;
            if (!moving) set(target);
            return moving;
        }
    }

    private static final class Ripple {
        final float x, y;
        float age;

        Ripple(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class Spark {
        float x, y, vx, vy, size, life, rot, age;
    }

    private void spawnSparks(float x, float y) {
        final java.util.Random random = new java.util.Random();
        for (int i = 0; i < 14; i++) {
            final Spark sp = new Spark();
            final float a = (float) (i / 14f * Math.PI * 2) + random.nextFloat() * .35f, speed = dp(265) + random.nextFloat() * dp(180);
            sp.x = x;
            sp.y = y;
            sp.vx = (float) Math.cos(a) * speed;
            sp.vy = (float) Math.sin(a) * speed;
            sp.size = dp(.8f) + random.nextFloat() * dp(1.1f);
            sp.life = .7f + random.nextFloat() * .35f;
            sp.rot = random.nextFloat() * 360;
            sparks.add(sp);
        }
    }

    private void wake() {
        if (disposed) return;
        cardLayer.removeCallbacks(tick);
        cardLayer.postOnAnimation(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (disposed || !cardLayer.isAttachedToWindow()) return;
            final long now = SystemClock.uptimeMillis();
            final float dt = lastFrame == 0 ? 1f / 60 : Math.min(.032f, (now - lastFrame) / 1000f);
            lastFrame = now;
            boolean moving = false;
            for (int i = 0; i < items.size(); i++) {
                final Item s = items.get(i);
                for (Spring spring : s.springs) moving |= spring.step(dt);
                if (s.state == FLIGHT) {
                    // 13.1.1 tick(): FLIGHT -> ring, contact (squash, kick, sparks, colours), slap, CARD.
                    final float tx = s.x * width, ty = s.y * height;
                    s.fx.to(tx);
                    s.fy.to(ty);
                    final float d = dist(s.fx.value + s.arcX.value, s.fy.value + s.arcY.value, tx, ty);
                    if (!s.ring && s.lift.value < .36f && d < dp(24)) {
                        s.ring = true;
                        if (width > 0) ripples.add(new Ripple(tx, ty));
                    }
                    if (!s.contact && s.lift.value < .22f && d < dp(12)) {
                        s.contact = true;
                        s.impact.set(1).to(0);
                        squash.with(s.flewIn ? 420 : 260, s.flewIn ? .38f : .85f);
                        squash.velocity -= s.flewIn ? .75f : .28f;
                        final float push = s.flewIn ? 3.4f : 1.4f;
                        kickH.velocity += (s.x - .5f) * 2 * push;
                        kickV.velocity += (s.y - .5f) * 2 * push;
                        if (s.flewIn && width > 0) spawnSparks(tx, ty);
                        if (pendingReveal == s) {
                            revealX = s.x;
                            revealY = s.y;
                            startReveal();
                        }
                    }
                    if (!s.slapped && s.curl.value < .3f) {
                        s.slapped = true;
                        s.fly.velocity -= 1.4f;
                        cardLayer.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    }
                    if (s.slapped && s.contact && d < .5f && Math.abs(s.fly.value - 1) < .004f && Math.abs(s.fly.velocity) < .02f) {
                        s.state = CARD;
                        s.fly.set(1);
                        s.curl.set(0);
                        s.fallback = null;
                        s.tossed = s.flewIn = false;
                    }
                    moving = true;
                }
                if (s.landing && s.lift.value < .22f) {
                    s.landing = false;
                    s.lift.with(380, .82f);
                    s.impact.set(1).to(0);
                    squash.with(s.flewIn ? 420 : 260, s.flewIn ? .38f : .85f);
                    squash.velocity -= s.flewIn ? .75f : .28f;
                    final float push = s.flewIn ? 3.4f : 1.4f;
                    kickH.velocity += (s.x - .5f) * 2 * push;
                    kickV.velocity += (s.y - .5f) * 2 * push;
                    if (width > 0) {
                        ripples.add(new Ripple(s.x * width, s.y * height));
                        if (s.flewIn) spawnSparks(s.x * width, s.y * height);
                    }
                    s.flewIn = false;
                    if (pendingReveal == s) {
                        revealX = s.x;
                        revealY = s.y;
                        startReveal();
                    }
                    cardLayer.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    moving = true;
                }
                if (s.landing) moving = true;
            }
            boolean cellsBack = false;
            for (int i = leaving.size() - 1; i >= 0; i--) {
                final Item s = leaving.get(i);
                for (Spring spring : s.springs) moving |= spring.step(dt);
                final boolean done;
                if (s.state == HOME || s.state == TRASH) {
                    s.homeT = Math.min(1, s.homeT + dt / s.homeDuration);
                    done = s.homeT >= 1;
                    if (done && s.state == HOME) {
                        cellsBack = true;
                        final View c = s.homeCell;
                        if (c != null) {
                            // 13.1.1: the cell dips as the gift lands in it and springs back.
                            c.animate().cancel();
                            c.animate().scaleX(.955f).scaleY(.955f).setDuration(70).setInterpolator(null)
                                    .withEndAction(() -> c.animate().scaleX(1).scaleY(1).setDuration(260).setInterpolator(new android.view.animation.OvershootInterpolator(2)).start()).start();
                        }
                        cardLayer.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    }
                } else {
                    done = s.appear.value < .03f;
                }
                if (done) {
                    leaving.remove(i);
                    s.homeCell = null;
                    s.image.onDetachedFromWindow();
                } else {
                    moving = true;
                }
            }
            if (cellsBack && cells != null) cells.onCellsChanged();
            boolean trashing = false;
            for (Item s : leaving) if (s.state == TRASH) trashing = true;
            final boolean dragging = touched != null && touched.state == DRAG;
            bin.to(dragging && bin.target > 0 || trashing || trashClosing ? 1 : 0);
            binHover.to(overBin || trashing || trashClosing ? 1 : 0);
            if (trashClosing) {
                trashFrame.set(Math.min(66, trashFrame.value + dt * 60));
                if (trashFrame.value >= 66) {
                    trashClosing = false;
                    trashFrame.set(0);
                }
                moving = true;
            } else {
                trashFrame.to(overBin ? 28 : 0);
                moving |= trashFrame.step(dt);
            }
            moving |= bin.step(dt) | binHover.step(dt);
            if (dragging) moving = true;
            for (int i = sparks.size() - 1; i >= 0; i--) {
                final Spark sp = sparks.get(i);
                sp.age += dt;
                final float drag = (float) Math.exp(-2.4f * dt);
                sp.vx *= drag;
                sp.vy *= drag;
                sp.x += sp.vx * dt;
                sp.y += sp.vy * dt;
                sp.rot += 90 * dt;
                if (sp.age >= sp.life) sparks.remove(i);
            }
            for (int i = ripples.size() - 1; i >= 0; i--) {
                final Ripple r = ripples.get(i);
                r.age += dt;
                if (r.age > .42f) ripples.remove(i);
            }
            if (revealing) {
                reveal = Math.min(1, reveal + dt / (fadeReveal ? .45f : 1.2f));
                if (reveal >= 1) revealing = false;
                applyStyle();
                patternLayer.invalidate();
                moving = true;
            }
            moving |= squash.step(dt) | kickH.step(dt) | kickV.step(dt);
            card.setStickerImpact(squash.value);
            card.setStickerKick(Math.max(-1, Math.min(1, kickH.value)), Math.max(-1, Math.min(1, kickV.value)));
            invalidateCard();
            if (moving || !sparks.isEmpty() || !ripples.isEmpty()) cardLayer.postOnAnimation(this);
            else lastFrame = 0;
        }
    };

    private void drawEffects(Canvas canvas) {
        for (Spark sp : sparks) {
            final float t = sp.age / sp.life;
            final float pop = t < .1f ? t / .1f : (1 - (t - .1f) / .9f) * (1 - (t - .1f) / .9f);
            final float r = sp.size * (.6f + .4f * pop);
            paint.setColor(Color.WHITE);
            paint.setAlpha((int) (130 * clamp01(pop)));
            canvas.save();
            canvas.translate(sp.x, sp.y);
            canvas.rotate(sp.rot);
            sparkPath.rewind();
            sparkPath.moveTo(0, -r * 2);
            sparkPath.lineTo(r * .35f, -r * .35f);
            sparkPath.lineTo(r * 2, 0);
            sparkPath.lineTo(r * .35f, r * .35f);
            sparkPath.lineTo(0, r * 2);
            sparkPath.lineTo(-r * .35f, r * .35f);
            sparkPath.lineTo(-r * 2, 0);
            sparkPath.lineTo(-r * .35f, -r * .35f);
            sparkPath.close();
            canvas.drawPath(sparkPath, paint);
            canvas.restore();
        }
        paint.setAlpha(255);
        for (Ripple r : ripples) {
            final float t = clamp01(r.age / .42f), e = 1 - (1 - t) * (1 - t) * (1 - t);
            line.setColor(Color.WHITE);
            line.setAlpha((int) (90 * (1 - t)));
            line.setStrokeWidth(dp(2.4f) * (1 - t));
            canvas.drawCircle(r.x, r.y, width * DEFAULT_SCALE * (.42f + .43f * e), line);
        }
    }

    // ---------------------------------------------------------------- drawing

    private void drawPatterns(Canvas canvas) {
        if (width <= 0) return;
        final float p = fadeReveal ? reveal * reveal * (3 - 2 * reveal) : 1 - (float) Math.pow(1 - reveal, 4);
        final float radius = fadeReveal || p >= 1 ? -1 : width * 1.2f * p;
        if (oldBackground != null && oldBackground != shownBackground && p < 1) {
            drawPattern(canvas, oldBackground, fadeReveal ? 1 - p : 1, radius, true);
        }
        if (shownBackground != null) drawPattern(canvas, shownBackground, fadeReveal ? p : 1, radius, false);
    }

    private void drawPattern(Canvas canvas, Background b, float alpha, float radius, boolean leaving) {
        final Bitmap bitmap = b.pattern.getBitmap();
        if (bitmap == null || b.backdrop == null) return;
        canvas.save();
        WalletCardGeometry.setFacePath(path, width, height);
        canvas.clipPath(path);
        card.getQrButtonPath(path, width, height);
        canvas.clipPath(path, android.graphics.Region.Op.DIFFERENCE);
        if (radius >= 0 && !leaving) {
            path.rewind();
            path.addCircle(revealX * width, revealY * height, radius, Path.Direction.CW);
            canvas.clipPath(path);
        }
        paint.setColorFilter(new PorterDuffColorFilter(b.backdrop.pattern_color | 0xFF000000, PorterDuff.Mode.SRC_IN));
        final float scaleX = width / 336f, scaleY = height / 205f;
        for (int col = 0, gx = 16; gx < 336; gx += 33, col++) {
            for (int gy = 16 + (col % 2) * 18; gy < 205; gy += 37) {
                final float x = gx * scaleX, y = gy * scaleY;
                float n = 12 * scaleX;
                final float edgeDistance = Math.min(Math.min(gx, 336 - gx), Math.min(gy, 205 - gy));
                float edgeFade = clamp01(edgeDistance / 48f);
                edgeFade = edgeFade * edgeFade * (3 - 2 * edgeFade);
                float opacity = alpha * .15f * edgeFade;
                if (radius >= 0) {
                    final float t = clamp01((radius - dist(x, y, revealX * width, revealY * height)) / dp(120));
                    if (leaving) {
                        n *= 1 - .8f * t;
                        opacity *= 1 - clamp01(t * 1.6f);
                    } else {
                        n *= .2f + .8f * (t + .28f * (float) Math.sin(Math.PI * t));
                        opacity *= clamp01(t * 1.6f);
                    }
                }
                paint.setAlpha((int) (255 * opacity));
                rect.set(x - n / 2, y - n / 2, x + n / 2, y + n / 2);
                canvas.drawBitmap(bitmap, null, rect, paint);
            }
        }
        paint.setColorFilter(null);
        paint.setAlpha(255);
        canvas.restore();
    }

    private void drawOnCard(Canvas canvas) {
        if (width <= 0) return;
        paint.setShader(null);
        paint.setColorFilter(null);
        final float shift = (rotY + rotX * .6f) / 10f;
        sheenMatrix.setScale(width * 1.5f, 1);
        sheenMatrix.postRotate(-24);
        sheenMatrix.postTranslate(-width * .25f + shift * width * .45f, 0);
        sheen.setLocalMatrix(sheenMatrix);
        final ArrayList<RectF> obstacles = card.getStickerObstacles();
        final RectF balanceText = obstacles.get(0), holderText = obstacles.get(1), numberMask = obstacles.get(2), qrMask = obstacles.get(3), usdText = obstacles.get(4), diamondIcon = obstacles.get(5);
        float balanceShadow = 0, holderShadow = 0, diamondFallback = 0, numberShadow = 0;
        drawEffects(canvas);
        drawList.clear();
        drawList.addAll(leaving);
        drawList.addAll(items);
        for (Item s : drawList) {
            // Flying high, flying home, falling into the bin or dragged: drawn by the overlay.
            if (s.state == HOME || s.state == TRASH || s.state == DRAG && overlay != null
                    || s.state == FLIGHT && s.tossed && s.lift.value > .3f) continue;
            final boolean flight = s.state == FLIGHT;
            final Bitmap bitmap = flight ? s.drawable() : s.bitmap();
            final float appear = clamp01(s.appear.value);
            final float press = s.press.value;
            final float n = width * s.scale * s.bump.value * s.fly.value * (1 + .04f * press) * (.8f + .2f * s.appear.value);
            if (bitmap == null || n < 1) continue;
            final float lift = Math.max(0, Math.max(s.lift.value, press * .7f));
            final float depth = dp(1.5f) + lift * dp(9);
            final float px = (float) Math.sin(Math.toRadians(rotY)) * depth * 2.2f, py = (float) Math.sin(Math.toRadians(rotX)) * depth * 2.2f;
            final float cx = flight ? s.fx.value + s.arcX.value : s.x * width, cy = flight ? s.fy.value + s.arcY.value : s.y * height;
            final float rotation = flight ? s.spin.value : s.rotation;
            final float x = cx + px, y = cy + py - lift * dp(4);
            final float sx = cx - px * .3f, sy = cy - py * .3f + depth * .22f;
            stickerBounds.set(Math.min(x, sx) - n, Math.min(y, sy) - n, Math.max(x, sx) + n, Math.max(y, sy) + n);
            final boolean overQr = RectF.intersects(stickerBounds, qrMask);
            rect.set(x - n * .4f, y - n * .4f, x + n * .4f, y + n * .4f);
            balanceShadow = Math.max(balanceShadow, appear * Math.max(coverage(rect, balanceText), coverage(rect, usdText)));
            holderShadow = Math.max(holderShadow, appear * coverage(rect, holderText));
            diamondFallback = Math.max(diamondFallback, appear * coverage(rect, diamondIcon));
            numberShadow = Math.max(numberShadow, appear * coverage(rect, numberMask));
            final int maskLayer = overQr ? canvas.saveLayer(stickerBounds, null) : -1;
            canvas.save();
            canvas.rotate(rotation, sx, sy);
            drawShadow(canvas, s, sx, sy, n * (.97f + lift * .08f + s.impact.value * .07f), Math.max(Math.min(1, lift), s.curl.value * .5f), s.impact.value, appear);
            canvas.restore();
            // The sheen needs its own layer per sticker (SRC_ATOP); weak GPUs skip both.
            final int layer = lowEnd ? -1 : canvas.saveLayer(x - n, y - n, x + n, y + n, null);
            paint.setAlpha((int) (255 * appear));
            if (s.curl.value > .004f) {
                // 13.1.1: the tossed sticker lands peeled and flattens onto the card.
                drawCurl(canvas, bitmap, x, y, n, s.curl.value, 1, rotation, s.curlX, s.curlY);
            } else {
                canvas.save();
                canvas.rotate(rotation, x, y);
                rect.set(x - n / 2, y - n / 2, x + n / 2, y + n / 2);
                canvas.drawBitmap(bitmap, null, rect, paint);
                canvas.restore();
            }
            paint.setAlpha(255);
            if (layer != -1) {
                canvas.drawRect(x - n, y - n, x + n, y + n, sheenPaint);
                canvas.restoreToCount(layer);
            }
            if (overQr) {
                card.getQrButtonPath(path, width, height);
                canvas.drawPath(path, erasePaint);
                canvas.restoreToCount(maskLayer);
            }
        }
        if (editable) drawSelection(canvas);
        card.setStickerTextShadow(balanceShadow, holderShadow, diamondFallback, numberShadow);
    }

    private final ArrayList<Item> drawList = new ArrayList<>();

    private void drawShadow(Canvas canvas, Item s, float cx, float cy, float size, float lift, float impact, float presence) {
        if (presence <= .01f || s.shadow == null) return;
        final float n = size * 176f / 128f * (1 + lift * .05f);
        rect.set(cx - n / 2, cy - n / 2, cx + n / 2, cy + n / 2);
        paint.setColor(0xFF0B2E5C);
        paint.setAlpha((int) (255 * clamp01(.16f + (.11f - .16f) * lift + .12f * impact) * presence));
        canvas.drawBitmap(s.shadow, null, rect, paint);
        paint.setColor(Color.WHITE);
        paint.setAlpha(255);
    }

    private float selectionRadius(Item s) {
        return width * s.scale * .5f + dp(9);
    }

    private void drawSelection(Canvas canvas) {
        final Item s = selected;
        if (s == null || !items.contains(s) || s.state != CARD) return;
        final float cx = s.x * width, cy = s.y * height;
        final float r = selectionRadius(s);
        selectionDash.setColor(Color.WHITE);
        selectionDash.setStyle(Paint.Style.STROKE);
        selectionDash.setStrokeCap(Paint.Cap.ROUND);
        selectionDash.setStrokeWidth(dp(2));
        if (selectionDash.getPathEffect() == null) {
            selectionDash.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(10), dp(10)}, .5f));
        }
        selectionDash.setShadowLayer(dp(.75f), 0, 0, 0x50000000);
        selectionStroke.setColor(Color.WHITE);
        selectionStroke.setStyle(Paint.Style.STROKE);
        selectionStroke.setStrokeWidth(dp(2.66f));
        selectionStroke.setShadowLayer(dp(.75f), 0, 0, 0x50000000);
        selectionDot.setColor(0xFF1A9CFF);
        canvas.save();
        canvas.rotate(s.rotation, cx, cy);
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 0, 180, false, selectionDash);
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 180, 180, false, selectionDash);
        for (int side = -1; side <= 1; side += 2) {
            canvas.drawCircle(cx + side * r, cy, dp(5.66f), selectionStroke);
            canvas.drawCircle(cx + side * r, cy, dp(4.66f), selectionDot);
        }
        canvas.restore();
    }

    // ---------------------------------------------------------------- touches (edit mode)

    private boolean geometry() {
        width = card.getFrontFace().getWidth();
        height = card.getFrontFace().getHeight();
        return width > 0 && height > 0 && card.getFrontFaceTransform(this, face) && face.invert(inverse);
    }

    private float[] local(float x, float y) {
        final float[] p = {x, y};
        inverse.mapPoints(p);
        return p;
    }

    private float[] root(float x, float y) {
        final float[] p = {x, y};
        face.mapPoints(p);
        return p;
    }

    private Item itemAt(float fx, float fy) {
        Item hit = null;
        for (Item s : items) {
            if (s.state != CARD) continue;
            if (dist(fx, fy, s.x * width, s.y * height) < Math.max(dp(18), width * s.scale * .5f)) hit = s;
        }
        return hit;
    }

    private boolean handleAt(float x, float y) {
        final Item s = selected;
        if (s == null) return false;
        final float r = selectionRadius(s);
        final double a = Math.toRadians(s.rotation);
        for (int side = -1; side <= 1; side += 2) {
            final float[] h = root(s.x * width + side * r * (float) Math.cos(a), s.y * height + side * r * (float) Math.sin(a));
            if (dist(x, y, h[0], h[1]) < dp(22)) return true;
        }
        return false;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (!editable) return false;
        return super.dispatchTouchEvent(event);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!editable) return false;
        final float x = event.getX(), y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (!geometry()) return false;
                mode = MODE_NONE;
                moved = false;
                downX = x;
                downY = y;
                if (handleAt(x, y)) {
                    mode = MODE_KNOB;
                    knobAngle = Float.NaN;
                    knobDistance = Float.NaN;
                    knobScale = selected.scale;
                    knobRotation = selected.rotation;
                    snapped = false;
                    selected.press.to(1);
                    wake();
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                final float[] p = local(x, y);
                touched = itemAt(p[0], p[1]);
                if (touched == null) {
                    // Not a sticker: deselect, and let the card below tilt as usual.
                    if (selected != null) {
                        selected = null;
                        invalidateCard();
                        if (listener != null) listener.onSelectionChanged(false);
                    }
                    return false;
                }
                grabDx = touched.x * width - p[0];
                grabDy = touched.y * height - p[1];
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                final Item s = selected != null ? selected : touched;
                if (s != null && event.getPointerCount() == 2) {
                    select(s);
                    mode = MODE_PINCH;
                    pinchDistance = (float) Math.hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0));
                    pinchAngle = (float) Math.toDegrees(Math.atan2(event.getY(1) - event.getY(0), event.getX(1) - event.getX(0)));
                    pinchScale = s.scale;
                    pinchRotation = s.rotation;
                    moved = true;
                    s.press.to(1);
                    wake();
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mode == MODE_KNOB && selected != null) {
                    knob(selected, x, y);
                    return true;
                }
                if (mode == MODE_PINCH && selected != null && event.getPointerCount() >= 2) {
                    final float d = (float) Math.hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0));
                    final float a = (float) Math.toDegrees(Math.atan2(event.getY(1) - event.getY(0), event.getX(1) - event.getX(0)));
                    selected.scale = clamp(pinchScale * d / Math.max(1, pinchDistance), MIN_SCALE, MAX_SCALE);
                    selected.rotation = snap(pinchRotation + angle(a - pinchAngle));
                    invalidateCard();
                    return true;
                }
                if (touched == null) return false;
                if (!moved && Math.hypot(x - downX, y - downY) > dp(4)) {
                    moved = true;
                    mode = MODE_MOVE;
                    select(touched);
                    bringToFront(touched);
                    // 13.1.1 pick-up: the sticker peels off and rises with a deeper shadow.
                    touched.lift.to(1);
                    touched.press.to(1);
                    // Above everything now, so it can leave the card (13.1.1 DRAG).
                    if (overlay != null) touched.state = DRAG;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    wake();
                }
                if (mode == MODE_MOVE) {
                    final float[] p = local(x, y);
                    touched.x = (p[0] + grabDx) / width;
                    touched.y = (p[1] + grabDy) / height;
                    if (touched.state == DRAG && overlay != null && overlay.getHeight() > 0) {
                        // 13.1.1 drag(): off the card the bin rises; near it, it opens.
                        final float m = .02f;
                        final boolean over = touched.x >= -m && touched.x <= 1 + m && touched.y >= -m && touched.y <= 1 + m;
                        getLocationOnScreen(loc);
                        final float ox = x + loc[0], oy = y + loc[1];
                        overlay.getLocationOnScreen(loc);
                        final boolean near = !over && dist(ox - loc[0], oy - loc[1], overlay.getWidth() / 2f, binY()) < dp(78);
                        if (near != overBin) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        overBin = near;
                        bin.to(over ? 0 : 1);
                        wake();
                    }
                    invalidateCard();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                final boolean cancel = event.getActionMasked() == MotionEvent.ACTION_CANCEL;
                getParent().requestDisallowInterceptTouchEvent(false);
                final int was = mode;
                mode = MODE_NONE;
                if (was == MODE_NONE && touched != null && !cancel) {
                    touched.bump.velocity -= 1.2f;
                    wake();
                    if (selected == touched) {
                        bringToFront(touched);
                    }
                    select(touched);
                } else if (was == MODE_MOVE && touched != null) {
                    // Dropped off the card: the sticker is removed - into the bin, or home into its cell.
                    final float m = .02f;
                    final boolean intoBin = overBin;
                    overBin = false;
                    bin.to(0);
                    if (touched.state == DRAG) touched.state = CARD;
                    if (!cancel && (intoBin || touched.x < -m || touched.x > 1 + m || touched.y < -m || touched.y > 1 + m)) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        removeItem(touched, intoBin ? TRASH : HOME);
                    } else {
                        touched.x = clamp(touched.x, 0, 1);
                        touched.y = clamp(touched.y, 0, 1);
                        // Dropped back on the card: it lands with a small squash.
                        touched.lift.to(0);
                        touched.press.to(0);
                        touched.landing = true;
                        wake();
                        settle(touched);
                        changed();
                    }
                } else if (was == MODE_KNOB || was == MODE_PINCH) {
                    if (selected != null) {
                        selected.press.to(0);
                        wake();
                    }
                    settle(selected);
                    changed();
                }
                touched = null;
                invalidateCard();
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
                if (mode == MODE_PINCH) {
                    mode = MODE_NONE;
                    touched = null;
                    if (selected != null) {
                        selected.press.to(0);
                        wake();
                    }
                    settle(selected);
                    changed();
                }
                return true;
        }
        return true;
    }

    private final int[] loc = new int[2];

    private void select(Item s) {
        if (selected == s) return;
        selected = s;
        invalidateCard();
        if (listener != null) listener.onSelectionChanged(s != null);
    }

    private void bringToFront(Item s) {
        if (items.remove(s)) items.add(s);
    }

    /** The side handle: distance from the centre scales, the angle around it rotates (13.1.1 rotate()). */
    private void knob(Item s, float x, float y) {
        final float[] c = root(s.x * width, s.y * height);
        final float distance = dist(x, y, c[0], c[1]);
        if (Float.isNaN(knobDistance)) knobDistance = distance;
        s.scale = clamp(knobScale * distance / Math.max(1, knobDistance), MIN_SCALE, MAX_SCALE);
        if (distance >= dp(14)) {
            final float a = (float) Math.toDegrees(Math.atan2(y - c[1], x - c[0]));
            if (Float.isNaN(knobAngle)) {
                knobAngle = a;
            }
            s.rotation = snap(knobRotation + angle(a - knobAngle));
        }
        invalidateCard();
    }

    private float snap(float target) {
        final float nearest = Math.round(target / 90) * 90;
        final boolean snap = Math.abs(target - nearest) < 5;
        if (snap && !snapped) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        snapped = snap;
        return angle(snap ? nearest : target);
    }

    // ---------------------------------------------------------------- utils

    private static float coverage(RectF art, RectF text) {
        final float w = Math.min(art.right, text.right) - Math.max(art.left, text.left);
        final float h = Math.min(art.bottom, text.bottom) - Math.max(art.top, text.top);
        if (w <= 0 || h <= 0 || text.width() <= 0 || text.height() <= 0) return 0;
        return clamp01(w * h / (text.width() * text.height()) * 4);
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x1 - x2, y1 - y2);
    }

    private static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float angle(float a) {
        a %= 360;
        if (a > 180) a -= 360;
        if (a < -180) a += 360;
        return a;
    }

    /** Whether a sticker document may go on a card: static webp/png only (no TGS, no video). */
    public static boolean isStaticSticker(TLRPC.Document document) {
        if (document == null || document.mime_type == null) return false;
        if (MessageObject.isAnimatedStickerDocument(document, true) || MessageObject.isVideoStickerDocument(document)) return false;
        return "image/webp".equals(document.mime_type) || "image/png".equals(document.mime_type);
    }
}
