package com.exteragram.messenger.ai.ui.activities;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Rect;
import android.net.Uri;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.WindowInsetsCompat;

import com.exteragram.messenger.ai.AiConfig;
import com.exteragram.messenger.ai.AiController;
import com.exteragram.messenger.ai.data.Service;
import com.exteragram.messenger.ai.data.Suggestions;
import com.exteragram.messenger.ai.network.Client;
import com.exteragram.messenger.ai.network.GenerationCallback;
import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.preferences.utils.SettingsRegistry;
import com.exteragram.messenger.utils.ExtensionsKt;
import com.exteragram.messenger.utils.text.LocaleUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BotWebViewVibrationEffect;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Add / edit an AI service: endpoint URL + model + the user's own API key (port of exteraGram's EditServiceActivity).
 *
 * Provider presets (Gemini, OpenAI, OpenRouter, Perplexity) only prefill URL and model; they contain no keys.
 * Saving sends one test request ("Say 'hi'.") to the entered endpoint with the entered key; the service is stored
 * locally only after it answers. While the screen is open the clipboard is checked for a "url / model / key" triple
 * or a key to offer a "Paste" button (nothing is read in the background).
 */
public final class EditServiceActivity extends BasePreferencesActivity {
    private static final ServicePreset[] SERVICE_PRESETS = {
            new ServicePreset("Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-3.5-flash"),
            new ServicePreset("OpenAI", "https://api.openai.com/v1", "gpt-5.4-mini"),
            new ServicePreset("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-5.4-mini"),
            new ServicePreset("Perplexity", "https://api.perplexity.ai/v1/agent", "perplexity/glm-5.3-flash"),
            new ServicePreset(null, null, null)
    };
    private static final Pattern WHITESPACE = Pattern.compile(".*\\s+.*", Pattern.DOTALL);
    private static final Pattern API_KEY = Pattern.compile("[A-Za-z0-9_.\\-]+");

    private final ClipboardManager.OnPrimaryClipChangedListener clipChangedListener = this::updateClipboardState;
    private ClipboardManager clipboardManager;
    private final Service currentService;
    private boolean forceCustomPreset;
    private boolean hasChanges;
    private String initialKey;
    private String initialModel;
    private boolean initialReasoningEnabled;
    private String initialUrl;
    private boolean isTesting;
    private EditTextCell keyCell;
    private int keyboardInset;
    private EditTextCell modelCell;
    private ParsedServiceInput pasteInput;
    private String pasteString;
    private boolean reasoningEnabled;
    private int selectedPresetIndex;
    private int shiftDp = -4;
    private Client testingClient;
    private AlertDialog testingProgressDialog;
    private String testingRequestId;
    private boolean updatingFields;
    private EditTextCell urlCell;

    public enum ServiceItem {
        PRESET_GEMINI,
        PRESET_OPENAI,
        PRESET_OPENROUTER,
        PRESET_PERPLEXITY,
        PRESET_CUSTOM,
        PASTE,
        SAVE,
        DELETE,
        REASONING;

        public int getId() {
            return ordinal() + 1;
        }
    }

    public EditServiceActivity() {
        this(null);
    }

    public EditServiceActivity(Service service) {
        this.currentService = service;
    }

    @Override
    public WindowInsetsCompat onInsetsInternal(@NonNull View view, @NonNull WindowInsetsCompat insets) {
        keyboardInset = Math.max(0, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom - insets.getInsets(WindowInsetsCompat.Type.navigationBars() | WindowInsetsCompat.Type.statusBars()).bottom);
        return super.onInsetsInternal(view, insets);
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        super.onInsets(left, top, right, bottom + keyboardInset);
        if (keyboardInset > 0 && listView != null) {
            listView.post(() -> {
                View focus = listView.findFocus();
                if (focus != null) {
                    focus.requestRectangleOnScreen(new Rect(0, 0, focus.getWidth(), focus.getHeight()), false);
                }
            });
        }
    }

    @Override
    public View createView(Context context) {
        Object service = context.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboardManager = service instanceof ClipboardManager ? (ClipboardManager) service : null;
        createFields(context);
        initializeState();
        updateClipboardState(context, false);
        View view = super.createView(context);
        actionBar.setAllowOverlayTitle(true);
        return view;
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(currentService != null ? R.string.EditService : R.string.NewService);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        CharSequence reasoningTitle = SettingsRegistry.markAsNewFeature("AI-Service-Reasoning") ? LocaleUtils.applyNewSpan(LocaleController.getString(R.string.AIReasoning)) : LocaleController.getString(R.string.AIReasoning);
        items.add(UItem.asHeader(LocaleController.getString(R.string.ServiceProvider)));
        for (int i = 0; i < SERVICE_PRESETS.length; i++) {
            int id;
            if (i == 0) {
                id = ServiceItem.PRESET_GEMINI.getId();
            } else if (i == 1) {
                id = ServiceItem.PRESET_OPENAI.getId();
            } else if (i == 2) {
                id = ServiceItem.PRESET_OPENROUTER.getId();
            } else if (i == 3) {
                id = ServiceItem.PRESET_PERPLEXITY.getId();
            } else {
                id = ServiceItem.PRESET_CUSTOM.getId();
            }
            items.add(UItem.asRadio(id, getPresetTitle(i)).setChecked(i == selectedPresetIndex).setEnabled(!isTesting));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.ServicesInfo)));
        items.add(UItem.asHeader(LocaleController.getString(R.string.ServiceInfo)));
        items.add(UItem.asCustom(urlCell));
        items.add(UItem.asCustom(modelCell));
        items.add(UItem.asCustom(keyCell));
        UItem reasoning = uCheck$TMessagesProj(ServiceItem.REASONING, reasoningTitle, reasoningEnabled, LocaleController.getString(R.string.AIReasoningInfo), 0, false, null, true, 0);
        reasoning.setEnabled(!isTesting);
        items.add(reasoning);
        items.add(UItem.asShadow());
        boolean hasButtons = false;
        if (pasteInput != null) {
            UItem paste = uButton$TMessagesProj(ServiceItem.PASTE, LocaleController.getString(pasteInput.hasServiceFields() ? R.string.ServicePasteService : R.string.ServicePasteKey), R.drawable.msg_copy, null, null, null, false, null, false, true, null);
            paste.setEnabled(!isTesting);
            items.add(paste);
            hasButtons = true;
        }
        if (hasChanges) {
            UItem save = uButton$TMessagesProj(ServiceItem.SAVE, LocaleController.getString(R.string.ServiceTestAndSave), R.drawable.ic_ab_done, null, null, null, false, null, false, true, null);
            save.setEnabled(!isTesting);
            items.add(save);
            hasButtons = true;
        }
        if (currentService != null) {
            UItem delete = uButton$TMessagesProj(ServiceItem.DELETE, LocaleController.getString(R.string.Delete), R.drawable.msg_delete, null, null, null, false, null, true, false, null);
            delete.setEnabled(!isTesting);
            items.add(delete);
            hasButtons = true;
        }
        ExtensionsKt.addIf(items, hasButtons, UItem.asShadow());
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (isTesting || item.id <= 0 || item.id > ServiceItem.values().length) {
            return;
        }
        switch (ServiceItem.values()[item.id - 1]) {
            case PRESET_GEMINI:
                applyPreset(0);
                break;
            case PRESET_OPENAI:
                applyPreset(1);
                break;
            case PRESET_OPENROUTER:
                applyPreset(2);
                break;
            case PRESET_PERPLEXITY:
                applyPreset(3);
                break;
            case PRESET_CUSTOM:
                applyPreset(4);
                break;
            case PASTE: {
                ParsedServiceInput input = pasteInput != null ? pasteInput : parseServiceInput(pasteString);
                if (input != null) {
                    applyParsedServiceInput(input);
                }
                break;
            }
            case SAVE:
                saveConfig();
                break;
            case DELETE:
                confirmDeleteService();
                break;
            case REASONING:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, value -> reasoningEnabled = value);
                updateFormState();
                break;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (clipboardManager != null) {
            clipboardManager.removePrimaryClipChangedListener(clipChangedListener);
            clipboardManager.addPrimaryClipChangedListener(clipChangedListener);
        }
        updateClipboardState();
        if (!MessagesController.getGlobalMainSettings().getBoolean("view_animations", true) && keyCell != null) {
            keyCell.editText.requestFocus();
            AndroidUtilities.showKeyboard(keyCell.editText);
        }
    }

    @Override
    public void onPause() {
        if (clipboardManager != null) {
            clipboardManager.removePrimaryClipChangedListener(clipChangedListener);
        }
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        stopTestingRequest();
        hideTestingProgressDialog();
        testingRequestId = null;
        testingClient = null;
        super.onFragmentDestroy();
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        if (isOpen && keyCell != null) {
            keyCell.editText.requestFocus();
            AndroidUtilities.showKeyboard(keyCell.editText);
        }
    }

    private EditTextCell createField(Context context, int hint, int inputType, int imeOptions) {
        EditTextCell cell = new EditTextCell(context, LocaleController.getString(hint), false, false, -1, getResourceProvider()) {
            @Override
            protected void onTextChanged(CharSequence newText) {
                updateFormState();
            }
        };
        cell.editText.setInputType(inputType);
        cell.editText.setRawInputType(inputType);
        cell.editText.setImeOptions(imeOptions);
        return cell;
    }

    private void createFields(Context context) {
        urlCell = createField(context, R.string.ServiceURL, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        urlCell.editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_NEXT && actionId != EditorInfo.IME_ACTION_DONE) {
                return false;
            }
            modelCell.editText.requestFocus();
            AndroidUtilities.showKeyboard(modelCell.editText);
            return true;
        });
        modelCell = createField(context, R.string.ServiceModel, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        modelCell.editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_NEXT && actionId != EditorInfo.IME_ACTION_DONE) {
                return false;
            }
            keyCell.editText.requestFocus();
            AndroidUtilities.showKeyboard(keyCell.editText);
            return true;
        });
        keyCell = createField(context, R.string.ServiceKey, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        keyCell.editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) {
                return false;
            }
            saveConfig();
            return true;
        });
    }

    private void initializeState() {
        Service service = currentService != null ? currentService : AiConfig.DEFAULT_SERVICE;
        initialUrl = service.getUrl();
        initialModel = service.getModel();
        initialKey = service.getKey();
        initialReasoningEnabled = service.isReasoningEnabled();
        reasoningEnabled = initialReasoningEnabled;
        updatingFields = true;
        urlCell.setText(safeString(initialUrl));
        modelCell.setText(safeString(initialModel));
        keyCell.setText(safeString(initialKey));
        updatingFields = false;
        hasChanges = false;
        selectedPresetIndex = findPresetIndex(initialUrl, initialModel);
        forceCustomPreset = selectedPresetIndex == getCustomPresetIndex();
    }

    private void updateFormState() {
        if (updatingFields || !hasFields()) {
            return;
        }
        boolean oldHasChanges = hasChanges;
        int oldPreset = selectedPresetIndex;
        boolean oldPaste = pasteInput != null;
        boolean oldPasteService = pasteInput != null && pasteInput.hasServiceFields();
        hasChanges = !TextUtils.equals(getFieldText(urlCell), safeString(initialUrl))
                || !TextUtils.equals(getFieldText(modelCell), safeString(initialModel))
                || !TextUtils.equals(getFieldText(keyCell), safeString(initialKey))
                || reasoningEnabled != initialReasoningEnabled;
        int preset = findPresetIndex(getFieldText(urlCell), getFieldText(modelCell));
        if (forceCustomPreset) {
            preset = getCustomPresetIndex();
        }
        selectedPresetIndex = preset;
        updateClipboardState(false);
        boolean newPaste = pasteInput != null;
        boolean newPasteService = pasteInput != null && pasteInput.hasServiceFields();
        if (fragmentView == null || listView == null || listView.adapter == null) {
            return;
        }
        if (oldHasChanges == hasChanges && oldPreset == selectedPresetIndex && oldPaste == newPaste && oldPasteService == newPasteService) {
            return;
        }
        listView.adapter.update(true);
    }

    private void setTesting(boolean testing) {
        isTesting = testing;
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void applyPreset(int index) {
        ServicePreset preset = SERVICE_PRESETS[index];
        if (preset.url == null || preset.model == null) {
            forceCustomPreset = true;
            updatingFields = true;
            urlCell.setText("");
            modelCell.setText("");
            updatingFields = false;
            updateFormState();
            urlCell.editText.requestFocus();
            AndroidUtilities.showKeyboard(urlCell.editText);
            return;
        }
        forceCustomPreset = false;
        updatingFields = true;
        urlCell.setText(preset.url);
        modelCell.setText(preset.model);
        updatingFields = false;
        updateFormState();
        keyCell.editText.requestFocus();
        AndroidUtilities.showKeyboard(keyCell.editText);
    }

    private String getPresetTitle(int index) {
        String name = SERVICE_PRESETS[index].name;
        return name == null ? LocaleController.getString(R.string.ServiceProviderCustom) : name;
    }

    private int findPresetIndex(String url, String model) {
        for (int i = 0; i < SERVICE_PRESETS.length - 1; i++) {
            if (TextUtils.equals(url, SERVICE_PRESETS[i].url) && TextUtils.equals(model, SERVICE_PRESETS[i].model)) {
                return i;
            }
        }
        return getCustomPresetIndex();
    }

    private int getCustomPresetIndex() {
        return SERVICE_PRESETS.length - 1;
    }

    private void saveConfig() {
        if (isTesting || !validateFields()) {
            return;
        }
        Service service = new Service(getFieldText(urlCell), getFieldText(modelCell), getFieldText(keyCell), reasoningEnabled);
        Service existing = findExistingService(service);
        boolean reasoningChanged = currentService != null && currentService.isReasoningEnabled() != reasoningEnabled;
        if (existing != null && !existing.equals(currentService)) {
            if (currentService != null) {
                BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.ServiceDuplicate)).show();
            } else {
                useExistingService(existing);
            }
            return;
        }
        if (existing != null && !reasoningChanged) {
            applySelection(existing);
        } else {
            testAndSave(service);
        }
    }

    private void testAndSave(final Service service) {
        testingClient = new Client.Builder().serviceOverride(service).roleOverride(Suggestions.ASSISTANT.getRole()).build();
        showTestingProgressDialog();
        setTesting(true);
        testingRequestId = testingClient.getResponse("Say 'hi'.", new GenerationCallback() {
            @Override
            public void onChunk(String text) {
            }

            @Override
            public void onResponse(String text) {
                clearTestingState();
                if (currentService != null) {
                    AiController.getInstance().updateService(currentService, service);
                } else {
                    AiController.getInstance().addService(service);
                }
                applySelection(service);
            }

            @Override
            public void onError(int code, String message) {
                clearTestingState();
                AiController.showErrorBulletin(EditServiceActivity.this, code, message);
                showFieldError(getFieldForError(code, message));
            }
        });
    }

    private void useExistingService(Service existing) {
        if (existing.isReasoningEnabled() != reasoningEnabled) {
            existing.setReasoningEnabled(reasoningEnabled);
            AiController.getInstance().saveServices();
        }
        applySelection(existing);
        BulletinFactory.global().createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.ServiceAlreadyExists), LocaleController.getString(R.string.ServiceAlreadyExistsInfo)).show();
    }

    private void applySelection(Service service) {
        AiConfig.setSelectedServices(service);
        getNotificationCenter().postNotificationName(NotificationCenter.servicesUpdated);
        finishFragment();
    }

    private Service findExistingService(Service service) {
        for (Service s : AiController.getInstance().getAll()) {
            if (s.equals(service)) {
                return s;
            }
        }
        return null;
    }

    private boolean validateFields() {
        if (!isValidServiceUrl(getFieldText(urlCell))) {
            showFieldError(urlCell);
            return false;
        }
        if (getFieldText(modelCell).length() == 0) {
            showFieldError(modelCell);
            return false;
        }
        if (getFieldText(keyCell).length() == 0) {
            showFieldError(keyCell);
            return false;
        }
        return true;
    }

    private boolean isValidServiceUrl(String url) {
        if (url.length() == 0) {
            return false;
        }
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) && host != null && host.length() != 0;
    }

    private EditTextCell getFieldForError(int code, String message) {
        switch (code) {
            case 400:
                return modelCell;
            case 401:
            case 403:
                return keyCell;
            case 404:
                return message != null && message.toLowerCase(Locale.ROOT).contains("model") ? modelCell : urlCell;
            default:
                return null;
        }
    }

    private void showFieldError(EditTextCell cell) {
        if (cell == null) {
            return;
        }
        BotWebViewVibrationEffect.APP_ERROR.vibrate();
        shiftDp = -shiftDp;
        AndroidUtilities.shakeViewSpring(cell, shiftDp);
        cell.editText.requestFocus();
        AndroidUtilities.showKeyboard(cell.editText);
    }

    private void applyParsedServiceInput(ParsedServiceInput input) {
        updatingFields = true;
        if (!TextUtils.isEmpty(input.url)) {
            urlCell.setText(input.url);
        }
        if (!TextUtils.isEmpty(input.model)) {
            modelCell.setText(input.model);
        }
        if (!TextUtils.isEmpty(input.key)) {
            keyCell.setText(input.key);
        }
        forceCustomPreset = false;
        updatingFields = false;
        updateFormState();
    }

    private void showTestingProgressDialog() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog dialog = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        dialog.setOnCancelListener(d -> cancelTesting());
        testingProgressDialog = dialog;
        showDialog(dialog);
    }

    private void cancelTesting() {
        stopTestingRequest();
        clearTestingState();
    }

    private void stopTestingRequest() {
        if (testingClient != null && !TextUtils.isEmpty(testingRequestId)) {
            testingClient.stopRequest(testingRequestId);
        }
    }

    private void hideTestingProgressDialog() {
        if (testingProgressDialog != null) {
            try {
                testingProgressDialog.dismiss();
            } catch (Exception ignore) {
            }
        }
        testingProgressDialog = null;
    }

    private void clearTestingState() {
        hideTestingProgressDialog();
        setTesting(false);
        testingRequestId = null;
        testingClient = null;
    }

    private ParsedServiceInput parseServiceInput(String text) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        ArrayList<String> lines = new ArrayList<>();
        for (String line : trimmed.split("\\r?\\n")) {
            String l = line.trim();
            if (l.length() > 0) {
                lines.add(l);
            }
        }
        for (int i = 0; i + 2 < lines.size(); i++) {
            if (isLikelyService(lines.get(i), lines.get(i + 1), lines.get(i + 2))) {
                return new ParsedServiceInput(lines.get(i), lines.get(i + 1), lines.get(i + 2));
            }
        }
        if (lines.size() == 1 && isLikelyApiKey(trimmed)) {
            return new ParsedServiceInput(null, null, trimmed);
        }
        return null;
    }

    private boolean isLikelyService(String url, String model, String key) {
        return isLikelyServiceUrl(url) && isLikelyModel(model) && isLikelyApiKey(key);
    }

    private boolean isLikelyServiceUrl(String text) {
        return text.length() > 0 && (text.startsWith("https://") || text.startsWith("http://"));
    }

    private boolean isLikelyModel(String text) {
        return text.length() > 0 && text.length() <= 128 && !WHITESPACE.matcher(text).matches();
    }

    private boolean isLikelyApiKey(String text) {
        return text.length() >= 20 && text.length() <= 512 && API_KEY.matcher(text).matches();
    }

    private void updateClipboardState() {
        updateClipboardState(true);
    }

    private void updateClipboardState(boolean updateList) {
        Context context = fragmentView != null ? fragmentView.getContext() : getContext();
        updateClipboardState(context, updateList);
    }

    private void updateClipboardState(Context context, boolean updateList) {
        if (clipboardManager == null || context == null || !hasFields()) {
            return;
        }
        boolean oldPaste = pasteInput != null;
        boolean oldPasteService = pasteInput != null && pasteInput.hasServiceFields();
        String clipboardText = readClipboardText(context);
        ParsedServiceInput input = parseServiceInput(clipboardText);
        if (input != null) {
            if (input.differsFrom(getFieldText(urlCell), getFieldText(modelCell), getFieldText(keyCell))) {
                pasteInput = input;
                pasteString = clipboardText;
            }
        } else {
            pasteInput = null;
            pasteString = null;
        }
        boolean newPaste = pasteInput != null;
        boolean newPasteService = pasteInput != null && pasteInput.hasServiceFields();
        if (!updateList || fragmentView == null) {
            return;
        }
        if ((oldPaste == newPaste && oldPasteService == newPasteService) || listView == null || listView.adapter == null) {
            return;
        }
        listView.adapter.update(true);
    }

    private String readClipboardText(Context context) {
        try {
            ClipData clip = clipboardManager != null ? clipboardManager.getPrimaryClip() : null;
            if (clip == null || clip.getItemCount() <= 0) {
                return null;
            }
            CharSequence text = clip.getItemAt(0).coerceToText(context);
            return text != null ? text.toString() : null;
        } catch (Exception ignore) {
        }
        return null;
    }

    private void confirmDeleteService() {
        if (currentService == null || getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.Delete));
        builder.setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.DeleteServiceInfo, currentService.getShortModel())));
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> deleteCurrentService());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private void deleteCurrentService() {
        if (currentService == null) {
            return;
        }
        boolean selected = currentService.isSelected();
        if (!AiController.getInstance().removeService(currentService)) {
            return;
        }
        if (selected) {
            AiConfig.clearSelectedService();
            if (!AiController.getInstance().isServicesEmpty()) {
                AiConfig.setSelectedServices(AiController.getInstance().getAll().get(0));
            }
        }
        getNotificationCenter().postNotificationName(NotificationCenter.servicesUpdated);
        finishFragment();
    }

    private boolean hasFields() {
        return urlCell != null && modelCell != null && keyCell != null;
    }

    private String getFieldText(EditTextCell field) {
        if (field == null || field.getText() == null) {
            return "";
        }
        return field.getText().toString().trim();
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private static final class ServicePreset {
        final String name;
        final String url;
        final String model;

        ServicePreset(String name, String url, String model) {
            this.name = name;
            this.url = url;
            this.model = model;
        }
    }

    private static final class ParsedServiceInput {
        final String url;
        final String model;
        final String key;

        ParsedServiceInput(String url, String model, String key) {
            this.url = url;
            this.model = model;
            this.key = key;
        }

        boolean hasServiceFields() {
            return !TextUtils.isEmpty(url) || !TextUtils.isEmpty(model);
        }

        boolean differsFrom(String currentUrl, String currentModel, String currentKey) {
            if (!TextUtils.isEmpty(url) && !url.equals(currentUrl)) {
                return true;
            }
            if (!TextUtils.isEmpty(model) && !model.equals(currentModel)) {
                return true;
            }
            return !TextUtils.isEmpty(key) && !key.equals(currentKey);
        }
    }
}
