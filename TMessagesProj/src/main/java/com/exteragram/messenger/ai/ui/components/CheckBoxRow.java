package com.exteragram.messenger.ai.ui.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;

/** Compact "checkbox + label" pill (port of exteraGram's components/CheckBoxRow). */
@SuppressLint("ViewConstructor")
public final class CheckBoxRow extends LinearLayout {
    private final CheckBox2 checkBox;
    private Utilities.Callback<Boolean> onCheckedChange;

    public CheckBoxRow(Context context, CharSequence text, boolean checked, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        checkBox = new CheckBox2(context, 21, resourcesProvider);
        checkBox.setColor(Theme.key_radioBackgroundChecked, Theme.key_checkboxDisabled, Theme.key_checkboxCheck);
        checkBox.setDrawUnchecked(true);
        checkBox.setChecked(checked, false);
        checkBox.setDrawBackgroundAsArc(10);
        setOrientation(HORIZONTAL);
        setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(6), AndroidUtilities.dp(12), AndroidUtilities.dp(6));
        setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), 18, 18));
        ScaleStateListAnimator.apply(this, 0.05f, 1.2f);
        FrameLayout checkContainer = new FrameLayout(context);
        checkContainer.addView(checkBox, LayoutHelper.createFrame(21, 21, android.view.Gravity.CENTER));
        addView(checkContainer, LayoutHelper.createLinear(24, 24, android.view.Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
        TextView textView = new TextView(context);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        textView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        textView.setTypeface(android.graphics.Typeface.DEFAULT);
        textView.setText(text);
        addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, android.view.Gravity.CENTER_VERTICAL));
        setOnClickListener(v -> {
            checkBox.setChecked(!checkBox.isChecked(), true);
            if (onCheckedChange != null) {
                onCheckedChange.run(checkBox.isChecked());
            }
        });
    }

    public void setOnCheckedChange(Utilities.Callback<Boolean> callback) {
        this.onCheckedChange = callback;
    }

    public boolean isChecked() {
        return checkBox.isChecked();
    }

    public void setChecked(boolean checked) {
        checkBox.setChecked(checked, true);
    }
}
