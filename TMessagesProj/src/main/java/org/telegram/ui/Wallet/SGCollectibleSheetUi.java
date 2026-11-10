package org.telegram.ui.Wallet;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.LayoutTransition;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.TableView;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

/**
 * StaticGram: the skeleton of Telegram 13.1's wallet collectible sheet
 * (WalletActivity.openCollectibleSheet), shared by usernames, numbers and .ton domains:
 * gray BottomSheet, the rounded image, the bold title, a row of subtitle chips, the white
 * action tiles (icon over a 12sp label), the rounded TableView and the «Готово» button.
 */
final class SGCollectibleSheetUi {

    final Context context;
    final Theme.ResourcesProvider resourcesProvider;
    final BottomSheet sheet;
    final LinearLayout layout;
    final BackupImageView imageView;
    final TextView titleView;
    final LinearLayout chips;
    final LinearLayout actions;
    final TableView table;
    final ButtonWithCounterView okButton;
    private final int backgroundColor;

    SGCollectibleSheetUi(Context context, Theme.ResourcesProvider resourcesProvider) {
        this.context = context;
        this.resourcesProvider = resourcesProvider;
        backgroundColor = Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider);
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        sheet = new BottomSheet(context, false, resourcesProvider) {
            {
                behindKeyboardColorKey = Theme.key_windowBackgroundGray;
            }
        };
        sheet.setBackgroundColor(backgroundColor);
        sheet.fixNavigationBar(backgroundColor);

        // As tall as the content (scrolling inside when it does not fit), with the grab handle on top.
        final ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(false);
        scrollView.setVerticalScrollBarEnabled(false);
        layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(dp(12), dp(8), dp(12), dp(12));
        final LayoutTransition transition = new LayoutTransition();
        transition.enableTransitionType(LayoutTransition.CHANGING);
        transition.setDuration(220);
        transition.setInterpolator(LayoutTransition.CHANGING, CubicBezierInterpolator.EASE_OUT_QUINT);
        layout.setLayoutTransition(transition);
        scrollView.addView(layout, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        layout.addView(createHandle(context, resourcesProvider), LayoutHelper.createLinear(36, 4, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));

        imageView = new BackupImageView(context);
        imageView.setRoundRadius(dp(16));
        layout.addView(imageView, LayoutHelper.createLinear(140, 140, Gravity.CENTER_HORIZONTAL));

        titleView = new TextView(context);
        titleView.setTextColor(textColor);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setGravity(Gravity.CENTER);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        layout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 16, 12, 0));

        chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER);
        layout.addView(chips, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 12, 8, 12, 0));

        actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        layout.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 54, 0, 20, 0, 14));

        table = new TableView(context, resourcesProvider);
        table.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider)));
        layout.addView(table, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        okButton = new ButtonWithCounterView(context, resourcesProvider).setRound();
        okButton.setText(getString(R.string.SgDnsDone), false);
        okButton.setOnClickListener(v -> sheet.dismiss());
        layout.addView(okButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));

        sheet.setCustomView(scrollView);
        sheet.useBackgroundTopPadding = false;
    }

    /** The bottom sheet grab handle (36×4, rounded, gray). */
    static android.view.View createHandle(Context context, Theme.ResourcesProvider resourcesProvider) {
        final android.view.View handle = new android.view.View(context);
        handle.setBackground(Theme.createRoundRectDrawable(dp(2),
                Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider), .4f)));
        return handle;
    }

    /** A rounded chip under the title: tinted text on a 10% tint, as the gift sheets' status chips. */
    TextView addChip(CharSequence text, int color) {
        final TextView chip = new TextView(context);
        chip.setText(text);
        chip.setTextColor(color);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        chip.setTypeface(AndroidUtilities.bold());
        chip.setSingleLine(true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(9), 0, dp(9), 0);
        chip.setBackground(Theme.createRoundRectDrawable(dp(11), Theme.multAlpha(color, .12f)));
        chips.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 22, Gravity.CENTER_VERTICAL, chips.getChildCount() == 0 ? 0 : 6, 0, 0, 0));
        return chip;
    }

    /** A 13.1 action tile: the 24dp icon over the bold 12sp label on a white 16dp rounded card. */
    LinearLayout addAction(int iconRes, CharSequence label, View.OnClickListener onClick) {
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        final LinearLayout action = new LinearLayout(context);
        action.setOrientation(LinearLayout.VERTICAL);
        action.setGravity(Gravity.CENTER);
        action.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(16),
                Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider),
                Theme.getColor(Theme.key_listSelector, resourcesProvider)));
        action.setClickable(true);
        action.setFocusable(true);
        action.setContentDescription(label);
        final ImageView icon = new ImageView(context);
        icon.setImageResource(iconRes);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        icon.setColorFilter(new PorterDuffColorFilter(textColor, PorterDuff.Mode.SRC_IN));
        action.addView(icon, LayoutHelper.createLinear(24, 24));
        final TextView text = new TextView(context);
        text.setText(label);
        text.setTextColor(textColor);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        text.setTypeface(AndroidUtilities.bold());
        text.setGravity(Gravity.CENTER);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        action.addView(text, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 4, 2, 4, 0));
        action.setOnClickListener(onClick);
        ScaleStateListAnimator.apply(action, .04f, 1.2f);
        final int index = actions.getChildCount();
        actions.addView(action, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, index == 0 ? 0 : 8, 0, 0, 0));
        return action;
    }

    static void setActionLabel(LinearLayout action, int iconRes, CharSequence label) {
        if (action == null) return;
        ((ImageView) action.getChildAt(0)).setImageResource(iconRes);
        ((TextView) action.getChildAt(1)).setText(label);
        action.setContentDescription(label);
    }

    BulletinFactory bulletins() {
        return BulletinFactory.of(sheet.container, resourcesProvider);
    }

    void show() {
        sheet.show();
        sheet.fixNavigationBar(backgroundColor);
        AndroidUtilities.setNavigationBarColor(sheet, backgroundColor, false);
    }

    /** «UQAb…x9Fz»: an address short enough for a table row next to a button. */
    static String shortAddress(String address) {
        if (address == null) return "";
        return address.length() > 14 ? address.substring(0, 6) + "…" + address.substring(address.length() - 6) : address;
    }
}
