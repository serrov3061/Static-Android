package com.exteragram.messenger.ai.ui.activities;

import android.text.TextUtils;
import android.view.View;

import com.exteragram.messenger.ai.AiConfig;
import com.exteragram.messenger.ai.AiController;
import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.preferences.utils.SettingsRegistry;
import com.exteragram.messenger.utils.text.LocaleUtils;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;

/** AI settings root (port of exteraGram's AiPreferencesActivity). */
public final class AiPreferencesActivity extends BasePreferencesActivity {

    public enum PreferenceItem {
        ENDPOINT,
        ROLE,
        HISTORY,
        TEMPERATURE,
        RESPONSE_STREAMING,
        SHOW_RESPONSE_ONLY,
        INSERT_AS_QUOTE,
        REPLACE_TELEGRAM_EDITOR,
        REPLACE_TELEGRAM_SUMMARIES;

        public int getId() {
            return ordinal() + 1;
        }
    }

    @Override
    public boolean needHideTitle() {
        return true;
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.AIChat);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asTopView(getTitle(), LocaleController.getString(R.string.AIChatInfo2), "exteraStaticPlaceholders", "🤖"));
        items.add(UItem.asShadow());
        items.add(UItem.asHeader(LocaleController.getString(R.string.General)));
        items.add(uButton$TMessagesProj(PreferenceItem.ENDPOINT, LocaleController.getString(R.string.Services), R.drawable.msg_language, getEndpointValue(), null, null, true, "aiServices", false, false, Boolean.TRUE));
        items.add(uButton$TMessagesProj(PreferenceItem.ROLE, LocaleController.getString(R.string.Roles), R.drawable.msg_openprofile, AiConfig.getSelectedRole(), null, null, true, "aiRoles", false, false, Boolean.TRUE));
        items.add(uButton$TMessagesProj(PreferenceItem.HISTORY, LocaleController.getString(R.string.MessageHistory), R.drawable.msg_discuss, getHistoryValue(), null, null, true, "aiHistory", false, false, Boolean.TRUE));
        items.add(UItem.asShadow(LocaleController.getString(R.string.HistoryInfo)));
        items.add(UItem.asHeader(LocaleController.getString(R.string.AIReplaceTelegram)));
        items.add(uCheck$TMessagesProj(PreferenceItem.REPLACE_TELEGRAM_EDITOR, LocaleController.getString(R.string.AIFeaturesEditor), AiConfig.getReplaceTelegramEditor(), LocaleController.getString(R.string.AIReplaceTelegramEditorInfo), 0, true, "replaceTelegramEditor", true, 0));
        items.add(uCheck$TMessagesProj(PreferenceItem.REPLACE_TELEGRAM_SUMMARIES, LocaleController.getString(R.string.AIFeaturesSummaries), AiConfig.getReplaceTelegramSummaries(), LocaleController.getString(R.string.AIReplaceTelegramSummariesInfo), 0, true, "replaceTelegramSummaries", true, 0).showDivider(false));
        items.add(UItem.asShadow(LocaleController.getString(R.string.AIReplaceTelegramInfo)));
        items.add(UItem.asHeader(LocaleController.getString(R.string.AIGeneration)));
        items.add(uCheck$TMessagesProj(PreferenceItem.RESPONSE_STREAMING, LocaleController.getString(R.string.ResponseStreaming), AiConfig.getResponseStreaming(), LocaleController.getString(R.string.ResponseStreamingInfo), 0, true, "responseStreaming", true, 0));
        items.add(uCheck$TMessagesProj(PreferenceItem.SHOW_RESPONSE_ONLY, LocaleController.getString(R.string.ShowResponseOnly), AiConfig.getShowResponseOnly(), null, 0, true, "showResponseOnly", true, 0));
        items.add(uCheck$TMessagesProj(PreferenceItem.INSERT_AS_QUOTE, LocaleController.getString(R.string.InsertResponseAsQuote), AiConfig.getInsertAsQuote(), null, 0, true, "insertResponseAsQuote", true, 0).showDivider(false));
        items.add(UItem.asShadow());
        CharSequence temperatureHeader = SettingsRegistry.markAsNewFeature("aiTemperature") ? LocaleUtils.applyNewSpan(LocaleController.getString(R.string.AITemperature)) : LocaleController.getString(R.string.AITemperature);
        items.add(UItem.asHeader(temperatureHeader));
        UItem slider = createTemperatureSliderItem();
        slider.showDivider(false);
        slider.setSearchable(this);
        slider.setLinkAlias("aiTemperature", this);
        items.add(slider);
        items.add(UItem.asShadow(LocaleController.getString(R.string.AITemperatureInfo)));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id <= 0 || id > PreferenceItem.values().length) {
            return;
        }
        switch (PreferenceItem.values()[id - 1]) {
            case ENDPOINT:
                presentFragment(new ServicesActivity());
                break;
            case ROLE:
                presentFragment(new RolesActivity());
                break;
            case HISTORY:
                presentFragment(new AiHistoryActivity());
                break;
            case RESPONSE_STREAMING:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, AiConfig::setResponseStreaming);
                break;
            case SHOW_RESPONSE_ONLY:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, AiConfig::setShowResponseOnly);
                break;
            case INSERT_AS_QUOTE:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, AiConfig::setInsertAsQuote);
                break;
            case REPLACE_TELEGRAM_EDITOR:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, AiConfig::setReplaceTelegramEditor);
                break;
            case REPLACE_TELEGRAM_SUMMARIES:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, AiConfig::setReplaceTelegramSummaries);
                break;
            default:
                break;
        }
    }

    private CharSequence getHistoryValue() {
        if (!AiConfig.getSaveHistory()) {
            return LocaleController.getString(R.string.BlurOff);
        }
        int size = AiConfig.getConversationHistory().size();
        return size == 0 ? LocaleController.getString(R.string.BlockedEmpty) : LocaleController.formatPluralString("messages", size);
    }

    private UItem createTemperatureSliderItem() {
        UItem item = UItem.asIntSlideView(1, 0, AiConfig.getTemperature(), 20, this::formatTemperature, AiConfig::setTemperature);
        item.id = PreferenceItem.TEMPERATURE.getId();
        item.text = LocaleController.getString(R.string.AITemperature);
        return item;
    }

    private CharSequence formatTemperature(int value) {
        return String.format(Locale.US, "%.1f", value / 10.0f);
    }

    private String getEndpointValue() {
        if (AiController.getInstance().getSelected().isOnDevice()) {
            return AiController.canUseAI() ? LocaleController.getString(R.string.AIOnDevice) : LocaleController.getString(R.string.BlockedEmpty);
        }
        try {
            String host = new URL(AiController.getInstance().getSelected().getUrl()).getHost();
            if (!TextUtils.isEmpty(host) && AiController.canUseAI()) {
                return host.contains("generativelanguage.googleapis") ? "Gemini" : host;
            }
        } catch (Exception ignore) {
        }
        return LocaleController.getString(R.string.BlockedEmpty);
    }
}
