package com.exteragram.messenger.backup;

import android.app.Activity;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.exteragram.messenger.utils.chats.ChatUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.io.File;

/**
 * Port of exteraGram's BackupBottomSheet: shown when a settings backup file (.staticgram / .extera) is tapped
 * in a chat or in shared media. exteraGram shows a sticker from the "exteraStaticPlaceholders" set,
 * which does not exist on our server, so a bundled Lottie animation is used instead.
 */
public class BackupBottomSheet extends BottomSheet {

    private final int difference;
    private final BaseFragment fragment;

    public BackupBottomSheet(BaseFragment fragment, MessageObject messageObject) {
        this(fragment, new File(ChatUtils.getInstance().getPathToMessage(messageObject)));
    }

    public BackupBottomSheet(final BaseFragment fragment, final File file) {
        super(fragment.getParentActivity(), false, fragment.getResourceProvider());
        this.fragment = fragment;
        Activity activity = fragment.getParentActivity();
        difference = PreferencesUtils.getInstance().getDiff(file);
        fixNavigationBar();

        FrameLayout frameLayout = new FrameLayout(activity);
        LinearLayout linearLayout = new LinearLayout(activity);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        frameLayout.addView(linearLayout);

        RLottieImageView imageView = new RLottieImageView(activity);
        imageView.setAutoRepeat(false);
        imageView.setAnimation(R.raw.utyan_change_number, 144, 144);
        imageView.playAnimation();
        linearLayout.addView(imageView, LayoutHelper.createLinear(144, 144, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));

        TextView titleView = new TextView(activity);
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);
        titleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setText(LocaleController.getString(R.string.ImportTitle));
        linearLayout.addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 40, 20, 40, 0));

        TextView descriptionView = new TextView(activity);
        descriptionView.setGravity(Gravity.CENTER_HORIZONTAL);
        descriptionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        descriptionView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        descriptionView.setText(AndroidUtilities.replaceTags(LocaleController.formatPluralString("ImportChanges", difference)));
        linearLayout.addView(descriptionView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 21, 15, 21, 8));

        ButtonWithCounterView applyButton = new ButtonWithCounterView(activity, true, resourcesProvider);
        applyButton.setRoundRadius(24);
        applyButton.setText(LocaleController.getString(R.string.ImportConfirm), false);
        applyButton.setOnClickListener(v -> {
            dismiss();
            PreferencesUtils.getInstance().importSettings(file, fragment.getParentActivity(), fragment.getParentLayout());
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.SettingsImported)).show();
        });
        linearLayout.addView(applyButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, 0, 16, 15, 16, 8));

        ButtonWithCounterView cancelButton = new ButtonWithCounterView(activity, false, resourcesProvider);
        cancelButton.setRoundRadius(24);
        cancelButton.setText(LocaleController.getString(R.string.CancelConfirm), false);
        cancelButton.setOnClickListener(v -> dismiss());
        linearLayout.addView(cancelButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, 0, 16, 0, 16, 0));

        ScrollView scrollView = new ScrollView(activity);
        scrollView.addView(frameLayout);
        setCustomView(scrollView);
    }

    public void showIfPossible() {
        if (difference > 0) {
            show();
        } else {
            AndroidUtilities.runOnUIThread(() -> BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.SameSettings)).show());
        }
    }
}
