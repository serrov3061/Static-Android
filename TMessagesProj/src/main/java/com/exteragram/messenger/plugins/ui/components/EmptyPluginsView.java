package com.exteragram.messenger.plugins.ui.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EffectsTextView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ViewHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

@SuppressLint({"ClickableViewAccessibility"})
public final class EmptyPluginsView extends FrameLayout {
    private final BackupImageView backupImageView;
    private final EffectsTextView textView;
    // exteraGram 13.1.1: optional title and button ("Open Catalog")
    private final TextView titleView;
    private final ButtonWithCounterView button;

    public EmptyPluginsView(Context context) {
        this(context, null);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }


    public EmptyPluginsView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        LinearLayout linearLayout = new LinearLayout(context);
        ViewHelper.setPadding(linearLayout, 20.0f, 0.0f, 20.0f, 0.0f);
        linearLayout.setGravity(1);
        linearLayout.setClipChildren(false);
        linearLayout.setClipToPadding(false);
        linearLayout.setOrientation(1);
        BackupImageView backupImageView = new BackupImageView(context);
        this.backupImageView = backupImageView;
        linearLayout.addView(backupImageView, LayoutHelper.createLinear(100, 100, 17, 0.0f, 0.0f, 0.0f, 20.0f));
        TextView titleView = new TextView(context);
        titleView.setTextSize(1, 20.0f);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setGravity(17);
        titleView.setVisibility(GONE);
        this.titleView = titleView;
        linearLayout.addView(titleView, LayoutHelper.createLinear(-2, -2, 17, 0.0f, 0.0f, 0.0f, 8.0f));
        EffectsTextView effectsTextView = new EffectsTextView(context);
        effectsTextView.setTextSize(1, 14.0f);
        effectsTextView.setTextColor(Theme.getColor(Theme.key_emptyListPlaceholder, resourcesProvider));
        effectsTextView.setGravity(1);
        effectsTextView.setText(LocaleController.getString(R.string.NoResult));
        effectsTextView.setTypeface(AndroidUtilities.regular());
        effectsTextView.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
        effectsTextView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText));
        this.textView = effectsTextView;
        effectsTextView.setMaxWidth(AndroidUtilities.dp(272.0f));
        effectsTextView.setLineSpacing(AndroidUtilities.dp(2.0f), 1.0f);
        linearLayout.addView(effectsTextView, LayoutHelper.createLinear(-2, -2, 17));
        ButtonWithCounterView button = new ButtonWithCounterView(context, resourcesProvider).setRound();
        button.setVisibility(GONE);
        this.button = button;
        linearLayout.addView(button, LayoutHelper.createLinear(256, 48, 17, 0.0f, 20.0f, 0.0f, 0.0f));
        addView(linearLayout, LayoutHelper.createFrame(-2, -2, 17));
        setOnTouchListener(new View.OnTouchListener() {
            @Override
            public final boolean onTouch(View view, MotionEvent motionEvent) {
                return EmptyPluginsView.$r8$lambda$Vd6WFN2r51k6Ofv4Z8vIUXzLhEQ(view, motionEvent);
            }
        });
    }

    public final BackupImageView getBackupImageView() {
        return this.backupImageView;
    }

    public static boolean $r8$lambda$Vd6WFN2r51k6Ofv4Z8vIUXzLhEQ(View view, MotionEvent motionEvent) {
        return true;
    }

    public final void setText(CharSequence text) {
        this.textView.setText(text);
    }

    public final void setTitle(CharSequence text) {
        this.titleView.setText(text);
        this.titleView.setVisibility(text == null || text.length() == 0 ? GONE : VISIBLE);
    }

    public final void setButton(CharSequence text, Runnable onClick) {
        if (text == null || text.length() == 0 || onClick == null) {
            this.button.setVisibility(GONE);
            this.button.setOnClickListener(null);
        } else {
            this.button.setText(text, false);
            this.button.setOnClickListener(v -> onClick.run());
            this.button.setVisibility(VISIBLE);
        }
    }
}
