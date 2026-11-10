package com.exteragram.messenger.plugins.ui.components.templates;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import org.telegram.messenger.Utilities;

public final class UniversalFrameLayout extends FrameLayout {
    private UniversalFrameLayoutListener universalFrameLayoutListener;

    public UniversalFrameLayout(Context context) {
        this(context, null);
    }


    public final UniversalFrameLayoutListener getUniversalFrameLayoutListener() {
        return this.universalFrameLayoutListener;
    }

    public final void setUniversalFrameLayoutListener(UniversalFrameLayoutListener universalFrameLayoutListener) {
        this.universalFrameLayoutListener = universalFrameLayoutListener;
    }

    public UniversalFrameLayout(Context context, UniversalFrameLayoutListener universalFrameLayoutListener) {
        super(context);
        this.universalFrameLayoutListener = universalFrameLayoutListener;
    }

    public final void callSuperOnLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
    }

    public final void callSuperOnMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    public final void callSuperSetTranslationX(float translationX) {
        super.setTranslationX(translationX);
    }

    public final void callSuperSetTranslationY(float translationY) {
        super.setTranslationY(translationY);
    }

    public final void callSuperOnDetachedFromWindow() {
        super.onDetachedFromWindow();
    }

    public final void callSuperOnAttachedToWindow() {
        super.onAttachedToWindow();
    }

    public final void callSuperDispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
    }

    public final void callSuperRequestLayout() {
        super.requestLayout();
    }

    public final void callSuperInvalidate() {
        super.invalidate();
    }

    public final void callSuperOnDraw(Canvas canvas) {
        super.onDraw(canvas);
    }

    public final void callSuperOnInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
    }

    public final boolean callSuperOnInterceptTouchEvent(MotionEvent ev) {
        return super.onInterceptTouchEvent(ev);
    }

    public final boolean callSuperOnTouchEvent(MotionEvent event) {
        return super.onTouchEvent(event);
    }

    public final boolean callSuperDrawChild(Canvas canvas, View child, long drawingTime) {
        return super.drawChild(canvas, child, drawingTime);
    }

    public final void callSuperSetVisibility(int visibility) {
        super.setVisibility(visibility);
    }

    @Override
    public void onLayout(boolean changed, int left, int top, int right, int bottom) {
        UniversalFrameLayoutListener universalFrameLayoutListener = this.universalFrameLayoutListener;
        if (universalFrameLayoutListener != null) {
            universalFrameLayoutListener.onLayout(changed, left, top, right, bottom, new Utilities.Callback5() {
                @Override
                public final void run(Object obj, Object obj2, Object obj3, Object obj4, Object obj5) {
                    UniversalFrameLayout.this.callSuperOnLayout(((Boolean) obj).booleanValue(), ((Integer) obj2).intValue(), ((Integer) obj3).intValue(), ((Integer) obj4).intValue(), ((Integer) obj5).intValue());
                }
            });
        } else {
            super.onLayout(changed, left, top, right, bottom);
        }
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        UniversalFrameLayoutListener universalFrameLayoutListener = this.universalFrameLayoutListener;
        if (universalFrameLayoutListener != null) {
            universalFrameLayoutListener.onMeasure(widthMeasureSpec, heightMeasureSpec, new Utilities.Callback2() {
                @Override
                public final void run(Object obj, Object obj2) {
                    UniversalFrameLayout.this.callSuperOnMeasure(((Integer) obj).intValue(), ((Integer) obj2).intValue());
                }
            });
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    public void setTranslationX(float translationX) {
        UniversalFrameLayoutListener universalFrameLayoutListener = this.universalFrameLayoutListener;
        if (universalFrameLayoutListener != null) {
            universalFrameLayoutListener.setTranslationX(translationX, new Utilities.Callback() {
                @Override
                public final void run(Object obj) {
                    UniversalFrameLayout.this.callSuperSetTranslationX(((Float) obj).floatValue());
                }
            });
        } else {
            super.setTranslationX(translationX);
        }
    }

    @Override
    public void setTranslationY(float translationY) {
        UniversalFrameLayoutListener universalFrameLayoutListener = this.universalFrameLayoutListener;
        if (universalFrameLayoutListener != null) {
            universalFrameLayoutListener.setTranslationY(translationY, new Utilities.Callback() {
                @Override
                public final void run(Object obj) {
                    UniversalFrameLayout.this.callSuperSetTranslationY(((Float) obj).floatValue());
                }
            });
        } else {
            super.setTranslationY(translationY);
        }
    }

    @Override
    public void onAttachedToWindow() {
        UniversalFrameLayoutListener universalFrameLayoutListener = this.universalFrameLayoutListener;
        if (universalFrameLayoutListener != null) {
            universalFrameLayoutListener.onAttachedToWindow(new Runnable() {
                @Override
                public final void run() {
                    UniversalFrameLayout.this.callSuperOnAttachedToWindow();
                }
            });
        } else {
            super.onAttachedToWindow();
        }
    }

    @Override
    public void onDetachedFromWindow() {
        UniversalFrameLayoutListener universalFrameLayoutListener = this.universalFrameLayoutListener;
        if (universalFrameLayoutListener != null) {
            universalFrameLayoutListener.onDetachedFromWindow(new Runnable() {
                @Override
                public final void run() {
                    UniversalFrameLayout.this.callSuperOnDetachedFromWindow();
                }
            });
        } else {
            super.onDetachedFromWindow();
        }
    }

    @Override
    public void dispatchDraw(Canvas canvas) {
        UniversalFrameLayoutListener universalFrameLayoutListener = this.universalFrameLayoutListener;
        if (universalFrameLayoutListener != null) {
            universalFrameLayoutListener.dispatchDraw(canvas, new Utilities.Callback() {
                @Override
                public final void run(Object obj) {
                    UniversalFrameLayout.this.callSuperDispatchDraw((Canvas) obj);
                }
            });
        } else {
            super.dispatchDraw(canvas);
        }
    }

    @Override
    public void requestLayout() {
        UniversalFrameLayoutListener universalFrameLayoutListener = this.universalFrameLayoutListener;
        if (universalFrameLayoutListener != null) {
            universalFrameLayoutListener.requestLayout(new Runnable() {
                @Override
                public final void run() {
                    UniversalFrameLayout.this.callSuperRequestLayout();
                }
            });
        } else {
            super.requestLayout();
        }
    }

    @Override
    public void invalidate() {
        UniversalFrameLayoutListener universalFrameLayoutListener = this.universalFrameLayoutListener;
        if (universalFrameLayoutListener != null) {
            universalFrameLayoutListener.invalidate(this::callSuperInvalidate);
        } else {
            super.invalidate();
        }
    }

    @Override
    @Deprecated
    public void invalidate(int l, int t, int r, int b2) {
        UniversalFrameLayoutListener universalFrameLayoutListener = this.universalFrameLayoutListener;
        if (universalFrameLayoutListener != null) {
            universalFrameLayoutListener.invalidate(l, t, r, b2, this::callSuperInvalidate);
        } else {
            super.invalidate(l, t, r, b2);
        }
    }

    @Override
    public void onDraw(Canvas canvas) {
        UniversalFrameLayoutListener universalFrameLayoutListener = this.universalFrameLayoutListener;
        if (universalFrameLayoutListener != null) {
            universalFrameLayoutListener.onDraw(canvas, new Utilities.Callback() {
                @Override
                public final void run(Object obj) {
                    UniversalFrameLayout.this.callSuperOnDraw((Canvas) obj);
                }
            });
        } else {
            super.onDraw(canvas);
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        UniversalFrameLayoutListener universalFrameLayoutListener = this.universalFrameLayoutListener;
        if (universalFrameLayoutListener != null) {
            universalFrameLayoutListener.onInitializeAccessibilityNodeInfo(info, new Utilities.Callback() {
                @Override
                public final void run(Object obj) {
                    UniversalFrameLayout.this.callSuperOnInitializeAccessibilityNodeInfo((AccessibilityNodeInfo) obj);
                }
            });
        } else {
            super.onInitializeAccessibilityNodeInfo(info);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        UniversalFrameLayoutListener universalFrameLayoutListener = this.universalFrameLayoutListener;
        if (universalFrameLayoutListener != null) {
            return universalFrameLayoutListener.onInterceptTouchEvent(ev, new Utilities.CallbackReturn() {
                @Override
                public final Object run(Object obj) {
                    return Boolean.valueOf(UniversalFrameLayout.this.callSuperOnInterceptTouchEvent((MotionEvent) obj));
                }
            });
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    @SuppressLint({"ClickableViewAccessibility"})
    public boolean onTouchEvent(MotionEvent event) {
        UniversalFrameLayoutListener universalFrameLayoutListener = this.universalFrameLayoutListener;
        return universalFrameLayoutListener != null ? universalFrameLayoutListener.onTouchEvent(event, new Utilities.CallbackReturn() {
            @Override
            public final Object run(Object obj) {
                return Boolean.valueOf(UniversalFrameLayout.this.callSuperOnTouchEvent((MotionEvent) obj));
            }
        }) : super.onTouchEvent(event);
    }

    @Override
    public boolean drawChild(Canvas canvas, View child, long drawingTime) {
        UniversalFrameLayoutListener universalFrameLayoutListener = this.universalFrameLayoutListener;
        if (universalFrameLayoutListener != null) {
            return universalFrameLayoutListener.drawChild(canvas, child, drawingTime, new Utilities.Callback3Return() {
                @Override
                public final Object run(Object obj, Object obj2, Object obj3) {
                    return Boolean.valueOf(UniversalFrameLayout.this.callSuperDrawChild((Canvas) obj, (View) obj2, ((Long) obj3).longValue()));
                }
            });
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    @Override
    public void setVisibility(int visibility) {
        UniversalFrameLayoutListener universalFrameLayoutListener = this.universalFrameLayoutListener;
        if (universalFrameLayoutListener != null) {
            universalFrameLayoutListener.setVisibility(visibility, new Utilities.Callback() {
                @Override
                public final void run(Object obj) {
                    UniversalFrameLayout.this.callSuperSetVisibility(((Integer) obj).intValue());
                }
            });
        } else {
            super.setVisibility(visibility);
        }
    }

    public interface UniversalFrameLayoutListener {


        default void onLayout(boolean changed, int left, int top, int right, int bottom, Utilities.Callback5<Boolean, Integer, Integer, Integer, Integer> originalMethod) {
            originalMethod.run(Boolean.valueOf(changed), Integer.valueOf(left), Integer.valueOf(top), Integer.valueOf(right), Integer.valueOf(bottom));
        }

        default void onMeasure(int widthMeasureSpec, int heightMeasureSpec, Utilities.Callback2<Integer, Integer> originalMethod) {
            originalMethod.run(Integer.valueOf(widthMeasureSpec), Integer.valueOf(heightMeasureSpec));
        }

        default void setTranslationX(float translationX, Utilities.Callback<Float> originalMethod) {
            originalMethod.run(Float.valueOf(translationX));
        }

        default void setTranslationY(float translationY, Utilities.Callback<Float> originalMethod) {
            originalMethod.run(Float.valueOf(translationY));
        }

        default void onAttachedToWindow(Runnable originalMethod) {
            originalMethod.run();
        }

        default void onDetachedFromWindow(Runnable originalMethod) {
            originalMethod.run();
        }

        default void dispatchDraw(Canvas canvas, Utilities.Callback<Canvas> originalMethod) {
            originalMethod.run(canvas);
        }

        default void requestLayout(Runnable originalMethod) {
            originalMethod.run();
        }

        default void invalidate(Runnable originalMethod) {
            originalMethod.run();
        }

        default void invalidate(int l, int t, int r, int b2, Runnable originalMethod) {
            originalMethod.run();
        }

        default void onDraw(Canvas canvas, Utilities.Callback<Canvas> originalMethod) {
            originalMethod.run(canvas);
        }

        default void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info, Utilities.Callback<AccessibilityNodeInfo> originalMethod) {
            originalMethod.run(info);
        }

        default boolean onInterceptTouchEvent(MotionEvent ev, Utilities.CallbackReturn<MotionEvent, Boolean> originalMethod) {
            return originalMethod.run(ev).booleanValue();
        }

        default boolean onTouchEvent(MotionEvent event, Utilities.CallbackReturn<MotionEvent, Boolean> originalMethod) {
            return originalMethod.run(event).booleanValue();
        }

        default boolean drawChild(Canvas canvas, View child, long drawingTime, Utilities.Callback3Return<Canvas, View, Long, Boolean> originalMethod) {
            return originalMethod.run(canvas, child, Long.valueOf(drawingTime)).booleanValue();
        }

        default void setVisibility(int visibility, Utilities.Callback<Integer> originalMethod) {
            originalMethod.run(Integer.valueOf(visibility));
        }
    }
}
