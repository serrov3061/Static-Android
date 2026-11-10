package com.exteragram.messenger.plugins.ui.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import com.exteragram.messenger.plugins.Plugin;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.models.EditTextSetting;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.EditTextCaption;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

@SuppressLint({"ViewConstructor"})
public final class PluginEditTextCell extends EditTextCell {
    private static final int SAVE_DEBOUNCE_MS = 750;
    private EditTextSetting currentSetting;
    private Runnable pendingSaveRunnable;
    private String pluginId;
    private final TextWatcher saveTextWatcher;
    private String valueToSave;

    public final void bind(String str, EditTextSetting editTextSetting) {
        bind(str, editTextSetting, editTextSetting.getHint());
    }

    public PluginEditTextCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, null, false, false, -1, resourcesProvider);
        TextWatcher r7 = new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence newText, int start, int before, int count) {
                PluginEditTextCell.this.valueToSave = newText.toString();
                PluginEditTextCell.this.scheduleSave();
            }
        };
        this.saveTextWatcher = r7;
        setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
        this.editText.addTextChangedListener(r7);
    }

    public final void scheduleSave() {
        Runnable runnable = this.pendingSaveRunnable;
        if (runnable != null) {
            AndroidUtilities.cancelRunOnUIThread(runnable);
        }
        EditTextSetting editTextSetting = this.currentSetting;
        final String key = editTextSetting != null ? editTextSetting.getKey() : null;
        final String str = this.valueToSave;
        final String str2 = this.pluginId;
        final EditTextSetting editTextSetting2 = this.currentSetting;
        if (key == null || str == null || str2 == null || editTextSetting2 == null) {
            return;
        }
        Runnable runnable2 = new Runnable() {
            @Override
            public final void run() {
                PluginEditTextCell.$r8$lambda$IbrC_cSxtCiu_K4wjWo2euGo6o8(PluginEditTextCell.this, editTextSetting2, str, str2, key);
            }
        };
        this.pendingSaveRunnable = runnable2;
        AndroidUtilities.runOnUIThread(runnable2, 750L);
    }

    public static void $r8$lambda$IbrC_cSxtCiu_K4wjWo2euGo6o8(PluginEditTextCell pluginEditTextCell, final EditTextSetting editTextSetting, final String str, final String str2, final String str3) {
        pluginEditTextCell.pendingSaveRunnable = null;
        PluginsController.Companion.runOnPluginsQueue(new Runnable() {
            @Override
            public final void run() {
                PluginEditTextCell.$r8$lambda$jcl0iUP5pG5MF2sgNmuVkCk7LY4(editTextSetting, str, str2, str3);
            }
        });
    }

    public static void $r8$lambda$jcl0iUP5pG5MF2sgNmuVkCk7LY4(EditTextSetting editTextSetting, String str, String str2, String str3) {
        if (editTextSetting.getMaxLength() > 0 && str.length() > editTextSetting.getMaxLength()) {
            str = str.substring(0, editTextSetting.getMaxLength());
        }
        PluginsController.Companion.getInstance().setPluginSettingAndTriggerOnChange(str2, str3, str, editTextSetting.getOnChangeCallback());
    }

    @Override
    public void onFocusChanged(boolean focused) {
        super.onFocusChanged(focused);
        if (focused) {
            return;
        }
        flushPendingSave();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        flushPendingSave();
    }

    private final void flushPendingSave() {
        Runnable runnable = this.pendingSaveRunnable;
        if (runnable != null) {
            AndroidUtilities.cancelRunOnUIThread(runnable);
            runnable.run();
            this.pendingSaveRunnable = null;
        }
    }

    public final void bind(String pluginId, EditTextSetting setting, CharSequence hint) {
        boolean z;
        InputFilter[] inputFilterArr;
        EditTextSetting editTextSetting = this.currentSetting;
        if (editTextSetting == null) {
            z = false;
        } else {
            if (TextUtils.equals(editTextSetting != null ? editTextSetting.getKey() : null, setting.getKey())) {
                z = true;
            } else {
                z = false;
            }
        }
        if (!z) {
            flushPendingSave();
        }
        this.pluginId = pluginId;
        this.currentSetting = setting;
        setWatchersEnabled(false);
        setMultiline(setting.getMultiline());
        int i = (setting.getMultiline() ? 131072 : 0) | 573441;
        if (this.editText.getInputType() != i) {
            this.editText.setInputType(i);
        }
        if (setting.getMaxLength() > 0) {
            setShowLimitWhenNear(setting.getMaxLength() / Math.min(setting.getMaxLength(), 4));
        } else {
            setShowLimitWhenNear(-1);
        }
        ArrayList arrayList = new ArrayList();
        InputFilter inputFilterCreateInputFilter = createInputFilter(setting.getMask());
        if (inputFilterCreateInputFilter != null) {
            arrayList.add(inputFilterCreateInputFilter);
        }
        if (setting.getMaxLength() > 0) {
            arrayList.add(new InputFilter.LengthFilter(setting.getMaxLength()));
        }
        EditTextCaption editTextCaption = this.editText;
        if (!arrayList.isEmpty()) {
            inputFilterArr = (InputFilter[]) arrayList.toArray(new InputFilter[0]);
        } else {
            inputFilterArr = new InputFilter[0];
        }
        editTextCaption.setFilters(inputFilterArr);
        String pluginSettingString = PluginsController.Companion.getInstance().getPluginSettingString(pluginId, setting.getKey(), setting.getDefaultValue());
        if (!TextUtils.equals(pluginSettingString, String.valueOf(this.editText.getText()))) {
            if (this.editText.hasFocus() && z) {
                setWatchersEnabled(true);
                return;
            }
            setText(pluginSettingString);
        }
        this.valueToSave = pluginSettingString;
        if (hint == null) {
            hint = setting.getHint();
        }
        if (hint != null) {
            this.editText.setHint(hint);
        }
        setWatchersEnabled(true);
    }

    // exteraGram's EditTextCell has setMultiline(), StaticGram's does not
    private void setMultiline(boolean multiline) {
        this.editText.setMaxLines(multiline ? 5 : 1);
        this.editText.setSingleLine(!multiline);
    }

    private final void setWatchersEnabled(boolean enabled) {
        this.editText.removeTextChangedListener(this.saveTextWatcher);
        if (enabled) {
            this.editText.addTextChangedListener(this.saveTextWatcher);
        }
    }

    private final InputFilter createInputFilter(String maskRegex) {
        if (TextUtils.isEmpty(maskRegex)) {
            return null;
        }
        final Pattern patternCompile;
        try {
            patternCompile = Pattern.compile(maskRegex);
        } catch (PatternSyntaxException e) {
            FileLog.e("Invalid mask for EditText: ".concat(maskRegex), e);
            return null;
        }
        return new InputFilter() {
            @Override
            public final CharSequence filter(CharSequence charSequence, int i, int i2, Spanned spanned, int i3, int i4) {
                return PluginEditTextCell.m1609$r8$lambda$yi_0eYqsHGvmzLBjgazYnBufaA(patternCompile, charSequence, i, i2, spanned, i3, i4);
            }
        };
    }

    public static CharSequence m1609$r8$lambda$yi_0eYqsHGvmzLBjgazYnBufaA(Pattern pattern, CharSequence charSequence, int i, int i2, Spanned spanned, int i3, int i4) {
        Matcher matcher;
        StringBuilder sb = new StringBuilder(i2 - i);
        boolean z = true;
        while (i < i2) {
            char cCharAt = charSequence.charAt(i);
            if ((pattern == null || (matcher = pattern.matcher(String.valueOf(cCharAt))) == null) ? false : matcher.matches()) {
                sb.append(cCharAt);
            } else {
                z = false;
            }
            i++;
        }
        if (z) {
            return null;
        }
        return sb.toString();
    }

    public static final class Factory extends UItem.UItemFactory<PluginEditTextCell> {

        public static final Companion Companion = new Companion();

        public static final UItem as(Plugin plugin, EditTextSetting editTextSetting) {
            return Companion.as(plugin, editTextSetting);
        }

        @Override
        public boolean isClickable() {
            return false;
        }

        @Override
        public PluginEditTextCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new PluginEditTextCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            if ((view instanceof PluginEditTextCell) && (item.settingItem instanceof EditTextSetting)) {
                Object obj = item.object;
                if (obj instanceof Plugin) {
                    PluginEditTextCell pluginEditTextCell = (PluginEditTextCell) view;
                    pluginEditTextCell.bind(((Plugin) obj).getId(), (EditTextSetting) item.settingItem, item.text);
                    pluginEditTextCell.setDivider(divider);
                }
            }
        }

        public static final class Companion {
            private Companion() {
            }

            public final UItem as(Plugin plugin, EditTextSetting setting) {
                UItem uItemOfFactory = UItem.ofFactory(Factory.class);
                uItemOfFactory.object = plugin;
                uItemOfFactory.settingItem = setting;
                return uItemOfFactory;
            }
        }

        static {
            UItem.UItemFactory.setup(new Factory());
        }
    }
}
