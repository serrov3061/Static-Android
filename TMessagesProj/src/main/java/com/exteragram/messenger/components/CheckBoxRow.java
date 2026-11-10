package com.exteragram.messenger.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** exteraGram: a rounded "checkbox + label" row (used by the plugin install sheet and SDK update alert). */
@SuppressLint("ViewConstructor")
public final class CheckBoxRow extends LinearLayout {

    private final CheckBox2 checkBox;
    private Function1<? super Boolean, Unit> onCheckedChange;

    public CheckBoxRow(Context context, CharSequence label) {
        this(context, label, false, null);
    }

    public CheckBoxRow(Context context, CharSequence label, boolean checked) {
        this(context, label, checked, null);
    }

    public CheckBoxRow(Context context, CharSequence label, boolean checked, Theme.ResourcesProvider resourcesProvider) {
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
        FrameLayout checkBoxContainer = new FrameLayout(context);
        checkBoxContainer.addView(checkBox, LayoutHelper.createFrame(21, 21, android.view.Gravity.CENTER));
        addView(checkBoxContainer, LayoutHelper.createLinear(24, 24, android.view.Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
        TextView textView = new TextView(context);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        textView.setTextSize(1, 14);
        textView.setTypeface(AndroidUtilities.regular());
        textView.setText(label);
        addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, android.view.Gravity.CENTER_VERTICAL));
        setOnClickListener(v -> {
            checkBox.setChecked(!checkBox.isChecked(), true);
            if (onCheckedChange != null) {
                onCheckedChange.invoke(checkBox.isChecked());
            }
        });
    }

    public Function1<? super Boolean, Unit> getOnCheckedChange() {
        return onCheckedChange;
    }

    public void setOnCheckedChange(Function1<? super Boolean, Unit> onCheckedChange) {
        this.onCheckedChange = onCheckedChange;
    }

    public boolean isChecked() {
        return checkBox.isChecked();
    }

    public void setChecked(boolean checked) {
        checkBox.setChecked(checked, true);
    }
}
