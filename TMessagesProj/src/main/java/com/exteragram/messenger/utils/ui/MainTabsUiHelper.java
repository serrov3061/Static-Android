package com.exteragram.messenger.utils.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.config.BottomNavigationBar;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProviderBuilder;
import org.telegram.ui.MainTabsLayout;

/**
 * exteraGram port: sizing / styling helpers for the main bottom tabs (default vs Material 3 navigation bar).
 * Plugins (e.g. Compact Bottom Nav) call these reflectively, so names and signatures must stay stable.
 */
public abstract class MainTabsUiHelper {

    public static boolean isMaterial3NavigationBar() {
        return ExteraConfig.getNewNavigationBarStyle();
    }

    public static void applyBackgroundStroke(BlurredBackgroundProviderBuilder builder) {
        if (isMaterial3NavigationBar()) {
            builder.setStrokeColorTop(0, 0).setStrokeColorBottom(0, 0).setStrokeColorFull(0, 0).setStrokeWidth(0f, 0f);
            return;
        }
        builder.setStrokeColorTop(0x11000000, 0x06FFFFFF)
                .setStrokeColorBottom(0x20000000, 0x11FFFFFF)
                .setStrokeWidth(dpf2(0.4f), dpf2(0.4f));
    }

    public static void applyMaterial3MainTabStyle(TextView textView) {
        textView.setIncludeFontPadding(false);
        textView.setLetterSpacing(0.04166667f);
        textView.setLayoutParams(LayoutHelper.createFrame(-1, 16f, 49, 0f, 42f, 0f, 0f));
    }

    public static void applyTabsBottomInset(MainTabsLayout tabsView, View container, int left, int right, int bottomInset) {
        int height = getTabsViewHeight(bottomInset);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) tabsView.getLayoutParams();
        if (lp.height != height) {
            lp.height = height;
            tabsView.setLayoutParams(lp);
        }
        int paddingBottom = getTabsInnerPaddingVertical() + (isMaterial3NavigationBar() ? bottomInset : 0);
        if (tabsView.getPaddingBottom() != paddingBottom) {
            tabsView.setPadding(tabsView.getPaddingLeft(), tabsView.getPaddingTop(), tabsView.getPaddingRight(), paddingBottom);
        }
        container.setPadding(left, 0, right, isMaterial3NavigationBar() ? 0 : bottomInset);
    }

    /** exteraGram 13.1.1: scales the tabs wrapper around the tabs' center while they hide (no scale for Material 3). */
    public static void applyTabsVisibilityScale(View wrapper, View tabsView, float scale) {
        if (wrapper == null || tabsView == null) {
            return;
        }
        if (isMaterial3NavigationBar()) {
            scale = 1.0f;
        }
        wrapper.setPivotX(tabsView.getLeft() + tabsView.getWidth() / 2.0f);
        wrapper.setPivotY(tabsView.getTop() + tabsView.getHeight() / 2.0f);
        wrapper.setScaleX(scale);
        wrapper.setScaleY(scale);
    }

    public static void applyTabsLayoutStyle(MainTabsLayout tabsView) {
        boolean m3 = isMaterial3NavigationBar();
        int horizontal = m3 ? 0 : dp(12);
        int vertical = getTabsInnerPaddingVertical();
        tabsView.setPadding(horizontal, vertical, horizontal, vertical);
        tabsView.setMaxWidth(m3 ? 0 : dp(344));
        tabsView.setFillAvailableWidth(m3);
        tabsView.setSwipeSelectionEnabled(!m3);
        tabsView.setDrawTopDivider(m3);
    }

    private static Drawable createMainTabsScrimBackground(int color, boolean circle) {
        ShapeDrawable drawable = circle ? Theme.createCircleDrawable(dp(40), color) : Theme.createRoundRectDrawable(dp(28), color);
        drawable.getPaint().setShadowLayer(dp(6), 0f, dp(1), Theme.multAlpha(0xFF000000, 0.15f));
        if (!isMaterial3NavigationBar()) {
            return drawable;
        }
        return new InsetDrawable(drawable, 0, dp(8), 0, dp(8));
    }

    public static Drawable createMainTabsScrimBackground(Theme.ResourcesProvider resourcesProvider, boolean circle) {
        return createMainTabsScrimBackground(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider), circle);
    }

    public static <K> SpringAnimation createMaterial3ColorSpring(K target, FloatPropertyCompat<K> property) {
        SpringAnimation spring = new SpringAnimation(target, property);
        spring.setMinimumVisibleChange(0.01f);
        spring.setSpring(new SpringForce(0f).setDampingRatio(1f).setStiffness(1600f));
        return spring;
    }

    public static <K> SpringAnimation createMaterial3IndicatorSizeSpring(K target, FloatPropertyCompat<K> property) {
        SpringAnimation spring = new SpringAnimation(target, property);
        spring.setMinimumVisibleChange(0.01f);
        spring.setSpring(new SpringForce(0f).setDampingRatio(0.8f).setStiffness(380f));
        return spring;
    }

    public static int getAdditionalNavigationBarHeight(boolean enabled) {
        if (enabled && !BottomNavigationBar.hidden() && !BottomNavigationBar.floating()) {
            return dp(getTabsViewHeightDp());
        }
        return 0;
    }

    public static int getBackgroundInset() {
        return isMaterial3NavigationBar() ? 0 : dp(7.666f);
    }

    public static float getBackgroundRadius() {
        return isMaterial3NavigationBar() ? 0f : dp(28);
    }

    public static int getFloatingTabsPadding(boolean enabled) {
        if (enabled && BottomNavigationBar.floating()) {
            return dp(getTabsViewHeightDp());
        }
        return 0;
    }

    public static float getMainTabCounterCenterY(boolean selectedIndicator) {
        if (selectedIndicator) {
            return dp(getMaterial3MainTabIconTopDp() + 6f);
        }
        return dpf2(10f);
    }

    public static int getMainTabSelectedIndicatorColor(int color, float progress) {
        return Theme.multAlpha(color, progress * 0.125f);
    }

    public static float getMaterial3MainTabAvatarTopDp() {
        return getMaterial3MainTabIconTopDp() + 1f;
    }

    public static float getMaterial3MainTabIconTopDp() {
        return 10f;
    }

    public static float getSelectedBackgroundScale(float progress) {
        return AndroidUtilities.lerp(0.6f, 1f, progress);
    }

    public static int getTabsFabOffset(boolean enabled) {
        if (enabled && BottomNavigationBar.visible()) {
            return dp(getTabsFabOffsetDp());
        }
        return 0;
    }

    public static int getTabsFabOffsetDp() {
        isMaterial3NavigationBar();
        return 64;
    }

    private static int getTabsInnerPaddingVertical() {
        return isMaterial3NavigationBar() ? 0 : dp(12);
    }

    private static int getTabsViewHeight(int bottomInset) {
        if (isMaterial3NavigationBar()) {
            return dp(64) + bottomInset;
        }
        return dp(72);
    }

    public static int getTabsViewHeightDp() {
        return isMaterial3NavigationBar() ? 64 : 72;
    }

    public static void setBlurBounds(RectF bounds, View view, int bottomInset) {
        int bottom = view.getMeasuredHeight();
        int top;
        if (isMaterial3NavigationBar()) {
            top = bottom - dp(64) - bottomInset;
        } else {
            bottom = bottom - bottomInset - dp(8);
            top = bottom - dp(56);
        }
        bounds.set(0f, top, view.getMeasuredWidth(), bottom);
    }

    public static void setMainTabSelectedIndicatorBounds(RectF bounds, float tabWidth, int tabHeight, float progress) {
        float maxWidth = dp(56);
        float width = Math.min(maxWidth, Math.max(0f, tabWidth - dp(4) * 2f)) * Math.max(0f, progress);
        float height = Math.min(dp(32), tabHeight);
        float left = (tabWidth - width) / 2f;
        float top = dp(6);
        bounds.set(left, top, left + width, top + height);
    }
}
