package com.exteragram.messenger.plugins.catalog.ui;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.utils.ui.ChatHeaderUiHelper;
import com.exteragram.messenger.plugins.Plugin;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.catalog.CatalogPlugin;
import com.exteragram.messenger.plugins.catalog.GitPluginRepository;
import com.exteragram.messenger.plugins.catalog.PluginCatalogController;
import com.exteragram.messenger.plugins.catalog.PluginCategory;
import com.exteragram.messenger.plugins.catalog.PluginRepositoryLinks;
import com.exteragram.messenger.plugins.catalog.db.PluginMetadataEntity;
import com.exteragram.messenger.plugins.catalog.db.PluginRepositoryEntity;
import com.exteragram.messenger.plugins.ui.components.EmptyPluginsView;
import com.exteragram.messenger.plugins.utils.PluginsWatchdog;
import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.utils.AppUtils;
import com.exteragram.messenger.utils.text.LocaleUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ChatAvatarContainer;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plugins of one repository (exteraGram 13.1.1 catalog/ui/PluginCatalogActivity): search, category tabs,
 * sort (updated / popular / newest / name), Updates / Installed / All sections, install, update and delete.
 * The pinned glass FilterTabsView header of exteraGram is a scrollable chip row at the top of the list here.
 * A tap downloads the file (size + SHA-256 pinned by the index) and opens the regular install sheet.
 */
public final class PluginCatalogActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate {

    private static final int SEARCH_ITEM_ID = 0;
    private static final int OTHER_ITEM_ID = 3;
    private static final int MAX_SKELETONS = 4;
    private static final int NEW_PERIOD = 86400;
    private static final long PROGRESS_DELAY = 300;
    private static final int SKELETON_ID_START = 100;
    private static final int TABS_ID = 99;
    private static final int SORT_UPDATED = 0;
    private static final int SORT_POPULAR = 1;
    private static final int SORT_NEWEST = 2;
    private static final int SORT_NAME = 3;
    private static final int[] SORT_TITLES = {R.string.PluginCatalogSortUpdated, R.string.PluginCatalogSortPopular, R.string.PluginCatalogSortNewest, R.string.PluginCatalogSortName};
    private static final Pattern VERSION_NUMBER = Pattern.compile("\\d+(?:\\.\\d+)*");

    private final long repositoryId;
    private PluginRepositoryEntity repository;
    private List<CatalogPlugin> plugins = Collections.emptyList();
    private List<CatalogPlugin> sortedPlugins = Collections.emptyList();
    private final HashSet<Integer> requested = new HashSet<>();
    private final HashSet<Integer> installing = new HashSet<>();
    private List<PluginCategory> tabCategories = Collections.emptyList();
    private PluginCategory selectedCategory;
    private int sortOrder = PluginCatalogController.getSortOrder();
    private boolean loaded;
    private boolean refreshing;
    private boolean searching;
    private String query;
    private ActionBarMenuItem otherItem;
    private ChatAvatarContainer avatarContainer;
    private AvatarDrawable letterAvatar;
    private boolean resolvingChannel;
    private EmptyPluginsView emptyView;
    private CategoryTabsView tabsView;

    public PluginCatalogActivity(long repositoryId) {
        this.repositoryId = repositoryId;
    }

    private PluginCatalogController getController() {
        return PluginCatalogController.getInstance(currentAccount);
    }

    @Override
    public boolean needDelayOpenAnimation() {
        return true;
    }

    @Override
    public String getTitle() {
        return repository != null ? CatalogRepositoryCell.getTitle(repository) : LocaleController.getString(R.string.PluginCatalog);
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        ActionBarMenuItem searchItem = actionBar.menu.addItem(SEARCH_ITEM_ID, R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchExpand() {
                searching = true;
                if (otherItem != null) {
                    otherItem.setVisibility(View.GONE);
                }
                listView.adapter.update(true);
                listView.scrollToPosition(0);
            }

            @Override
            public void onSearchCollapse() {
                searching = false;
                query = null;
                if (otherItem != null) {
                    otherItem.setVisibility(View.VISIBLE);
                }
                updateTabs();
                listView.adapter.update(true);
                listView.scrollToPosition(0);
            }

            @Override
            public void onTextChanged(EditText editText) {
                query = editText.getText().toString();
                listView.adapter.update(true);
                listView.scrollToPosition(0);
            }
        });
        searchItem.setSearchPaddingStart(7);
        searchItem.setSearchFieldHint(LocaleController.getString(R.string.Search));
        otherItem = actionBar.menu.addItem(OTHER_ITEM_ID, R.drawable.ic_ab_other);
        otherItem.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == OTHER_ITEM_ID) {
                    showMenu();
                }
            }
        });
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && getParentActivity() != null) {
                    AndroidUtilities.hideKeyboard(getParentActivity().getCurrentFocus());
                }
            }
        });
        // exteraGram: the repository avatar + title + animated subtitle in a glass ChatAvatarContainer
        avatarContainer = new ChatAvatarContainer(context, null, false, resourceProvider) {
            @Override
            protected boolean useAnimatedSubtitle() {
                return true;
            }
        };
        ChatHeaderUiHelper.setupGlassAvatarContainer(avatarContainer);
        avatarContainer.setOccupyStatusBar(!AndroidUtilities.isTablet());
        avatarContainer.setEnabled(false);
        actionBar.setTitle(null);
        actionBar.addView(avatarContainer, 0, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, ChatHeaderUiHelper.getAvatarContainerLeftMargin(false, 54) + 6, 0, 106, 0));
        fragmentView = view;
        updateTabs();
        updateHeader();
        return view;
    }

    // region menu / sorting

    private boolean hasViews() {
        for (CatalogPlugin plugin : plugins) {
            if (plugin.getViews() > 0) {
                return true;
            }
        }
        return false;
    }

    // exteraGram hides "Most Viewed" for Git repositories (no views); a StaticGram index may send "downloads"
    private int getEffectiveSortOrder() {
        return sortOrder == SORT_POPULAR && !hasViews() ? SORT_UPDATED : sortOrder;
    }

    private void showMenu() {
        if (otherItem == null) {
            return;
        }
        ItemOptions options = ItemOptions.makeOptions(this, otherItem, true);
        ItemOptions sortOptions = options.makeSwipeback();
        sortOptions.add(R.drawable.ic_ab_back, LocaleController.getString(R.string.Back), () -> options.closeSwipeback());
        sortOptions.addGap();
        int effective = getEffectiveSortOrder();
        for (int i = 0; i < SORT_TITLES.length; i++) {
            if (i == SORT_POPULAR && !hasViews()) {
                continue;
            }
            final int order = i;
            sortOptions.addChecked(effective == i, LocaleController.getString(SORT_TITLES[i]), () -> {
                options.dismiss();
                setSortOrder(order);
            });
        }
        options.add(R.drawable.msg_filter, LocaleController.getString(R.string.SortBy), LocaleController.getString(SORT_TITLES[effective >= 0 && effective < SORT_TITLES.length ? effective : 0]), () -> options.openSwipeback(sortOptions));
        ActionBarMenuSubItem last = options.getLast();
        if (last != null) {
            last.setRightIcon(R.drawable.msg_arrowright);
        }
        options.addGap();
        options.add(R.drawable.msg_retry, LocaleController.getString(R.string.Refresh), this::refresh);
        if (repository != null) {
            String webUrl = GitPluginRepository.getWebUrl(repository.getUrl(), repository.getHomepage());
            if (webUrl != null) {
                options.add(R.drawable.msg_openin, LocaleController.getString(R.string.PluginCatalogOpenRepository), () -> Browser.openUrl(getParentActivity(), webUrl));
            }
        }
        String link = PluginRepositoryLinks.build(repository);
        if (link != null) {
            options.add(R.drawable.msg_share, LocaleController.getString(R.string.ShareLink), () -> PluginRepositoryLinks.share(this, link));
            options.add(R.drawable.msg_copy, LocaleController.getString(R.string.CopyLink), () -> {
                if (AndroidUtilities.addToClipboard(link)) {
                    BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.LinkCopied)).show();
                }
            });
        }
        options.setGravity(Gravity.RIGHT);
        options.setSwipebackGravity(false, false);
        options.setOnTopOfScrim();
        options.show();
    }

    private void setSortOrder(int order) {
        if (getEffectiveSortOrder() == order) {
            return;
        }
        sortOrder = order;
        PluginCatalogController.setSortOrder(order);
        sortedPlugins = sortPlugins(plugins);
        listView.adapter.update(true);
        listView.scrollToPosition(0);
    }

    private static String displayName(CatalogPlugin plugin) {
        PluginMetadataEntity metadata = plugin.getMetadata();
        String name = metadata != null ? metadata.getName() : null;
        return (name != null ? name : plugin.getFileName()).toLowerCase(Locale.ROOT);
    }

    private List<CatalogPlugin> sortPlugins(List<CatalogPlugin> source) {
        ArrayList<CatalogPlugin> list = new ArrayList<>(source);
        switch (getEffectiveSortOrder()) {
            case SORT_POPULAR:
                Collections.sort(list, (a, b) -> Integer.compare(b.getViews(), a.getViews()));
                break;
            case SORT_NEWEST:
                Collections.sort(list, (a, b) -> Integer.compare(b.getPublishedAt(), a.getPublishedAt()));
                break;
            case SORT_NAME:
                Collections.sort(list, (a, b) -> displayName(a).compareTo(displayName(b)));
                break;
            default:
                return source;
        }
        return list;
    }

    // endregion

    // region tabs / header

    private void updateTabs() {
        ArrayList<PluginCategory> present = new ArrayList<>();
        for (PluginCategory category : PluginCategory.values()) {
            for (CatalogPlugin plugin : plugins) {
                if (plugin.getCategories().contains(category)) {
                    present.add(category);
                    break;
                }
            }
        }
        if (!present.contains(selectedCategory)) {
            selectedCategory = null;
        }
        ArrayList<PluginCategory> tabs = new ArrayList<>();
        if (!present.isEmpty()) {
            tabs.add(null);
            tabs.addAll(present);
        }
        if (!tabs.equals(tabCategories)) {
            tabCategories = tabs;
            if (tabsView != null) {
                tabsView.setTabs(tabs, selectedCategory);
            }
        } else if (tabsView != null) {
            tabsView.select(selectedCategory, false);
        }
    }

    private void updateHeader() {
        if (actionBar == null || avatarContainer == null) {
            return;
        }
        updateHeaderTitle();
        int count = 0;
        for (CatalogPlugin plugin : plugins) {
            if (selectedCategory == null || plugin.getCategories().contains(selectedCategory)) {
                count++;
            }
        }
        avatarContainer.setSubtitle(!loaded || refreshing ? LocaleController.getString(R.string.PluginCatalogUpdating) : LocaleController.formatPluralString("PluginCatalogPlugins", count));
    }

    /** Title, trusted badge and the repository channel's avatar (letter avatar until it is resolved / without a channel). */
    private void updateHeaderTitle() {
        avatarContainer.setTitle(getTitle(), false, false, false, false, null, null, false);
        avatarContainer.getTitleTextView().setRightDrawable(PluginCatalogController.isTrustedRepository(repositoryId) ? CatalogRepositoryCell.createTrustedBadge(resourceProvider) : null);
        String channel = repository != null ? repository.getUsername() : null;
        TLRPC.Chat chat = TextUtils.isEmpty(channel) ? null : getController().getCachedChannel(channel);
        if (chat != null) {
            avatarContainer.setChatAvatar(chat);
            return;
        }
        if (letterAvatar == null) {
            letterAvatar = new AvatarDrawable();
        }
        letterAvatar.setInfo(repositoryId, getTitle(), null);
        avatarContainer.avatarImageView.setImageDrawable(letterAvatar);
        if (!TextUtils.isEmpty(channel) && !resolvingChannel) {
            resolvingChannel = true;
            getController().resolveChannel(channel, resolved -> {
                resolvingChannel = false;
                if (resolved != null && avatarContainer != null && repository != null && channel.equals(repository.getUsername())) {
                    avatarContainer.setChatAvatar(resolved);
                }
            });
        }
    }

    private void onCategorySelected(PluginCategory category) {
        if (selectedCategory == category) {
            listView.smoothScrollToPosition(0);
            return;
        }
        selectedCategory = category;
        updateHeader();
        listView.adapter.update(true);
        listView.scrollToPosition(0);
    }

    // endregion

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.pluginCatalogUpdated);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginsUpdated);
        reloadCatalog();
        getController().loadRepositories(list -> {
            repository = null;
            for (PluginRepositoryEntity r : list) {
                if (r.getRepositoryId() == repositoryId) {
                    repository = r;
                    break;
                }
            }
            updateList(true);
        });
        getController().syncRepository(repositoryId, false);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.pluginCatalogUpdated);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginsUpdated);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.pluginCatalogUpdated) {
            Object arg = args.length > 0 ? args[0] : null;
            if (arg instanceof Long && (Long) arg == repositoryId) {
                reloadCatalog();
            } else if (arg instanceof Long && (Long) arg == PluginCatalogController.ALL_REPOSITORIES) {
                getController().loadRepositories(list -> {
                    for (PluginRepositoryEntity r : list) {
                        if (r.getRepositoryId() == repositoryId) {
                            repository = r;
                            updateHeader();
                            break;
                        }
                    }
                });
            }
        } else if (id == NotificationCenter.pluginsUpdated) {
            updateList(true);
        }
    }

    private void reloadCatalog() {
        getController().loadCatalog(repositoryId, list -> {
            plugins = list;
            sortedPlugins = sortPlugins(list);
            boolean wasLoaded = loaded;
            loaded = true;
            updateList(wasLoaded);
            if (!wasLoaded) {
                resumeDelayedFragmentAnimation();
            }
        });
    }

    private void refresh() {
        if (refreshing) {
            return;
        }
        refreshing = true;
        updateHeader();
        getController().syncRepository(repositoryId, true, changed -> {
            refreshing = false;
            if (fragmentView == null) {
                return;
            }
            updateHeader();
            if (changed == null) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.PluginCatalogRefreshFailed)).show();
            } else if (changed == 0) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.done, LocaleController.getString(R.string.PluginCatalogUpToDate)).show();
            } else {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.done, AndroidUtilities.replaceTags(LocaleController.formatPluralString("PluginCatalogNewVersions", changed))).show();
            }
        });
    }

    private void updateList(boolean animated) {
        if (fragmentView == null || listView == null) {
            return;
        }
        updateTabs();
        updateHeader();
        boolean scrolled = listView.canScrollVertically(-1);
        listView.adapter.update(animated);
        if (!scrolled) {
            listView.scrollToPosition(0);
        }
    }

    // region list

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean detailsAvailable = getController().isMetadataAvailable();
        PluginCategory category = searching ? null : selectedCategory;
        ArrayList<CatalogPlugin> filtered = new ArrayList<>();
        for (CatalogPlugin plugin : filterByQuery(sortedPlugins)) {
            if (category == null || plugin.getCategories().contains(category)) {
                filtered.add(plugin);
            }
        }
        ArrayList<CatalogPlugin> ready;
        int skeletons = 0;
        if (detailsAvailable) {
            ready = new ArrayList<>();
            int pending = 0;
            for (CatalogPlugin plugin : filtered) {
                if (plugin.isPending()) {
                    pending++;
                } else {
                    ready.add(plugin);
                }
            }
            if (!searching && category == null) {
                skeletons = Math.min(pending, MAX_SKELETONS);
            }
        } else {
            ready = filtered;
        }

        if (!searching && tabCategories.size() > 1) {
            if (tabsView == null) {
                tabsView = new CategoryTabsView(getContext());
                tabsView.setTabs(tabCategories, selectedCategory);
            }
            items.add(UItem.asCustom(TABS_ID, tabsView).setTransparent(true));
        }

        if (ready.isEmpty() && skeletons == 0) {
            if (searching || (loaded && !getController().isSyncing(repositoryId))) {
                items.add(UItem.asFullscreenCustom(getEmptyView(), AndroidUtilities.dp(searching ? 148 : 74), true).setTransparent(true));
                return;
            }
            items.add(UItem.asHeader(LocaleController.getString(R.string.PluginCatalogAllPlugins)));
            addSkeletons(items, MAX_SKELETONS);
            items.add(UItem.asShadow());
            return;
        }

        HashMap<CatalogPlugin, Integer> states = new HashMap<>();
        for (CatalogPlugin plugin : ready) {
            states.put(plugin, getState(plugin));
        }
        ArrayList<CatalogPlugin> updates = new ArrayList<>();
        ArrayList<CatalogPlugin> installed = new ArrayList<>();
        ArrayList<CatalogPlugin> rest = new ArrayList<>();
        for (CatalogPlugin plugin : ready) {
            int state = states.get(plugin);
            if (!searching && state == CatalogPluginCell.STATE_UPDATE) {
                updates.add(plugin);
            } else if (!searching && state == CatalogPluginCell.STATE_INSTALLED) {
                installed.add(plugin);
            } else {
                rest.add(plugin);
            }
        }
        if (!updates.isEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.PluginCatalogUpdates)));
            for (CatalogPlugin plugin : updates) {
                items.add(createItem(plugin, CatalogPluginCell.STATE_UPDATE, detailsAvailable));
            }
            items.add(UItem.asShadow());
        }
        if (!installed.isEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.PluginCatalogInstalled)));
            for (CatalogPlugin plugin : installed) {
                items.add(createItem(plugin, CatalogPluginCell.STATE_INSTALLED, detailsAvailable));
            }
            items.add(UItem.asShadow());
        }
        if (rest.isEmpty() && skeletons == 0) {
            return;
        }
        items.add(UItem.asHeader(category != null ? LocaleController.getString(category.getTitleRes()) : LocaleController.getString(R.string.PluginCatalogAllPlugins)));
        for (CatalogPlugin plugin : rest) {
            items.add(createItem(plugin, states.get(plugin), detailsAvailable));
        }
        addSkeletons(items, skeletons);
        if (!detailsAvailable) {
            for (CatalogPlugin plugin : rest) {
                if (plugin.isPending()) {
                    items.add(UItem.asShadow(LocaleController.getString(R.string.PluginCatalogEngineUnavailable)));
                    return;
                }
            }
        }
        items.add(UItem.asShadow());
    }

    private void addSkeletons(ArrayList<UItem> items, int count) {
        for (int i = 0; i < count; i++) {
            items.add(CatalogPluginSkeletonCell.Factory.of(SKELETON_ID_START + i, i));
        }
    }

    private UItem createItem(CatalogPlugin plugin, int state, boolean detailsAvailable) {
        PluginMetadataEntity metadata = plugin.getMetadata();
        CharSequence versionLabel = metadata != null && metadata.getError() == null ? metadata.getVersion() : null;
        String pluginId = plugin.getPluginId();
        Plugin installedPlugin = pluginId != null ? PluginsController.getInstance().getPlugins().get(pluginId) : null;
        if (state == CatalogPluginCell.STATE_UPDATE && installedPlugin != null && installedPlugin.getVersion() != null && versionLabel != null) {
            versionLabel = LocaleUtils.replaceArrows(getContext(), installedPlugin.getVersion() + " -> " + versionLabel, R.drawable.msg_mini_arrow_mediathin);
        }
        boolean isNew = state == CatalogPluginCell.STATE_GET && plugin.getPublishedAt() >= getConnectionsManager().getCurrentTime() - NEW_PERIOD;
        return CatalogPluginCell.Factory.of(plugin, state, versionLabel, isNew, installing.contains(plugin.getEntryId()), detailsAvailable,
                installedPlugin != null ? v -> confirmDelete(installedPlugin) : null);
    }

    private void confirmDelete(Plugin plugin) {
        if (plugin.isNotResponding()) {
            PluginsWatchdog.showNotRespondingAlert(plugin);
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), resourceProvider)
                .setTitle(LocaleController.getString(R.string.PluginDelete))
                .setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.PluginDeleteInfo, plugin.getName())))
                .setPositiveButton(LocaleController.getString(R.string.Delete), (d, which) -> PluginsController.getInstance().deletePlugin(plugin.getId(),
                        error -> AndroidUtilities.runOnUIThread(() -> {
                            if (fragmentView != null && error != null) {
                                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, error).show();
                            }
                        })))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        showDialog(dialog);
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private List<CatalogPlugin> filterByQuery(List<CatalogPlugin> source) {
        String q = query != null ? query.trim().toLowerCase(Locale.ROOT) : null;
        if (!searching || TextUtils.isEmpty(q)) {
            return source;
        }
        ArrayList<CatalogPlugin> result = new ArrayList<>();
        for (CatalogPlugin plugin : source) {
            PluginMetadataEntity m = plugin.getMetadata();
            String[] fields = {m != null ? m.getName() : null, m != null ? m.getAuthor() : null, m != null ? m.getDescription() : null, plugin.getFileName()};
            for (String field : fields) {
                if (field != null && field.toLowerCase(Locale.ROOT).contains(q)) {
                    result.add(plugin);
                    break;
                }
            }
        }
        return result;
    }

    private static String versionNumber(String version) {
        if (version == null) {
            return null;
        }
        Matcher m = VERSION_NUMBER.matcher(version);
        return m.find() ? m.group() : null;
    }

    private int getState(CatalogPlugin plugin) {
        String pluginId = plugin.getPluginId();
        Plugin installed = pluginId != null ? PluginsController.getInstance().getPlugins().get(pluginId) : null;
        if (installed == null) {
            return CatalogPluginCell.STATE_GET;
        }
        PluginMetadataEntity metadata = plugin.getMetadata();
        String remote = versionNumber(metadata != null ? metadata.getVersion() : null);
        String local = versionNumber(installed.getVersion());
        if (remote != null && local != null) {
            try {
                if (AppUtils.compareVersionValues(remote, local) > 0) {
                    return CatalogPluginCell.STATE_UPDATE;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return CatalogPluginCell.STATE_INSTALLED;
    }

    private EmptyPluginsView getEmptyView() {
        if (emptyView == null) {
            emptyView = new EmptyPluginsView(getContext(), resourceProvider);
        }
        int tag = searching ? 1 : 2;
        if (!Integer.valueOf(tag).equals(emptyView.getTag())) {
            MediaDataController.getInstance(currentAccount).setPlaceholderImage(emptyView.getBackupImageView(), "AnimatedEmojies", searching ? "🔎" : "📂", "100_100");
            emptyView.setText(LocaleController.getString(searching ? R.string.PluginsNotFound : R.string.PluginCatalogEmpty));
            emptyView.setTag(tag);
        }
        return emptyView;
    }

    // endregion

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (!(item.object instanceof CatalogPlugin)) {
            return;
        }
        CatalogPlugin plugin = (CatalogPlugin) item.object;
        if (!requested.add(plugin.getEntryId())) {
            return;
        }
        Runnable showProgress = () -> {
            installing.add(plugin.getEntryId());
            updateList(true);
        };
        AndroidUtilities.runOnUIThread(showProgress, PROGRESS_DELAY);
        boolean trusted = PluginCatalogController.isTrustedRepository(repositoryId);
        getController().downloadPlugin(plugin, file -> {
            AndroidUtilities.cancelRunOnUIThread(showProgress);
            requested.remove(plugin.getEntryId());
            if (fragmentView == null) {
                return;
            }
            if (installing.remove(plugin.getEntryId())) {
                updateList(true);
            }
            if (file == null) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, AndroidUtilities.replaceTags(LocaleController.formatString(R.string.PluginCatalogDownloadFailed, plugin.getFileName()))).show();
                return;
            }
            // the regular install sheet: PluginGuard server check, local blocklist, static analysis,
            // unknown-sources gate (only the built-in StaticGram catalog counts as a trusted source)
            PluginsController.getInstance().showInstallDialog(this, file.getAbsolutePath(), trusted);
        });
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (!searching) {
            return super.onBackPressed(invoked);
        }
        if (invoked) {
            actionBar.closeSearchField();
        }
        return false;
    }

    /**
     * Category tabs (exteraGram: pinned FilterTabsView with icons and titles). The selection pill slides and
     * morphs between the chips, text/icon colors crossfade and the row scrolls the selected chip into view.
     */
    private final class CategoryTabsView extends HorizontalScrollView {
        private final TabsContainer container;
        private final ArrayList<TextView> chips = new ArrayList<>();
        private final ArrayList<Drawable> icons = new ArrayList<>();
        private final ArrayList<PluginCategory> chipCategories = new ArrayList<>();
        private final android.graphics.Paint pillPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint cardPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private int fromIndex = -1;
        private int toIndex = -1;
        private float progress = 1f;
        private android.animation.ValueAnimator animator;

        private final class TabsContainer extends LinearLayout {
            TabsContainer(Context context) {
                super(context);
                setWillNotDraw(false);
            }

            @Override
            protected void onDraw(android.graphics.Canvas canvas) {
                float radius = AndroidUtilities.dp(16);
                float stroke = Math.max(1f, AndroidUtilities.dp(1.5f));
                cardPaint.setStyle(android.graphics.Paint.Style.STROKE);
                cardPaint.setStrokeWidth(stroke);
                cardPaint.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourceProvider), 0.45f));
                for (TextView chip : chips) {
                    android.graphics.RectF r = AndroidUtilities.rectTmp;
                    r.set(chip.getLeft() + stroke / 2, chip.getTop() + stroke / 2, chip.getRight() - stroke / 2, chip.getBottom() - stroke / 2);
                    canvas.drawRoundRect(r, radius, radius, cardPaint);
                }
                if (toIndex >= 0 && toIndex < chips.size()) {
                    TextView to = chips.get(toIndex);
                    TextView from = fromIndex >= 0 && fromIndex < chips.size() ? chips.get(fromIndex) : to;
                    float t = progress;
                    float left = from.getLeft() + (to.getLeft() - from.getLeft()) * t;
                    float right = from.getRight() + (to.getRight() - from.getRight()) * t;
                    float top = to.getTop();
                    float bottom = to.getBottom();
                    // the pill stretches in the middle of the move, like the exteraGram tab selector
                    float stretch = (float) Math.sin(Math.PI * t) * AndroidUtilities.dp(6);
                    pillPaint.setColor(Theme.getColor(Theme.key_featuredStickers_addButton, resourceProvider));
                    android.graphics.RectF r = AndroidUtilities.rectTmp;
                    r.set(left - (to.getLeft() > from.getLeft() ? 0 : stretch), top, right + (to.getLeft() > from.getLeft() ? stretch : 0), bottom);
                    canvas.drawRoundRect(r, radius, radius, pillPaint);
                }
            }
        }

        CategoryTabsView(Context context) {
            super(context);
            setHorizontalScrollBarEnabled(false);
            setClipToPadding(false);
            container = new TabsContainer(context);
            container.setOrientation(LinearLayout.HORIZONTAL);
            container.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(4));
            addView(container, new LayoutParams(-2, -2));
        }

        void setTabs(List<PluginCategory> tabs, PluginCategory selected) {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            container.removeAllViews();
            chips.clear();
            icons.clear();
            chipCategories.clear();
            int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourceProvider);
            for (PluginCategory category : tabs) {
                TextView chip = new TextView(getContext());
                chip.setTextSize(1, 14);
                chip.setTypeface(AndroidUtilities.bold());
                chip.setGravity(Gravity.CENTER_VERTICAL);
                chip.setSingleLine(true);
                chip.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(14), 0);
                chip.setCompoundDrawablePadding(AndroidUtilities.dp(6));
                chip.setText(category == null ? LocaleController.getString(R.string.FilterAllChatsShort) : LocaleController.getString(category.getTitleRes()));
                Drawable icon = ContextCompat.getDrawable(getContext(), category == null ? R.drawable.filter_plugins : category.getIconRes());
                if (icon != null) {
                    icon = icon.mutate();
                    icon.setBounds(0, 0, AndroidUtilities.dp(20), AndroidUtilities.dp(20));
                    chip.setCompoundDrawablesRelative(icon, null, null, null);
                }
                chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(16), 0, Theme.multAlpha(accent, 0.15f)));
                chip.setOnClickListener(v -> {
                    if (category == selectedCategory) {
                        onCategorySelected(category);
                        return;
                    }
                    onCategorySelected(category);
                    select(category, true);
                });
                container.addView(chip, LayoutHelper.createLinear(-2, 32, 0, 0, 6, 0));
                chips.add(chip);
                icons.add(icon);
                chipCategories.add(category);
            }
            fromIndex = toIndex = Math.max(0, chipCategories.indexOf(selected));
            progress = 1f;
            applyColors();
            container.invalidate();
            final int index = toIndex;
            post(() -> scrollToChip(index, false));
        }

        void select(PluginCategory category, boolean animated) {
            int index = chipCategories.indexOf(category);
            if (index < 0 || index == toIndex) {
                return;
            }
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            fromIndex = toIndex;
            toIndex = index;
            if (!animated) {
                fromIndex = toIndex;
                progress = 1f;
                applyColors();
                container.invalidate();
                scrollToChip(index, false);
                return;
            }
            progress = 0f;
            final int startScroll = getScrollX();
            final int endScroll = targetScroll(index);
            animator = android.animation.ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(320);
            animator.setInterpolator(org.telegram.ui.Components.CubicBezierInterpolator.EASE_OUT_QUINT);
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                scrollTo((int) (startScroll + (endScroll - startScroll) * progress), 0);
                applyColors();
                container.invalidate();
            });
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    if (animator == a) {
                        animator = null;
                        fromIndex = toIndex;
                        progress = 1f;
                        applyColors();
                        container.invalidate();
                    }
                }
            });
            animator.start();
        }

        private int targetScroll(int index) {
            if (index < 0 || index >= chips.size()) {
                return getScrollX();
            }
            TextView chip = chips.get(index);
            int max = Math.max(0, container.getMeasuredWidth() - getWidth());
            int target = chip.getLeft() + chip.getWidth() / 2 - getWidth() / 2;
            return Math.max(0, Math.min(max, target));
        }

        private void scrollToChip(int index, boolean animated) {
            int target = targetScroll(index);
            if (animated) {
                smoothScrollTo(target, 0);
            } else {
                scrollTo(target, 0);
            }
        }

        private void applyColors() {
            int normal = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider);
            int selected = Theme.getColor(Theme.key_featuredStickers_buttonText, resourceProvider);
            for (int i = 0; i < chips.size(); i++) {
                float f = i == toIndex ? progress : (i == fromIndex ? 1f - progress : 0f);
                int color = androidx.core.graphics.ColorUtils.blendARGB(normal, selected, f);
                chips.get(i).setTextColor(color);
                Drawable icon = icons.get(i);
                if (icon != null) {
                    icon.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
                }
            }
        }
    }
}
