package com.exteragram.messenger.badges;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;

/**
 * Avatar frames v2 drawing: the five chosen concepts of frames-demo/frames.js (inferno, pixel,
 * solitaire, cosmos, frost) and the v3 neko, angel and devil (frames-v3/frames.js) ported to
 * android.graphics. Only android.graphics and FrameParams are
 * used, so the renderer can be exercised offscreen (see {@link #renderDebug}).
 *
 * <p>Coordinates follow frames.js: a square box of {@code side} px, u = side / 100, centre c = side / 2;
 * the avatar photo is a circle of {@link #avatarU} u (drawn by the caller, not here). Everything that
 * needs blur or shadows is pre-rendered into bitmaps of a {@link Layers} (static layers, rotating
 * layers, the inferno flame strip, the pixel states); {@link #drawLive} then only draws bitmaps and
 * a few cheap primitives per frame. The loop is {@link #LOOP} s at speed 1, every animation is
 * periodic in it.
 */
public final class FrameRenderer {

    public static final float LOOP = 3f;
    private static final float TAU = (float) (Math.PI * 2);
    private static final float PI = (float) Math.PI;
    public static final int INFERNO_STRIP = 16;
    private static final int STRIP_MAX_SIDE = 224;
    public static final int PIXEL_STATES = 24;
    private static final int OUTLINE = 0xFF120A1F;

    private FrameRenderer() {
    }

    /** Avatar radius in u (box = 100 u) of each style, as in frames.js. */
    public static float avatarU(String style) {
        if (style == null) {
            return 38.5f;
        }
        switch (style) {
            case FrameParams.INFERNO:
                return 36f;
            case FrameParams.PIXEL:
                return 10.4f * 100f / 30f;
            case FrameParams.SOLITAIRE:
                return 40.5f;
            case FrameParams.NEKO:
            case FrameParams.ANGEL:
            case FrameParams.DEVIL:
                return V3_AVATAR_U;
            default:
                return 38.5f;
        }
    }

    /** Phase 0..1 of the loop at {@code timeMs} for a speed multiplier. */
    public static float phase(long timeMs, float speed) {
        double t = timeMs / 1000.0 * speed;
        return (float) ((t % LOOP) / LOOP);
    }

    // ------------------------------------------------------------------ palettes

    /** Inferno: 3 flame layers x 3 stops (rgb), heat, ring1, ring2, ember1, ember2, ember glow. */
    private static final int[][] INFERNO_PAL = {
            {0xff2800, 0xc81400, 0x780000, 0xff7800, 0xff5000, 0xff3c00, 0xfff096, 0xffbe28, 0xff8c00, 0xff6e00, 0xffd36b, 0xfff3c4, 0xffb347, 0xfff1a8, 0xff7b00},
            {0x0050ff, 0x0028c8, 0x000a78, 0x0096ff, 0x006eff, 0x0050ff, 0xd2f5ff, 0x5ac8ff, 0x008cff, 0x0078ff, 0x8fd0ff, 0xe6f6ff, 0x7fc8ff, 0xe8f8ff, 0x2a7bff},
            {0x28c800, 0x148c00, 0x005000, 0x78ff28, 0x50dc14, 0x28b400, 0xe6ffaa, 0xaaff50, 0x64dc00, 0x5ae600, 0xc8ff7a, 0xf2ffd8, 0xb4ff6a, 0xefffc8, 0x4cd600},
            {0x9600ff, 0x6400c8, 0x3c0078, 0xbe50ff, 0xa032ff, 0x821ee6, 0xf5d2ff, 0xdc96ff, 0xb45aff, 0xaa3cff, 0xe0b0ff, 0xf8eaff, 0xd39bff, 0xf6e6ff, 0x9b30ff},
            {0xff0078, 0xc8005a, 0x78003c, 0xff5aaa, 0xff3c96, 0xff2882, 0xffe1f0, 0xffa0d2, 0xff64b4, 0xff46a0, 0xffb3dc, 0xfff0f8, 0xff9fd2, 0xffe6f3, 0xff3fa0},
    };

    /** Pixel: 7 ring colours, star on, star off. */
    private static final int[][] PIXEL_PAL = {
            {0xff004d, 0xff77a8, 0xffa300, 0xffec27, 0x00e436, 0x29adff, 0x83769c, 0xffec27, 0xff77a8},
            {0xffb3c7, 0xffd6a5, 0xfdffb6, 0xcaffbf, 0x9bf6ff, 0xa0c4ff, 0xbdb2ff, 0xfdffb6, 0xffb3c7},
            {0xff00ff, 0x00ffff, 0x39ff14, 0xffff00, 0xff3131, 0xbc13fe, 0x00b3ff, 0x39ff14, 0xff00ff},
            {0xffffff, 0xd9d9d9, 0xb3b3b3, 0x8c8c8c, 0x666666, 0x8c8c8c, 0xd9d9d9, 0xffffff, 0xb3b3b3},
            {0xfff1a8, 0xffd84a, 0xf4b400, 0xc98a00, 0x8a5a00, 0xc98a00, 0xffd84a, 0xffd84a, 0xfff1a8},
    };

    /** Solitaire metals: 9 conic stops, accent dot, bezel fill, bezel light, bezel dark. */
    private static final int[][] METAL_PAL = {
            {0x7d8189, 0xffffff, 0xa3a7ae, 0xeef0f3, 0x6f737b, 0xffffff, 0x959aa2, 0xf6f7f9, 0x7d8189, 0xf4f5f7, 0xd9dde3, 0xffffff, 0x7b7f86},
            {0x8a6a1f, 0xfff3b0, 0xb8892b, 0xf7e08a, 0x7a5a14, 0xfff3b0, 0xa67c22, 0xfbe9a6, 0x8a6a1f, 0xfff1c2, 0xe8cf86, 0xfff6d0, 0x8a6a1f},
            {0x8a4f4a, 0xffe1d6, 0xb9786d, 0xf6c9bb, 0x7a4440, 0xffe1d6, 0xa86a60, 0xfad6ca, 0x8a4f4a, 0xffe4da, 0xeec0b3, 0xfff0ea, 0x8a4f4a},
            {0x111113, 0x6b6d73, 0x1f2023, 0x4a4c52, 0x0b0b0c, 0x7a7c82, 0x1a1b1e, 0x505258, 0x111113, 0x9a9ca3, 0x3a3b40, 0x8a8c92, 0x0d0d0f},
    };

    /** Solitaire gems: facet dark, facet light, 8 fire colours, table light, table dark, sparkle glow. */
    private static final int[][] GEM_PAL = {
            {0x5a6273, 0xffffff, 0xffffff, 0xe8f0ff, 0xffe3f3, 0xffffff, 0xfff4d6, 0xe6ecf5, 0xffffff, 0xf3e6ff, 0xffffff, 0xc9d0da, 0xe8f0ff},
            {0x5a0414, 0xff6f8f, 0xffd0dc, 0xff9fb5, 0xffe6ec, 0xff8aa5, 0xffd0dc, 0xffb3c4, 0xffe6ec, 0xff9fb5, 0xffb3c4, 0xc0143c, 0xffc0cf},
            {0x003d22, 0x5cf0a0, 0xc8ffe0, 0x8af5be, 0xe6fff1, 0x6fe8a8, 0xc8ffe0, 0xa8f5cf, 0xe6fff1, 0x8af5be, 0xa8f5cf, 0x0f8a50, 0xc0ffe0},
            {0x0a1a66, 0x6fa8ff, 0xcfe0ff, 0x9fc0ff, 0xe6efff, 0x7fa8ff, 0xcfe0ff, 0xb0ccff, 0xe6efff, 0x9fc0ff, 0xb0ccff, 0x1f4fc0, 0xc8dcff},
            {0x2e0e5a, 0xc89bff, 0xeedcff, 0xd6b3ff, 0xf6ecff, 0xc39bff, 0xeedcff, 0xdcc0ff, 0xf6ecff, 0xd6b3ff, 0xdcc0ff, 0x6a2fc0, 0xe6d0ff},
    };

    /** Cosmos: nebula 4, edge line, sparkle glow, planet light/mid/dark, planet ring, planet glow. */
    private static final int[][] COSMOS_PAL = {
            {0x2a0b52, 0x8f2bd1, 0xff4fa3, 0x3b0f73, 0xffc8f0, 0xffb3f0, 0xffe6c7, 0xff8fb1, 0x6a1b9a, 0xffd6aa, 0xff9ad5},
            {0x071a4a, 0x1f5fd1, 0x4fd1ff, 0x0d2a73, 0xbee1ff, 0x9fdcff, 0xe6f4ff, 0x6fb7ff, 0x1a3a9a, 0xc8e6ff, 0x7fc4ff},
            {0x4a0710, 0xc21f2f, 0xff8a4f, 0x6b0f1a, 0xffd2be, 0xffb39a, 0xfff0d0, 0xff8a5f, 0x8a1a1a, 0xffdcb4, 0xff7a5a},
            {0x052e2e, 0x118a7e, 0x4fffd6, 0x0b4a4a, 0xbefff0, 0x9ffff0, 0xe8fff9, 0x5fe0c8, 0x0e5a5a, 0xc8fff0, 0x5fffe0},
            {0x3a2306, 0xb07a12, 0xffd36b, 0x5a3a0a, 0xffecbe, 0xffe3a0, 0xfff6dc, 0xffc860, 0x8a5a10, 0xffe6b4, 0xffcf70},
    };

    /**
     * Frost: 7 ring conic stops, ring shadow, tint, crystal, crystal shadow, cap base, cap top,
     * icicle top, icicle bottom, outline, flake glow, light-theme dark accent.
     */
    private static final int[][] FROST_PAL = {
            {0xffffff, 0xbfe3ff, 0xf2f9ff, 0x9fd0ff, 0xffffff, 0xcbe8ff, 0xffffff, 0xa0d2ff, 0xd2ebff, 0xe1f0ff, 0x285a96, 0xd7e9f8, 0xeef6fd, 0xebf6ff, 0xbee1ff, 0x4678af, 0x3c78be, 0x3a6e9e},
            {0xffffff, 0xe6e9ee, 0xffffff, 0xd5dae2, 0xffffff, 0xeceff3, 0xffffff, 0xdce1eb, 0xf0f2f8, 0xf5f6fa, 0x505a6e, 0xe3e6ec, 0xf6f7fa, 0xf5f6fa, 0xd8dce4, 0x6e788c, 0x788296, 0x5a6478},
            {0xb8ffe9, 0x6ff7c8, 0x9fd8ff, 0xc7a8ff, 0xb8ffe9, 0x7fe0ff, 0xb8ffe9, 0x78ffd2, 0xaaffe1, 0xd7fff5, 0x1e6e64, 0xd2f5ec, 0xeefcf8, 0xdcfff4, 0xa0f0dc, 0x3c8c8c, 0x50c8b4, 0x2e7a72},
            {0xffffff, 0xffd1e6, 0xfff2f8, 0xffb3d6, 0xffffff, 0xffd9ea, 0xffffff, 0xffb4d7, 0xffdceb, 0xffebf5, 0x8c3c6e, 0xf6dbe8, 0xfdf0f6, 0xfff0f7, 0xffc8e1, 0xaf5a82, 0xc85a96, 0x9a4a72},
    };

    /** Colour dot for pickers: palette (metal for solitaire) {@code index} of {@code style}. */
    public static int paletteColor(String style, int index) {
        switch (style) {
            case FrameParams.INFERNO:
                return 0xFF000000 | INFERNO_PAL[clamp(index, INFERNO_PAL.length)][3];
            case FrameParams.PIXEL:
                return 0xFF000000 | PIXEL_PAL[clamp(index, PIXEL_PAL.length)][index == 3 ? 4 : 0];
            case FrameParams.SOLITAIRE:
                return 0xFF000000 | METAL_PAL[clamp(index, METAL_PAL.length)][index == 3 ? 3 : 2];
            case FrameParams.COSMOS:
                return 0xFF000000 | COSMOS_PAL[clamp(index, COSMOS_PAL.length)][1];
            case FrameParams.FROST:
                return 0xFF000000 | FROST_PAL[clamp(index, FROST_PAL.length)][3];
            case FrameParams.NEKO:
                return 0xFF000000 | NEKO_PAL[clamp(index, NEKO_PAL.length)][index == 1 ? 0 : 1];
            case FrameParams.ANGEL:
                return 0xFF000000 | ANGEL_PAL[clamp(index, ANGEL_PAL.length)][1];
            case FrameParams.DEVIL:
                return 0xFF000000 | DEVIL_PAL[clamp(index, DEVIL_PAL.length)][1];
            default:
                return Color.GRAY;
        }
    }

    /** Colour dot of a solitaire gem ({@code none} is transparent). */
    public static int gemColor(int index) {
        if (index < 0 || index >= GEM_PAL.length) {
            return 0;
        }
        return 0xFF000000 | (index == 0 ? 0xdfe6f0 : GEM_PAL[index][1]);
    }

    private static int clamp(int i, int n) {
        return Math.max(0, Math.min(n - 1, i));
    }

    // ------------------------------------------------------------------ layers

    /** Everything pre-rendered for one look (params + light/dark) at one pixel size. */
    public static final class Layers {
        public final FrameParams p;
        public final boolean light;
        public final int side;
        final float u, c, g;
        Bitmap poster;
        Bitmap under, over, rot1, rot2;
        Bitmap[] pixels;
        Bitmap sparkle, glowDot, planet, flake;
        Path[] facets;
        Path earOuter, earInner, hole;
        final Path tail = new Path();
        Bitmap halo;
        SweepGradient metal;
        Matrix metalMatrix;
        float jx, jy;
        public final int stripSide;
        final Bitmap[] strip;
        private volatile boolean stripReady;
        private boolean stripRequested;

        public Layers(FrameParams p, boolean light, int side) {
            this.p = p;
            this.light = light;
            this.side = side;
            this.u = side / 100f;
            this.c = side / 2f;
            this.g = p.glow / (float) FrameParams.DEFAULT_GLOW;
            if (FrameParams.INFERNO.equals(p.style)) {
                stripSide = Math.min(side, STRIP_MAX_SIDE);
                strip = new Bitmap[INFERNO_STRIP];
            } else {
                stripSide = 0;
                strip = null;
            }
        }

        /** Rough upper bound of the bitmap memory of this entry, for the LRU cache. */
        public int estimateBytes() {
            int full = side * side * 4;
            int n;
            switch (p.style) {
                case FrameParams.PIXEL:
                    n = 1;
                    break;
                case FrameParams.INFERNO:
                    n = 2;
                    break;
                case FrameParams.SOLITAIRE:
                    n = 3;
                    break;
                default:
                    n = 4;
                    break;
            }
            return full * n + (strip != null ? INFERNO_STRIP * stripSide * stripSide * 4 : 0) + 64 * 1024;
        }

        /** Whether the animated drawing needs a strip that is rendered off the UI thread. */
        public boolean needsStrip() {
            return strip != null;
        }

        public boolean isStripReady() {
            return strip == null || stripReady;
        }

        /** True once, for the caller that schedules {@link #buildStrip}. */
        public synchronized boolean requestStrip() {
            if (strip == null || stripRequested) {
                return false;
            }
            stripRequested = true;
            return true;
        }

        /** Renders the inferno flame strip (any thread; the UI only reads it once ready). */
        public void buildStrip() {
            if (strip == null || stripReady) {
                return;
            }
            float k = stripSide / (float) side;
            for (int i = 0; i < INFERNO_STRIP; i++) {
                Bitmap b = Bitmap.createBitmap(stripSide, stripSide, Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(b);
                cv.scale(k, k);
                infernoFlames(cv, this, i / (float) INFERNO_STRIP);
                strip[i] = b;
            }
            stripReady = true;
        }

        /** The whole frame at phase 0 (used for static frames and while animation is not allowed). */
        public Bitmap poster() {
            if (poster == null) {
                Bitmap b = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(b);
                if (FrameParams.INFERNO.equals(p.style)) {
                    infernoFlames(cv, this, 0f);
                    drawInferno(cv, this, 0f, false);
                } else {
                    drawLive(cv, this, 0f);
                }
                poster = b;
            }
            return poster;
        }
    }

    // ------------------------------------------------------------------ shared helpers

    private static final Matrix liveMatrix = new Matrix();
    private static final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Paint addPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Paint pixelPaint = new Paint();
    private static final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF liveRect = new RectF();
    private static SweepGradient meteorShader;

    static {
        addPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        pixelPaint.setFilterBitmap(false);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
    }

    private static final class Rng {
        private long s;

        Rng(long seed) {
            s = seed & 0xffffffffL;
        }

        float next() {
            s = (s * 1664525L + 1013904223L) & 0xffffffffL;
            return (float) (s / 4294967296.0);
        }
    }

    private static int argb(int rgb, float a) {
        int al = Math.round(255 * Math.max(0f, Math.min(1f, a)));
        return (al << 24) | (rgb & 0xffffff);
    }

    private static int lerpColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xff000000 | (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    private static float deg(float rad) {
        return (float) Math.toDegrees(rad);
    }

    /**
     * The reference renders (frames-demo MP4s, sheet) show about 60% of the nominal CSS blur, and
     * the frames are tuned to look like them.
     */
    private static final float BLUR_K = 0.6f;

    /** CSS blur(px) (a gaussian sigma) as a BlurMaskFilter radius. */
    private static BlurMaskFilter blur(float px) {
        return new BlurMaskFilter(Math.max(0.5f, (px * BLUR_K - 0.5f) / 0.57735f), BlurMaskFilter.Blur.NORMAL);
    }

    /** canvas shadowBlur (2 sigma) as a setShadowLayer radius. */
    private static float shadow(float shadowBlur) {
        return Math.max(0.5f, (shadowBlur / 2f - 0.5f) / 0.57735f);
    }

    private static RadialGradient radial(float cx, float cy, float r0, float r1, int[] colors, float[] stops) {
        float[] pos = new float[stops.length];
        for (int i = 0; i < stops.length; i++) {
            pos[i] = (r0 + stops[i] * (r1 - r0)) / r1;
        }
        return new RadialGradient(cx, cy, r1, colors, pos, Shader.TileMode.CLAMP);
    }

    private static SweepGradient sweep(float cx, float cy, float rotRad, int[] rgb) {
        int[] cols = new int[rgb.length];
        float[] pos = new float[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            cols[i] = 0xff000000 | rgb[i];
            pos[i] = i / (float) (rgb.length - 1);
        }
        SweepGradient s = new SweepGradient(cx, cy, cols, pos);
        if (rotRad != 0) {
            Matrix m = new Matrix();
            m.setRotate(deg(rotRad), cx, cy);
            s.setLocalMatrix(m);
        }
        return s;
    }

    private static Bitmap newLayer(Layers L) {
        return Bitmap.createBitmap(L.side, L.side, Bitmap.Config.ARGB_8888);
    }

    private static void clearInside(Canvas cv, float c, float r) {
        Paint clr = new Paint(Paint.ANTI_ALIAS_FLAG);
        clr.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        cv.drawCircle(c, c, r, clr);
    }

    private static Path star4(float r) {
        float k = r * 0.16f;
        Path path = new Path();
        path.moveTo(0, -r);
        path.quadTo(k, -k, r, 0);
        path.quadTo(k, k, 0, r);
        path.quadTo(-k, k, -r, 0);
        path.quadTo(-k, -k, 0, -r);
        path.close();
        return path;
    }

    private static final float SPARKLE_R = 20f;
    private static final int SPARKLE_BMP = 72;

    /** frames.js sparkle(): a white 4-point star plus a smaller one at 45 degrees, glowing in {@code glow}. */
    private static Bitmap sparkleBitmap(int glow) {
        Bitmap b = Bitmap.createBitmap(SPARKLE_BMP, SPARKLE_BMP, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.translate(SPARKLE_BMP / 2f, SPARKLE_BMP / 2f);
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setShadowLayer(shadow(SPARKLE_R * 1.6f), 0, 0, 0xff000000 | glow);
        pt.setColor(Color.WHITE);
        cv.drawPath(star4(SPARKLE_R), pt);
        cv.rotate(45);
        pt.setColor(0xD9FFFFFF);
        cv.drawPath(star4(SPARKLE_R * 0.55f), pt);
        return b;
    }

    private static void drawSparkle(Canvas cv, Bitmap bmp, float x, float y, float r, float rotRad, float alpha) {
        if (alpha <= 0.01f || r <= 0 || bmp == null) {
            return;
        }
        liveMatrix.reset();
        liveMatrix.postTranslate(-SPARKLE_BMP / 2f, -SPARKLE_BMP / 2f);
        liveMatrix.postScale(r / SPARKLE_R, r / SPARKLE_R);
        liveMatrix.postRotate(deg(rotRad));
        liveMatrix.postTranslate(x, y);
        bmpPaint.setAlpha(Math.round(255 * Math.min(1f, alpha)));
        cv.drawBitmap(bmp, liveMatrix, bmpPaint);
        bmpPaint.setAlpha(255);
    }

    private static void drawRotated(Canvas cv, Bitmap b, float c, float rotRad, Paint paint) {
        if (b == null) {
            return;
        }
        cv.save();
        cv.rotate(deg(rotRad), c, c);
        cv.drawBitmap(b, 0, 0, paint);
        cv.restore();
    }

    // ------------------------------------------------------------------ entry point

    /** Draws the frame of {@code L} at loop phase {@code p} (0..1) into a canvas in box px coordinates. */
    public static void drawLive(Canvas cv, Layers L, float p) {
        switch (L.p.style) {
            case FrameParams.INFERNO:
                drawInferno(cv, L, p, true);
                break;
            case FrameParams.PIXEL:
                drawPixel(cv, L, p);
                break;
            case FrameParams.SOLITAIRE:
                drawSolitaire(cv, L, p);
                break;
            case FrameParams.COSMOS:
                drawCosmos(cv, L, p);
                break;
            case FrameParams.FROST:
                drawFrost(cv, L, p);
                break;
            case FrameParams.NEKO:
                drawNeko(cv, L, p);
                break;
            case FrameParams.ANGEL:
                drawAngel(cv, L, p);
                break;
            case FrameParams.DEVIL:
                drawDevil(cv, L, p);
                break;
            default:
                break;
        }
    }

    /** Milliseconds until the drawing of {@code L} changes after phase {@code p} (pixel art steps; 0 = every frame). */
    public static long nextChangeMs(Layers L, float p, float speed) {
        if (FrameParams.PIXEL.equals(L.p.style)) {
            float state = p * PIXEL_STATES;
            float left = (float) (Math.floor(state) + 1) - state;
            return Math.max(16, (long) (left * LOOP * 1000f / PIXEL_STATES / speed) + 1);
        }
        return 0;
    }

    // ------------------------------------------------------------------ 6 Inferno

    private static final float[] EMBER_TH0 = new float[16], EMBER_OFF = new float[16];

    static {
        Rng r = new Rng(11);
        for (int i = 0; i < 16; i++) {
            EMBER_TH0[i] = r.next() * TAU;
            EMBER_OFF[i] = r.next();
        }
    }

    private static float flameF(float th, float k, float w) {
        double s = 0.42 * Math.sin(9 * th - 2 * w + k) + 0.33 * Math.sin(14 * th + 3 * w + 2 * k) + 0.25 * Math.sin(23 * th - 5 * w + 1 + k);
        double up = 1 + 0.55 * Math.max(0, -Math.sin(th));
        return (float) (Math.pow(Math.max(0, s + 0.45) / 1.45, 1.6) * up);
    }

    /** The three additive flame layers (blurred), with the avatar disc cut out. */
    private static void infernoFlames(Canvas cv, Layers L, float p) {
        float u = L.u, c = L.c, a = 36 * u, w = TAU * p, g = L.g;
        int[] P = INFERNO_PAL[clamp(L.p.palette, INFERNO_PAL.length)];
        flameLayer(cv, L, 4 * u, 12 * u, 0f, new int[]{argb(P[0], 0.9f * g), argb(P[1], 0.55f * g), argb(P[2], 0)}, new float[]{0, 0.55f, 1}, 2.2f * u, w);
        flameLayer(cv, L, 3 * u, 9 * u, 1.7f, new int[]{argb(P[3], 0.95f), argb(P[4], 0.5f), argb(P[5], 0)}, new float[]{0, 0.5f, 1}, 1.2f * u, w);
        flameLayer(cv, L, 1.5f * u, 5 * u, 3.1f, new int[]{argb(P[6], 1f), argb(P[7], 0.7f), argb(P[8], 0)}, new float[]{0, 0.45f, 1}, 0.6f * u, w);
        clearInside(cv, c, a);
    }

    private static void flameLayer(Canvas cv, Layers L, float base, float amp, float k, int[] colors, float[] stops, float blurPx, float w) {
        float u = L.u, c = L.c, a = 36 * u;
        Path path = new Path();
        path.setFillType(Path.FillType.EVEN_ODD);
        for (int i = 0; i <= 240; i++) {
            float th = i / 240f * TAU, r = a + base + amp * flameF(th, k, w);
            float x = c + r * (float) Math.cos(th), y = c + r * (float) Math.sin(th);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.close();
        path.addCircle(c, c, a - 2 * u, Path.Direction.CCW);
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setShader(radial(c, c, a - 2 * u, a + base + amp * 1.5f, colors, stops));
        pt.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        // the flames of the reference are crisper than the other blurs: half again
        pt.setMaskFilter(blur(blurPx * 0.5f));
        cv.drawPath(path, pt);
    }

    private static void buildInfernoOver(Layers L) {
        float u = L.u, c = L.c, a = 36 * u, g = L.g;
        int[] P = INFERNO_PAL[clamp(L.p.palette, INFERNO_PAL.length)];
        Bitmap b = newLayer(L);
        Canvas cv = new Canvas(b);
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        // heat glow over the avatar edge
        pt.setShader(radial(c, c, a * 0.7f, a, new int[]{argb(P[9], 0), argb(P[9], 0.5f * g)}, new float[]{0, 1}));
        cv.drawCircle(c, c, a, pt);
        pt.setShader(null);
        pt.setStyle(Paint.Style.STROKE);
        pt.setMaskFilter(blur(0.8f * u));
        pt.setColor(0xff000000 | P[10]);
        pt.setStrokeWidth(2.2f * u);
        cv.drawCircle(c, c, a, pt);
        pt.setMaskFilter(null);
        pt.setColor(0xff000000 | P[11]);
        pt.setStrokeWidth(0.8f * u);
        cv.drawCircle(c, c, a + 0.3f * u, pt);
        L.over = b;
        // a soft dot for the ember glow (shadowBlur 2u)
        Bitmap d = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
        dp.setShader(new RadialGradient(16, 16, 16, new int[]{argb(P[14], 0.9f), argb(P[14], 0.35f), argb(P[14], 0)}, new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP));
        new Canvas(d).drawCircle(16, 16, 16, dp);
        L.glowDot = d;
    }

    private static void drawInferno(Canvas cv, Layers L, float p, boolean useStrip) {
        float u = L.u, c = L.c, a = 36 * u;
        if (useStrip && L.strip != null && L.stripReady) {
            float f = p * INFERNO_STRIP;
            int i0 = (int) Math.floor(f) % INFERNO_STRIP, i1 = (i0 + 1) % INFERNO_STRIP;
            float mix = f - (float) Math.floor(f);
            liveRect.set(0, 0, L.side, L.side);
            // cross-fade the two nearest flame frames: additive like the source's 'lighter' layers on
            // dark backgrounds (the weights sum to an exact blend); plain alpha on light ones
            Paint pt = L.light ? bmpPaint : addPaint;
            pt.setAlpha(Math.round(255 * (1 - mix)));
            cv.drawBitmap(L.strip[i0], null, liveRect, pt);
            pt.setAlpha(Math.round(255 * mix));
            cv.drawBitmap(L.strip[i1], null, liveRect, pt);
            pt.setAlpha(255);
        }
        if (L.over == null) {
            buildInfernoOver(L);
        }
        cv.drawBitmap(L.over, 0, 0, bmpPaint);
        if (!L.p.toggle1) {
            return;
        }
        int[] P = INFERNO_PAL[clamp(L.p.palette, INFERNO_PAL.length)];
        for (int i = 0; i < 16; i++) {
            float q = (p * (1 + (i % 2)) + EMBER_OFF[i]) % 1f;
            float th = EMBER_TH0[i] + 0.25f * (float) Math.sin(TAU * q), d = a + (3 + q * 13) * u;
            float alpha = (float) Math.sin(PI * q);
            if (alpha <= 0.01f) {
                continue;
            }
            float x = c + d * (float) Math.cos(th), y = c + d * (float) Math.sin(th) - q * 4 * u;
            float r = (0.6f + 0.6f * (1 - q)) * u;
            float gr = r + 2 * u;
            liveRect.set(x - gr, y - gr, x + gr, y + gr);
            bmpPaint.setAlpha(Math.round(255 * alpha * Math.min(1f, L.g)));
            cv.drawBitmap(L.glowDot, null, liveRect, bmpPaint);
            bmpPaint.setAlpha(255);
            fill.setColor(argb(i % 3 != 0 ? P[12] : P[13], alpha));
            cv.drawCircle(x, y, r, fill);
        }
    }

    // ------------------------------------------------------------------ 8 Pixel Arcade

    private static final int[][] PIXEL_STARS = {{3, 4, 0}, {26, 3, 6}, {27, 26, 12}, {3, 26, 18}};

    private static int shade(int rgb) {
        float k = 0.62f;
        return 0xff000000 | ((int) (((rgb >> 16) & 255) * k) << 16) | ((int) (((rgb >> 8) & 255) * k) << 8) | (int) ((rgb & 255) * k);
    }

    /** The 30x30 art of state {@code idx} (12 stripe steps x 2 star phases = 24 states per loop). */
    private static Bitmap pixelState(Layers L, int idx) {
        final int N = 30;
        final float cc = N / 2f, ra = 12.4f;
        int[] P = PIXEL_PAL[clamp(L.p.palette, PIXEL_PAL.length)];
        float g = L.g;
        int[] pal = new int[7];
        for (int i = 0; i < 7; i++) {
            int col = 0xff000000 | P[i];
            pal[i] = g < 1f ? lerpColor(col, shade(col), (1 - g) * 0.6f) : lerpColor(col, 0xffffffff, (g - 1) * 0.45f);
        }
        int step = idx / 2;
        int[] px = new int[N * N];
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                float dx = x + 0.5f - cc, dy = y + 0.5f - cc;
                float d = (float) Math.hypot(dx, dy);
                int col = 0;
                if (d >= ra - 1.6f && d < ra + 1.1f) {
                    col = pal[(((x + y) / 3 - step) % 7 + 7) % 7];
                    if (d < ra - 0.6f) {
                        col = shade(col);
                    }
                } else if (d >= ra + 1.1f && d < ra + 2.05f) {
                    col = OUTLINE;
                } else if (d >= ra - 2.5f && d < ra - 1.6f) {
                    col = OUTLINE;
                }
                px[y * N + x] = col;
            }
        }
        if (L.p.toggle1) {
            boolean[] star = new boolean[N * N];
            for (int[] s : PIXEL_STARS) {
                int x = s[0], y = s[1], o = s[2];
                boolean on = ((idx + o) % 24) < 12, big = ((2 * idx + o) % 24) < 12;
                int col = 0xff000000 | (on ? P[7] : P[8]);
                int[][] cross = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] q : cross) {
                    px[(y + q[1]) * N + x + q[0]] = col;
                    star[(y + q[1]) * N + x + q[0]] = true;
                }
                if (big) {
                    int[][] tips = {{0, 0}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}};
                    for (int[] q : tips) {
                        int at = (y + q[1]) * N + x + q[0];
                        px[at] = q[0] == 0 && q[1] == 0 ? lerpColor(col, 0xffffffff, 0.9f) : 0xE6FFFFFF;
                        star[at] = true;
                    }
                }
            }
            if (L.light) {
                // light theme: a dark outline so the stars read on white
                int[] copy = px.clone();
                for (int y = 0; y < N; y++) {
                    for (int x = 0; x < N; x++) {
                        if (copy[y * N + x] != 0) {
                            continue;
                        }
                        boolean near = (x > 0 && star[y * N + x - 1]) || (x < N - 1 && star[y * N + x + 1])
                                || (y > 0 && star[(y - 1) * N + x]) || (y < N - 1 && star[(y + 1) * N + x]);
                        if (near) {
                            px[y * N + x] = OUTLINE;
                        }
                    }
                }
            }
        }
        Bitmap b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        b.setPixels(px, 0, N, 0, 0, N, N);
        return b;
    }

    private static void drawPixel(Canvas cv, Layers L, float p) {
        if (L.pixels == null) {
            L.pixels = new Bitmap[PIXEL_STATES];
        }
        int idx = Math.min(PIXEL_STATES - 1, (int) Math.floor(p * PIXEL_STATES));
        if (L.pixels[idx] == null) {
            L.pixels[idx] = pixelState(L, idx);
        }
        float cell = L.side / 30f;
        // the photo is a circle: fill the strip between it and the stepped inner outline
        stroke.setShader(null);
        stroke.setColor(OUTLINE);
        stroke.setStrokeWidth(0.55f * cell);
        cv.drawCircle(L.c, L.c, (12.4f - 2f + 0.25f) * cell, stroke);
        liveRect.set(0, 0, L.side, L.side);
        cv.drawBitmap(L.pixels[idx], null, liveRect, pixelPaint);
    }

    // ------------------------------------------------------------------ 10 Solitaire

    private static float gemAngle(Layers L) {
        switch (L.p.gemPos) {
            case 1:
                return -PI / 2;
            case 2:
                return 0;
            default:
                return PI / 2;
        }
    }

    private static boolean hasGem(Layers L) {
        return L.p.gem >= 0 && L.p.gem < GEM_PAL.length;
    }

    private static void solitaireArcs(Canvas cv, Layers L, Paint pt) {
        float u = L.u, c = L.c, a = 40.5f * u, r1 = a + 0.6f * u, r2 = a + 4.6f * u;
        boolean gem = hasGem(L);
        float bot = gemAngle(L), gap = gem ? 0.23f : 0, gap2 = gem ? 0.19f : 0;
        pt.setStyle(Paint.Style.STROKE);
        pt.setStrokeCap(Paint.Cap.ROUND);
        pt.setStrokeWidth(1.7f * u);
        RectF o = new RectF(c - r1, c - r1, c + r1, c + r1);
        if (gem) {
            cv.drawArc(o, deg(bot + gap2), deg(TAU - 2 * gap2), false, pt);
        } else {
            cv.drawCircle(c, c, r1, pt);
        }
        pt.setStrokeWidth(0.9f * u);
        o.set(c - r2, c - r2, c + r2, c + r2);
        if (gem) {
            cv.drawArc(o, deg(bot + gap), deg(TAU - 2 * gap), false, pt);
        } else {
            cv.drawCircle(c, c, r2, pt);
        }
    }

    private static void buildSolitaire(Layers L) {
        float u = L.u, c = L.c, a = 40.5f * u, r1 = a + 0.6f * u, r2 = a + 4.6f * u;
        int[] M = METAL_PAL[clamp(L.p.palette, METAL_PAL.length)];
        float bot = gemAngle(L);
        // under: the line shadows (the live metal lines cover the dark lines drawn with them)
        Bitmap under = newLayer(L);
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setColor(M[0] | 0xff000000);
        pt.setShadowLayer(shadow(1.5f * u), 0, 0, argb(0, Math.min(0.8f, 0.45f * Math.max(0.6f, L.g))));
        solitaireArcs(new Canvas(under), L, pt);
        L.under = under;
        // over: accent dots, gem bezel and table
        Bitmap over = newLayer(L);
        Canvas cv = new Canvas(over);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(0xff000000 | M[9]);
        for (float an : new float[]{bot + 0.68f * PI, bot - 0.68f * PI}) {
            cv.drawCircle(c + r2 * (float) Math.cos(an), c + r2 * (float) Math.sin(an), 1.1f * u, dot);
        }
        if (hasGem(L)) {
            int[] G = GEM_PAL[L.p.gem];
            // the source hangs the stone 1.2u outward at the bottom; at the top/right that would leave the box
            float off = (r1 + r2) / 2 + (L.p.gemPos == 0 ? 1.2f * u : 0);
            L.jx = c + off * (float) Math.cos(bot);
            L.jy = c + off * (float) Math.sin(bot);
            float R = 5.4f * u, rt = R * 0.52f;
            cv.save();
            cv.translate(L.jx, L.jy);
            Paint bz = new Paint(Paint.ANTI_ALIAS_FLAG);
            bz.setShadowLayer(shadow(R * 0.6f), 0, 0, 0x8C000000);
            bz.setColor(0xff000000 | M[10]);
            cv.drawCircle(0, 0, R * 1.22f, bz);
            bz.clearShadowLayer();
            bz.setStyle(Paint.Style.STROKE);
            bz.setStrokeWidth(R * 0.18f);
            bz.setShader(new LinearGradient(0, -R * 1.2f, 0, R * 1.2f, 0xff000000 | M[11], 0xff000000 | M[12], Shader.TileMode.CLAMP));
            cv.drawCircle(0, 0, R * 1.13f, bz);
            // table
            Path table = new Path();
            for (int i = 0; i < 8; i++) {
                float an = i / 8f * TAU - PI / 2;
                if (i == 0) {
                    table.moveTo(rt * (float) Math.cos(an), rt * (float) Math.sin(an));
                } else {
                    table.lineTo(rt * (float) Math.cos(an), rt * (float) Math.sin(an));
                }
            }
            table.close();
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setShader(new LinearGradient(-rt, -rt, rt, rt, 0xff000000 | G[10], 0xff000000 | G[11], Shader.TileMode.CLAMP));
            cv.drawPath(table, tp);
            tp.setShader(null);
            tp.setStyle(Paint.Style.STROKE);
            tp.setColor(0x805A6473);
            tp.setStrokeWidth(R * 0.06f);
            cv.drawPath(table, tp);
            cv.restore();
            // facet paths (live colours), in box coordinates
            Path[] facets = new Path[16];
            for (int i = 0; i < 8; i++) {
                float a0 = i / 8f * TAU - PI / 2, a1 = (i + 1) / 8f * TAU - PI / 2, am = (a0 + a1) / 2;
                float x0 = L.jx + R * (float) Math.cos(a0), y0 = L.jy + R * (float) Math.sin(a0);
                float x1 = L.jx + R * (float) Math.cos(a1), y1 = L.jy + R * (float) Math.sin(a1);
                float t0x = L.jx + rt * (float) Math.cos(a0), t0y = L.jy + rt * (float) Math.sin(a0);
                float t1x = L.jx + rt * (float) Math.cos(a1), t1y = L.jy + rt * (float) Math.sin(a1);
                float mx = L.jx + R * (float) Math.cos(am), my = L.jy + R * (float) Math.sin(am);
                Path tri = new Path();
                tri.moveTo(x0, y0);
                tri.lineTo(mx, my);
                tri.lineTo(t0x, t0y);
                tri.close();
                Path quad = new Path();
                quad.moveTo(mx, my);
                quad.lineTo(x1, y1);
                quad.lineTo(t1x, t1y);
                quad.lineTo(t0x, t0y);
                quad.close();
                facets[i * 2] = tri;
                facets[i * 2 + 1] = quad;
            }
            L.facets = facets;
            L.sparkle = sparkleBitmap(G[12]);
        }
        L.over = over;
    }

    private static void drawSolitaire(Canvas cv, Layers L, float p) {
        if (L.over == null) {
            buildSolitaire(L);
        }
        float u = L.u, c = L.c;
        int[] M = METAL_PAL[clamp(L.p.palette, METAL_PAL.length)];
        cv.drawBitmap(L.under, 0, 0, bmpPaint);
        if (L.metal == null) {
            int[] stops = new int[9];
            System.arraycopy(M, 0, stops, 0, 9);
            L.metal = sweep(c, c, 0, stops);
            L.metalMatrix = new Matrix();
        }
        L.metalMatrix.setRotate(deg(TAU * p), c, c);
        L.metal.setLocalMatrix(L.metalMatrix);
        stroke.setShader(L.metal);
        solitaireArcs(cv, L, stroke);
        stroke.setShader(null);
        stroke.setStrokeCap(Paint.Cap.BUTT);
        cv.drawBitmap(L.over, 0, 0, bmpPaint);
        if (L.facets == null) {
            return;
        }
        int[] G = GEM_PAL[L.p.gem];
        float rot = TAU * p / 8;
        for (int i = 0; i < 8; i++) {
            float am = (i + 0.5f) / 8f * TAU - PI / 2;
            float lum = 0.5f + 0.5f * (float) Math.cos(am * 3 + rot * 8 + 0.6f);
            fill.setColor(lerpColor(0xff000000 | G[0], 0xff000000 | G[1], lum));
            cv.drawPath(L.facets[i * 2], fill);
            fill.setColor(argb(G[2 + (i + 3) % 8], 0.4f + 0.6f * (1 - lum)));
            cv.drawPath(L.facets[i * 2 + 1], fill);
        }
        float f = (float) Math.max(0, Math.sin(PI * Math.min(1, Math.max(0, (p - 0.35f) / 0.3f))));
        float k = Math.min(1.3f, Math.max(0.4f, L.g));
        drawSparkle(cv, L.sparkle, L.jx + 1.6f * u, L.jy - 1.8f * u, (1 + 7 * f) * u * k, PI * p, f);
    }

    // ------------------------------------------------------------------ 11 Cosmos

    private static final float[] STAR_AN = new float[42], STAR_D = new float[42], STAR_O = new float[42], STAR_R = new float[42];
    private static final boolean[] STAR_BIG = new boolean[42];

    static {
        Rng r = new Rng(3);
        for (int i = 0; i < 42; i++) {
            STAR_AN[i] = r.next() * TAU;
            STAR_D[i] = 0.5f + r.next() * 10.5f;
            STAR_BIG[i] = r.next() < 0.18f;
            STAR_O[i] = r.next();
            if (!STAR_BIG[i]) {
                STAR_R[i] = 0.35f + 0.45f * r.next();
            }
        }
    }

    private static void buildCosmos(Layers L) {
        float u = L.u, c = L.c, a = 38.5f * u, g = L.g;
        int[] P = COSMOS_PAL[clamp(L.p.palette, COSMOS_PAL.length)];
        int[] neb = {P[0], P[1], P[2], P[3], P[0]};
        int[] cols = new int[13];
        cols[0] = neb[0];
        for (int k = 0; k < 3; k++) {
            for (int i = 1; i < 5; i++) {
                cols[k * 4 + i] = neb[i];
            }
        }
        Bitmap b1 = newLayer(L);
        Canvas cv = new Canvas(b1);
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setStyle(Paint.Style.STROKE);
        pt.setMaskFilter(blur(3.5f * u));
        pt.setShader(sweep(c, c, 0, cols));
        pt.setStrokeWidth(12 * u);
        pt.setAlpha(Math.round(255 * Math.min(1f, 0.35f + 0.65f * g)));
        cv.drawCircle(c, c, a + 3 * u, pt);
        clearInside(cv, c, a);
        L.rot1 = b1;
        Bitmap b2 = newLayer(L);
        cv = new Canvas(b2);
        pt.setMaskFilter(blur(1.2f * u));
        pt.setShader(sweep(c, c, 1f, cols));
        pt.setStrokeWidth(4 * u);
        pt.setAlpha(255);
        cv.drawCircle(c, c, a + 2.5f * u, pt);
        clearInside(cv, c, a);
        L.rot2 = b2;
        Bitmap over = newLayer(L);
        cv = new Canvas(over);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(0.7f * u);
        line.setColor(argb(P[4], 0.75f));
        cv.drawCircle(c, c, a + 0.2f * u, line);
        L.over = over;
        L.sparkle = sparkleBitmap(P[5]);
        // ringed planet: back half of the ring, the planet, front half
        int ps = (int) Math.ceil(18 * u);
        Bitmap pb = Bitmap.createBitmap(Math.max(4, ps), Math.max(4, ps), Bitmap.Config.ARGB_8888);
        Canvas pc = new Canvas(pb);
        pc.translate(ps / 2f, ps / 2f);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(0.8f * u);
        RectF ov = new RectF(-6.2f * u, -1.9f * u, 6.2f * u, 1.9f * u);
        pc.save();
        pc.rotate(deg(-0.4f));
        ring.setColor(argb(P[9], 0.9f));
        pc.drawArc(ov, 180, 180, false, ring);
        pc.restore();
        Paint planet = new Paint(Paint.ANTI_ALIAS_FLAG);
        planet.setShadowLayer(shadow(4 * u * Math.max(0.3f, g)), 0, 0, 0xff000000 | P[10]);
        planet.setShader(new RadialGradient(-1.2f * u, -1.2f * u, 4.6f * u, new int[]{0xff000000 | P[6], 0xff000000 | P[7], 0xff000000 | P[8]}, new float[]{0.06f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        pc.drawCircle(0, 0, 3.4f * u, planet);
        pc.save();
        pc.rotate(deg(-0.4f));
        ring.setColor(argb(P[9], 0.95f));
        pc.drawArc(ov, 0, 180, false, ring);
        pc.restore();
        L.planet = pb;
    }

    private static void drawCosmos(Canvas cv, Layers L, float p) {
        if (L.over == null) {
            buildCosmos(L);
        }
        float u = L.u, c = L.c, a = 38.5f * u;
        drawRotated(cv, L.rot1, c, TAU * p / 3, bmpPaint);
        addPaint.setAlpha(Math.round(255 * Math.min(1f, 0.6f * L.g)));
        drawRotated(cv, L.rot2, c, -TAU * p / 3, addPaint);
        addPaint.setAlpha(255);
        cv.drawBitmap(L.over, 0, 0, bmpPaint);
        for (int i = 0; i < 42; i++) {
            float tw = 0.5f + 0.5f * (float) Math.sin(TAU * ((1 + (i % 3)) * p + STAR_O[i]));
            float d = a + STAR_D[i] * u;
            float x = c + d * (float) Math.cos(STAR_AN[i]), y = c + d * (float) Math.sin(STAR_AN[i]);
            if (STAR_BIG[i]) {
                drawSparkle(cv, L.sparkle, x, y, (1.2f + 2.6f * tw) * u, 0, 0.5f + 0.5f * tw);
            } else {
                fill.setColor(argb(0xffffff, 0.35f + 0.65f * tw));
                cv.drawCircle(x, y, STAR_R[i] * u, fill);
            }
        }
        if (L.p.toggle1 && L.planet != null) {
            float pa = TAU * p - PI / 2, pr = a + 5 * u;
            float px = c + pr * (float) Math.cos(pa), py = c + pr * (float) Math.sin(pa);
            cv.drawBitmap(L.planet, px - L.planet.getWidth() / 2f, py - L.planet.getHeight() / 2f, bmpPaint);
        }
        if (L.p.toggle2) {
            float q = (p - 0.55f) / 0.2f;
            if (q > 0 && q < 1) {
                float an = -2.4f + q * 0.9f, d = a + 9 * u;
                float x = c + d * (float) Math.cos(an), y = c + d * (float) Math.sin(an);
                float s = (float) Math.sin(PI * q);
                if (meteorShader == null) {
                    float tail = 0.6f / TAU;
                    meteorShader = new SweepGradient(0, 0, new int[]{0x00FFFFFF, 0xE6FFFFFF, 0x00000000, 0x00000000}, new float[]{0, tail, tail + 0.001f, 1});
                }
                liveMatrix.reset();
                liveMatrix.setRotate(deg(an - 0.6f));
                liveMatrix.postTranslate(c, c);
                meteorShader.setLocalMatrix(liveMatrix);
                stroke.setShader(meteorShader);
                stroke.setAlpha(Math.round(255 * s));
                stroke.setStrokeWidth(0.8f * u);
                liveRect.set(c - d, c - d, c + d, c + d);
                cv.drawArc(liveRect, deg(an - 0.6f), deg(0.6f), false, stroke);
                stroke.setShader(null);
                stroke.setAlpha(255);
                drawSparkle(cv, L.sparkle, x, y, 2.2f * u, 0, s);
            }
        }
    }

    // ------------------------------------------------------------------ 12 Frost

    private static final float[] FLAKE_X = new float[9], FLAKE_OFF = new float[9], FLAKE_SZ = new float[9];
    private static final float[][] ICICLES = {{1.32f, 4.5f}, {1.42f, 6.5f}, {1.5f, 3.5f}, {1.57f, 7.5f}, {1.66f, 5f}, {1.73f, 3.5f}};

    static {
        Rng r = new Rng(9);
        for (int i = 0; i < 9; i++) {
            FLAKE_X[i] = r.next();
            FLAKE_OFF[i] = r.next();
            FLAKE_SZ[i] = 1.6f + r.next() * 1.8f;
        }
    }

    private static final float FLAKE_R = 16f;
    private static final int FLAKE_BMP = 48;

    private static Bitmap flakeBitmap(int color, int glow) {
        Bitmap b = Bitmap.createBitmap(FLAKE_BMP, FLAKE_BMP, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.translate(FLAKE_BMP / 2f, FLAKE_BMP / 2f);
        float r = FLAKE_R;
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setStyle(Paint.Style.STROKE);
        pt.setStrokeCap(Paint.Cap.ROUND);
        pt.setStrokeWidth(r * 0.22f);
        pt.setColor(color);
        pt.setShadowLayer(shadow(r * 0.7f), 0, 0, glow);
        Path path = new Path();
        for (int i = 0; i < 6; i++) {
            Matrix m = new Matrix();
            m.setRotate(60 * (i + 1));
            Path arm = new Path();
            arm.moveTo(0, 0);
            arm.lineTo(0, -r);
            arm.moveTo(0, -r * 0.55f);
            arm.lineTo(r * 0.28f, -r * 0.8f);
            arm.moveTo(0, -r * 0.55f);
            arm.lineTo(-r * 0.28f, -r * 0.8f);
            arm.transform(m);
            path.addPath(arm);
        }
        cv.drawPath(path, pt);
        return b;
    }

    private static Path capPath(Layers L, float rr, float scale, float dy) {
        float u = L.u, c = L.c;
        float a0 = PI * 1.2f, a1 = PI * 1.8f;
        Rng rc = new Rng(4);
        Path path = new Path();
        for (int i = 0; i <= 11; i++) {
            float k = i / 11f, an = a0 + (a1 - a0) * k;
            float br = (2.6f + 3.2f * (float) Math.sin(PI * k) + rc.next() * 0.8f) * u;
            float d = rr + 1.2f * u + 0.4f * br;
            path.addCircle(c + d * (float) Math.cos(an), c + d * (float) Math.sin(an) + dy, br * scale, Path.Direction.CW);
        }
        return path;
    }

    private static void buildFrost(Layers L) {
        float u = L.u, c = L.c, a = 38.5f * u, rr = a + 1.5f * u, g = L.g;
        int[] P = FROST_PAL[clamp(L.p.palette, FROST_PAL.length)];
        boolean cap = L.p.toggle2;
        boolean light = L.light;
        // under: cold tint on the photo edge
        Bitmap under = newLayer(L);
        Canvas cv = new Canvas(under);
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setShader(radial(c, c, a * 0.7f, a, new int[]{argb(P[8], 0), argb(P[8], Math.min(0.75f, 0.45f * g))}, new float[]{0, 1}));
        cv.drawCircle(c, c, a, pt);
        L.under = under;
        // rotating ice ring with its glow
        Bitmap ring = newLayer(L);
        cv = new Canvas(ring);
        Paint rp = new Paint(Paint.ANTI_ALIAS_FLAG);
        rp.setStyle(Paint.Style.STROKE);
        rp.setStrokeWidth(3.6f * u);
        rp.setShader(sweep(c, c, 0, new int[]{P[0], P[1], P[2], P[3], P[4], P[5], P[6]}));
        if (g > 0.01f) {
            rp.setShadowLayer(shadow(5 * u), 0, 0, argb(P[7], Math.min(1f, 0.8f * g)));
        }
        cv.drawCircle(c, c, rr, rp);
        if (light) {
            // light theme: a darker rim so the white ring reads on white
            Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(0.45f * u);
            rim.setColor(argb(P[17], 0.75f));
            cv.drawCircle(c, c, rr - 1.8f * u, rim);
        }
        L.rot1 = ring;
        // over: outline, crystals, snow cap, icicles
        Bitmap over = newLayer(L);
        cv = new Canvas(over);
        Paint ol = new Paint(Paint.ANTI_ALIAS_FLAG);
        ol.setStyle(Paint.Style.STROKE);
        ol.setStrokeWidth((light ? 0.8f : 0.5f) * u);
        ol.setColor(light ? argb(P[17], 0.95f) : argb(P[15], 0.55f));
        cv.drawCircle(c, c, rr + 1.9f * u, ol);
        Rng r = new Rng(21);
        Paint cr = new Paint(Paint.ANTI_ALIAS_FLAG);
        cr.setStyle(Paint.Style.STROKE);
        cr.setStrokeCap(Paint.Cap.ROUND);
        cr.setStrokeWidth((light ? 0.7f : 0.55f) * u);
        cr.setColor(light ? argb(P[17], 0.95f) : argb(P[9], 0.95f));
        cr.setShadowLayer(shadow(1 * u), 0, 0, argb(P[10], 0.6f));
        Path crystals = new Path();
        for (int i = 0; i < 26; i++) {
            float an = r.next() * TAU, len = (2.5f + r.next() * 5) * u, d0 = rr + 1.2f * u;
            if (cap && an > 3.75f && an < 5.65f) {
                continue;
            }
            float dx = (float) Math.cos(an), dy = (float) Math.sin(an);
            float x0 = c + d0 * dx, y0 = c + d0 * dy;
            crystals.moveTo(x0, y0);
            crystals.lineTo(x0 + dx * len, y0 + dy * len);
            for (int s = 1; s >= -1; s -= 2) {
                float bx = x0 + dx * len * 0.5f, by = y0 + dy * len * 0.5f, ba = an + s * 0.7f;
                crystals.moveTo(bx, by);
                crystals.lineTo(bx + (float) Math.cos(ba) * len * 0.4f, by + (float) Math.sin(ba) * len * 0.4f);
            }
        }
        cv.drawPath(crystals, cr);
        if (cap) {
            Path base = capPath(L, rr, 1f, 0);
            Paint cp = new Paint(Paint.ANTI_ALIAS_FLAG);
            cp.setShadowLayer(shadow(3 * u), 0, 1.2f * u, 0x80001431);
            cp.setColor(0xff000000 | P[11]);
            cv.drawPath(base, cp);
            if (light) {
                Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
                edge.setStyle(Paint.Style.STROKE);
                edge.setStrokeWidth(0.6f * u);
                edge.setColor(argb(P[17], 0.6f));
                cv.drawPath(base, edge);
            }
            Path top = capPath(L, rr, 0.92f, -0.9f * u);
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setShader(new LinearGradient(0, c - rr - 10 * u, 0, c - rr + 4 * u, 0xffffffff, 0xff000000 | P[12], Shader.TileMode.CLAMP));
            cv.drawPath(top, tp);
            Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG);
            for (float[] ic : ICICLES) {
                float an = PI * ic[0], x = c + (rr - 1.5f * u) * (float) Math.cos(an), y = c + (rr - 1.5f * u) * (float) Math.sin(an);
                ip.setShader(new LinearGradient(x, y, x, y + ic[1] * u, argb(P[13], 0.95f), argb(P[14], 0.4f), Shader.TileMode.CLAMP));
                Path tri = new Path();
                tri.moveTo(x - 1.2f * u, y - 0.5f * u);
                tri.lineTo(x + 1.2f * u, y - 0.5f * u);
                tri.lineTo(x, y + ic[1] * u);
                tri.close();
                cv.drawPath(tri, ip);
            }
        }
        L.over = over;
        L.flake = flakeBitmap(light ? lerpColor(0xff000000 | P[17], 0xffffffff, 0.2f) : 0xffffffff, argb(P[16], light ? 0.3f : 0.9f));
    }

    private static void drawFrost(Canvas cv, Layers L, float p) {
        if (L.over == null) {
            buildFrost(L);
        }
        float u = L.u, c = L.c, a = 38.5f * u, rr = a + 1.5f * u, S = L.side;
        cv.drawBitmap(L.under, 0, 0, bmpPaint);
        drawRotated(cv, L.rot1, c, TAU * p, bmpPaint);
        cv.drawBitmap(L.over, 0, 0, bmpPaint);
        if (L.p.toggle2) {
            // a drip of light running down one icicle
            float dq = (p * 2) % 1f, an = PI * 1.57f;
            float ix = c + (rr - 1.5f * u) * (float) Math.cos(an), iy = c + (rr - 1.5f * u) * (float) Math.sin(an);
            fill.setColor(argb(0xffffff, (float) Math.sin(PI * dq)));
            cv.drawCircle(ix, iy + dq * 7 * u, 0.7f * u, fill);
        }
        if (L.p.toggle1) {
            for (int i = 0; i < 9; i++) {
                float sz = FLAKE_SZ[i] * u, q = (p + FLAKE_OFF[i]) % 1f;
                float x = FLAKE_X[i] * S + (float) Math.sin(TAU * (q + FLAKE_OFF[i])) * 3 * u, y = -5 * u + q * (S + 10 * u);
                boolean inside = Math.hypot(x - c, y - c) < a - 2 * u;
                liveMatrix.reset();
                liveMatrix.postTranslate(-FLAKE_BMP / 2f, -FLAKE_BMP / 2f);
                liveMatrix.postScale(sz / FLAKE_R, sz / FLAKE_R);
                liveMatrix.postRotate(deg(TAU * q / 6));
                liveMatrix.postTranslate(x, y);
                bmpPaint.setAlpha(inside ? 140 : 242);
                cv.drawBitmap(L.flake, liveMatrix, bmpPaint);
                bmpPaint.setAlpha(255);
            }
        }
    }

    // ------------------------------------------------------------------ v3 shared

    /** Avatar radius of the v3 styles: smaller, so ears, halo, wings and horns fit the box. */
    private static final float V3_AVATAR_U = 34f;

    private static Path avatarHole(Layers L) {
        Path hole = new Path();
        hole.addCircle(L.c, L.c, V3_AVATAR_U * L.u, Path.Direction.CW);
        return hole;
    }

    private static Bitmap glowRing(Layers L, float r, float width, int color, int glow, float glowBlur) {
        Bitmap b = newLayer(L);
        Canvas cv = new Canvas(b);
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setStyle(Paint.Style.STROKE);
        pt.setStrokeWidth(width);
        pt.setColor(0xff000000 | color);
        if (L.g > 0.01f) {
            pt.setShadowLayer(shadow(glowBlur * L.g), 0, 0, argb(glow, Math.min(1f, 0.7f * L.g)));
        }
        cv.drawCircle(L.c, L.c, r, pt);
        return b;
    }

    // ------------------------------------------------------------------ v3 Neko

    /** Neko: ear, ring (ear edge), inner ear / paw pads, glow, whiskers, light-theme outline. */
    private static final int[][] NEKO_PAL = {
            {0x2b2b33, 0xffb3d1, 0xff9ec4, 0xff96c8, 0xffe1ee, 0xd06a96},
            {0x16161a, 0x4a4a56, 0xff9ec4, 0x9a9ab4, 0xd8d8e6, 0x2a2a32},
            {0xf6f6f8, 0xffffff, 0xffc4d8, 0xffffff, 0xffffff, 0x9a9aa6},
            {0xe07a26, 0xffb066, 0xffd0a8, 0xff9a3c, 0xfff0dc, 0xb05a14},
            {0x7a5ac4, 0xc9b3ff, 0xf0c8ff, 0xb08cff, 0xf4ecff, 0x6a4ab4},
    };

    private static Path earPath(float u, boolean inner) {
        Path p = new Path();
        if (inner) {
            p.moveTo(-5 * u, 1 * u);
            p.quadTo(-2 * u, -9 * u, 1 * u, -11 * u);
            p.quadTo(5 * u, -5 * u, 5.5f * u, 1 * u);
        } else {
            p.moveTo(-10 * u, 3 * u);
            p.quadTo(-4 * u, -14 * u, 1 * u, -17 * u);
            p.quadTo(7 * u, -8 * u, 10 * u, 3 * u);
        }
        p.close();
        return p;
    }

    private static void buildNeko(Layers L) {
        float u = L.u, c = L.c, a = V3_AVATAR_U * u;
        int[] P = NEKO_PAL[clamp(L.p.palette, NEKO_PAL.length)];
        L.rot1 = glowRing(L, a + 1.6f * u, 2.4f * u, P[1], P[3], 3 * u);
        Bitmap over = newLayer(L);
        Canvas cv = new Canvas(over);
        if (L.light) {
            Paint ol = new Paint(Paint.ANTI_ALIAS_FLAG);
            ol.setStyle(Paint.Style.STROKE);
            ol.setStrokeWidth(0.6f * u);
            ol.setColor(argb(P[5], 0.8f));
            cv.drawCircle(c, c, a + 2.9f * u, ol);
        }
        if (L.p.toggle1) {
            Paint wp = new Paint(Paint.ANTI_ALIAS_FLAG);
            wp.setStyle(Paint.Style.STROKE);
            wp.setStrokeCap(Paint.Cap.ROUND);
            wp.setStrokeWidth(0.8f * u);
            wp.setColor(0xff000000 | (L.light ? P[5] : P[4]));
            for (int s = -1; s <= 1; s += 2) {
                for (int k = -1; k <= 1; k++) {
                    cv.drawLine(c + s * (a + 3 * u), c + 4 * u + k * 3 * u, c + s * (a + 10 * u), c + 3 * u + k * 4.5f * u, wp);
                }
            }
        }
        L.over = over;
        L.earOuter = earPath(u, false);
        L.earInner = earPath(u, true);
    }

    private static void drawNeko(Canvas cv, Layers L, float p) {
        if (L.over == null) {
            buildNeko(L);
        }
        float u = L.u, c = L.c, a = V3_AVATAR_U * u;
        int[] P = NEKO_PAL[clamp(L.p.palette, NEKO_PAL.length)];
        // the right ear twitches twice per loop
        float tw = (float) Math.pow(Math.max(0, Math.sin(TAU * p * 2)), 8) * 0.25f;
        for (int s = -1; s <= 1; s += 2) {
            float base = -PI / 2 + s * 0.62f;
            cv.save();
            cv.translate(c + a * (float) Math.cos(base), c + a * (float) Math.sin(base));
            cv.rotate(deg(base + PI / 2 + (s > 0 ? tw : 0)));
            fill.setColor(0xff000000 | P[0]);
            cv.drawPath(L.earOuter, fill);
            stroke.setStrokeWidth(1.4f * u);
            stroke.setColor(0xff000000 | (L.light && P[0] == 0xf6f6f8 ? P[5] : P[1]));
            cv.drawPath(L.earOuter, stroke);
            fill.setColor(0xff000000 | P[2]);
            cv.drawPath(L.earInner, fill);
            cv.restore();
        }
        cv.drawBitmap(L.rot1, 0, 0, bmpPaint);
        cv.drawBitmap(L.over, 0, 0, bmpPaint);
        if (L.p.toggle2) {
            float py = c + a + 2.5f * u + (float) Math.sin(TAU * p) * 0.6f * u;
            fill.setColor(L.light ? argb(P[5], 0.35f) : 0x80000000);
            liveRect.set(c - 5.4f * u, py - 3.6f * u, c + 5.4f * u, py + 4.6f * u);
            cv.drawOval(liveRect, fill);
            fill.setColor(0xffffffff);
            liveRect.set(c - 5 * u, py - 4 * u, c + 5 * u, py + 4 * u);
            cv.drawOval(liveRect, fill);
            fill.setColor(0xff000000 | P[2]);
            liveRect.set(c - 2.2f * u, py - 0.9f * u, c + 2.2f * u, py + 2.5f * u);
            cv.drawOval(liveRect, fill);
            cv.drawCircle(c - 2.6f * u, py - 2.2f * u, 0.9f * u, fill);
            cv.drawCircle(c, py - 2.8f * u, 0.9f * u, fill);
            cv.drawCircle(c + 2.6f * u, py - 2.2f * u, 0.9f * u, fill);
        }
    }

    // ------------------------------------------------------------------ v3 Angel

    /** Angel: halo light, halo mid, halo glow, ring, wing edge. */
    private static final int[][] ANGEL_PAL = {
            {0xfff6c9, 0xffcf33, 0xffd84d, 0xfff4c2, 0xd9e4ff},
            {0xffffff, 0xb9c0cc, 0xe6ecf5, 0xf2f4f7, 0xd2d8e2},
            {0xffe6ef, 0xff8fb8, 0xffb3cf, 0xffe0ea, 0xffd6e6},
            {0xe6f6ff, 0x6cbcff, 0x9fd8ff, 0xdff2ff, 0xcfe8ff},
    };

    private static Bitmap wingBitmap(Layers L, int s, int[] P) {
        float u = L.u;
        Bitmap b = newLayer(L);
        Canvas cv = new Canvas(b);
        Paint wf = new Paint(Paint.ANTI_ALIAS_FLAG);
        wf.setColor(0xffffffff);
        if (L.g > 0.01f) {
            wf.setShadowLayer(shadow(4 * u * L.g), 0, 0, argb(P[4], Math.min(1f, 0.8f * L.g)));
        }
        Paint we = new Paint(Paint.ANTI_ALIAS_FLAG);
        we.setStyle(Paint.Style.STROKE);
        we.setStrokeWidth((L.light ? 0.7f : 0.5f) * u);
        we.setColor(L.light ? 0xff9aa6bd : 0xff000000 | P[4]);
        cv.translate(L.c + s * (V3_AVATAR_U - 3) * u, L.c - 4 * u);
        for (int i = 0; i < 4; i++) {
            cv.save();
            cv.translate(s * (5 + i * 3.2f) * u, (-4 + i * 3.4f) * u);
            cv.rotate(deg(s * (0.5f + i * 0.25f)));
            liveRect.set(-(9.5f - i * 1.4f) * u, -3.2f * u, (9.5f - i * 1.4f) * u, 3.2f * u);
            cv.drawOval(liveRect, wf);
            cv.drawOval(liveRect, we);
            cv.restore();
        }
        return b;
    }

    private static void buildAngel(Layers L) {
        float u = L.u, c = L.c, a = V3_AVATAR_U * u;
        int[] P = ANGEL_PAL[clamp(L.p.palette, ANGEL_PAL.length)];
        L.rot1 = glowRing(L, a + 1.2f * u, 1.2f * u, L.light ? P[1] : P[3], P[2], 4 * u);
        if (L.p.toggle1) {
            L.under = wingBitmap(L, -1, P);
            L.over = wingBitmap(L, 1, P);
        }
        // halo centred at (c, 0) of the bitmap's own frame: drawn translated per frame
        Bitmap halo = newLayer(L);
        Canvas cv = new Canvas(halo);
        Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);
        hp.setStyle(Paint.Style.STROKE);
        hp.setStrokeWidth(2.6f * u);
        hp.setShader(new LinearGradient(c - 16 * u, 0, c + 16 * u, 0, new int[]{0xff000000 | P[1], 0xff000000 | P[0], 0xff000000 | P[1]}, null, Shader.TileMode.CLAMP));
        if (L.g > 0.01f) {
            hp.setShadowLayer(shadow(8 * u * L.g), 0, 0, argb(P[2], Math.min(1f, L.g)));
        }
        liveRect.set(c - 15 * u, c - 4 * u, c + 15 * u, c + 4 * u);
        cv.drawOval(liveRect, hp);
        L.halo = halo;
        L.sparkle = sparkleBitmap(P[2]);
        L.hole = avatarHole(L);
    }

    private static void drawAngel(Canvas cv, Layers L, float p) {
        if (L.halo == null) {
            buildAngel(L);
        }
        float u = L.u, c = L.c, a = V3_AVATAR_U * u;
        if (L.p.toggle1) {
            float fl = (float) Math.sin(TAU * p) * 0.12f;
            float px = (V3_AVATAR_U - 3) * u, py = c - 4 * u;
            cv.save();
            cv.clipPath(L.hole, android.graphics.Region.Op.DIFFERENCE);
            for (int s = -1; s <= 1; s += 2) {
                cv.save();
                cv.rotate(deg(s * fl), c + s * px, py);
                cv.drawBitmap(s < 0 ? L.under : L.over, 0, 0, bmpPaint);
                cv.restore();
            }
            cv.restore();
        }
        cv.drawBitmap(L.rot1, 0, 0, bmpPaint);
        float hy = c - a - 3 * u + (float) Math.sin(TAU * p) * 1.2f * u;
        cv.drawBitmap(L.halo, 0, hy - c, bmpPaint);
        if (L.p.toggle2) {
            float sv = (float) Math.sin(TAU * p);
            drawSparkle(cv, L.sparkle, c + 13 * u * (float) Math.cos(TAU * p), hy + 3.5f * u * sv, 2.4f * u, 0, Math.max(0, sv));
        }
    }

    // ------------------------------------------------------------------ v3 Devil

    /** Devil: ring dark, ring bright, ring accent, horn base, horn tip, tail, glow. */
    private static final int[][] DEVIL_PAL = {
            {0x7a0010, 0xff2a2a, 0xff7a1a, 0x8a0012, 0xff3b30, 0xd0101e, 0xff2a1a},
            {0x3a0a5a, 0xb02aff, 0xff4fd8, 0x4a0a6a, 0xc04aff, 0x8a1ad0, 0xb02aff},
            {0x111111, 0x5a5a62, 0xff2a2a, 0x0a0a0c, 0x55555e, 0x2a2a30, 0xff2a1a},
            {0x0a4a10, 0x3aff4a, 0xc8ff3a, 0x0a5a14, 0x4aff5a, 0x20b030, 0x3aff4a},
    };

    private static void buildDevil(Layers L) {
        float u = L.u, c = L.c, a = V3_AVATAR_U * u, r = a + 1.8f * u;
        int[] P = DEVIL_PAL[clamp(L.p.palette, DEVIL_PAL.length)];
        Bitmap ring = newLayer(L);
        Canvas cv = new Canvas(ring);
        Paint rp = new Paint(Paint.ANTI_ALIAS_FLAG);
        rp.setStyle(Paint.Style.STROKE);
        rp.setStrokeWidth(2 * u);
        rp.setShader(sweep(c, c, 0, new int[]{P[0], P[1], P[2], P[1], P[0]}));
        cv.drawCircle(c, c, r, rp);
        L.rot1 = ring;
        // glow halo of the ring, pulsed by alpha
        Bitmap glow = newLayer(L);
        cv = new Canvas(glow);
        Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);
        gp.setStyle(Paint.Style.STROKE);
        gp.setStrokeWidth(3.5f * u);
        gp.setMaskFilter(blur(4 * u));
        gp.setColor(argb(P[6], Math.min(1f, 0.9f * L.g)));
        cv.drawCircle(c, c, r, gp);
        L.rot2 = glow;
        Bitmap over = newLayer(L);
        cv = new Canvas(over);
        for (int s = -1; s <= 1; s += 2) {
            cv.save();
            cv.translate(c + s * 13 * u, c - a + 3 * u);
            Path h = new Path();
            h.moveTo(-4.5f * u, 2 * u);
            h.quadTo(s * -2 * u, -8 * u, s * 7 * u, -15 * u);
            h.quadTo(s * 2 * u, -6 * u, 4.5f * u, 2 * u);
            h.close();
            Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);
            hp.setShader(new LinearGradient(0, 2 * u, s * 6 * u, -15 * u, 0xff000000 | P[3], 0xff000000 | P[4], Shader.TileMode.CLAMP));
            if (L.g > 0.01f) {
                hp.setShadowLayer(shadow(4 * u * L.g), 0, 0, argb(P[6], Math.min(1f, 0.8f * L.g)));
            }
            cv.drawPath(h, hp);
            if (L.light || P[3] == 0x0a0a0c) {
                Paint he = new Paint(Paint.ANTI_ALIAS_FLAG);
                he.setStyle(Paint.Style.STROKE);
                he.setStrokeWidth(0.5f * u);
                he.setColor(argb(P[6], 0.7f));
                cv.drawPath(h, he);
            }
            cv.restore();
        }
        L.over = over;
    }

    private static void drawDevil(Canvas cv, Layers L, float p) {
        if (L.over == null) {
            buildDevil(L);
        }
        float u = L.u, c = L.c, a = V3_AVATAR_U * u;
        int[] P = DEVIL_PAL[clamp(L.p.palette, DEVIL_PAL.length)];
        float pulse = L.p.toggle2 ? 0.55f + 0.45f * (0.5f + 0.5f * (float) Math.sin(TAU * p * 2)) : 1f;
        bmpPaint.setAlpha(Math.round(255 * pulse));
        cv.drawBitmap(L.rot2, 0, 0, bmpPaint);
        bmpPaint.setAlpha(255);
        drawRotated(cv, L.rot1, c, -TAU * p, bmpPaint);
        cv.drawBitmap(L.over, 0, 0, bmpPaint);
        if (L.p.toggle1) {
            float sw = (float) Math.sin(TAU * p) * 2.5f * u, x0 = c + a * 0.7f, y0 = c + a * 0.72f;
            Path t = L.tail;
            t.rewind();
            t.moveTo(x0, y0);
            t.cubicTo(x0 + 8 * u, y0 + 10 * u, x0 + 14 * u + sw, y0 - 2 * u, x0 + 10 * u + sw, y0 - 8 * u);
            stroke.setStrokeWidth(1.6f * u);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setColor(0xff000000 | P[5]);
            cv.drawPath(t, stroke);
            stroke.setStrokeCap(Paint.Cap.BUTT);
            float tx = x0 + 10 * u + sw, ty = y0 - 8 * u;
            t.rewind();
            t.moveTo(tx, ty - 3.5f * u);
            t.lineTo(tx + 3 * u, ty + 1.5f * u);
            t.lineTo(tx - 3 * u, ty + 1.5f * u);
            t.close();
            fill.setColor(0xff000000 | P[1]);
            cv.drawPath(t, fill);
        }
    }

    // ------------------------------------------------------------------ debug

    /**
     * Debug aid: renders {@code style} with {@code params} over a stand-in photo at phase {@code p}
     * into a new bitmap of {@code side} px (light or dark background). Used by the offscreen render
     * harness; not called by the app.
     */
    public static Bitmap renderDebug(FrameParams params, boolean light, int side, float p, boolean poster) {
        Bitmap out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        cv.drawColor(light ? 0xffffffff : 0xff1c1c1e);
        float c = side / 2f, r = avatarU(params.style) * side / 100f;
        Paint photo = new Paint(Paint.ANTI_ALIAS_FLAG);
        photo.setShader(new LinearGradient(0, c - r, 0, c + r, new int[]{0xff7b3fe4, 0xff4a1fa8, 0xff8f5cf0}, null, Shader.TileMode.MIRROR));
        cv.drawCircle(c, c, r, photo);
        Layers L = new Layers(params, light, side);
        if (poster) {
            cv.drawBitmap(L.poster(), 0, 0, null);
        } else {
            L.buildStrip();
            drawLive(cv, L, p);
        }
        return out;
    }
}
