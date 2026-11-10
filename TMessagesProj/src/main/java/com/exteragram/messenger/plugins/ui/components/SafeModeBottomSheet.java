package com.exteragram.messenger.plugins.ui.components;

import android.app.Activity;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.plugins.PluginsController;
import kotlin.Unit;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.StickerImageView;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

public final class SafeModeBottomSheet extends BottomSheet {
    @Override
    public void setLastVisible(boolean z) {
        super.setLastVisible(z);
    }

    public SafeModeBottomSheet(BaseFragment baseFragment) {
        super(baseFragment.getParentActivity(), false, baseFragment.getResourceProvider());
        Activity parentActivity = baseFragment.getParentActivity();
        fixNavigationBar();
        FrameLayout frameLayout = new FrameLayout(parentActivity);
        LinearLayout linearLayout = new LinearLayout(parentActivity);
        linearLayout.setOrientation(1);
        frameLayout.addView(linearLayout);
        StickerImageView stickerImageView = new StickerImageView(parentActivity, this.currentAccount);
        stickerImageView.setStickerPackName("exteraGramPlaceholders");
        stickerImageView.setStickerNum(10);
        stickerImageView.getImageReceiver().setAutoRepeat(1);
        linearLayout.addView(stickerImageView, LayoutHelper.createLinear(144, 144, 1, 0.0f, 16.0f, 0.0f, 0.0f));
        TextView textView = new TextView(parentActivity);
        textView.setGravity(1);
        textView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        textView.setTextSize(1, 20.0f);
        textView.setTypeface(AndroidUtilities.bold());
        textView.setText(LocaleController.getString(R.string.PluginsSafeMode));
        linearLayout.addView(textView, LayoutHelper.createFrame(-1, -2.0f, 0, 40.0f, 20.0f, 40.0f, 0.0f));
        TextView textView2 = new TextView(parentActivity);
        textView2.setGravity(1);
        textView2.setTypeface(AndroidUtilities.regular());
        textView2.setTextSize(1, 14.0f);
        textView2.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        textView2.setText(LocaleController.getString(R.string.PluginsSafeModeInfo));
        linearLayout.addView(textView2, LayoutHelper.createFrame(-1, -2.0f, 0, 21.0f, 8.0f, 21.0f, 0.0f));
        ButtonWithCounterView buttonWithCounterView = new ButtonWithCounterView(parentActivity, true, this.resourcesProvider);
        buttonWithCounterView.setRound();
        buttonWithCounterView.setText(LocaleController.getString(R.string.Disable), false);
        buttonWithCounterView.setOnClickListener(new View.OnClickListener() {
            @Override
            public final void onClick(View view) {
                SafeModeBottomSheet.$r8$lambda$OB8JQSwdEyFfmR8jRXIGBMuo7io(SafeModeBottomSheet.this, view);
            }
        });
        linearLayout.addView(buttonWithCounterView, LayoutHelper.createFrame(-1, 48.0f, 0, 16.0f, 28.0f, 16.0f, 16.0f));
        setCustomView(frameLayout);
    }

    public static void $r8$lambda$OB8JQSwdEyFfmR8jRXIGBMuo7io(SafeModeBottomSheet safeModeBottomSheet, View view) {
        safeModeBottomSheet.dismiss();
        SharedPreferences.Editor editor = ExteraConfig.getEditor();
        ExteraConfig.setPluginsSafeMode(false);
        editor.putBoolean("pluginsSafeMode", false).apply();
        PluginsController.Companion.getInstance().restart(false);
    }
}
