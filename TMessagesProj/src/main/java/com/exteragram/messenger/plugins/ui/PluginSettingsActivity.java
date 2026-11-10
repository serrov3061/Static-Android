package com.exteragram.messenger.plugins.ui;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.text.Editable;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.chaquo.python.PyObject;
import com.exteragram.messenger.plugins.Plugin;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.PythonPluginsEngine;
import com.exteragram.messenger.plugins.models.CustomSetting;
import com.exteragram.messenger.plugins.models.DividerSetting;
import com.exteragram.messenger.plugins.models.EditTextSetting;
import com.exteragram.messenger.plugins.models.HeaderSetting;
import com.exteragram.messenger.plugins.models.InputSetting;
import com.exteragram.messenger.plugins.models.SelectorSetting;
import com.exteragram.messenger.plugins.models.SettingItem;
import com.exteragram.messenger.plugins.models.SwitchSetting;
import com.exteragram.messenger.plugins.models.TextSetting;
import com.exteragram.messenger.plugins.ui.components.PluginEditTextCell;
import com.exteragram.messenger.plugins.ui.components.PluginTranslateButton;
import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.preferences.utils.SettingsRegistry;
import com.exteragram.messenger.utils.text.LocaleUtils;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import kotlin.Lazy;
import kotlin.LazyKt;
import kotlin.Unit;
import kotlin.collections.ArraysKt;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.internal.Intrinsics;
import kotlin.jvm.internal.Ref;
import kotlin.ranges.ClosedRange;
import kotlin.ranges.RangesKt;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

public final class PluginSettingsActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate, PluginSettingsTranslation.Listener {
    private PyObject createSubFragmentCallback;
    private String customTitle;
    private Plugin plugin;
    private ActionBarMenuItem resetItem;
    private List<? extends SettingItem> settingItems;
    private String settingsLinkPrefix;
    private Integer targetSettingItemId;
    private String targetSettingName;
    private PluginTranslateButton translateButton;
    private ActionBarMenuItem translateItem;

    private final Lazy<PluginSettingsTranslation> translation;
    private final LinkedHashSet<String> visibleTexts;

    public PluginSettingsActivity(Plugin plugin) {
        this(plugin, (String) null);
    }

    public PluginSettingsActivity(Plugin plugin, String str, List<? extends SettingItem> list, PyObject pyObject) {
        this(plugin, str, list, pyObject, null);
    }

    public static PluginSettingsTranslation m1586$r8$lambda$xCyMslpbmlOrWwsP0i6GlhGMtE(PluginSettingsActivity pluginSettingsActivity) {
        PluginSettingsTranslation.Companion companion = PluginSettingsTranslation.Companion;
        Plugin plugin = pluginSettingsActivity.plugin;
        if (plugin == null) {
            plugin = null;
        }
        return companion.of(plugin.getId());
    }

    private final PluginSettingsTranslation getTranslation() {
        return this.translation.getValue();
    }

    public PluginSettingsActivity() {
        this.translation = LazyKt.lazy(() -> PluginSettingsActivity.m1586$r8$lambda$xCyMslpbmlOrWwsP0i6GlhGMtE(this));
        this.visibleTexts = new LinkedHashSet<>();
    }


    public PluginSettingsActivity(Plugin plugin, String str) {
        this();
        this.plugin = plugin;
        this.targetSettingName = str;
    }


    public PluginSettingsActivity(Plugin plugin, String str, List<? extends SettingItem> list, PyObject pyObject, String str2) {
        this();
        this.plugin = plugin;
        this.customTitle = str;
        this.settingItems = list;
        this.createSubFragmentCallback = pyObject;
        this.targetSettingName = str2;
    }

    public final PluginSettingsActivity setSettingsLinkPrefix(String settingsLinkPrefix) {
        this.settingsLinkPrefix = settingsLinkPrefix;
        return this;
    }

    @Override
    public String getTitle() {
        PluginSettingsTranslation translation = getTranslation();
        String name = this.customTitle;
        if (name == null) {
            Plugin plugin = this.plugin;
            if (plugin == null) {
                plugin = null;
            }
            name = plugin.getName();
        }
        String strText = translation.text(name);
        return strText == null ? "" : strText;
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginSettingsRegistered);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginSettingsUnregistered);
        getTranslation().addListener(this);
        getTranslation().resetFailed();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginSettingsRegistered);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginSettingsUnregistered);
        getTranslation().removeListener(this);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.pluginSettingsRegistered) {
            Object objFirstOrNull = ArraysKt.firstOrNull(args);
            String str = objFirstOrNull instanceof String ? (String) objFirstOrNull : null;
            if (str != null) {
                Plugin plugin = this.plugin;
                if (plugin == null) {
                    plugin = null;
                }
                if (!Intrinsics.areEqual(plugin.getId(), str)) {
                    return;
                }
            }
            final PyObject pyObject = this.createSubFragmentCallback;
            if (pyObject != null) {
                PluginsController.Companion.runOnPluginsQueue(new Runnable() {
                    @Override
                    public final void run() {
                        PluginSettingsActivity.m1582$r8$lambda$9pY5TzlHVvCQHIyjDvEtTS9eys(PluginSettingsActivity.this, pyObject);
                    }
                });
                return;
            }
            UniversalAdapter universalAdapter = getListView$TMessagesProj().adapter;
            if (universalAdapter != null) {
                universalAdapter.update(true);
            }
            ActionBarMenuItem actionBarMenuItem = this.resetItem;
            if (actionBarMenuItem != null) {
                PluginsController companion = PluginsController.Companion.getInstance();
                Plugin plugin2 = this.plugin;
                AndroidUtilities.updateViewVisibilityAnimated(actionBarMenuItem, companion.hasPluginSettingsPreferences((plugin2 != null ? plugin2 : null).getId()), 0.5f, true, 1.0f, true, new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public final void onAnimationUpdate(ValueAnimator valueAnimator) {
                        PluginSettingsActivity.$r8$lambda$VrbycZPHCdbIObj6EGdZ3pXZPkI(PluginSettingsActivity.this, valueAnimator);
                    }
                });
                return;
            }
            return;
        }
        if (id == NotificationCenter.pluginSettingsUnregistered) {
            Object objFirstOrNull2 = ArraysKt.firstOrNull(args);
            String str2 = objFirstOrNull2 instanceof String ? (String) objFirstOrNull2 : null;
            if (str2 == null) {
                return;
            }
            Plugin plugin3 = this.plugin;
            if (plugin3 == null) {
                plugin3 = null;
            }
            if (Intrinsics.areEqual(plugin3.getId(), str2)) {
                PluginsController companion2 = PluginsController.Companion.getInstance();
                Plugin plugin4 = this.plugin;
                if (companion2.hasPluginSettings((plugin4 != null ? plugin4 : null).getId())) {
                    return;
                }
                finishFragment();
            }
        }
    }

    public static void m1582$r8$lambda$9pY5TzlHVvCQHIyjDvEtTS9eys(final PluginSettingsActivity pluginSettingsActivity, PyObject pyObject) {
        PluginsController.Companion companion = PluginsController.Companion;
        PluginsController companion2 = companion.getInstance();
        Plugin plugin = pluginSettingsActivity.plugin;
        if (plugin == null) {
            plugin = null;
        }
        if (companion2.isPluginActive(plugin)) {
            try {
                PyObject pyObjectCall = pyObject.call(new Object[0]);
                if (pyObjectCall == null) {
                    return;
                }
                PluginsController.PluginsEngine pluginsEngine = companion.getEngines().get(PluginsConstants.PYTHON);
                PythonPluginsEngine pythonPluginsEngine = pluginsEngine instanceof PythonPluginsEngine ? (PythonPluginsEngine) pluginsEngine : null;
                if (pythonPluginsEngine == null) {
                    return;
                }
                final List<SettingItem> pySettingDefinitions = pythonPluginsEngine.parsePySettingDefinitions(pyObjectCall.asList());
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public final void run() {
                        PluginSettingsActivity.$r8$lambda$T_NnNSIT5Jyk2zKST0nB2HCN7WE(pluginSettingsActivity, pySettingDefinitions);
                    }
                });
            } catch (Exception unused) {
            }
        }
    }

    public static void $r8$lambda$T_NnNSIT5Jyk2zKST0nB2HCN7WE(PluginSettingsActivity pluginSettingsActivity, List list) {
        pluginSettingsActivity.settingItems = list;
        UniversalAdapter universalAdapter = pluginSettingsActivity.getListView$TMessagesProj().adapter;
        if (universalAdapter != null) {
            universalAdapter.update(true);
        }
    }

    public static void $r8$lambda$VrbycZPHCdbIObj6EGdZ3pXZPkI(PluginSettingsActivity pluginSettingsActivity, ValueAnimator valueAnimator) {
        ActionBar actionBar = pluginSettingsActivity.actionBar;
        if (actionBar != null) {
            actionBar.requestLayout();
        }
    }

    @Override
    public View createView(Context context) {
        View viewCreateView = super.createView(context);
        ActionBarMenu actionBarMenuCreateMenu = this.actionBar.createMenu();
        ActionBarMenuItem actionBarMenuItemAddItem = actionBarMenuCreateMenu.addItem(1, 0);
        PluginTranslateButton pluginTranslateButton = new PluginTranslateButton(context, Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
        actionBarMenuItemAddItem.addView(pluginTranslateButton, LayoutHelper.createFrame(-1, -1.0f));
        this.translateButton = pluginTranslateButton;
        actionBarMenuItemAddItem.setContentDescription(pluginTranslateButton.getContentDescription());
        AndroidUtilities.updateViewVisibilityAnimated(actionBarMenuItemAddItem, false, 0.5f, false);
        actionBarMenuItemAddItem.setOnClickListener(new View.OnClickListener() {
            @Override
            public final void onClick(View view) {
                PluginSettingsActivity.this.onTranslateClick();
            }
        });
        this.translateItem = actionBarMenuItemAddItem;
        if (this.createSubFragmentCallback == null) {
            final ActionBarMenuItem actionBarMenuItemAddItem2 = actionBarMenuCreateMenu.addItem(0, R.drawable.msg_reset);
            actionBarMenuItemAddItem2.setContentDescription(LocaleController.getString(R.string.Reset));
            PluginsController companion = PluginsController.Companion.getInstance();
            Plugin plugin = this.plugin;
            if (plugin == null) {
                plugin = null;
            }
            AndroidUtilities.updateViewVisibilityAnimated(actionBarMenuItemAddItem2, companion.hasPluginSettingsPreferences(plugin.getId()), 0.5f, false);
            actionBarMenuItemAddItem2.setTag(null);
            actionBarMenuItemAddItem2.setOnClickListener(new View.OnClickListener() {
                @Override
                public final void onClick(View view) {
                    PluginSettingsActivity.m1585$r8$lambda$UzH2mCmxw9lvEAd71iI0aCVthU(actionBarMenuItemAddItem2, PluginSettingsActivity.this, view);
                }
            });
            this.resetItem = actionBarMenuItemAddItem2;
        }
        checkTargetSetting();
        updateTranslateItem(false);
        this.fragmentView = viewCreateView;
        return viewCreateView;
    }

    public static void m1585$r8$lambda$UzH2mCmxw9lvEAd71iI0aCVthU(ActionBarMenuItem actionBarMenuItem, final PluginSettingsActivity pluginSettingsActivity, final View view) {
        AlertDialog.Builder builder = new AlertDialog.Builder(actionBarMenuItem.getContext(), pluginSettingsActivity.getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.ResetSettings));
        int i = R.string.ResetPluginSettingsInfo;
        Plugin plugin = pluginSettingsActivity.plugin;
        if (plugin == null) {
            plugin = null;
        }
        builder.setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(i, plugin.getName())));
        builder.setPositiveButton(LocaleController.getString(R.string.Reset), new AlertDialog.OnButtonClickListener() {
            @Override
            public final void onClick(AlertDialog alertDialog, int i2) {
                PluginSettingsActivity.$r8$lambda$h03KbGNTNb1OtVtW90IDgFBXz1A(pluginSettingsActivity, view, alertDialog, i2);
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog alertDialogCreate = builder.create();
        pluginSettingsActivity.showDialog(alertDialogCreate);
        View button = alertDialogCreate.getButton(-1);
        TextView textView = button instanceof TextView ? (TextView) button : null;
        if (textView != null) {
            textView.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    public static void $r8$lambda$h03KbGNTNb1OtVtW90IDgFBXz1A(final PluginSettingsActivity pluginSettingsActivity, View view, AlertDialog alertDialog, int i) {
        View viewFindFocus;
        View view2 = pluginSettingsActivity.fragmentView;
        if (view2 != null && (viewFindFocus = view2.findFocus()) != null) {
            viewFindFocus.clearFocus();
        }
        AndroidUtilities.updateViewVisibilityAnimated(view, false, 0.5f, true, 1.0f, true, new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public final void onAnimationUpdate(ValueAnimator valueAnimator) {
                PluginSettingsActivity.m1583$r8$lambda$Ce6DjGUz1AF3cZvPIAbGAMv1I8(pluginSettingsActivity, valueAnimator);
            }
        });
        PluginsController.Companion.runOnPluginsQueue(new Runnable() {
            @Override
            public final void run() {
                PluginSettingsActivity.$r8$lambda$PhH2ExfyC8JNHIRVvp74w06bowU(pluginSettingsActivity);
            }
        });
    }

    public static void m1583$r8$lambda$Ce6DjGUz1AF3cZvPIAbGAMv1I8(PluginSettingsActivity pluginSettingsActivity, ValueAnimator valueAnimator) {
        ActionBar actionBar = pluginSettingsActivity.actionBar;
        if (actionBar != null) {
            actionBar.requestLayout();
        }
    }

    public static void $r8$lambda$PhH2ExfyC8JNHIRVvp74w06bowU(final PluginSettingsActivity pluginSettingsActivity) {
        PluginsController.Companion companion = PluginsController.Companion;
        PluginsController companion2 = companion.getInstance();
        Plugin plugin = pluginSettingsActivity.plugin;
        if (plugin == null) {
            plugin = null;
        }
        companion2.clearPluginSettingsPreferences(plugin.getId(), false);
        PluginsController companion3 = companion.getInstance();
        Plugin plugin2 = pluginSettingsActivity.plugin;
        companion3.loadPluginSettings((plugin2 != null ? plugin2 : null).getId());
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public final void run() {
                PluginSettingsActivity.m1580$r8$lambda$1Ja_dxkLD7loik7O2cLHRMxUJg(pluginSettingsActivity);
            }
        });
    }

    public static void m1580$r8$lambda$1Ja_dxkLD7loik7O2cLHRMxUJg(PluginSettingsActivity pluginSettingsActivity) {
        BulletinFactory bulletinFactoryOf = BulletinFactory.of(pluginSettingsActivity);
        int i = R.raw.info;
        int i2 = R.string.ResetPluginSettings;
        Plugin plugin = pluginSettingsActivity.plugin;
        if (plugin == null) {
            plugin = null;
        }
        bulletinFactoryOf.createSimpleBulletin(i, LocaleController.formatString(i2, plugin.getName())).show();
    }

    @Override
    public void onTranslationChanged(boolean failed) {
        UniversalAdapter universalAdapter = getListView$TMessagesProj().adapter;
        if (universalAdapter != null) {
            universalAdapter.update(true);
        }
        ActionBar actionBar = this.actionBar;
        if (actionBar != null) {
            actionBar.setTitle(getTitle());
        }
        updateTranslateItem(true);
        if (failed && isLastFragment()) {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.TranslationFailedAlert2)).show();
        }
    }

    public final void onTranslateClick() {
        if (getTranslation().isLoading()) {
            return;
        }
        getTranslation().setEnabled(!getTranslation().getEnabled());
    }

    private final void updateTranslateItem(boolean animated) {
        int i = 1;
        AndroidUtilities.updateViewVisibilityAnimated(this.translateItem, getTranslation().isTranslatable() || getTranslation().getEnabled(), 0.5f, true, 1.0f, animated, new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public final void onAnimationUpdate(ValueAnimator valueAnimator) {
                PluginSettingsActivity.$r8$lambda$wxlGKayieU74hzvWkJtTGgqkxNw(PluginSettingsActivity.this, valueAnimator);
            }
        });
        PluginTranslateButton pluginTranslateButton = this.translateButton;
        if (pluginTranslateButton != null) {
            if (!getTranslation().getEnabled()) {
                i = 0;
            } else if (!getTranslation().isLoading()) {
                i = 2;
            }
            pluginTranslateButton.setState(i);
            ActionBarMenuItem actionBarMenuItem = this.translateItem;
            if (actionBarMenuItem != null) {
                actionBarMenuItem.setContentDescription(pluginTranslateButton.getContentDescription());
            }
        }
    }

    public static void $r8$lambda$wxlGKayieU74hzvWkJtTGgqkxNw(PluginSettingsActivity pluginSettingsActivity, ValueAnimator valueAnimator) {
        ActionBar actionBar = pluginSettingsActivity.actionBar;
        if (actionBar != null) {
            actionBar.requestLayout();
        }
    }

    private final String tr(String text) {
        if (text != null) {
            this.visibleTexts.add(text);
        }
        return getTranslation().text(text);
    }

    public final void checkTargetSetting() {
        Integer num = this.targetSettingItemId;
        if (num != null) {
            scrollToItem(num.intValue());
            this.targetSettingItemId = null;
        }
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        String str;
        int identifier;
        UItem item;
        CharSequence charSequenceFullyFormatText;
        UItem uItemAsButton;
        List<? extends SettingItem> pluginSettingsList = this.settingItems;
        if (pluginSettingsList == null) {
            PluginsController companion = PluginsController.Companion.getInstance();
            Plugin plugin = this.plugin;
            if (plugin == null) {
                plugin = null;
            }
            pluginSettingsList = companion.getPluginSettingsList(plugin.getId());
        }
        List list = pluginSettingsList;
        if (list == null || list.isEmpty()) {
            return;
        }
        this.visibleTexts.clear();
        String name = this.customTitle;
        if (name == null) {
            Plugin plugin2 = this.plugin;
            if (plugin2 == null) {
                plugin2 = null;
            }
            name = plugin2.getName();
        }
        tr(name);
        boolean z = false;
        for (SettingItem settingItem : pluginSettingsList) {
            item = null;
            String icon = settingItem.getIcon();
            if (icon == null) {
                identifier = 0;
            } else {
                if (icon.length() <= 0) {
                    icon = null;
                }
                if (icon != null) {
                    Context context = ApplicationLoader.applicationContext;
                    identifier = context.getResources().getIdentifier(icon, "drawable", context.getPackageName());
                } else {
                    identifier = 0;
                }
            }
            try {
                if (settingItem instanceof HeaderSetting) {
                    String text = ((HeaderSetting) settingItem).getText();
                    if (text != null) {
                        item = UItem.asHeader(tr(text));
                        item.settingItem = settingItem;
                    } else {
                        item = null;
                    }
                } else if (settingItem instanceof SwitchSetting) {
                    String key = ((SwitchSetting) settingItem).getKey();
                    String text2 = ((SwitchSetting) settingItem).getText();
                    if (key == null || text2 == null) {
                        item = null;
                    } else {
                        PluginsController companion2 = PluginsController.Companion.getInstance();
                        Plugin plugin3 = this.plugin;
                        if (plugin3 == null) {
                            plugin3 = null;
                        }
                        boolean pluginSettingBoolean = companion2.getPluginSettingBoolean(plugin3.getId(), key, ((SwitchSetting) settingItem).getDefaultValue());
                        uItemAsButton = UItem.asCheck(0, tr(text2));
                        uItemAsButton.setChecked(pluginSettingBoolean);
                        uItemAsButton.drawLine = false;
                        String subtext = ((SwitchSetting) settingItem).getSubtext();
                        if (subtext != null) {
                            uItemAsButton.textValue = tr(subtext);
                            uItemAsButton.multiline = true;
                        }
                        if (identifier != 0) {
                            uItemAsButton.iconResId = identifier;
                        }
                        uItemAsButton.object2 = key;
                        uItemAsButton.settingItem = settingItem;
                        item = uItemAsButton;
                    }
                } else if (settingItem instanceof InputSetting) {
                    String key2 = ((InputSetting) settingItem).getKey();
                    String text3 = ((InputSetting) settingItem).getText();
                    if (key2 == null || text3 == null) {
                        item = null;
                    } else {
                        PluginsController companion3 = PluginsController.Companion.getInstance();
                        Plugin plugin4 = this.plugin;
                        if (plugin4 == null) {
                            plugin4 = null;
                        }
                        uItemAsButton = UItem.asButton(0, tr(text3), companion3.getPluginSettingString(plugin4.getId(), key2, ((InputSetting) settingItem).getDefaultValue()));
                        tr(((InputSetting) settingItem).getSubtext());
                        if (identifier != 0) {
                            uItemAsButton.iconResId = identifier;
                        }
                        uItemAsButton.object2 = key2;
                        uItemAsButton.settingItem = settingItem;
                        item = uItemAsButton;
                    }
                } else if (settingItem instanceof EditTextSetting) {
                    if (((EditTextSetting) settingItem).getKey() == null || ((EditTextSetting) settingItem).getHint() == null) {
                        item = null;
                    } else {
                        PluginEditTextCell.Factory.Companion companion4 = PluginEditTextCell.Factory.Companion;
                        Plugin plugin5 = this.plugin;
                        if (plugin5 == null) {
                            plugin5 = null;
                        }
                        item = companion4.as(plugin5, (EditTextSetting) settingItem);
                        item.text = tr(((EditTextSetting) settingItem).getHint());
                    }
                } else {
                    boolean z2 = settingItem instanceof SelectorSetting;
                    CharSequence charSequence = "";
                    if (z2) {
                        String key3 = ((SelectorSetting) settingItem).getKey();
                        String text4 = ((SelectorSetting) settingItem).getText();
                        if (key3 != null && text4 != null) {
                            if (!(((SelectorSetting) settingItem).getItems().length == 0)) {
                                PluginsController.Companion companion5 = PluginsController.Companion;
                                PluginsController companion6 = companion5.getInstance();
                                Plugin plugin6 = this.plugin;
                                if (plugin6 == null) {
                                    plugin6 = null;
                                }
                                int pluginSettingInt = companion6.getPluginSettingInt(plugin6.getId(), key3, ((SelectorSetting) settingItem).getDefaultValue());
                                if (pluginSettingInt < 0 || pluginSettingInt >= ((SelectorSetting) settingItem).getItems().length) {
                                    pluginSettingInt = Math.max(0, Math.min(((SelectorSetting) settingItem).getDefaultValue(), ((SelectorSetting) settingItem).getItems().length - 1));
                                    PluginsController companion7 = companion5.getInstance();
                                    Plugin plugin7 = this.plugin;
                                    if (plugin7 == null) {
                                        plugin7 = null;
                                    }
                                    companion7.setPluginSetting(plugin7.getId(), key3, Integer.valueOf(pluginSettingInt));
                                }
                                String[] items2 = ((SelectorSetting) settingItem).getItems();
                                ArrayList arrayList = new ArrayList(items2.length);
                                for (String str2 : items2) {
                                    String strTr = tr(str2);
                                    if (strTr == null) {
                                        strTr = "";
                                    }
                                    arrayList.add(strTr);
                                }
                                String[] strArr = (String[]) arrayList.toArray(new String[0]);
                                uItemAsButton = UItem.asButton(0, tr(text4), strArr[pluginSettingInt]);
                                uItemAsButton.texts = strArr;
                                uItemAsButton.intValue = pluginSettingInt;
                                if (identifier != 0) {
                                    uItemAsButton.iconResId = identifier;
                                }
                                uItemAsButton.object2 = key3;
                                uItemAsButton.settingItem = settingItem;
                                item = uItemAsButton;
                            }
                        }
                    } else if (settingItem instanceof TextSetting) {
                        UItem uItemAsButton2 = UItem.asButton(0, tr(((TextSetting) settingItem).getText()));
                        uItemAsButton2.settingItem = settingItem;
                        if (identifier != 0) {
                            uItemAsButton2.iconResId = identifier;
                        }
                        uItemAsButton2.accent = ((TextSetting) settingItem).getAccent();
                        uItemAsButton2.red = ((TextSetting) settingItem).getRed();
                        String subtext2 = ((TextSetting) settingItem).getSubtext();
                        if (subtext2 != null && subtext2.length() != 0) {
                            uItemAsButton2.subtext = tr(((TextSetting) settingItem).getSubtext());
                            uItemAsButton2.intValue = 60;
                        }
                        item = uItemAsButton2;
                    } else if (settingItem instanceof DividerSetting) {
                        String strTr2 = tr(((DividerSetting) settingItem).getText());
                        if (strTr2 != null && (charSequenceFullyFormatText = LocaleUtils.fullyFormatText(strTr2, this, null)) != null) {
                            charSequence = charSequenceFullyFormatText;
                        }
                        item = UItem.asShadow(charSequence);
                    } else if (settingItem instanceof CustomSetting) {
                        item = ((CustomSetting) settingItem).getItem();
                        if (item == null) {
                            CustomSetting.Factory<?> factory = ((CustomSetting) settingItem).getFactory();
                            if (factory != null) {
                                Plugin plugin8 = this.plugin;
                                if (plugin8 == null) {
                                    plugin8 = null;
                                }
                                item = factory.create(plugin8, (CustomSetting) settingItem, ((CustomSetting) settingItem).getFactoryArgs());
                            } else {
                                item = null;
                            }
                        }
                        if (item != null) {
                            item.settingItem = settingItem;
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("PluginSettings", "Error creating item", e);
            }
            if (item != null) {
                item.id = getStableId(settingItem);
                SettingItem settingItem2 = item.settingItem;
                String linkAlias = settingItem2 != null ? settingItem2.getLinkAlias() : null;
                String str3 = this.targetSettingName;
                if (str3 != null && str3.length() != 0 && Intrinsics.areEqual(linkAlias, this.targetSettingName)) {
                    this.targetSettingItemId = Integer.valueOf(item.id);
                    this.targetSettingName = null;
                    z = true;
                }
                items.add(item);
            }
        }
        if (!z && (str = this.targetSettingName) != null && str.length() != 0) {
            SettingsRegistry.getInstance().onSettingNotFound(this);
            this.targetSettingName = null;
        }
        getTranslation().check(this.visibleTexts);
        updateTranslateItem(true);
    }

    @Override
    public void onClick(UItem item, final View view, int position, float x, float y) {
        SettingItem settingItem;
        PluginsController companion = PluginsController.Companion.getInstance();
        Plugin plugin = this.plugin;
        Plugin plugin2 = null;
        if (plugin == null) {
            plugin = null;
        }
        if (companion.isPluginActive(plugin) && (settingItem = item.settingItem) != null) {
            if (settingItem instanceof TextSetting) {
                TextSetting textSetting = (TextSetting) settingItem;
                PyObject createSubFragmentCallback = textSetting.getCreateSubFragmentCallback();
                if (createSubFragmentCallback != null) {
                    openSubFragmentNative(item, createSubFragmentCallback);
                    return;
                }
                PyObject onClickCallback = textSetting.getOnClickCallback();
                if (onClickCallback != null) {
                    try {
                        onClickCallback.call(view);
                        return;
                    } catch (Exception unused) {
                        return;
                    }
                }
                return;
            }
            if (settingItem instanceof CustomSetting) {
                PyObject createSubFragmentCallback2 = ((CustomSetting) settingItem).getCreateSubFragmentCallback();
                if (createSubFragmentCallback2 != null) {
                    openSubFragmentNative(item, createSubFragmentCallback2);
                    return;
                }
                try {
                    CustomSetting.Factory<?> factory = ((CustomSetting) settingItem).getFactory();
                    if (factory != null) {
                        Plugin plugin3 = this.plugin;
                        if (plugin3 != null) {
                            plugin2 = plugin3;
                        }
                        factory.onClick(plugin2, item, view);
                        return;
                    }
                    PyObject onClickCallback2 = ((CustomSetting) settingItem).getOnClickCallback();
                    if (onClickCallback2 != null) {
                        onClickCallback2.call(view);
                        return;
                    }
                    return;
                } catch (Exception unused2) {
                    return;
                }
            }
            Object obj = item.object2;
            String str = obj instanceof String ? (String) obj : null;
            if (str == null) {
                return;
            }
            if (view instanceof TextCheckCell) {
                toggleSwitch(item, str, !((TextCheckCell) view).isChecked(), new Function1() {
                    @Override
                    public final Object invoke(Object obj2) {
                        return PluginSettingsActivity.$r8$lambda$Mp2TFAjnIDnsFeYKm2SBjgKL0yY(view, ((Boolean) obj2).booleanValue());
                    }
                });
                return;
            }
            if (view instanceof NotificationsCheckCell) {
                toggleSwitch(item, str, !((NotificationsCheckCell) view).isChecked(), new Function1() {
                    @Override
                    public final Object invoke(Object obj2) {
                        return PluginSettingsActivity.$r8$lambda$RxWgJDfcHix48N9hb55E5aPYwzo(view, ((Boolean) obj2).booleanValue());
                    }
                });
                return;
            }
            if (view instanceof TextCell) {
                if (settingItem instanceof SelectorSetting) {
                    showSelectorDialog(item, view, str);
                } else if (settingItem instanceof InputSetting) {
                    showStringInputDialog(item, view, str);
                }
            }
        }
    }

    public static Unit $r8$lambda$Mp2TFAjnIDnsFeYKm2SBjgKL0yY(View view, boolean z) {
        ((TextCheckCell) view).setChecked(z);
        return Unit.INSTANCE;
    }

    public static Unit $r8$lambda$RxWgJDfcHix48N9hb55E5aPYwzo(View view, boolean z) {
        ((NotificationsCheckCell) view).setChecked(z);
        return Unit.INSTANCE;
    }

    private final void toggleSwitch(UItem item, final String key, final boolean newValue, Function1<? super Boolean, Unit> setViewChecked) {
        setViewChecked.invoke(Boolean.valueOf(newValue));
        item.setChecked(newValue);
        SettingItem settingItem = item.settingItem;
        SwitchSetting switchSetting = settingItem instanceof SwitchSetting ? (SwitchSetting) settingItem : null;
        final PyObject onChangeCallback = switchSetting != null ? switchSetting.getOnChangeCallback() : null;
        PluginsController.Companion.runOnPluginsQueue(new Runnable() {
            @Override
            public final void run() {
                PluginSettingsActivity.$r8$lambda$gAEBM3Bjop7AmlJJRHsVjP6iksk(PluginSettingsActivity.this, key, newValue, onChangeCallback);
            }
        });
    }

    public static void $r8$lambda$gAEBM3Bjop7AmlJJRHsVjP6iksk(PluginSettingsActivity pluginSettingsActivity, String str, boolean z, PyObject pyObject) {
        PluginsController companion = PluginsController.Companion.getInstance();
        Plugin plugin = pluginSettingsActivity.plugin;
        if (plugin == null) {
            plugin = null;
        }
        companion.setPluginSettingAndTriggerOnChange(plugin.getId(), str, Boolean.valueOf(z), pyObject);
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        SettingItem settingItem;
        CustomSetting.Factory<?> factory;
        PluginsController companion = PluginsController.Companion.getInstance();
        Plugin plugin = this.plugin;
        Plugin plugin2 = null;
        if (plugin == null) {
            plugin = null;
        }
        if (!companion.isPluginActive(plugin) || (settingItem = item.settingItem) == null) {
            return false;
        }
        PyObject onLongClickCallback = settingItem.getOnLongClickCallback();
        if (onLongClickCallback != null) {
            try {
                onLongClickCallback.call(view);
            } catch (Exception unused) {
            }
            return true;
        }
        String linkAlias = settingItem.getLinkAlias();
        if (linkAlias != null && linkAlias.length() != 0) {
            Plugin plugin3 = this.plugin;
            if (plugin3 == null) {
                plugin3 = null;
            }
            String link = settingItem.getLink(plugin3.getId(), this.settingsLinkPrefix);
            if (link != null) {
                showCopyLinkOptions(view, link);
                return true;
            }
        }
        if (!(settingItem instanceof CustomSetting) || (factory = ((CustomSetting) settingItem).getFactory()) == null) {
            return false;
        }
        try {
            Plugin plugin4 = this.plugin;
            if (plugin4 != null) {
                plugin2 = plugin4;
            }
            factory.onLongClick(plugin2, item, view);
        } catch (Exception unused2) {
        }
        return true;
    }

    private final void showStringInputDialog(UItem item, final View view, final String key) {
        SettingItem settingItem = item.settingItem;
        final InputSetting inputSetting = settingItem instanceof InputSetting ? (InputSetting) settingItem : null;
        if (inputSetting == null || getParentActivity() == null) {
            return;
        }
        final Ref.ObjectRef objectRef = new Ref.ObjectRef();
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext(), getResourceProvider());
        builder.setTitle(item.text);
        LinearLayout linearLayout = new LinearLayout(getContext());
        linearLayout.setOrientation(1);
        String subtext = inputSetting.getSubtext();
        if (subtext != null) {
            TextView textView = new TextView(getContext());
            textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
            textView.setTextSize(1, 16.0f);
            textView.setText(getTranslation().text(subtext));
            linearLayout.addView(textView, LayoutHelper.createLinear(-1, -2, 24.0f, 5.0f, 24.0f, 12.0f));
        }
        final EditTextBoldCursor editTextBoldCursor = new EditTextBoldCursor(getContext());
        editTextBoldCursor.lineYFix = true;
        editTextBoldCursor.setTextSize(1, 18.0f);
        PluginsController companion = PluginsController.Companion.getInstance();
        Plugin plugin = this.plugin;
        if (plugin == null) {
            plugin = null;
        }
        editTextBoldCursor.setText(companion.getPluginSettingString(plugin.getId(), key, inputSetting.getDefaultValue()));
        editTextBoldCursor.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        editTextBoldCursor.setHintColor(Theme.getColor(Theme.key_groupcreate_hintText, getResourceProvider()));
        editTextBoldCursor.setHintText(LocaleController.getString(R.string.EnterValue));
        editTextBoldCursor.setFocusable(true);
        editTextBoldCursor.setInputType(147457);
        int i = Theme.key_windowBackgroundWhiteInputFieldActivated;
        editTextBoldCursor.setCursorColor(Theme.getColor(i, getResourceProvider()));
        editTextBoldCursor.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField, getResourceProvider()), Theme.getColor(i, getResourceProvider()), Theme.getColor(Theme.key_text_RedRegular, getResourceProvider()));
        editTextBoldCursor.setBackground(null);
        editTextBoldCursor.setPadding(0, AndroidUtilities.dp(6.0f), 0, AndroidUtilities.dp(6.0f));
        final Function0<Unit> function0 = () -> PluginSettingsActivity.m1578$r8$lambda$pUp2ILhhcyv5WxvS1CSPTqdrFI(editTextBoldCursor, objectRef, view, this, key, inputSetting);
        linearLayout.addView(editTextBoldCursor, LayoutHelper.createLinear(-1, -2, 24.0f, 0.0f, 24.0f, 10.0f));
        builder.makeCustomMaxHeight();
        builder.setView(linearLayout);
        builder.setWidth(AndroidUtilities.dp(292.0f));
        builder.setPositiveButton(LocaleController.getString(R.string.Done), new AlertDialog.OnButtonClickListener() {
            @Override
            public final void onClick(AlertDialog alertDialog, int i2) {
                function0.invoke();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), new AlertDialog.OnButtonClickListener() {
            @Override
            public final void onClick(AlertDialog alertDialog, int i2) {
                alertDialog.dismiss();
            }
        });
        AlertDialog Create = builder.create();
        Create.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public final void onDismiss(DialogInterface dialogInterface) {
                AndroidUtilities.hideKeyboard(editTextBoldCursor);
            }
        });
        Create.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public final void onShow(DialogInterface dialogInterface) {
                PluginSettingsActivity.$r8$lambda$JT5omKF85jr8iGVjIOakR0qPPKE(editTextBoldCursor, dialogInterface);
            }
        });
        Create.setDismissDialogByButtons(false);
        objectRef.element = Create;
        showDialog((Dialog) Create);
    }

    public static Unit m1578$r8$lambda$pUp2ILhhcyv5WxvS1CSPTqdrFI(EditTextBoldCursor editTextBoldCursor, Ref.ObjectRef objectRef, View view, final PluginSettingsActivity pluginSettingsActivity, final String str, final InputSetting inputSetting) {
        Editable text = editTextBoldCursor.getText();
        final String string = text != null ? text.toString() : "";
        AlertDialog alertDialog = (AlertDialog) objectRef.element;
        if (alertDialog != null) {
            alertDialog.dismiss();
        }
        ((TextCell) view).setValue(string, true);
        PluginsController.Companion.runOnPluginsQueue(new Runnable() {
            @Override
            public final void run() {
                PluginSettingsActivity.m1579$r8$lambda$0EPq7QsU6trt8nXerx8Tdh5Ql4(pluginSettingsActivity, str, string, inputSetting);
            }
        });
        return Unit.INSTANCE;
    }

    public static void m1579$r8$lambda$0EPq7QsU6trt8nXerx8Tdh5Ql4(PluginSettingsActivity pluginSettingsActivity, String str, String str2, InputSetting inputSetting) {
        PluginsController companion = PluginsController.Companion.getInstance();
        Plugin plugin = pluginSettingsActivity.plugin;
        if (plugin == null) {
            plugin = null;
        }
        companion.setPluginSettingAndTriggerOnChange(plugin.getId(), str, str2, inputSetting.getOnChangeCallback());
    }

    public static void $r8$lambda$JT5omKF85jr8iGVjIOakR0qPPKE(EditTextBoldCursor editTextBoldCursor, DialogInterface dialogInterface) {
        editTextBoldCursor.requestFocus();
        editTextBoldCursor.setSelection(editTextBoldCursor.length());
        AndroidUtilities.showKeyboard(editTextBoldCursor);
    }

    private final void showSelectorDialog(UItem item, final View view, String key) {
        SettingItem settingItem = item.settingItem;
        final SelectorSetting selectorSetting = settingItem instanceof SelectorSetting ? (SelectorSetting) settingItem : null;
        if (selectorSetting == null || getParentActivity() == null) {
            return;
        }
        final Ref.ObjectRef objectRef = new Ref.ObjectRef();
        LinearLayout linearLayout = new LinearLayout(getContext());
        linearLayout.setOrientation(1);
        String[] items = selectorSetting.getItems();
        final ArrayList<String> arrayList = new ArrayList<>(items.length);
        for (String str : items) {
            String strText = getTranslation().text(str);
            if (strText == null) {
                strText = "";
            }
            arrayList.add(strText);
        }
        PluginsController companion = PluginsController.Companion.getInstance();
        Plugin plugin = this.plugin;
        if (plugin == null) {
            plugin = null;
        }
        final String str2 = key;
        int pluginSettingInt = companion.getPluginSettingInt(plugin.getId(), str2, selectorSetting.getDefaultValue());
        int size = arrayList.size();
        for (int index = 0; index < size; index++) {
            final int i = index;
            RadioColorCell radioColorCell = new RadioColorCell(getParentActivity());
            radioColorCell.setPadding(AndroidUtilities.dp(4.0f), 0, AndroidUtilities.dp(4.0f), 0);
            radioColorCell.setCheckColor(Theme.getColor(Theme.key_radioBackground), Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
            radioColorCell.setTextAndValue((CharSequence) arrayList.get(i), pluginSettingInt == i);
            radioColorCell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
            linearLayout.addView(radioColorCell);
            radioColorCell.setOnClickListener(new View.OnClickListener() {
                @Override
                public final void onClick(View view2) {
                    PluginSettingsActivity.m1584$r8$lambda$FtON3Qfl_JLDF4AkVSaK3VuaU(objectRef, view, arrayList, i, PluginSettingsActivity.this, str2, selectorSetting, view2);
                }
            });
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(item.text);
        builder.setView(linearLayout);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog Create = builder.create();
        objectRef.element = Create;
        showDialog((Dialog) Create);
    }

    public static void m1584$r8$lambda$FtON3Qfl_JLDF4AkVSaK3VuaU(Ref.ObjectRef objectRef, View view, List list, final int i, final PluginSettingsActivity pluginSettingsActivity, final String str, final SelectorSetting selectorSetting, View view2) {
        Dialog dialog = (Dialog) objectRef.element;
        if (dialog != null) {
            dialog.dismiss();
        }
        ((TextCell) view).setValue((CharSequence) list.get(i), true);
        PluginsController.Companion.runOnPluginsQueue(new Runnable() {
            @Override
            public final void run() {
                PluginSettingsActivity.$r8$lambda$HBVQq8PwM6cRiVfKvot_s_07HcA(pluginSettingsActivity, str, i, selectorSetting);
            }
        });
    }

    public static void $r8$lambda$HBVQq8PwM6cRiVfKvot_s_07HcA(PluginSettingsActivity pluginSettingsActivity, String str, int i, SelectorSetting selectorSetting) {
        PluginsController companion = PluginsController.Companion.getInstance();
        Plugin plugin = pluginSettingsActivity.plugin;
        if (plugin == null) {
            plugin = null;
        }
        companion.setPluginSettingAndTriggerOnChange(plugin.getId(), str, Integer.valueOf(i), selectorSetting.getOnChangeCallback());
    }

    private final int getStableId(SettingItem item) {
        if (item instanceof SwitchSetting) {
            return Objects.hash(PluginsConstants.Settings.TYPE_SWITCH, ((SwitchSetting) item).getKey());
        }
        if (item instanceof InputSetting) {
            return Objects.hash(PluginsConstants.Settings.TYPE_INPUT, ((InputSetting) item).getKey());
        }
        if (item instanceof EditTextSetting) {
            return Objects.hash("edit", ((EditTextSetting) item).getKey());
        }
        if (item instanceof SelectorSetting) {
            return Objects.hash(PluginsConstants.Settings.TYPE_SELECTOR, ((SelectorSetting) item).getKey());
        }
        if (item instanceof HeaderSetting) {
            return Objects.hash(PluginsConstants.Settings.TYPE_HEADER, ((HeaderSetting) item).getText());
        }
        if (item instanceof DividerSetting) {
            return Objects.hash(PluginsConstants.Settings.TYPE_DIVIDER, ((DividerSetting) item).getText());
        }
        if (item instanceof TextSetting) {
            return Objects.hash("text", ((TextSetting) item).getText());
        }
        if (item instanceof CustomSetting) {
            CustomSetting customSetting = (CustomSetting) item;
            UItem item2 = customSetting.getItem();
            Integer identity;
            if (item2 != null) {
                identity = item2.id;
            } else {
                CustomSetting.Factory<?> factory = customSetting.getFactory();
                identity = factory != null ? factory.hashCode() : null;
            }
            PyObject factoryArgs = customSetting.getFactoryArgs();
            return Objects.hash(PluginsConstants.Settings.TYPE_CUSTOM, identity, factoryArgs != null ? Integer.valueOf(factoryArgs.hashCode()) : null);
        }
        return item.hashCode();
    }

    private final void openSubFragmentNative(final UItem item, final PyObject callback) {
        PluginsController.Companion.runOnPluginsQueue(new Runnable() {
            @Override
            public final void run() {
                PluginSettingsActivity.$r8$lambda$6Z6uxA2UOxlGL4PYCk_0i6kuoQo(PluginSettingsActivity.this, callback, item);
            }
        });
    }

    public static void $r8$lambda$6Z6uxA2UOxlGL4PYCk_0i6kuoQo(final PluginSettingsActivity pluginSettingsActivity, final PyObject pyObject, final UItem uItem) {
        PluginsController.Companion companion = PluginsController.Companion;
        PluginsController companion2 = companion.getInstance();
        Plugin plugin = pluginSettingsActivity.plugin;
        if (plugin == null) {
            plugin = null;
        }
        if (companion2.isPluginActive(plugin)) {
            try {
                PyObject pyObjectCall = pyObject.call(new Object[0]);
                if (pyObjectCall == null) {
                    return;
                }
                PluginsController.PluginsEngine pluginsEngine = companion.getEngines().get(PluginsConstants.PYTHON);
                PythonPluginsEngine pythonPluginsEngine = pluginsEngine instanceof PythonPluginsEngine ? (PythonPluginsEngine) pluginsEngine : null;
                if (pythonPluginsEngine == null) {
                    return;
                }
                final List<SettingItem> pySettingDefinitions = pythonPluginsEngine.parsePySettingDefinitions(pyObjectCall.asList());
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public final void run() {
                        PluginSettingsActivity.$r8$lambda$OFWPI4yzwIJOrUvW8Cepcz2YkHA(pySettingDefinitions, uItem, pluginSettingsActivity, pyObject);
                    }
                });
            } catch (Exception e) {
                FileLog.e("Error opening subfragment", e);
            }
        }
    }

    public static void $r8$lambda$OFWPI4yzwIJOrUvW8Cepcz2YkHA(List list, UItem uItem, PluginSettingsActivity pluginSettingsActivity, PyObject pyObject) {
        Object text;
        String name;
        Plugin plugin;
        if (list.isEmpty()) {
            return;
        }
        SettingItem settingItem = uItem.settingItem;
        TextSetting textSetting = settingItem instanceof TextSetting ? (TextSetting) settingItem : null;
        if (textSetting == null || (text = textSetting.getText()) == null) {
            text = uItem.text;
        }
        if (text == null || (name = text.toString()) == null) {
            name = pluginSettingsActivity.customTitle;
            if (name == null) {
                plugin = pluginSettingsActivity.plugin;
                if (plugin == null) {
                    plugin = null;
                }
                name = plugin.getName();
            }
        } else {
            if (name.length() <= 0) {
                name = null;
            }
            if (name == null) {
                name = pluginSettingsActivity.customTitle;
                if (name == null) {
                    plugin = pluginSettingsActivity.plugin;
                    if (plugin == null) {
                        plugin = null;
                    }
                    name = plugin.getName();
                }
            }
        }
        String str = name;
        String str2 = pluginSettingsActivity.settingsLinkPrefix;
        String str3 = "";
        if (str2 == null || str2.length() == 0) {
            SettingItem settingItem2 = uItem.settingItem;
            String linkAlias = settingItem2 != null ? settingItem2.getLinkAlias() : null;
            if (linkAlias != null) {
                str3 = linkAlias;
            }
        } else {
            String str4 = pluginSettingsActivity.settingsLinkPrefix;
            SettingItem settingItem3 = uItem.settingItem;
            String linkAlias2 = settingItem3 != null ? settingItem3.getLinkAlias() : null;
            if (linkAlias2 != null) {
                str3 = linkAlias2;
            }
            str3 = str4 + ":" + str3;
        }
        Plugin plugin2 = pluginSettingsActivity.plugin;
        pluginSettingsActivity.presentFragment(new PluginSettingsActivity(plugin2, str, list, pyObject, null).setSettingsLinkPrefix(str3));
    }
}
