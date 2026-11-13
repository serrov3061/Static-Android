package com.exteragram.messenger.badges;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.View;
import android.view.ViewParent;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LiteMode;
import org.telegram.ui.ActionBar.Theme;

import java.lang.ref.WeakReference;
import java.util.Map;

/**
 * StaticGram avatar frames v2 (frames-demo/CONTRACT.md): inferno, pixel, solitaire, cosmos and
 * frost with per-user settings ({@link FrameParams}), drawn by {@link FrameRenderer} around a badge
 * holder's avatar for every exteraStatic user.
 *
 * <p>Callers draw a frame only while no story ring is shown, so the story ring always wins.
 * The frame takes room outside the photo: the photo keeps its size and the frame is drawn outside
 * the avatar bounds, shrunk only as much as the current clip (the cell or view) requires, down to
 * {@link #MIN_FIT} of its natural size; whatever still does not fit is clipped by the view.
 *
 * <p>Performance: everything blurred is pre-rendered per look and pixel size (LRU, {@link #cacheBytes});
 * a frame animates at most at 30 fps, only in views that are being drawn, in at most
 * {@link #MAX_ANIMATED} views at once, and never in power saving / reduced motion (LiteMode) or
 * with {@code anim=0}; otherwise one pre-rendered bitmap is drawn.
 */
public final class AvatarFrames {

    public static final String INFERNO = FrameParams.INFERNO;
    public static final String PIXEL = FrameParams.PIXEL;
    public static final String SOLITAIRE = FrameParams.SOLITAIRE;
    public static final String COSMOS = FrameParams.COSMOS;
    public static final String FROST = FrameParams.FROST;
    public static final String NEKO = FrameParams.NEKO;
    public static final String ANGEL = FrameParams.ANGEL;
    public static final String DEVIL = FrameParams.DEVIL;

    /** Picker order (same as the server's domain.BadgeFrames). */
    public static final String[] STYLES = FrameParams.STYLES;

    /** Frames animate in at most this many views at once; the others draw their static frame. */
    public static final int MAX_ANIMATED = 6;
    private static final long FRAME_MS = 33;
    /** A view that has not drawn an animated frame for this long gives its animation slot back. */
    private static final long SLOT_IDLE_MS = 250;
    /** Retry delay for a view that did not get an animation slot. */
    private static final long SLOT_RETRY_MS = 1000;
    /** The frame may shrink to this share of its natural size to fit the room around the avatar. */
    private static final float MIN_FIT = 0.86f;
    private static final int MAX_SIDE = 720;

    private static final Rect clipRect = new Rect();
    private static final RectF rect = new RectF();
    private static final RectF dst = new RectF();
    private static final Paint posterPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private static final int cacheBytes = (int) Math.min(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 10);
    private static final LruCache<String, FrameRenderer.Layers> cache = new LruCache<String, FrameRenderer.Layers>(cacheBytes) {
        @Override
        protected int sizeOf(String key, FrameRenderer.Layers value) {
            return value.estimateBytes();
        }
    };
    private static DispatchQueue stripQueue;

    @SuppressWarnings("unchecked")
    private static final WeakReference<View>[] slots = new WeakReference[MAX_ANIMATED];
    private static final long[] slotSeen = new long[MAX_ANIMATED];

    /** Debug counters (see {@link #stats()}). */
    private static long statLive, statPoster, statLiveNanos, statBuildNanos;

    private AvatarFrames() {
    }

    public static boolean isKnown(String style) {
        return FrameParams.isKnownStyle(style);
    }

    /** The frame of a peer with its settings (users only; chats and channels have none). */
    public static FrameParams paramsOf(long dialogId) {
        if (dialogId <= 0) {
            return null;
        }
        String frame = BadgesController.INSTANCE.getFrame(dialogId);
        if (!isKnown(frame)) {
            return null;
        }
        Map<String, String> raw = BadgesController.INSTANCE.getFrameParams(dialogId);
        // parsed once per (style, settings map instance) instead of on every animated draw
        Object[] cached = parsed.get(dialogId);
        if (cached != null && cached[0].equals(frame) && cached[1] == raw) {
            return (FrameParams) cached[2];
        }
        FrameParams params = FrameParams.of(frame, raw);
        parsed.put(dialogId, new Object[]{frame, raw, params});
        return params;
    }

    private static final android.util.LruCache<Long, Object[]> parsed = new android.util.LruCache<>(256);

    /** The frame style of a peer, or null. */
    public static String frameOf(long dialogId) {
        if (dialogId <= 0) {
            return null;
        }
        String frame = BadgesController.INSTANCE.getFrame(dialogId);
        return isKnown(frame) ? frame : null;
    }

    public static Map<String, String> frameParamsOf(long dialogId) {
        return dialogId > 0 ? BadgesController.INSTANCE.getFrameParams(dialogId) : null;
    }

    /**
     * Draws the peer's frame over an avatar that was just drawn with {@code avatarImage}'s current
     * coordinates. Call it only when no story ring is shown. The frame fits the current clip.
     */
    public static void drawFor(long dialogId, Canvas canvas, ImageReceiver avatarImage, float alpha) {
        drawFor(dialogId, canvas, avatarImage, alpha, false);
    }

    /** {@code overflow}: the site has room outside the canvas clip (the frame need not shrink). */
    public static void drawFor(long dialogId, Canvas canvas, ImageReceiver avatarImage, float alpha, boolean overflow) {
        drawFor(dialogId, canvas, avatarImage, alpha, overflow, null);
    }

    /** {@code host}: the view that actually draws the frame (re-invalidated for animation); null = the receiver parent. */
    public static void drawFor(long dialogId, Canvas canvas, ImageReceiver avatarImage, float alpha, boolean overflow, View host) {
        if (avatarImage == null || alpha <= 0f) {
            return;
        }
        FrameParams params = paramsOf(dialogId);
        if (params == null) {
            return;
        }
        rect.set(avatarImage.getImageX(), avatarImage.getImageY(), avatarImage.getImageX2(), avatarImage.getImageY2());
        draw(canvas, rect, params, alpha, host != null ? host : avatarImage.getParentView(), overflow);
    }

    /** Draws {@code params} around the circular avatar in {@code bounds}. */
    public static void draw(Canvas canvas, RectF bounds, FrameParams params, float alpha, View parent, boolean overflow) {
        if (params == null || alpha <= 0f || bounds.width() <= 1 || bounds.height() <= 1) {
            return;
        }
        try {
            drawInternal(canvas, bounds, params, alpha, parent, overflow);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void drawInternal(Canvas canvas, RectF bounds, FrameParams params, float alpha, View parent, boolean overflow) {
        final float cx = bounds.centerX(), cy = bounds.centerY();
        final float radius = Math.min(bounds.width(), bounds.height()) / 2f;
        float u = radius / FrameRenderer.avatarU(params.style);
        if (!overflow && canvas.getClipBounds(clipRect)) {
            float room = Math.min(Math.min(cx - clipRect.left, clipRect.right - cx), Math.min(cy - clipRect.top, clipRect.bottom - cy));
            if (room > 0 && 50 * u > room) {
                u = Math.max(u * MIN_FIT, room / 50f);
            }
        }
        final float box = 100 * u;
        int side = (int) Math.ceil(box * viewScale(parent) / 8f) * 8;
        side = Math.max(16, Math.min(MAX_SIDE, side));
        final boolean light = !Theme.isCurrentThemeDark();
        final String key = params.sizedKey(side, light);
        long t0 = System.nanoTime();
        FrameRenderer.Layers layers = cache.get(key);
        if (layers == null) {
            layers = new FrameRenderer.Layers(params, light, side);
            cache.put(key, layers);
        }
        final long now = SystemClock.elapsedRealtime();
        boolean animate = params.anim && parent != null && motionAllowed();
        if (animate && !acquireSlot(parent, now)) {
            animate = false;
            parent.postInvalidateDelayed(SLOT_RETRY_MS);
        }
        if (animate && layers.needsStrip() && !layers.isStripReady()) {
            requestStrip(layers, parent);
            animate = false;
        }
        final boolean saveLayer = alpha < 0.995f;
        dst.set(cx - box / 2f, cy - box / 2f, cx + box / 2f, cy + box / 2f);
        if (saveLayer) {
            canvas.saveLayerAlpha(dst, Math.round(255 * alpha));
        }
        if (animate) {
            float p = FrameRenderer.phase(now, params.speed);
            canvas.save();
            canvas.translate(dst.left, dst.top);
            canvas.scale(box / side, box / side);
            FrameRenderer.drawLive(canvas, layers, p);
            canvas.restore();
            long next = FrameRenderer.nextChangeMs(layers, p, params.speed);
            parent.postInvalidateDelayed(next > 0 ? next : FRAME_MS);
            statLive++;
            statLiveNanos += System.nanoTime() - t0;
        } else {
            Bitmap poster = layers.poster();
            canvas.drawBitmap(poster, null, dst, posterPaint);
            statPoster++;
            statBuildNanos += System.nanoTime() - t0;
        }
        if (saveLayer) {
            canvas.restore();
        }
    }

    /** Battery saver / LiteMode animations off / system animations off (reduced motion) draw static frames. */
    public static boolean motionAllowed() {
        if (!LiteMode.isEnabled(LiteMode.FLAG_ANIMATED_EMOJI_CHAT)) {
            return false;
        }
        return Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled();
    }

    /** Product of the view scales up the hierarchy, so a magnified avatar (profile header) gets sharp bitmaps. */
    private static float viewScale(View view) {
        float s = 1f;
        int depth = 0;
        View v = view;
        while (v != null && depth++ < 10) {
            s *= Math.max(Math.abs(v.getScaleX()), Math.abs(v.getScaleY()));
            ViewParent vp = v.getParent();
            v = vp instanceof View ? (View) vp : null;
        }
        return Math.max(0.25f, Math.min(4f, s));
    }

    /** One of {@link #MAX_ANIMATED} animation slots for a view that keeps drawing; false when all are busy. */
    private static boolean acquireSlot(View view, long now) {
        int free = -1;
        for (int i = 0; i < MAX_ANIMATED; i++) {
            View v = slots[i] != null ? slots[i].get() : null;
            if (v == view) {
                slotSeen[i] = now;
                return true;
            }
            if (free < 0 && (v == null || now - slotSeen[i] > SLOT_IDLE_MS || !v.isAttachedToWindow())) {
                free = i;
            }
        }
        if (free < 0) {
            return false;
        }
        slots[free] = new WeakReference<>(view);
        slotSeen[free] = now;
        return true;
    }

    private static void requestStrip(FrameRenderer.Layers layers, View parent) {
        if (!layers.requestStrip()) {
            return;
        }
        if (stripQueue == null) {
            stripQueue = new DispatchQueue("sgAvatarFrames");
        }
        final WeakReference<View> ref = new WeakReference<>(parent);
        stripQueue.postRunnable(() -> {
            long t0 = System.nanoTime();
            try {
                layers.buildStrip();
            } catch (Throwable t) {
                FileLog.e(t);
            }
            statBuildNanos += System.nanoTime() - t0;
            AndroidUtilities.runOnUIThread(() -> {
                View v = ref.get();
                if (v != null) {
                    v.invalidate();
                }
            });
        });
    }

    /** Drops every cached frame (theme change, low memory). */
    public static void clearCache() {
        cache.evictAll();
    }

    /** Debug: cache use and per-draw costs since start. */
    public static String stats() {
        return "cache " + cache.size() / 1024 + "/" + cacheBytes / 1024 + " KB, live draws " + statLive
                + (statLive > 0 ? " (" + statLiveNanos / statLive / 1000 + " us avg)" : "")
                + ", static draws " + statPoster + ", build " + statBuildNanos / 1000000 + " ms";
    }
}
