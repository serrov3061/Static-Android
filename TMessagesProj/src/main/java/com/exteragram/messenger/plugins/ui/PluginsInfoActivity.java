package com.exteragram.messenger.plugins.ui;

import android.content.SharedPreferences;
import android.text.Html;
import android.text.SpannableString;
import android.view.View;
import androidx.core.util.Consumer;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.PythonPluginsEngine;
import com.exteragram.messenger.plugins.ui.components.UnknownSourcesBottomSheet;
import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.preferences.utils.SettingsRegistry;
import com.exteragram.messenger.utils.text.LocaleUtils;
import java.util.ArrayList;
import kotlin.Unit;
import kotlin.enums.EnumEntries;
import kotlin.enums.EnumEntriesKt;
import kotlin.jvm.functions.Function0;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

public final class PluginsInfoActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate {

    public static final class WhenMappings {
        public static final int[] $EnumSwitchMapping$0;

        static {
            int[] iArr = new int[PreferenceItem.values().length];
            try {
                iArr[PreferenceItem.DEVELOPER_MODE.ordinal()] = 1;
            } catch (NoSuchFieldError unused) {
            }
            try {
                iArr[PreferenceItem.COMPACT_VIEW.ordinal()] = 2;
            } catch (NoSuchFieldError unused2) {
            }
            try {
                iArr[PreferenceItem.UNKNOWN_SOURCES.ordinal()] = 3;
            } catch (NoSuchFieldError unused3) {
            }
            try {
                iArr[PreferenceItem.SAFE_MODE.ordinal()] = 4;
            } catch (NoSuchFieldError unused4) {
            }
            try {
                iArr[PreferenceItem.PLUGINS_DISABLE_ART_OPTS.ordinal()] = 5;
            } catch (NoSuchFieldError unused5) {
            }
            try {
                iArr[PreferenceItem.SDK_AUTO_UPDATE.ordinal()] = 6;
            } catch (NoSuchFieldError unused6) {
            }
            try {
                iArr[PreferenceItem.SDK_BETA_VERSIONS.ordinal()] = 7;
            } catch (NoSuchFieldError unused7) {
            }
            $EnumSwitchMapping$0 = iArr;
        }
    }

    public enum PreferenceItem {
        DEVELOPER_MODE,
        COMPACT_VIEW,
        SAFE_MODE,
        SDK_AUTO_UPDATE,
        SDK_BETA_VERSIONS,
        CHECK_SDK_UPDATES,
        RESTORE_SDK_FROM_APK,
        DOCUMENTATION,
        TRUSTED_PLUGINS,
        PLUGINS_DISABLE_ART_OPTS,
        SDK_HEADER,
        UNKNOWN_SOURCES,
        SENSITIVE_REQUESTS, // exteraGram 13.1.1
        STATIC_ANALYSIS;

        private static final EnumEntries $ENTRIES = EnumEntriesKt.enumEntries(values());

        public static EnumEntries<PreferenceItem> getEntries() {
            return $ENTRIES;
        }

        public final int getId() {
            return ordinal() + 1;
        }
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.PluginsEngine);
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginsPySdkInfoChanged);
        PythonPluginsEngine.Updater.Companion.setNotifyWhenChangeStatus(true);
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginsPySdkInfoChanged);
        PythonPluginsEngine.Updater.Companion companion = PythonPluginsEngine.Updater.Companion;
        companion.setNotifyWhenChangeStatus(false);
        if (companion.getStatus() == 2) {
            companion.setStatus(0);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.pluginsPySdkInfoChanged) {
            getListView$TMessagesProj().adapter.update(true);
        }
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.Settings)));
        items.add(UItem.asCheck(PreferenceItem.DEVELOPER_MODE.getId(), LocaleController.getString(R.string.PluginsDevMode), R.drawable.msg_settings).setChecked(ExteraConfig.getPluginsDevMode()).setEnabled(ExteraConfig.getPluginsEngine() && !ExteraConfig.getPluginsSafeMode()).setSearchable(this).setLinkAlias("pluginsDeveloperMode", this));
        items.add(UItem.asCheck(PreferenceItem.COMPACT_VIEW.getId(), LocaleController.getString(R.string.PluginsCompactView), R.drawable.msg_topics).setChecked(ExteraConfig.getPluginsCompactView()).setEnabled(ExteraConfig.getPluginsEngine()).setSearchable(this).setLinkAlias("pluginsCompactView", this));
        items.add(UItem.asCheck(PreferenceItem.PLUGINS_DISABLE_ART_OPTS.getId(), LocaleController.getString(R.string.PluginsDisableArt), R.drawable.msg_link2).setChecked(ExteraConfig.getPluginsDisableArtOpts()).setEnabled(ExteraConfig.getPluginsEngine()).setSearchable(this).setValue(LocaleController.getString(R.string.PluginsDisableArtInfo)).setMultiline(true).setLinkAlias("pluginsDisableArtOpts", this));
        items.add(UItem.asCheck(PreferenceItem.UNKNOWN_SOURCES.getId(), LocaleController.getString(R.string.PluginsUnknownSources), R.drawable.msg_download).setChecked(ExteraConfig.getPluginsUnknownSources()).setEnabled(ExteraConfig.getPluginsEngine()).setSearchable(this).setValue(LocaleController.getString(R.string.PluginsUnknownSourcesInfo2)).setMultiline(true).setLinkAlias("pluginsUnknownSources", this));
        // exteraGram 13.1.1: check the plugin code before installing
        items.add(UItem.asCheck(PreferenceItem.STATIC_ANALYSIS.getId(), LocaleController.getString(R.string.PluginsStaticAnalysis), R.drawable.msg_policy).setChecked(ExteraConfig.getPluginsStaticAnalysis()).setEnabled(ExteraConfig.getPluginsEngine()).setSearchable(this).setValue(LocaleController.getString(R.string.PluginsStaticAnalysisInfo)).setMultiline(true).setLinkAlias("pluginsStaticAnalysis", this));
        // exteraGram 13.1.1: confirmations for sensitive actions started by plugins
        final int sensitiveState = com.exteragram.messenger.security.SensitiveRequestSwitch.getState();
        final String sensitiveInfo;
        if (sensitiveState == com.exteragram.messenger.security.SensitiveRequestSwitch.STATE_PENDING) {
            sensitiveInfo = LocaleController.formatString(R.string.SensitiveRequestsConfirmPending, com.exteragram.messenger.security.SensitiveRequestSwitch.formatDisableAt());
        } else if (sensitiveState == com.exteragram.messenger.security.SensitiveRequestSwitch.STATE_DISABLED) {
            sensitiveInfo = LocaleController.getString(R.string.SensitiveRequestsConfirmDisabled);
        } else {
            sensitiveInfo = LocaleController.getString(R.string.SensitiveRequestsConfirmInfo);
        }
        items.add(UItem.asCheck(PreferenceItem.SENSITIVE_REQUESTS.getId(), LocaleController.getString(R.string.SensitiveRequestsConfirm), R.drawable.msg_permissions).setChecked(sensitiveState != com.exteragram.messenger.security.SensitiveRequestSwitch.STATE_DISABLED).setSearchable(this).setValue(sensitiveInfo).setMultiline(true).setLinkAlias("sensitiveRequestConfirmations", this));
        items.add(UItem.asCheck(PreferenceItem.SAFE_MODE.getId(), LocaleController.getString(R.string.PluginsSafeMode), R.drawable.msg_secret).setChecked(ExteraConfig.getPluginsSafeMode()).setSearchable(this).setLinkAlias("pluginsSafeMode", this));
        items.add(UItem.asShadow(LocaleController.getString(R.string.PluginsSafeModeInfo2)));
        items.add(UItem.asHeader(PreferenceItem.SDK_HEADER.getId(), SettingsRegistry.markAsNewFeature("Plugins-Python-SDK") ? LocaleUtils.applyNewSpan("Python SDK") : "Python SDK"));
        UItem searchable = UItem.asCheck(PreferenceItem.SDK_AUTO_UPDATE.getId(), LocaleController.getString(R.string.PluginsPySdkAutoUpdate)).setChecked(ExteraConfig.getPluginsPySdkAutoUpdate()).setSearchable(this);
        PythonPluginsEngine.Updater.Companion companion = PythonPluginsEngine.Updater.Companion;
        items.add(searchable.setEnabled(companion.getStatus() < 3).setValue(companion.getStateString()).setMultiline(true).setLinkAlias("pluginsPySdkAutoUpdate", this));
        items.add(UItem.asCheck(PreferenceItem.SDK_BETA_VERSIONS.getId(), LocaleController.getString(R.string.PluginsPySdkEnableBetaVersion)).setChecked(ExteraConfig.getPluginsPySdkBetaVersions()).setSearchable(this).setEnabled(companion.getStatus() < 3).setLinkAlias("pluginsPySdkBetaVersions", this));
        items.add(UItem.asButton(PreferenceItem.CHECK_SDK_UPDATES.getId(), LocaleController.getString(R.string.PluginsPySdkCheckUpdates)).accent().setSearchable(this).setEnabled(companion.getStatus() < 3).setIcon(R.drawable.msg_retry).setLinkAlias("pluginsPySdkCheckUpdates", this));
        if (ExteraConfig.getPluginsDevMode() && !ExteraConfig.getPluginsEngine() && !companion.isSdkFromApk()) {
            items.add(UItem.asButton(PreferenceItem.RESTORE_SDK_FROM_APK.getId(), LocaleController.getString(R.string.RestoreSdkFromApk)).red().setIcon(R.drawable.msg_reset));
        }
        items.add(UItem.asShadow());
        items.add(UItem.asHeader(LocaleController.getString(R.string.Links)));
        items.add(UItem.asButton(PreferenceItem.DOCUMENTATION.getId(), LocaleController.getString(R.string.PluginsDocumentation)).setSearchable(this).setIcon(R.drawable.menu_intro).setLinkAlias("pluginsDocumentation", this));
        // StaticGram: no "Trusted plugins" link (owner decision)
        items.add(UItem.asShadow(LocaleUtils.formatWithHtmlURLs(new SpannableString(Html.fromHtml(LocaleController.getString(R.string.PluginsPoweredBy), 0)))));
    }

    @Override
    public void onClick(final UItem item, View view, int position, float x, float y) {
        int i = item.id;
        if (i <= 0 || i > PreferenceItem.getEntries().size()) {
            return;
        }
        PreferenceItem preferenceItem = PreferenceItem.getEntries().get(item.id - 1);
        if (preferenceItem == PreferenceItem.STATIC_ANALYSIS) { // exteraGram 13.1.1
            if (!ExteraConfig.getPluginsEngine()) return;
            ExteraConfig.setPluginsStaticAnalysis(!ExteraConfig.getPluginsStaticAnalysis());
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(ExteraConfig.getPluginsStaticAnalysis());
            return;
        }
        if (preferenceItem == PreferenceItem.SENSITIVE_REQUESTS) { // exteraGram 13.1.1
            if (com.exteragram.messenger.security.SensitiveRequestSwitch.getState() == com.exteragram.messenger.security.SensitiveRequestSwitch.STATE_ENABLED) {
                com.exteragram.messenger.security.SensitiveRequestSwitch.requestDisable(this, () -> getListView$TMessagesProj().adapter.update(true));
            } else {
                com.exteragram.messenger.security.SensitiveRequestSwitch.enforce();
                getListView$TMessagesProj().adapter.update(true);
            }
            return;
        }

        if ((view instanceof TextCheckCell) && (ExteraConfig.getPluginsEngine() || preferenceItem == PreferenceItem.SAFE_MODE || preferenceItem == PreferenceItem.SDK_AUTO_UPDATE || preferenceItem == PreferenceItem.SDK_BETA_VERSIONS)) {
            switch (WhenMappings.$EnumSwitchMapping$0[preferenceItem.ordinal()]) {
                case 1:
                    toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() {
                        @Override
                        public final void accept(Object obj) {
                            PluginsInfoActivity.$r8$lambda$MNahqCzadSU3IcAO5MTHf7QGiw4(PluginsInfoActivity.this, ((Boolean) obj).booleanValue());
                        }
                    });
                    break;
                case 2:
                    toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() {
                        @Override
                        public final void accept(Object obj) {
                            PluginsInfoActivity.$r8$lambda$jyKoHtubfEZWYmGRcWJUtJHu3g0(((Boolean) obj).booleanValue());
                        }
                    });
                    break;
                case 3:
                    if (ExteraConfig.getPluginsUnknownSources()) {
                        toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() {
                            @Override
                            public final void accept(Object obj) {
                                ExteraConfig.setPluginsUnknownSources(((Boolean) obj).booleanValue());
                            }
                        });
                    } else {
                        new UnknownSourcesBottomSheet(this, new Function0() {
                            @Override
                            public final Object invoke() {
                                return PluginsInfoActivity.$r8$lambda$jZWitV_uc5kJ6EDjZA6AlqmjReQ(PluginsInfoActivity.this, item);
                            }
                        }).show();
                    }
                    break;
                case 4:
                    final SharedPreferences preferences = PluginsController.Companion.getInstance().getPreferences();
                    toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() {
                        @Override
                        public final void accept(Object obj) {
                            PluginsInfoActivity.m1594$r8$lambda$75FvA8Wx6_ammvoVdr0N3hTJI(preferences, ((Boolean) obj).booleanValue());
                        }
                    });
                    break;
                case 5:
                    toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() {
                        @Override
                        public final void accept(Object obj) {
                            PluginsInfoActivity.$r8$lambda$oRmSB9poUPcKGSoweQ5zECk8nLc(PluginsInfoActivity.this, ((Boolean) obj).booleanValue());
                        }
                    });
                    break;
                case 6:
                    toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() {
                        @Override
                        public final void accept(Object obj) {
                            ExteraConfig.setPluginsPySdkAutoUpdate(((Boolean) obj).booleanValue());
                        }
                    });
                    break;
                case 7:
                    toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() {
                        @Override
                        public final void accept(Object obj) {
                            PluginsInfoActivity.m1595$r8$lambda$ZKxgRHkSxBXEH09P5GrwvXrtn0(((Boolean) obj).booleanValue());
                        }
                    });
                    break;
            }
            return;
        }
        PreferenceItem preferenceItem2 = PreferenceItem.DOCUMENTATION;
        if (preferenceItem == preferenceItem2 || preferenceItem == PreferenceItem.TRUSTED_PLUGINS) {
            com.exteragram.messenger.utils.ExternalTelegram.openUrl(getParentActivity(), preferenceItem == preferenceItem2 ? "https://plugins.exteragram.app/" : "https://t.me/addlist/pPhOtEq00KhjYTc6");
            return;
        }
        if (preferenceItem == PreferenceItem.CHECK_SDK_UPDATES) {
            PythonPluginsEngine.Updater.Companion.checkUpdates(true);
        } else if (preferenceItem == PreferenceItem.RESTORE_SDK_FROM_APK) {
            PythonPluginsEngine.Updater.Companion.restoreSdkFromApk();
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.RestartRequired)).show();
            getListView$TMessagesProj().adapter.update(true);
        }
    }

    public static void $r8$lambda$MNahqCzadSU3IcAO5MTHf7QGiw4(PluginsInfoActivity pluginsInfoActivity, boolean z) {
        ExteraConfig.setPluginsDevMode(z);
        PluginsController.Companion.getInstance().checkDevServers();
        BulletinFactory.of(pluginsInfoActivity).createSimpleBulletin(ExteraConfig.getPluginsDevMode() ? R.raw.contact_check : R.raw.error, LocaleController.getString(ExteraConfig.getPluginsDevMode() ? R.string.PluginsDevServerLaunched : R.string.PluginsDevServerStopped)).show();
    }

    public static void $r8$lambda$jyKoHtubfEZWYmGRcWJUtJHu3g0(boolean z) {
        ExteraConfig.setPluginsCompactView(z);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface, new Object[0]);
    }

    public static Unit $r8$lambda$jZWitV_uc5kJ6EDjZA6AlqmjReQ(PluginsInfoActivity pluginsInfoActivity, UItem uItem) {
        pluginsInfoActivity.toggleBooleanSettingAndRefresh$TMessagesProj(uItem, new Consumer() {
            @Override
            public final void accept(Object obj) {
                ExteraConfig.setPluginsUnknownSources(((Boolean) obj).booleanValue());
            }
        });
        return Unit.INSTANCE;
    }

    public static void m1594$r8$lambda$75FvA8Wx6_ammvoVdr0N3hTJI(SharedPreferences sharedPreferences, boolean z) {
        ExteraConfig.setPluginsSafeMode(z);
        SharedPreferences.Editor editorEdit = sharedPreferences.edit();
        if (z) {
            editorEdit.putString("crashed_plugin_id", "manual!");
        } else {
            editorEdit.remove("crashed_plugin_id");
        }
        editorEdit.apply();
        PluginsController.Companion.getInstance().restart(z);
    }

    public static void $r8$lambda$oRmSB9poUPcKGSoweQ5zECk8nLc(PluginsInfoActivity pluginsInfoActivity, boolean z) {
        ExteraConfig.setPluginsDisableArtOpts(z);
        PluginsController.Companion.applyArtOpts();
        pluginsInfoActivity.showRestartBulletin();
    }

    public static void m1595$r8$lambda$ZKxgRHkSxBXEH09P5GrwvXrtn0(boolean z) {
        ExteraConfig.setPluginsPySdkBetaVersions(z);
        PythonPluginsEngine.Updater.Companion.checkUpdates(true);
    }
}
