package com.exteragram.messenger.plugins.ui.components.templates;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import org.telegram.messenger.Utilities;

public final class UniversalView extends View {
    private UniversalViewDelegate delegate;

    public UniversalView(Context context) {
        this(context, null);
    }


    public final UniversalViewDelegate getDelegate() {
        return this.delegate;
    }

    public final void setDelegate(UniversalViewDelegate universalViewDelegate) {
        this.delegate = universalViewDelegate;
    }

    public UniversalView(Context context, UniversalViewDelegate universalViewDelegate) {
        super(context);
        this.delegate = universalViewDelegate;
    }

    public final void callSuperOnDraw(Canvas canvas) {
        super.onDraw(canvas);
    }

    public final boolean callSuperOnTouchEvent(MotionEvent event) {
        return super.onTouchEvent(event);
    }

    public final void callSuperOnMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    public final void callSuperOnInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
    }

    @Override
    public void onDraw(Canvas canvas) {
        UniversalViewDelegate universalViewDelegate = this.delegate;
        if (universalViewDelegate != null) {
            universalViewDelegate.onDraw(canvas, new Utilities.Callback() {
                @Override
                public final void run(Object obj) {
                    UniversalView.this.callSuperOnDraw((Canvas) obj);
                }
            });
        } else {
            super.onDraw(canvas);
        }
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        UniversalViewDelegate universalViewDelegate = this.delegate;
        if (universalViewDelegate != null) {
            universalViewDelegate.onAttachedToWindow();
        }
    }

    @Override
    @SuppressLint({"ClickableViewAccessibility"})
    public boolean onTouchEvent(MotionEvent event) {
        UniversalViewDelegate universalViewDelegate = this.delegate;
        return universalViewDelegate != null ? universalViewDelegate.onTouchEvent(event, new Utilities.CallbackReturn() {
            @Override
            public final Object run(Object obj) {
                return Boolean.valueOf(UniversalView.this.callSuperOnTouchEvent((MotionEvent) obj));
            }
        }) : super.onTouchEvent(event);
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        UniversalViewDelegate universalViewDelegate = this.delegate;
        if (universalViewDelegate != null) {
            universalViewDelegate.onMeasure(widthMeasureSpec, heightMeasureSpec, new Utilities.Callback2() {
                @Override
                public final void run(Object obj, Object obj2) {
                    UniversalView.this.callSuperOnMeasure(((Integer) obj).intValue(), ((Integer) obj2).intValue());
                }
            });
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        UniversalViewDelegate universalViewDelegate = this.delegate;
        if (universalViewDelegate != null) {
            universalViewDelegate.onDetachedFromWindow();
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        UniversalViewDelegate universalViewDelegate = this.delegate;
        if (universalViewDelegate != null) {
            universalViewDelegate.onInitializeAccessibilityNodeInfo(info, new Utilities.Callback() {
                @Override
                public final void run(Object obj) {
                    UniversalView.this.callSuperOnInitializeAccessibilityNodeInfo((AccessibilityNodeInfo) obj);
                }
            });
        } else {
            super.onInitializeAccessibilityNodeInfo(info);
        }
    }

    public interface UniversalViewDelegate {
        default void onAttachedToWindow() {
        }

        default void onDetachedFromWindow() {
        }


        default void onDraw(Canvas canvas, Utilities.Callback<Canvas> originalMethod) {
            originalMethod.run(canvas);
        }

        default boolean onTouchEvent(MotionEvent event, Utilities.CallbackReturn<MotionEvent, Boolean> originalMethod) {
            return originalMethod.run(event).booleanValue();
        }

        default void onMeasure(int widthMeasureSpec, int heightMeasureSpec, Utilities.Callback2<Integer, Integer> originalMethod) {
            originalMethod.run(Integer.valueOf(widthMeasureSpec), Integer.valueOf(heightMeasureSpec));
        }

        default void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info, Utilities.Callback<AccessibilityNodeInfo> originalMethod) {
            originalMethod.run(info);
        }
    }
}
