package com.exteragram.messenger.icons.ui.components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.util.SparseIntArray;
import android.view.View;

import androidx.core.content.res.ResourcesCompat;

import com.exteragram.messenger.icons.ExteraResources;
import com.exteragram.messenger.icons.IconManager;
import com.exteragram.messenger.icons.IconPack;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * exteraGram IconPackPreviewView: a 2x2 grid of icons of a pack (base packs: sticker/link/pin/photos, custom packs:
 * random replaced icons that cross-fade every few seconds), or a circular layout of 13 icons for the install sheet.
 */
public class IconPackPreviewView extends View {

    private static final int[] DEFAULT_ICONS = {R.drawable.msg_sticker, R.drawable.msg_link2, R.drawable.msg_pin, R.drawable.msg_photos};

    private float animationProgress;
    private ValueAnimator animator;
    private boolean attached;
    private final List<Integer> availableIconIds = new ArrayList<>();
    private final Runnable changeRunnable = this::animateToNext;
    private final int circCenterSize = AndroidUtilities.dp(36);
    private final int circInnerIconSize = AndroidUtilities.dp(24);
    private final int circInnerRadius = AndroidUtilities.dp(46);
    private final int circOuterIconSize = AndroidUtilities.dp(20);
    private final int circOuterRadius = AndroidUtilities.dp(72);
    private int count = 4;
    private final int[] currentIconIds = new int[20];
    private final Drawable[] currentIcons = new Drawable[20];
    private final Drawable[] nextIcons = new Drawable[20];
    private IconPack currentPack;
    private final int gap = AndroidUtilities.dp(4);
    private final int iconSize = AndroidUtilities.dp(20);
    private boolean isCircularMode;
    private final Random random = new Random();
    private int refreshTimeMilliseconds = 5000;
    private boolean shouldAnimate;

    public IconPackPreviewView(Context context) {
        super(context);
    }

    public void setCircularMode(boolean circular) {
        isCircularMode = circular;
        if (circular) {
            count = 13;
        }
        requestLayout();
        if (currentPack != null) {
            setIconPack(currentPack);
        }
    }

    public void setRefreshTime(int millis) {
        refreshTimeMilliseconds = millis;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (isCircularMode) {
            setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec), resolveSize(((circOuterRadius + circOuterIconSize / 2) * 2) + AndroidUtilities.dp(8), heightMeasureSpec));
        } else {
            setMeasuredDimension(resolveSize(iconSize * 2 + gap + getPaddingLeft() + getPaddingRight(), widthMeasureSpec), resolveSize(iconSize * 2 + gap + getPaddingTop() + getPaddingBottom(), heightMeasureSpec));
        }
    }

    public void setIconPack(IconPack pack) {
        if (currentPack != null && sameIconSource(currentPack, pack) && !availableIconIds.isEmpty() && availableIconIds.size() >= count) {
            return;
        }
        currentPack = pack;
        availableIconIds.clear();
        if (pack.isBase()) {
            for (int id : DEFAULT_ICONS) {
                availableIconIds.add(id);
            }
            shouldAnimate = false;
        } else {
            ArrayList<String> names = new ArrayList<>();
            HashSet<String> files = new HashSet<>();
            for (Map.Entry<String, String> entry : pack.getIcons().entrySet()) {
                if (files.add(entry.getValue())) {
                    names.add(entry.getKey());
                }
            }
            if (names.isEmpty()) {
                availableIconIds.add(R.drawable.msg_media);
            } else {
                ConcurrentHashMap<String, Integer> systemIcons = IconManager.INSTANCE.getSystemIcons();
                if (!systemIcons.isEmpty()) {
                    for (String name : names) {
                        Integer id = systemIcons.get(name);
                        if (id != null) {
                            availableIconIds.add(id);
                        }
                    }
                } else {
                    int checked = 0;
                    for (String name : names) {
                        int id = getResources().getIdentifier(name, "drawable", getContext().getPackageName());
                        if (id != 0) {
                            availableIconIds.add(id);
                        }
                        if (++checked > 30) {
                            break;
                        }
                    }
                }
            }
            shouldAnimate = files.size() >= count * 2;
        }
        loadIcons(false);
    }

    private static boolean sameIconSource(IconPack a, IconPack b) {
        if (!a.getId().equals(b.getId())) {
            return false;
        }
        Map<String, String> ia = a.getIcons();
        Map<String, String> ib = b.getIcons();
        return ia == ib || (ia.isEmpty() && ib.isEmpty());
    }

    private void loadIcons(boolean next) {
        IconPack pack = currentPack;
        ArrayList<Integer> ids = new ArrayList<>(availableIconIds);
        int[] exclude = next ? currentIconIds.clone() : null;
        int amount = pack.isBase() ? count : Math.min(count, ids.size());
        Utilities.globalQueue.postRunnable(() -> {
            List<Integer> picked = pickRandomIconIds(ids, amount, exclude);
            Drawable[] drawables = new Drawable[currentIconIds.length];
            int[] pickedIds = new int[currentIconIds.length];
            for (int i = 0; i < currentIconIds.length; i++) {
                if (i < picked.size()) {
                    pickedIds[i] = picked.get(i);
                    drawables[i] = getIconDrawable(pack, pickedIds[i]);
                } else if (!picked.isEmpty() && isCircularMode) {
                    pickedIds[i] = picked.get(i % picked.size());
                    drawables[i] = getIconDrawable(pack, pickedIds[i]);
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (currentPack != pack) {
                    return;
                }
                if (next) {
                    System.arraycopy(drawables, 0, nextIcons, 0, nextIcons.length);
                    startTransition(picked);
                    return;
                }
                for (int i = 0; i < currentIcons.length; i++) {
                    currentIconIds[i] = pickedIds[i];
                    currentIcons[i] = drawables[i];
                }
                invalidate();
                if (attached && shouldAnimate) {
                    scheduleNext();
                } else {
                    removeCallbacks(changeRunnable);
                }
            });
        });
    }

    private List<Integer> pickRandomIconIds(List<Integer> ids, int amount, int[] exclude) {
        ArrayList<Integer> result = new ArrayList<>();
        if (ids.isEmpty()) {
            return result;
        }
        ArrayList<Integer> pool = new ArrayList<>(ids);
        if (exclude != null) {
            for (int id : exclude) {
                pool.remove(Integer.valueOf(id));
            }
        }
        if (pool.isEmpty()) {
            pool.addAll(ids);
        }
        HashSet<Integer> indices = new HashSet<>();
        int size = pool.size();
        for (int i = 0; indices.size() < amount && indices.size() < size && i < amount * 4; i++) {
            indices.add(random.nextInt(size));
        }
        for (int i = 0; i < size && indices.size() < amount; i++) {
            indices.add(i);
        }
        for (Integer index : indices) {
            result.add(pool.get(index));
        }
        return result;
    }

    private Drawable getIconDrawable(IconPack pack, int resId) {
        if (resId == 0) {
            return null;
        }
        Resources resources = getResources();
        Drawable drawable = null;
        try {
            if (pack.isBase()) {
                SparseIntArray map = pack.getPreinstalledMap();
                int mapped = map != null ? map.get(resId, -1) : -1;
                drawable = mapped != -1 ? ResourcesCompat.getDrawable(resources, mapped, null) : null;
            } else {
                drawable = IconManager.INSTANCE.getPackIconDrawable(pack, resId);
            }
            if (drawable == null) {
                if (resources instanceof ExteraResources) {
                    // the activity resources substitute the active pack: take Telegram's own drawable
                    drawable = ((ExteraResources) resources).getOriginalDrawable(resId);
                }
                if (drawable == null) {
                    drawable = ResourcesCompat.getDrawable(resources, resId, null);
                }
            }
        } catch (Exception ignore) {
        }
        if (drawable == null) {
            return null;
        }
        drawable = drawable.mutate();
        drawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
        return drawable;
    }

    private void scheduleNext() {
        removeCallbacks(changeRunnable);
        if (attached && shouldAnimate) {
            postDelayed(changeRunnable, refreshTimeMilliseconds);
        }
    }

    private void animateToNext() {
        if (attached && shouldAnimate) {
            loadIcons(true);
        }
    }

    private void startTransition(List<Integer> ids) {
        if (!attached) {
            return;
        }
        if (animator != null) {
            animator.cancel();
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(300);
        animator.setInterpolator(CubicBezierInterpolator.DEFAULT);
        animator.addUpdateListener(a -> {
            animationProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (animator == animation) {
                    animator = null;
                }
                if (cancelled || !attached) {
                    return;
                }
                for (int i = 0; i < currentIcons.length; i++) {
                    currentIconIds[i] = i < ids.size() ? ids.get(i) : 0;
                    currentIcons[i] = nextIcons[i];
                    nextIcons[i] = null;
                }
                animationProgress = 0f;
                invalidate();
                scheduleNext();
            }
        });
        animator.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        if (shouldAnimate) {
            scheduleNext();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        removeCallbacks(changeRunnable);
        if (animator != null) {
            ValueAnimator a = animator;
            animator = null;
            a.cancel();
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (isCircularMode) {
            drawCircularLayout(canvas);
            return;
        }
        if (count == 4) {
            int visible = 0;
            for (Drawable d : currentIcons) {
                if (d != null) {
                    visible++;
                }
            }
            if (visible == 1) {
                int size = AndroidUtilities.dp(24);
                drawIcon(canvas, 0, (getWidth() - size) / 2, (getHeight() - size) / 2, size, 1f);
                return;
            }
            int size = AndroidUtilities.dp(20);
            int step = size + AndroidUtilities.dp(4);
            if (visible == 2) {
                int top = (getHeight() - size) / 2;
                drawIcon(canvas, 0, 0, top, size, 1f);
                drawIcon(canvas, 1, step, top, size, 1f);
                return;
            }
            if (visible == 3) {
                drawIcon(canvas, 0, 0, 0, size, 1f);
                drawIcon(canvas, 1, step, 0, size, 1f);
                drawIcon(canvas, 2, (getWidth() - size) / 2, step, size, 1f);
                return;
            }
            drawIcon(canvas, 0, 0, 0, size, 1f);
            drawIcon(canvas, 1, step, 0, size, 1f);
            drawIcon(canvas, 2, 0, step, size, 1f);
            drawIcon(canvas, 3, step, step, size, 1f);
            return;
        }
        int index = 0;
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2 && index < currentIcons.length; col++) {
                drawIcon(canvas, index++, getPaddingLeft() + (iconSize + gap) * col, getPaddingTop() + (iconSize + gap) * row, iconSize, 1f);
            }
        }
    }

    private void drawCircularLayout(Canvas canvas) {
        int cx = getWidth() / 2;
        int cy = getHeight() / 2;
        drawIcon(canvas, 0, cx - circCenterSize / 2, cy - circCenterSize / 2, circCenterSize, 1f);
        for (int i = 0; i < 6 && 1 + i < currentIcons.length; i++) {
            double angle = Math.toRadians(i * 60.0);
            int x = (int) (cx + circInnerRadius * Math.cos(angle)) - circInnerIconSize / 2;
            int y = (int) (cy + circInnerRadius * Math.sin(angle));
            drawIcon(canvas, 1 + i, x, y - circInnerIconSize / 2, circInnerIconSize, 0.7f);
        }
        for (int i = 0; i < 6 && 7 + i < currentIcons.length; i++) {
            double angle = Math.toRadians(i * 60.0 + 30.0);
            int x = (int) (cx + circOuterRadius * Math.cos(angle)) - circOuterIconSize / 2;
            int y = (int) (cy + circOuterRadius * Math.sin(angle));
            drawIcon(canvas, 7 + i, x, y - circOuterIconSize / 2, circOuterIconSize, 0.3f);
        }
    }

    private void drawIcon(Canvas canvas, int index, int x, int y, int size, float alpha) {
        if (index >= currentIcons.length) {
            return;
        }
        canvas.save();
        canvas.translate(x, y);
        Drawable current = currentIcons[index];
        Drawable next = nextIcons[index];
        if (current != null) {
            current.setBounds(0, 0, size, size);
            current.setAlpha((int) ((1f - animationProgress) * alpha * 255));
            current.draw(canvas);
        }
        if (next != null) {
            next.setBounds(0, 0, size, size);
            next.setAlpha((int) (animationProgress * alpha * 255));
            next.draw(canvas);
        }
        canvas.restore();
    }
}
