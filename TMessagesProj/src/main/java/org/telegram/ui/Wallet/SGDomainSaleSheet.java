package org.telegram.ui.Wallet;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.tgnet.tl.TL_tondns;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.math.BigDecimal;

/**
 * StaticGram: list an own minted .ton domain for sale at a fixed GRAM price (tondns.listForSale),
 * change the price of the active listing or take it off sale (tondns.cancelSale). Shows what the
 * seller receives after the burned fee and the allowed price range (getMyListings).
 */
public class SGDomainSaleSheet {

    public static void open(Context context, int currentAccount, String rawName, Theme.ResourcesProvider resourcesProvider, Runnable onDone) {
        if (context == null || TextUtils.isEmpty(rawName)) return;
        final String name = SGTonDns.fullName(rawName);
        final SGTonDns dns = SGTonDns.getInstance(currentAccount);
        final int background = Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider);
        final int black = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        final int grayText = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider);

        final BottomSheet sheet = new BottomSheet(context, true, resourcesProvider);
        sheet.setBackgroundColor(background);
        sheet.fixNavigationBar(background);

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(dp(16), dp(8), dp(16), dp(12));
        layout.addView(SGCollectibleSheetUi.createHandle(context, resourcesProvider), LayoutHelper.createLinear(36, 4, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));

        final View card = new View(context);
        card.setBackground(new SGDomainCardDrawable(name).setRadiusFraction(.11f));
        layout.addView(card, LayoutHelper.createLinear(96, 96, Gravity.CENTER_HORIZONTAL));

        final TextView title = new TextView(context);
        title.setText(formatString(R.string.SgDnsSaleTitle, name));
        title.setTextColor(black);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));

        final TextView bounds = new TextView(context);
        bounds.setTextColor(grayText);
        bounds.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        bounds.setGravity(Gravity.CENTER);
        layout.addView(bounds, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        // the price field: «[ 120 ] GRAM»
        final LinearLayout field = new LinearLayout(context);
        field.setOrientation(LinearLayout.HORIZONTAL);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(dp(16), 0, dp(16), 0);
        field.setBackground(Theme.createRoundRectDrawable(dp(16), Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider)));
        final EditTextBoldCursor edit = new EditTextBoldCursor(context);
        edit.setBackground(null);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        edit.setTypeface(AndroidUtilities.bold());
        edit.setTextColor(black);
        edit.setHintTextColor(grayText);
        edit.setHint(getString(R.string.SgDnsSalePriceHint));
        edit.setCursorColor(Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider));
        edit.setCursorSize(dp(22));
        edit.setCursorWidth(1.5f);
        edit.setSingleLine(true);
        edit.setPadding(0, dp(14), 0, dp(14));
        edit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        edit.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.addView(edit, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        final TextView unit = new TextView(context);
        unit.setText("GRAM");
        unit.setTextColor(grayText);
        unit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        unit.setTypeface(AndroidUtilities.bold());
        field.addView(unit, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 6, 0, 0, 0));
        layout.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 18, 0, 0));

        final TextView receive = new TextView(context);
        receive.setTextColor(grayText);
        receive.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        receive.setGravity(Gravity.CENTER);
        layout.addView(receive, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        final ButtonWithCounterView button = new ButtonWithCounterView(context, resourcesProvider).setRound();
        layout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 18, 0, 0));
        final ButtonWithCounterView cancelButton = new ButtonWithCounterView(context, false, resourcesProvider).setRound();
        cancelButton.setText(getString(R.string.SgDnsSaleCancel), false);
        cancelButton.setTextColor(Theme.getColor(Theme.key_text_RedBold, resourcesProvider));
        layout.addView(cancelButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 8, 0, 0));

        final Runnable bind = () -> {
            final TL_tondns.TL_listing listing = dns.getMyListing(name);
            bounds.setText(listing != null
                    ? formatString(R.string.SgDnsSaleListedFor, GramWallet.formatGrams(listing.price))
                    : formatString(R.string.SgDnsSaleBounds, GramWallet.formatGrams(dns.saleMinPrice), GramWallet.formatGrams(dns.saleMaxPrice)));
            button.setText(getString(listing != null ? R.string.SgDnsSaleChangePrice : R.string.SgDnsSaleList), false);
            cancelButton.setVisibility(listing != null ? View.VISIBLE : View.GONE);
            final long price = parseGrams(edit.getText().toString());
            if (price > 0) {
                final long net = price - price * dns.saleFeePercent / 100;
                receive.setText(formatString(R.string.SgDnsSaleYouGet, GramWallet.formatGrams(net), dns.saleFeePercent));
                final boolean inRange = price >= dns.saleMinPrice && price <= dns.saleMaxPrice;
                receive.setTextColor(inRange ? grayText : Theme.getColor(Theme.key_text_RedBold, resourcesProvider));
                button.setEnabled(inRange && (listing == null || listing.price != price));
            } else {
                receive.setText(formatString(R.string.SgDnsSaleFee, dns.saleFeePercent));
                receive.setTextColor(grayText);
                button.setEnabled(false);
            }
        };
        edit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                bind.run();
            }
        });

        button.setOnClickListener(v -> {
            final long price = parseGrams(edit.getText().toString());
            if (price < dns.saleMinPrice || price > dns.saleMaxPrice) {
                AndroidUtilities.shakeViewSpring(field, -6);
                return;
            }
            button.setLoading(true);
            dns.listForSale(name, price, (res, err) -> {
                button.setLoading(false);
                if (res == null) {
                    SGTonDns.showError(BulletinFactory.of(sheet.getContainer(), resourcesProvider), err);
                    return;
                }
                sheet.dismiss();
                if (onDone != null) onDone.run();
                BulletinFactory.global().createSimpleBulletin(R.raw.contact_check, formatString(R.string.SgDnsSaleListed, name)).show();
            });
        });
        cancelButton.setOnClickListener(v -> {
            cancelButton.setLoading(true);
            dns.cancelSale(name, err -> {
                cancelButton.setLoading(false);
                if (err != null) {
                    SGTonDns.showError(BulletinFactory.of(sheet.getContainer(), resourcesProvider), err);
                    return;
                }
                sheet.dismiss();
                if (onDone != null) onDone.run();
                BulletinFactory.global().createSimpleBulletin(R.raw.contact_check, getString(R.string.SgDnsSaleCancelled)).show();
            });
        });

        final TL_tondns.TL_listing current = dns.getMyListing(name);
        if (current != null) {
            edit.setText(BigDecimal.valueOf(current.price, 9).stripTrailingZeros().toPlainString());
            edit.setSelection(edit.length());
        }
        bind.run();
        final Runnable listener = bind;
        dns.subscribe(listener);
        sheet.setOnDismissListener(di -> dns.unsubscribe(listener));
        dns.loadMyListings(null); // bounds, fee, the current listing

        sheet.setCustomView(layout);
        sheet.show();
        AndroidUtilities.runOnUIThread(() -> {
            edit.requestFocus();
            AndroidUtilities.showKeyboard(edit);
        }, 250);
    }

    /** «12.5» GRAM → nanoton; 0 when it is not a positive amount with at most 9 decimals. */
    static long parseGrams(String text) {
        if (text == null) return 0;
        final String t = text.trim().replace(',', '.').replace(" ", "");
        if (TextUtils.isEmpty(t) || !t.matches("[0-9]+(\\.[0-9]{0,9})?")) return 0;
        try {
            final long v = new BigDecimal(t).movePointRight(9).longValueExact();
            return Math.max(0, v);
        } catch (Exception e) {
            return 0;
        }
    }
}
