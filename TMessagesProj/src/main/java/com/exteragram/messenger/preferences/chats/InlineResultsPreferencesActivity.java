package com.exteragram.messenger.preferences.chats;

import android.view.View;

import com.exteragram.messenger.AngleUnit;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.MeasureSystem;
import com.exteragram.messenger.inline.InlineRates;
import com.exteragram.messenger.inline.InlineResults;
import com.exteragram.messenger.inline.docs.InlineExampleGroup;
import com.exteragram.messenger.inline.docs.InlineExamples;
import com.exteragram.messenger.inline.math.MathExpression;
import com.exteragram.messenger.inline.math.MathOptions;
import com.exteragram.messenger.inline.math.MathSuggestion;
import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.preferences.chats.components.InlineDocBlock;
import com.exteragram.messenger.preferences.chats.components.InlineDocExample;
import com.exteragram.messenger.preferences.chats.components.InlineFeature;
import com.exteragram.messenger.preferences.chats.components.InlineFeatureCell;
import com.exteragram.messenger.utils.network.ExchangeRates;
import com.exteragram.messenger.utils.ui.PopupUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.text.Collator;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * exteraGram 13.1.1: "Inline Results" settings (Chats → Inline Results): the master switch, the features
 * (calculator, currencies, units, time zones), their options and an expandable documentation with live examples.
 */
public final class InlineResultsPreferencesActivity extends BasePreferencesActivity {

    public enum InlineItem {
        SHOW_RESULTS,
        CALCULATOR,
        CURRENCIES,
        UNITS,
        TIME_ZONES,
        DEFAULT_CURRENCY,
        MEASURE_SYSTEM,
        ANGLE_UNIT,
        DIGIT_GROUPING,
        ARITHMETIC,
        PERCENTAGES,
        CURRENCY_CONVERSION,
        UNIT_CONVERSION,
        TIME_CONVERSION;

        public int getId() {
            return ordinal() + 1;
        }
    }

    enum Section {
        ARITHMETIC(InlineItem.ARITHMETIC, R.drawable.msg_calculator, R.string.InlineDocArithmetic, R.string.InlineDocArithmeticInfo, ExteraConfig::getInlineMathResult),
        PERCENTAGES(InlineItem.PERCENTAGES, R.drawable.msg_percent, R.string.InlineDocPercent, R.string.InlineDocPercentInfo, ExteraConfig::getInlineMathResult),
        CURRENCIES(InlineItem.CURRENCY_CONVERSION, R.drawable.menu_feature_paid, R.string.InlineCurrencies, R.string.InlineDocCurrenciesInfo, ExteraConfig::getInlineCurrencyRates),
        UNITS(InlineItem.UNIT_CONVERSION, R.drawable.msg_ruler, R.string.InlineUnits, R.string.InlineDocUnitsInfo, ExteraConfig::getInlineUnitConversion),
        TIME(InlineItem.TIME_CONVERSION, R.drawable.msg_language, R.string.InlineTimeZones, R.string.InlineDocTimeInfo, ExteraConfig::getInlineTimeZones);

        final InlineItem item;
        final int icon;
        final int title;
        final int subtitle;
        final BooleanSupplier enabled;

        Section(InlineItem item, int icon, int title, int subtitle, BooleanSupplier enabled) {
            this.item = item;
            this.icon = icon;
            this.title = title;
            this.subtitle = subtitle;
            this.enabled = enabled;
        }
    }

    private final LinkedHashMap<String, String> cryptoNames = new LinkedHashMap<>();
    {
        cryptoNames.put("GRAM", "Gram");
        cryptoNames.put("BTC", "Bitcoin");
        cryptoNames.put("ETH", "Ethereum");
        cryptoNames.put("SOL", "Solana");
        cryptoNames.put("USDT", "Tether");
    }
    private final HashSet<Section> expanded = new HashSet<>();
    private final InlineRates rates = new InlineRates(this::updateList);
    private final View.OnClickListener featureClick = this::onFeatureClick;

    private String[] angleOptions;
    private String[] measureOptions;
    private List<String> currencyCodes;
    private String[] currencyOptions;

    private void updateList() {
        if (fragmentView == null || getListView$TMessagesProj() == null || getListView$TMessagesProj().adapter == null) {
            return;
        }
        getListView$TMessagesProj().adapter.update(true);
    }

    private void onFeatureClick(View view) {
        final Object tag = view.getTag();
        if (tag instanceof String) {
            // an example: copy it so it can be pasted into a chat
            if (AndroidUtilities.addToClipboard((CharSequence) tag)) {
                BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
            }
        } else if (tag instanceof Integer) {
            // a feature header: expand or collapse its documentation
            for (Section section : Section.values()) {
                if (section.item.getId() == (Integer) tag) {
                    if (!expanded.remove(section)) {
                        expanded.add(section);
                    }
                    updateList();
                    break;
                }
            }
        }
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.InlineMathResult);
    }

    @Override
    public void initializeOptionStrings() {
        angleOptions = new String[]{LocaleController.getString(R.string.InlineAngleDegrees), LocaleController.getString(R.string.InlineAngleRadians)};
        measureOptions = new String[]{LocaleController.getString(R.string.QualityAuto), LocaleController.getString(R.string.InlineMeasureMetric), LocaleController.getString(R.string.InlineMeasureImperial)};

        Locale locale = LocaleController.getInstance().getCurrentLocale();
        if (locale == null) {
            locale = Locale.getDefault();
        }
        final List<String> supported = new ArrayList<>();
        for (String code : ExchangeRates.getSupportedCurrencies()) {
            supported.add(InlineRates.INSTANCE.normalize(code));
        }
        // fiat currencies sorted by their localized names, then the supported crypto ones in a fixed order
        final List<String[]> fiat = new ArrayList<>();
        for (String code : supported) {
            if (cryptoNames.containsKey(code)) {
                continue;
            }
            String name;
            try {
                name = Currency.getInstance(code).getDisplayName(locale);
                if (name.length() > 0) {
                    name = name.substring(0, 1).toUpperCase(locale) + name.substring(1);
                }
            } catch (IllegalArgumentException e) {
                name = code;
            }
            fiat.add(new String[]{code, name});
        }
        final Collator collator = Collator.getInstance(locale);
        fiat.sort((a, b) -> collator.compare(a[1], b[1]));
        for (Map.Entry<String, String> entry : cryptoNames.entrySet()) {
            if (supported.contains(entry.getKey())) {
                fiat.add(new String[]{entry.getKey(), entry.getValue()});
            }
        }
        currencyCodes = new ArrayList<>();
        currencyCodes.add("");
        final ArrayList<String> options = new ArrayList<>();
        options.add(LocaleController.getString(R.string.QualityAuto));
        for (String[] currency : fiat) {
            currencyCodes.add(currency[0]);
            options.add(currency[1] + " — " + currency[0]);
        }
        currencyOptions = options.toArray(new String[0]);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asRippleCheck(InlineItem.SHOW_RESULTS.getId(), LocaleController.getString(R.string.InlineShowResults)).setChecked(ExteraConfig.getInlineResults()).setSearchable(this));
        items.add(UItem.asShadow(LocaleController.getString(R.string.InlineMathResultHint)));
        if (ExteraConfig.getInlineResults()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.InlineFeatures)));
            items.add(uCheck$TMessagesProj(InlineItem.CALCULATOR, LocaleController.getString(R.string.InlineCalculator), ExteraConfig.getInlineMathResult(), null, 0, true, "inlineCalculator", true, R.drawable.msg_calculator));
            items.add(uCheck$TMessagesProj(InlineItem.CURRENCIES, LocaleController.getString(R.string.InlineCurrencies), ExteraConfig.getInlineCurrencyRates(), null, 0, true, "inlineCurrencyRates", true, R.drawable.menu_feature_paid));
            items.add(uCheck$TMessagesProj(InlineItem.UNITS, LocaleController.getString(R.string.InlineUnits), ExteraConfig.getInlineUnitConversion(), null, 0, true, "inlineUnitConversion", true, R.drawable.msg_ruler));
            items.add(uCheck$TMessagesProj(InlineItem.TIME_ZONES, LocaleController.getString(R.string.InlineTimeZones), ExteraConfig.getInlineTimeZones(), null, 0, true, "inlineTimeZones", true, R.drawable.msg_language));
            items.add(UItem.asShadow());
            items.add(UItem.asHeader(LocaleController.getString(R.string.Settings)));
            items.add(uCheck$TMessagesProj(InlineItem.DIGIT_GROUPING, LocaleController.getString(R.string.InlineDigitGrouping), ExteraConfig.getInlineDigitGrouping(), null, 0, true, "inlineDigitGrouping", true, 0));
            if (ExteraConfig.getInlineMathResult()) {
                items.add(uButton$TMessagesProj(InlineItem.ANGLE_UNIT, LocaleController.getString(R.string.InlineAngleUnit), 0, angleOptions[ExteraConfig.getInlineAngleUnit().ordinal()], null, null, true, "inlineAngleUnit", false, false, null));
            }
            if (ExteraConfig.getInlineCurrencyRates()) {
                items.add(uButton$TMessagesProj(InlineItem.DEFAULT_CURRENCY, LocaleController.getString(R.string.InlineDefaultCurrency), 0, defaultCurrencyValue(), null, null, true, "inlineTargetCurrency", false, false, null));
            }
            if (ExteraConfig.getInlineUnitConversion()) {
                items.add(uButton$TMessagesProj(InlineItem.MEASURE_SYSTEM, LocaleController.getString(R.string.InlineMeasureSystem), 0, measureOptions[ExteraConfig.getInlineMeasureSystem().ordinal()], null, null, true, "inlineMeasureSystem", false, false, null));
            }
            items.add(UItem.asShadow(settingsInfo()));
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.InlineCapabilities)));
        final MathOptions options = expanded.isEmpty() ? null : InlineResults.INSTANCE.options(rates, decimalSeparator(), true);
        for (Section section : Section.values()) {
            final CharSequence badge = ExteraConfig.getInlineResults() && !section.enabled.getAsBoolean() ? LocaleController.getString(R.string.InlineFeatureOff) : null;
            final List<InlineDocBlock> docs = options != null && expanded.contains(section) ? blocks(section, options) : null;
            items.add(InlineFeatureCell.Factory.of(section.item.getId(), new InlineFeature(section.icon, LocaleController.getString(section.title), LocaleController.getString(section.subtitle), badge, docs), featureClick));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.InlineTapToCopy)));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        final InlineItem[] entries = InlineItem.values();
        if (item.id <= 0 || item.id > entries.length) {
            return;
        }
        switch (entries[item.id - 1]) {
            case SHOW_RESULTS:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, ExteraConfig::setInlineResults);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setBackgroundColorAnimated(ExteraConfig.getInlineResults(), getThemedColor(ExteraConfig.getInlineResults() ? Theme.key_windowBackgroundChecked : Theme.key_windowBackgroundUnchecked));
                }
                break;
            case CALCULATOR:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, ExteraConfig::setInlineMathResult);
                break;
            case CURRENCIES:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, ExteraConfig::setInlineCurrencyRates);
                break;
            case UNITS:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, ExteraConfig::setInlineUnitConversion);
                break;
            case TIME_ZONES:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, ExteraConfig::setInlineTimeZones);
                break;
            case DIGIT_GROUPING:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, ExteraConfig::setInlineDigitGrouping);
                break;
            case DEFAULT_CURRENCY: {
                final int current = Math.max(0, currencyCodes.indexOf(InlineRates.INSTANCE.normalize(ExteraConfig.getInlineTargetCurrency())));
                PopupUtils.showDialog(currencyOptions, null, LocaleController.getString(R.string.InlineDefaultCurrency), current, getContext(), which -> {
                    if (which == current) {
                        return;
                    }
                    ExteraConfig.setInlineTargetCurrency(currencyCodes.get(which));
                    final View cell = getListView$TMessagesProj().findViewByItemId(item.id);
                    if (cell instanceof TextCell) {
                        ((TextCell) cell).setValue(defaultCurrencyValue(), true);
                    }
                    updateList();
                }, getResourceProvider(), true);
                break;
            }
            case ANGLE_UNIT:
                showListDialog(item, angleOptions, LocaleController.getString(R.string.InlineAngleUnit), ExteraConfig.getInlineAngleUnit().ordinal(), which -> ExteraConfig.setInlineAngleUnit(AngleUnit.values()[which]));
                break;
            case MEASURE_SYSTEM:
                showListDialog(item, measureOptions, LocaleController.getString(R.string.InlineMeasureSystem), ExteraConfig.getInlineMeasureSystem().ordinal(), which -> ExteraConfig.setInlineMeasureSystem(MeasureSystem.values()[which]));
                break;
            default:
                break;
        }
    }

    private String defaultCurrencyValue() {
        final String code = InlineRates.INSTANCE.normalize(ExteraConfig.getInlineTargetCurrency());
        return rates.supports(code) ? code : LocaleController.getString(R.string.QualityAuto);
    }

    private String settingsInfo() {
        if (!ExteraConfig.getInlineCurrencyRates()) {
            return null;
        }
        final String info = LocaleController.getString(R.string.InlineAutoCurrencyInfo);
        final ExchangeRates.State cached = ExchangeRates.peekCached();
        if (cached == null) {
            return info;
        }
        return info + " " + LocaleController.formatString(R.string.InlineRatesUpdated, LocaleController.formatDateAudio(cached.time() / 1000, true));
    }

    private char decimalSeparator() {
        Locale locale = LocaleController.getInstance().getCurrentLocale();
        if (locale == null) {
            locale = Locale.US;
        }
        return DecimalFormatSymbols.getInstance(locale).getDecimalSeparator();
    }

    private List<InlineDocBlock> blocks(Section section, MathOptions options) {
        final InlineExamples ex = InlineExamples.INSTANCE;
        switch (section) {
            case ARITHMETIC:
                return Arrays.asList(examples(ex.getArithmetic(), options), text(R.string.InlineDocFunctionsText), new InlineDocBlock.Codes(ex.getFunctions()), examples(ex.getArithmeticFunctions(), options), text(R.string.InlineDocBasesText), examples(ex.getBases(), options));
            case PERCENTAGES:
                return Arrays.asList(text(R.string.InlineDocPercentText), examples(ex.getPercentages(), options), text(R.string.InlineDocShorthandsText), examples(ex.getShorthands(), options));
            case CURRENCIES:
                return Arrays.asList(text(R.string.InlineDocCurrencyText), examples(ex.getCurrencyDefault(), options), examples(ex.getCurrencyTarget(), options));
            case UNITS:
                return Arrays.asList(text(R.string.InlineDocUnitsText), examples(ex.getUnitsDefault(), options), examples(ex.getUnitsTarget(), options), text(R.string.InlineDocDurationsText), examples(ex.getDurations(), options));
            case TIME:
            default:
                return Arrays.asList(text(R.string.InlineDocTimeText), examples(ex.getTimeZones(), options), text(R.string.InlineDocCurrentTimeText), examples(ex.getCurrentTime(), options));
        }
    }

    private InlineDocBlock text(int res) {
        return new InlineDocBlock.Text(LocaleController.getString(res));
    }

    private InlineDocBlock examples(InlineExampleGroup group, MathOptions options) {
        final Locale locale = LocaleController.getInstance().getCurrentLocale();
        final String language = locale != null ? locale.getLanguage() : null;
        final List<String> inputs = group.examples("ru".equals(language) || "be".equals(language));
        final ArrayList<InlineDocExample> result = new ArrayList<>(inputs.size());
        for (String input : inputs) {
            final MathSuggestion suggestion = MathExpression.INSTANCE.suggestionAt(input, input.length(), options);
            result.add(new InlineDocExample(input, suggestion != null ? suggestion.getValue() : null));
        }
        return new InlineDocBlock.Examples(result);
    }
}
