package com.exteragram.messenger.icons.ui.components;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StrikethroughSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.components.CheckBoxRow;
import com.exteragram.messenger.icons.IconManager;
import com.exteragram.messenger.icons.IconPack;
import com.exteragram.messenger.utils.text.LocaleUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EffectsTextView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.io.File;

/**
 * exteraGram InstallIconPackBottomSheet: preview of a ".icons" archive (circular icon preview, name, version change
 * and author) with an Install / Update button and "Enable after installation". The extracted preview directory is
 * deleted a second after the sheet is closed.
 */
public final class InstallIconPackBottomSheet extends BottomSheet {

    public interface InstallDelegate {
        void onInstall(boolean enable, boolean update);
    }

    private final IconPack iconPack;
    private final InstallDelegate installDelegate;

    public InstallIconPackBottomSheet(Context context, IconPack iconPack, InstallDelegate installDelegate) {
        super(context, false);
        this.iconPack = iconPack;
        this.installDelegate = installDelegate;
        fixNavigationBar();
        setCustomView(createView(context));
        setOnDismissListener(() -> AndroidUtilities.runOnUIThread(() -> Utilities.globalQueue.postRunnable(() -> {
            File location = iconPack.getLocation();
            if (location != null) {
                deleteRecursively(location);
            }
        }), 1000));
    }

    private static void deleteRecursively(File file) {
        File[] children = file.isDirectory() ? file.listFiles() : null;
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    private View createView(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, AndroidUtilities.dp(16), 0, 0);

        IconPackPreviewView preview = new IconPackPreviewView(context);
        preview.setCircularMode(true);
        preview.setRefreshTime(3000);
        preview.setIconPack(iconPack);
        layout.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        title.setText(iconPack.getName());
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 0));

        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        IconPack installed = IconManager.INSTANCE.findPackById(iconPack.getId());
        boolean update = installed != null;
        EffectsTextView subtitle = new EffectsTextView(context, resourcesProvider);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setTypeface(AndroidUtilities.regular());
        subtitle.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
        subtitle.setLinkTextColor(getThemedColor(Theme.key_dialogTextLink));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        SpannableStringBuilder text = new SpannableStringBuilder(LocaleController.getString(R.string.PluginVersion)).append(" ");
        int start = text.length();
        if (installed != null) {
            String version = installed.getVersion();
            text.append(version).append(" -> ").append(iconPack.getVersion());
            text = LocaleUtils.replaceArrows(context, text, R.drawable.msg_mini_arrow_mediathin);
            text.setSpan(new StrikethroughSpan(), start, start + version.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (!TextUtils.isEmpty(iconPack.getVersion())) {
            text.append(iconPack.getVersion());
        }
        if (!TextUtils.isEmpty(iconPack.getAuthor())) {
            text.append(" • ").append(LocaleUtils.formatWithUsernames(iconPack.getAuthor(), fragment, this::dismiss));
        }
        subtitle.setText(text);
        layout.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 24));

        ButtonWithCounterView button = new ButtonWithCounterView(context, true, resourcesProvider);
        button.setRound();
        button.setCount(LocaleController.formatPluralString("IconCount", iconPack.getIcons().size()), false);
        button.setText(LocaleController.getString(update ? R.string.UpdatePack : R.string.InstallPack), false);
        layout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 16, 0, 16, 16));

        CheckBoxRow enableRow;
        if (ExteraConfig.getIconPacksLayout().contains(iconPack.getId())) {
            enableRow = null;
        } else {
            enableRow = new CheckBoxRow(context, LocaleController.getString(R.string.EnableAfterInstallation), true, resourcesProvider);
            layout.addView(enableRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 8));
        }
        button.setOnClickListener(v -> {
            dismiss();
            AndroidUtilities.runOnUIThread(() -> installDelegate.onInstall(enableRow != null && enableRow.isChecked(), update), 200);
        });
        return layout;
    }
}
