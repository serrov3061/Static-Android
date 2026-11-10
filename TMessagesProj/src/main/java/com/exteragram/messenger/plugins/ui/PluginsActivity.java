package com.exteragram.messenger.plugins.ui;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.widget.FrameLayout;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.plugins.OfficialPlugins;
import com.exteragram.messenger.plugins.Plugin;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.catalog.ui.PluginRepositoriesActivity;
import com.exteragram.messenger.plugins.ui.components.EmptyPluginsView;
import com.exteragram.messenger.plugins.ui.components.PluginCell;
import com.exteragram.messenger.plugins.ui.components.PluginCellDelegate;
import com.exteragram.messenger.plugins.utils.PluginsWatchdog;
import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.utils.text.LocaleUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;
import kotlin.collections.CollectionsKt;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.internal.FunctionReferenceImpl;
import kotlin.text.StringsKt;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProviderThemed;
import org.telegram.utils.glass.source.GlassSourceColor;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import me.vkryl.android.animator.BoolAnimator;

public final class PluginsActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate {
    private EmptyPluginsView emptyView;
    private ActionBarMenuItem infoItem;
    private boolean isSwitchingEngineState;
    private String query;
    private ActionBarMenuItem searchItem;
    private boolean searching;
    // StaticGram: official plugin ids being installed right now
    private final java.util.HashSet<String> installingOfficial = new java.util.HashSet<>();
    // exteraGram 13.1.1 plugin catalog: floating "Plugin Catalog" button
    private static final float CATALOG_BUTTON_HEIGHT = 60.0f;
    private ProfileActivity.Button2 catalogButton;
    private int catalogButtonInset;
    private boolean catalogButtonScrollShown = true;
    private boolean emptyViewShown;
    private final BoolAnimator catalogButtonVisibility = new BoolAnimator(0, (id, factor, fraction, callee) -> updateCatalogButtonPosition(), CubicBezierInterpolator.EASE_OUT_QUINT, 380, true);

    @Override
    public View createView(Context context) {
        View viewCreateView = super.createView(context);
        ActionBarMenuItem actionBarMenuItemSearchListener = this.actionBar.menu.addItem(0, R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchExpand() {
                PluginsActivity.this.searching = true;
                PluginsActivity.this.updateCatalogButton(true);
                PluginsActivity.this.getListView$TMessagesProj().adapter.update(true);
                PluginsActivity.this.getListView$TMessagesProj().scrollToPosition(0);
                ActionBarMenuItem actionBarMenuItem = PluginsActivity.this.infoItem;
                if (actionBarMenuItem != null) {
                    actionBarMenuItem.setVisibility(8);
                }
            }

            @Override
            public void onSearchCollapse() {
                PluginsActivity.this.searching = false;
                PluginsActivity.this.query = null;
                PluginsActivity.this.updateCatalogButton(true);
                PluginsActivity.this.getListView$TMessagesProj().adapter.update(true);
                PluginsActivity.this.getListView$TMessagesProj().scrollToPosition(0);
                ActionBarMenuItem actionBarMenuItem = PluginsActivity.this.infoItem;
                if (actionBarMenuItem != null) {
                    actionBarMenuItem.setVisibility(0);
                }
            }

            @Override
            public void onTextChanged(EditText editText) {
                PluginsActivity.this.query = editText.getText().toString();
                PluginsActivity.this.getListView$TMessagesProj().adapter.update(true);
                PluginsActivity.this.getListView$TMessagesProj().scrollToPosition(0);
            }
        });
        this.searchItem = actionBarMenuItemSearchListener;
        if (actionBarMenuItemSearchListener != null) {
            actionBarMenuItemSearchListener.setSearchPaddingStart(7);
        }
        ActionBarMenuItem actionBarMenuItem = this.searchItem;
        if (actionBarMenuItem != null) {
            actionBarMenuItem.setSearchFieldHint(LocaleController.getString(R.string.Search));
        }
        AndroidUtilities.updateViewVisibilityAnimated(this.searchItem, ExteraConfig.getPluginsEngine() && !PluginsController.Companion.getInstance().getPlugins().isEmpty(), 0.5f, false);
        ActionBarMenuItem actionBarMenuItemAddItem = this.actionBar.menu.addItem(1, R.drawable.msg_info);
        this.infoItem = actionBarMenuItemAddItem;
        if (actionBarMenuItemAddItem != null) {
            actionBarMenuItemAddItem.setOnClickListener(new View.OnClickListener() {
                @Override
                public final void onClick(View view) {
                    PluginsActivity.this.presentFragment(new PluginsInfoActivity());
                }
            });
        }
        createCatalogButton(context, viewCreateView);
        getListView$TMessagesProj().setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == 1) {
                    AndroidUtilities.hideKeyboard(PluginsActivity.this.getParentActivity().getCurrentFocus());
                }
            }

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (dy != 0) {
                    catalogButtonScrollShown = dy < 0;
                    updateCatalogButton(true);
                }
            }
        });
        this.fragmentView = viewCreateView;
        return viewCreateView;
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.Plugins);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        Plugin plugin;
        emptyViewShown = false;
        if (!this.searching) {
            items.add(UItem.asRippleCheck(0, LocaleController.getString(R.string.EnablePluginsEngine)).setChecked(ExteraConfig.getPluginsEngine()));
        }
        if (ExteraConfig.getPluginsEngine()) {
            HashMap<String, Plugin> map = new HashMap<>(PluginsController.Companion.getInstance().getPlugins());
            UItem uItemAsSpace = UItem.asSpace(AndroidUtilities.dp(8.0f));
            uItemAsSpace.transparent = true;
            items.add(uItemAsSpace);
            if (this.searching && !TextUtils.isEmpty(this.query)) {
                map.values().removeIf(p -> PluginsActivity.$r8$lambda$TEMUUXesJLeJaCYdM8utDdAnU1s(this, p));
            }
            boolean hasOfficial = fillOfficialItems(items);
            if (map.isEmpty() && hasOfficial) {
                // the Official block replaces the empty placeholder
            } else if (map.isEmpty()) {
                if (this.emptyView == null) {
                    this.emptyView = new EmptyPluginsView(getContext(), getResourceProvider());
                }
                EmptyPluginsView emptyPluginsView = this.emptyView;
                if (emptyPluginsView == null) {
                    return;
                }
                if (this.searching) {
                    if (emptyPluginsView.getTag() == null || ((Integer) emptyPluginsView.getTag()).intValue() != 1) {
                        MediaDataController.getInstance(UserConfig.selectedAccount).setPlaceholderImage(emptyPluginsView.getBackupImageView(), "AnimatedEmojies", "🔎", "100_100");
                        emptyPluginsView.setTitle(null);
                        emptyPluginsView.setText(LocaleController.getString(R.string.PluginsNotFound));
                        emptyPluginsView.setButton(null, null);
                        emptyPluginsView.setTag(1);
                    }
                } else if (emptyPluginsView.getTag() == null || ((Integer) emptyPluginsView.getTag()).intValue() != 2) {
                    MediaDataController.getInstance(UserConfig.selectedAccount).setPlaceholderImage(emptyPluginsView.getBackupImageView(), "AnimatedEmojies", "📂", "100_100");
                    emptyPluginsView.setTitle(LocaleController.getString(R.string.PluginsEmptyTitle));
                    emptyPluginsView.setText(LocaleUtils.formatWithUsernames(LocaleController.getString(R.string.PluginsEmptyInfo)));
                    emptyPluginsView.setButton(LocaleController.getString(R.string.PluginCatalogOpen), this::openCatalog);
                    emptyPluginsView.setTag(2);
                }
                emptyViewShown = !this.searching;
                items.add(UItem.asFullscreenCustom(emptyPluginsView, AndroidUtilities.dp((this.searching ? 2 : 1) * 74), true).setTransparent(true));
            } else {
                if (!ExteraConfig.getPinnedPlugins().isEmpty()) {
                    for (String str : ExteraConfig.getPinnedPlugins()) {
                        if (map.containsKey(str) && (plugin = map.get(str)) != null) {
                            items.add(createPluginItem(plugin));
                            items.add(UItem.asSpace(AndroidUtilities.dp(8.0f)));
                        }
                    }
                }
                ArrayList<Plugin> arrayList = new ArrayList<>(map.values());
                java.util.Collections.sort(arrayList, (a, b) -> a.getName().compareTo(b.getName()));
                for (Plugin plugin2 : arrayList) {
                    if (!PluginsController.Companion.isPluginPinned(plugin2.getId())) {
                        items.add(createPluginItem(plugin2));
                        items.add(UItem.asSpace(AndroidUtilities.dp(8.0f)));
                    }
                }
            }
            // room for the floating catalog button
            UItem uItemAsSpace2 = UItem.asSpace(AndroidUtilities.dp((this.searching || emptyViewShown) ? 4.0f : 52.0f));
            uItemAsSpace2.transparent = true;
            items.add(uItemAsSpace2);
        } else if (!this.searching) {
            // StaticGram: the Official block is visible with the engine off too; installing
            // one turns the engine on first (installOfficial)
            UItem space = UItem.asSpace(AndroidUtilities.dp(8.0f));
            space.transparent = true;
            items.add(space);
            fillOfficialItems(items);
        }
    }

    /** StaticGram: "Official plugins" block with the bundled plugins that are not installed yet. */
    private boolean fillOfficialItems(ArrayList<UItem> items) {
        java.util.List<OfficialPlugins.Entry> official = OfficialPlugins.getNotInstalled();
        if (this.searching && !TextUtils.isEmpty(this.query)) {
            String q = this.query.toLowerCase(Locale.ROOT);
            official.removeIf(e -> !e.getName().toLowerCase(Locale.ROOT).contains(q) && !e.getDescription().toLowerCase(Locale.ROOT).contains(q));
        }
        if (official.isEmpty()) {
            return false;
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.PluginsOfficial)));
        for (int i = 0; i < official.size(); i++) {
            OfficialPlugins.Entry e = official.get(i);
            UItem item = UItem.asButtonWithSubtext(OFFICIAL_ITEM_ID_BASE + i, e.getIconResId(), e.getName(),
                    installingOfficial.contains(e.id) ? LocaleController.getString(R.string.PluginsOfficialInstalling) : e.getDescription(), 64, 60);
            item.object = e;
            items.add(item);
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.PluginsOfficialInfo)));
        return true;
    }

    private static final int OFFICIAL_ITEM_ID_BASE = 100000;

    private void installOfficial(OfficialPlugins.Entry entry) {
        if (installingOfficial.contains(entry.id)) {
            return;
        }
        installingOfficial.add(entry.id);
        getListView$TMessagesProj().adapter.update(true);
        if (!ExteraConfig.getPluginsEngine()) {
            // StaticGram: an official plugin needs the engine; switch it on, then install
            ExteraConfig.setPluginsEngine(true);
            ExteraConfig.getEditor().putBoolean("pluginsEngine", true).apply();
            PluginsController.Companion.getInstance().init(() -> AndroidUtilities.runOnUIThread(() -> {
                installingOfficial.remove(entry.id);
                if (fragmentView == null || getParentActivity() == null) {
                    return;
                }
                AndroidUtilities.updateViewVisibilityAnimated(this.searchItem, !PluginsController.Companion.getInstance().getPlugins().isEmpty(), 0.5f, true);
                installOfficial(entry);
            }));
            return;
        }
        OfficialPlugins.install(entry, error -> {
            installingOfficial.remove(entry.id);
            if (fragmentView == null || getParentActivity() == null) {
                return;
            }
            getListView$TMessagesProj().adapter.update(true);
            if (com.exteragram.messenger.plugins.analyzer.PluginAnalysisGate.DECLINED.equals(error)) {
                return; // closed the install sheet
            }
            if (error == null && com.exteragram.messenger.plugins.analyzer.PluginAnalysisGate.isActive()) {
                return; // the install sheet showed its own result
            }
            if (error == null) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, AndroidUtilities.replaceTags(LocaleController.formatString(R.string.PluginInstalled, entry.getName()))).show();
            } else {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, AndroidUtilities.replaceTags(LocaleController.formatString(R.string.PluginInstallError, entry.getName())), LocaleUtils.createCopySpan(this), () -> {
                    if (AndroidUtilities.addToClipboard(error)) {
                        BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                    }
                }).show();
            }
        });
    }

    public static boolean $r8$lambda$TEMUUXesJLeJaCYdM8utDdAnU1s(PluginsActivity pluginsActivity, Plugin plugin) {
        String name = plugin.getName();
        Locale locale = Locale.ROOT;
        return !StringsKt.contains((CharSequence) name.toLowerCase(locale), (CharSequence) pluginsActivity.query.toLowerCase(locale), false);
    }

    /** Actions of a plugin cell in the list. */
    private final class PluginItemDelegate implements PluginCellDelegate {
        private final Plugin $plugin;

        PluginItemDelegate(Plugin plugin) {
            this.$plugin = plugin;
        }

        @Override
        public void sharePlugin() {
            PluginsController.PluginsEngine pluginEngine = PluginsController.Companion.getInstance().getPluginEngine(this.$plugin.getId());
            if (pluginEngine != null) {
                pluginEngine.sharePlugin(this.$plugin.getId());
            }
        }

        @Override
        public void openInExternalApp() {
            PluginsController.PluginsEngine pluginEngine = PluginsController.Companion.getInstance().getPluginEngine(this.$plugin.getId());
            if (pluginEngine != null) {
                pluginEngine.openInExternalApp(this.$plugin.getId());
            }
        }

        @Override
        public void deletePlugin() {
            if (this.$plugin.isNotResponding()) {
                PluginsWatchdog.Companion.showNotRespondingAlert(this.$plugin);
                return;
            }
            AlertDialog.Builder message = new AlertDialog.Builder(PluginsActivity.this.getParentActivity(), PluginsActivity.this.getResourceProvider()).setTitle(LocaleController.getString(R.string.PluginDelete)).setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.PluginDeleteInfo, this.$plugin.getName())));
            String string = LocaleController.getString(R.string.Delete);
            final Plugin plugin = this.$plugin;
            final PluginsActivity pluginsActivity = PluginsActivity.this;
            AlertDialog alertDialogCreate = message.setPositiveButton(string, new AlertDialog.OnButtonClickListener() {
                @Override
                public final void onClick(AlertDialog alertDialog, int i) {
                    PluginsController.Companion.getInstance().deletePlugin(plugin.getId(), error -> AndroidUtilities.runOnUIThread(() -> onPluginDeleted(pluginsActivity, error)));
                }
            }).setNegativeButton(LocaleController.getString(R.string.Cancel), null).create();
            alertDialogCreate.show();
            View button = alertDialogCreate.getButton(-1);
            TextView textView = button instanceof TextView ? (TextView) button : null;
            if (textView != null) {
                textView.setTextColor(Theme.getColor(Theme.key_text_RedBold));
            }
        }

        private void onPluginDeleted(PluginsActivity pluginsActivity, String str) {
            if (pluginsActivity.fragmentView == null || pluginsActivity.getParentActivity() == null) {
                return;
            }
            pluginsActivity.getListView$TMessagesProj().adapter.update(true);
            if (str != null) {
                BulletinFactory.of(pluginsActivity).createSimpleBulletin(R.raw.error, str).show();
            }
        }

        @Override
        public void togglePlugin(View view) {
            if (this.$plugin.isNotResponding()) {
                PluginsWatchdog.Companion.showNotRespondingAlert(this.$plugin);
                return;
            }
            final PluginCell pluginCell = (PluginCell) view;
            final boolean z = !this.$plugin.isEnabled();
            PluginsController companion = PluginsController.Companion.getInstance();
            String id = this.$plugin.getId();
            final PluginsActivity pluginsActivity = PluginsActivity.this;
            final Plugin plugin = this.$plugin;
            companion.setPluginEnabled(id, z, error -> AndroidUtilities.runOnUIThread(() -> onPluginToggled(pluginsActivity, error, z, plugin, pluginCell)));
        }

        private void onPluginToggled(final PluginsActivity pluginsActivity, final String str, boolean z, Plugin plugin, PluginCell pluginCell) {
            if (pluginsActivity.fragmentView == null || pluginsActivity.getParentActivity() == null) {
                return;
            }
            if (com.exteragram.messenger.plugins.analyzer.PluginAnalysisGate.DECLINED.equals(str)) {
                return; // the user closed the static analysis without enabling
            }
            if (str != null) {
                BulletinFactory.of(pluginsActivity).createSimpleBulletin(R.raw.error, LocaleController.formatString(z ? R.string.PluginEnableError : R.string.PluginDisableError, plugin.getName()), LocaleUtils.createCopySpan(pluginsActivity), new Runnable() {
                    @Override
                    public final void run() {
                        copyError(str, pluginsActivity);
                    }
                }).show();
            } else {
                pluginCell.setChecked(z, true);
            }
        }

        private void copyError(String str, PluginsActivity pluginsActivity) {
            if (AndroidUtilities.addToClipboard(str)) {
                BulletinFactory.of(pluginsActivity).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
            }
        }

        @Override
        public void openPluginSettings() {
            PluginsController.Companion.openPluginSettings(this.$plugin.getId());
        }

        @Override
        public void pinPlugin(View view) {
            PluginsController.Companion companion = PluginsController.Companion;
            boolean zIsPluginPinned = companion.isPluginPinned(this.$plugin.getId());
            companion.setPluginPinned(this.$plugin.getId(), !zIsPluginPinned);
            ((PluginCell) view).setPinned(!zIsPluginPinned);
            PluginsActivity.this.getListView$TMessagesProj().adapter.update(true);
            PluginsActivity.this.getListView$TMessagesProj().smoothScrollToPosition(0);
        }

        @Override
        public boolean canOpenInExternalApp() {
            PluginsController.PluginsEngine pluginEngine = PluginsController.Companion.getInstance().getPluginEngine(this.$plugin.getId());
            return pluginEngine != null && pluginEngine.canOpenInExternalApp();
        }
    }

    private final UItem createPluginItem(Plugin plugin) {
        return PluginCell.Factory.Companion.asPlugin(plugin, new PluginItemDelegate(plugin));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item.viewType == 9) {
            togglePluginsEngine(view, item);
        } else if (item.object instanceof OfficialPlugins.Entry) {
            installOfficial((OfficialPlugins.Entry) item.object);
        }
    }

    private final void togglePluginsEngine(View view, UItem item) {
        if (this.isSwitchingEngineState) {
            return;
        }
        this.isSwitchingEngineState = true;
        ExteraConfig.setPluginsEngine(true ^ ExteraConfig.getPluginsEngine());
        ExteraConfig.getEditor().putBoolean("pluginsEngine", ExteraConfig.getPluginsEngine()).apply();
        TextCheckCell textCheckCell = (TextCheckCell) view;
        boolean pluginsEngine = ExteraConfig.getPluginsEngine();
        item.checked = pluginsEngine;
        textCheckCell.setChecked(pluginsEngine);
        textCheckCell.setBackgroundColorAnimated(ExteraConfig.getPluginsEngine(), Theme.getColor(ExteraConfig.getPluginsEngine() ? Theme.key_windowBackgroundChecked : Theme.key_windowBackgroundUnchecked));
        Runnable runnable = () -> AndroidUtilities.runOnUIThread(() -> PluginsActivity.$r8$lambda$eZWok32IQObkKLTATWSLUxE8JkE(this));
        if (ExteraConfig.getPluginsEngine()) {
            PluginsController.Companion.getInstance().init(runnable);
        } else {
            PluginsController.Companion.getInstance().shutdown(runnable);
        }
    }

    public static void $r8$lambda$eZWok32IQObkKLTATWSLUxE8JkE(PluginsActivity pluginsActivity) {
        if (pluginsActivity.fragmentView == null || pluginsActivity.getParentActivity() == null) {
            return;
        }
        if (pluginsActivity.searching) {
            pluginsActivity.actionBar.closeSearchField();
        }
        AndroidUtilities.updateViewVisibilityAnimated(pluginsActivity.searchItem, ExteraConfig.getPluginsEngine() && !PluginsController.Companion.getInstance().getPlugins().isEmpty(), 0.5f, true);
        pluginsActivity.getListView$TMessagesProj().adapter.update(true);
        pluginsActivity.updateCatalogButton(true);
        pluginsActivity.isSwitchingEngineState = false;
    }

    // region exteraGram 13.1.1 plugin catalog button

    private void createCatalogButton(Context context, View contentView) {
        SpannableStringBuilder text = new SpannableStringBuilder("C " + LocaleController.getString(R.string.PluginCatalog));
        text.setSpan(new ColoredImageSpan(R.drawable.settings_business), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        GlassSourceColor source = new GlassSourceColor();
        source.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
        ProfileActivity.Button2 button = new ProfileActivity.Button2(context);
        button.setBackground(new BlurredBackgroundDrawableViewFactory(source).create(button, new BlurredBackgroundColorProviderThemed(getResourceProvider(), Theme.key_windowBackgroundWhite)).setPadding(AndroidUtilities.dp(8.0f)).setRadius(AndroidUtilities.dp(22.0f)));
        ScaleStateListAnimator.apply(button, 0.02f, 1.2f);
        ButtonWithCounterView inner = new ButtonWithCounterView(context, getResourceProvider());
        inner.setUseWrapContent(true);
        inner.setPadding(AndroidUtilities.dp(16.0f), 0, AndroidUtilities.dp(16.0f), 0);
        inner.setRoundRadius(19);
        inner.setText(text, false);
        inner.setStateListAnimator(null);
        button.addView(inner, LayoutHelper.createFrame(-2, -1, 17));
        button.setOnClickListener(v -> openCatalog());
        catalogButton = button;
        if (contentView instanceof FrameLayout) {
            ((FrameLayout) contentView).addView(button, LayoutHelper.createFrame(-2, (int) CATALOG_BUTTON_HEIGHT, 81));
        }
        updateCatalogButton(false);
        updateCatalogButtonPosition();
    }

    // StaticGram: also shown while the list holds only the Official block (no empty view with its own button)
    private boolean hasCatalogButton() {
        return ExteraConfig.getPluginsEngine() && !this.searching && !emptyViewShown;
    }

    private void updateCatalogButton(boolean animated) {
        boolean visible = hasCatalogButton() && (catalogButtonScrollShown || listView == null || !listView.canScrollVertically(1));
        catalogButtonVisibility.setValue(visible, animated);
    }

    private void updateCatalogButtonPosition() {
        if (catalogButton == null) {
            return;
        }
        float value = catalogButtonVisibility.getFloatValue();
        catalogButton.setTranslationY(-catalogButtonInset + AndroidUtilities.dp(CATALOG_BUTTON_HEIGHT) * (1.0f - value));
        catalogButton.setAlpha(value);
        catalogButton.setVisibility(value > 0.0f ? View.VISIBLE : View.INVISIBLE);
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        catalogButtonInset = bottom;
        updateCatalogButtonPosition();
        super.onInsets(left, top, right, bottom);
    }

    private void openCatalog() {
        presentFragment(new PluginRepositoriesActivity());
    }

    // endregion

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginsUpdated);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.reloadInterface);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginIsNotResponding);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginsUpdated);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.reloadInterface);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginIsNotResponding);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.pluginsUpdated) {
            AndroidUtilities.updateViewVisibilityAnimated(this.searchItem, ExteraConfig.getPluginsEngine() && !PluginsController.Companion.getInstance().getPlugins().isEmpty(), 0.5f, true);
            getListView$TMessagesProj().adapter.update(true);
            updateCatalogButton(true);
        } else if (id == NotificationCenter.reloadInterface) {
            getListView$TMessagesProj().invalidateViews();
        } else if (id == NotificationCenter.pluginIsNotResponding) {
            getListView$TMessagesProj().adapter.update(true);
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (!this.searching) {
            return super.onBackPressed(invoked);
        }
        if (!invoked) {
            return false;
        }
        this.actionBar.closeSearchField();
        return false;
    }
}
