package com.exteragram.messenger.plugins.ui.components;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import com.exteragram.messenger.plugins.PluginsConstants;
import java.util.List;
import kotlin.text.MatchResult;
import kotlin.text.Regex;
import kotlin.text.StringsKt;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ScaleStateListAnimator;

public final class PluginRequirementsView extends ViewGroup {
    private final int itemSpacing;
    private final int lineSpacing;
    private final Theme.ResourcesProvider resourcesProvider;

    public static final Companion Companion = new Companion();
    private static final Regex NAME_PATTERN = new Regex("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Regex EXTRAS_PATTERN = new Regex("\\[[A-Za-z0-9,._\\s-]*]");

    public PluginRequirementsView(Context context) {
        this(context, null);
    }

    public static final String requirementName(String str) {
        return Companion.requirementName(str);
    }

    public static final String requirementUrl(String str) {
        return Companion.requirementUrl(str);
    }


    public PluginRequirementsView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.itemSpacing = AndroidUtilities.dp(4.0f);
        this.lineSpacing = AndroidUtilities.dp(4.0f);
    }

    public final void setRequirements(List<String> requirements) {
        removeAllViews();
        List<String> list = requirements;
        if (list == null || list.isEmpty()) {
            setVisibility(8);
            return;
        }
        setVisibility(0);
        for (final String str : requirements) {
            final TextView textView = new TextView(getContext());
            textView.setText(str);
            textView.setTextSize(1, 12.0f);
            int themedColor = getThemedColor(Theme.key_featuredStickers_addButton);
            textView.setTextColor(themedColor);
            textView.setTypeface(AndroidUtilities.regular());
            textView.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(6.0f), ColorUtils.setAlphaComponent(themedColor, 30)));
            textView.setPadding(AndroidUtilities.dp(6.0f), AndroidUtilities.dp(2.0f), AndroidUtilities.dp(6.0f), AndroidUtilities.dp(2.0f));
            ScaleStateListAnimator.apply(textView);
            textView.setOnClickListener(new View.OnClickListener() {
                @Override
                public final void onClick(View view) {
                    PluginRequirementsView.m1612$r8$lambda$AK1KYC4qaDW5pgXJLeqVBXRMjA(str, textView, view);
                }
            });
            addView(textView);
        }
        requestLayout();
    }

    public static void m1612$r8$lambda$AK1KYC4qaDW5pgXJLeqVBXRMjA(String str, TextView textView, View view) {
        String strRequirementUrl = Companion.requirementUrl(str);
        if (strRequirementUrl != null) {
            com.exteragram.messenger.utils.ExternalTelegram.openUrl(textView.getContext(), strRequirementUrl);
        }
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = View.MeasureSpec.getSize(widthMeasureSpec);
        int mode = View.MeasureSpec.getMode(widthMeasureSpec);
        int paddingLeft = (size - getPaddingLeft()) - getPaddingRight();
        int paddingTop = getPaddingTop();
        int childCount = getChildCount();
        int iMax = 0;
        int iMax2 = 0;
        int i = 0;
        for (int i2 = 0; i2 < childCount; i2++) {
            View childAt = getChildAt(i2);
            if (childAt.getVisibility() != 8) {
                measureChild(childAt, widthMeasureSpec, heightMeasureSpec);
                int measuredWidth = childAt.getMeasuredWidth();
                int measuredHeight = childAt.getMeasuredHeight();
                if (i + measuredWidth > paddingLeft) {
                    paddingTop += iMax + this.lineSpacing;
                    iMax = 0;
                    i = 0;
                }
                i += measuredWidth + this.itemSpacing;
                iMax = Math.max(iMax, measuredHeight);
                iMax2 = Math.max(iMax2, i - this.itemSpacing);
            }
        }
        int paddingBottom = paddingTop + iMax + getPaddingBottom();
        if (mode != 1073741824) {
            size = iMax2 + getPaddingRight() + getPaddingLeft();
        }
        setMeasuredDimension(size, paddingBottom);
    }

    @Override
    public void onLayout(boolean changed, int l, int t, int r, int b2) {
        int i = r - l;
        int paddingLeft = getPaddingLeft();
        int paddingTop = getPaddingTop();
        int childCount = getChildCount();
        int iMax = 0;
        for (int i2 = 0; i2 < childCount; i2++) {
            View childAt = getChildAt(i2);
            if (childAt.getVisibility() != 8) {
                int measuredWidth = childAt.getMeasuredWidth();
                int measuredHeight = childAt.getMeasuredHeight();
                if (paddingLeft + measuredWidth > i - getPaddingRight()) {
                    paddingLeft = getPaddingLeft();
                    paddingTop += iMax + this.lineSpacing;
                    iMax = 0;
                }
                childAt.layout(paddingLeft, paddingTop, paddingLeft + measuredWidth, paddingTop + measuredHeight);
                paddingLeft += measuredWidth + this.itemSpacing;
                iMax = Math.max(iMax, measuredHeight);
            }
        }
    }

    private final int getThemedColor(int key) {
        return Theme.getColor(key, this.resourcesProvider);
    }

    public static final class Companion {
        private Companion() {
        }

        public final String requirementName(String requirement) {
            String value;
            String string = StringsKt.trim((CharSequence) requirement).toString();
            MatchResult matchResultMatchAt = PluginRequirementsView.NAME_PATTERN.matchAt(string, 0);
            if (matchResultMatchAt == null || (value = matchResultMatchAt.getValue()) == null) {
                return string;
            }
            MatchResult matchResultMatchAt2 = PluginRequirementsView.EXTRAS_PATTERN.matchAt(string, value.length());
            String value2 = matchResultMatchAt2 != null ? matchResultMatchAt2.getValue() : null;
            if (value2 == null) {
                value2 = "";
            }
            return value.concat(value2);
        }

        public final String requirementUrl(String requirement) {
            MatchResult matchResultMatchAt;
            String value;
            String string = StringsKt.trim((CharSequence) requirement).toString();
            if (StringsKt.contains((CharSequence) string, (CharSequence) "://", false) || (matchResultMatchAt = PluginRequirementsView.NAME_PATTERN.matchAt(string, 0)) == null || (value = matchResultMatchAt.getValue()) == null) {
                return null;
            }
            return "https://pypi.org/project/" + value + "/";
        }
    }
}
