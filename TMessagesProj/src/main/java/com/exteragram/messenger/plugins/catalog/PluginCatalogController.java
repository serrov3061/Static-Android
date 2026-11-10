package com.exteragram.messenger.plugins.catalog;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.PythonPluginsEngine;
import com.exteragram.messenger.plugins.catalog.db.CatalogEntryEntity;
import com.exteragram.messenger.plugins.catalog.db.CatalogEntryVersion;
import com.exteragram.messenger.plugins.catalog.db.CatalogEntryWithMetadata;
import com.exteragram.messenger.plugins.catalog.db.PluginCatalogDao;
import com.exteragram.messenger.plugins.catalog.db.PluginCatalogDatabase;
import com.exteragram.messenger.plugins.catalog.db.PluginMetadataEntity;
import com.exteragram.messenger.plugins.catalog.db.PluginRepositoryEntity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.AppGlobalConfig;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.HttpUrl;

/**
 * Plugin catalog (exteraGram 13.1.1 catalog/PluginCatalogController), StaticGram edition.
 *
 * exteraGram builds its catalog from the Telegram channels of a trusted chat folder plus channels and Git
 * repositories the user adds. StaticGram ships one built-in repository instead: the @PluginsBot index at the
 * app config key {@code sg_plugins_catalog_url} (default {@link #DEFAULT_CATALOG_URL}), shown in the
 * "Verified" section and installed as a trusted source. Users can add other index (Git) repositories exactly
 * like in exteraGram; those stay unknown sources. Channel repositories are not ported (no folder, no message
 * history sync, no metadata parsing of channel documents).
 *
 * Several channel repositories (docs §8): next to the built-in index the server publishes sources.json, one
 * index per channel (source) of the catalog. Each source becomes a built-in trusted repository in "Verified"
 * (like exteraGram's channel repositories from the trusted folder: channel avatar, title, plugins count),
 * with its own index, ETag and cache. The aggregated built-in index stays synced (it triggers the sources
 * refresh and is the fallback) but is hidden from "Verified" when every plugin of it belongs to a listed
 * source, as exteraGram shows only channel repositories there; otherwise it is shown first. Without
 * sources.json (404 / invalid) the list is the single built-in repository as before.
 *
 * Every install still goes through PluginsController.showInstallDialog -> InstallPluginBottomSheet
 * (PluginGuard server check, local blocklist, static analysis, unknown-sources gate).
 */
public final class PluginCatalogController {

    public static final int ADD_OK = 0;
    public static final int ADD_EXISTS = 1;
    public static final int ADD_FAILED = 2;
    public static final int ADD_UNSUPPORTED = 3;
    public static final long ALL_REPOSITORIES = 0;

    public static final String DEFAULT_CATALOG_URL = "https://plugins.staticgram.top/catalog/index.json";
    public static final String DEFAULT_CATALOG_CHANNEL = "StaticPlugins";
    /** The built-in StaticGram repository (index repositories have negative ids). */
    public static final long DEFAULT_REPOSITORY_ID = -1;

    private static final long SYNC_INTERVAL = 10 * 60 * 1000L;
    private static final long UPDATE_DEBOUNCE_MS = 100;
    private static final String KEY_SORT_ORDER = "sortOrder";
    private static final String KEY_LAST_GIT_REPOSITORY_ID = "lastGitRepositoryId";
    private static final String KEY_SOURCES_SYNCED_AT = "sourcesSyncedAt";
    private static final String KEY_SOURCES_SYNCED_URL = "sourcesSyncedUrl";
    private static final String KEY_SOURCES_ETAG = "sourcesEtag";
    private static final String KEY_SOURCES_ETAG_URL = "sourcesEtagUrl";
    /** Distinct "source" values of the built-in index (comma-separated) and whether some plugin has none. */
    private static final String KEY_MAIN_SOURCE_REFS = "mainSourceRefs";
    private static final String KEY_MAIN_UNCOVERED = "mainUncovered";
    /** Source repositories ("s<n>" of sources.json) get ids SOURCE_REPOSITORY_BASE - n, apart from user repositories. */
    private static final long SOURCE_REPOSITORY_BASE = -(1L << 40);
    private static final long SOURCE_REPOSITORY_SPAN = 100_000_000_000L;

    private static volatile DispatchQueue catalogQueue;
    private static volatile ExecutorService networkExecutor;
    private static volatile SharedPreferences preferences;
    private static final PluginCatalogController[] instances = new PluginCatalogController[UserConfig.MAX_ACCOUNT_COUNT];
    private static final HashMap<String, PluginsController.PluginsEngine> extensionEngines = new HashMap<>();
    private static int extensionEnginesKey;

    private final int account;
    private final HashSet<Long> syncingRepositories = new HashSet<>();
    private final HashMap<Long, ArrayList<Utilities.Callback<Integer>>> syncCallbacks = new HashMap<>();
    private final HashSet<Long> pendingForcedSyncs = new HashSet<>();
    private final HashSet<Long> pendingUpdates = new HashSet<>();
    private final Runnable postUpdatesRunnable = this::postUpdates;
    /** Catalog queue only. */
    private boolean sourcesSyncing;
    private volatile boolean defaultRepositoryHidden;

    public static PluginCatalogController getInstance(int account) {
        PluginCatalogController local = instances[account];
        if (local == null) {
            synchronized (instances) {
                local = instances[account];
                if (local == null) {
                    instances[account] = local = new PluginCatalogController(account);
                }
            }
        }
        return local;
    }

    private PluginCatalogController(int account) {
        this.account = account;
    }

    // region statics

    private static DispatchQueue getCatalogQueue() {
        DispatchQueue local = catalogQueue;
        if (local == null) {
            synchronized (PluginCatalogController.class) {
                local = catalogQueue;
                if (local == null) {
                    catalogQueue = local = new DispatchQueue("pluginCatalogQueue");
                }
            }
        }
        return local;
    }

    private static ExecutorService getNetworkExecutor() {
        ExecutorService local = networkExecutor;
        if (local == null) {
            synchronized (PluginCatalogController.class) {
                local = networkExecutor;
                if (local == null) {
                    networkExecutor = local = Executors.newFixedThreadPool(3);
                }
            }
        }
        return local;
    }

    private static SharedPreferences getPreferences() {
        SharedPreferences local = preferences;
        if (local == null) {
            preferences = local = ApplicationLoader.applicationContext.getSharedPreferences("plugin_catalog", Context.MODE_PRIVATE);
        }
        return local;
    }

    public static int getSortOrder() {
        return getPreferences().getInt(KEY_SORT_ORDER, 0);
    }

    public static void setSortOrder(int order) {
        getPreferences().edit().putInt(KEY_SORT_ORDER, order).apply();
    }

    private static String etagKey(long repositoryId) {
        return "etag_" + repositoryId;
    }

    private static String etagUrlKey(long repositoryId) {
        return "etagUrl_" + repositoryId;
    }

    private static PluginsController.PluginsEngine getEngine(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (extension.isEmpty()) {
            return null;
        }
        synchronized (extensionEngines) {
            int key = PluginsController.getEngines().keySet().hashCode();
            if (key != extensionEnginesKey) {
                extensionEngines.clear();
                extensionEnginesKey = key;
            }
            if (!extensionEngines.containsKey(extension)) {
                extensionEngines.put(extension, PluginsController.getPluginEngine(new File("plugin." + extension)));
            }
            return extensionEngines.get(extension);
        }
    }

    /** A file the plugin engines can install (.plugin, Elyx archives). */
    public static boolean isSupported(String fileName) {
        return fileName != null && getEngine(fileName) != null;
    }

    public static boolean isTrustedRepository(long repositoryId) {
        return repositoryId == DEFAULT_REPOSITORY_ID || isSourceRepository(repositoryId);
    }

    /** A channel repository of sources.json (built-in, trusted, managed by the server list). */
    public static boolean isSourceRepository(long repositoryId) {
        return repositoryId <= SOURCE_REPOSITORY_BASE && repositoryId > SOURCE_REPOSITORY_BASE - SOURCE_REPOSITORY_SPAN;
    }

    /** "s12" -> repository id (sourceId already matches ^s[0-9]{1,11}$). */
    private static long sourceRepositoryId(String sourceId) {
        return SOURCE_REPOSITORY_BASE - Long.parseLong(sourceId.substring(1));
    }

    private static String sourceIdOf(long repositoryId) {
        return "s" + (SOURCE_REPOSITORY_BASE - repositoryId);
    }

    private static String sourceUpdatedKey(long repositoryId) {
        return "srcUpdated_" + repositoryId;
    }

    private static String sourceSyncedUpdatedKey(long repositoryId) {
        return "srcSyncedUpdated_" + repositoryId;
    }

    /** True when "Verified" shows the channel repositories of sources.json only (see the class comment). */
    public boolean isDefaultRepositoryHidden() {
        return defaultRepositoryHidden;
    }

    /** sg_plugins_catalog_url from the app config when it is a valid HTTPS URL, else the built-in default. */
    public String getDefaultCatalogUrl() {
        String url = null;
        try {
            url = AppGlobalConfig.getInstance(account).sgPluginsCatalogUrl.get();
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (!TextUtils.isEmpty(url)) {
            HttpUrl parsed = HttpUrl.parse(url.trim());
            if (parsed != null && parsed.isHttps()) {
                return parsed.toString();
            }
        }
        return DEFAULT_CATALOG_URL;
    }

    /** sg_plugins_catalog_channel (username of the channel whose avatar the built-in catalog shows), default StaticPlugins. */
    public String getDefaultCatalogChannel() {
        String channel = null;
        try {
            channel = AppGlobalConfig.getInstance(account).sgPluginsCatalogChannel.get();
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (channel != null) {
            channel = channel.trim();
            channel = channel.startsWith("@") ? channel.substring(1) : channel;
        }
        return TextUtils.isEmpty(channel) ? DEFAULT_CATALOG_CHANNEL : channel;
    }

    /** The cached channel for a username, or null. */
    public org.telegram.tgnet.TLRPC.Chat getCachedChannel(String username) {
        org.telegram.tgnet.TLObject object = org.telegram.messenger.MessagesController.getInstance(account).getUserOrChat(username);
        return object instanceof org.telegram.tgnet.TLRPC.Chat ? (org.telegram.tgnet.TLRPC.Chat) object : null;
    }

    /** Calls back on the UI thread with the channel (cached or resolved by username), or null. */
    public void resolveChannel(String username, Utilities.Callback<org.telegram.tgnet.TLRPC.Chat> callback) {
        org.telegram.tgnet.TLRPC.Chat cached = getCachedChannel(username);
        if (cached != null) {
            callback.run(cached);
            return;
        }
        org.telegram.messenger.MessagesController controller = org.telegram.messenger.MessagesController.getInstance(account);
        controller.getUserNameResolver().resolve(username, peerId -> {
            org.telegram.tgnet.TLRPC.Chat chat = peerId != null && peerId < 0 ? controller.getChat(-peerId) : null;
            callback.run(chat);
        });
    }

    // endregion

    private PluginCatalogDao getDao() {
        return PluginCatalogDatabase.getInstance().catalogDao();
    }

    private void postUpdates() {
        ArrayList<Long> ids = new ArrayList<>(pendingUpdates);
        pendingUpdates.clear();
        NotificationCenter center = NotificationCenter.getInstance(account);
        for (Long id : ids) {
            center.postNotificationName(NotificationCenter.pluginCatalogUpdated, id);
        }
    }

    private void postUpdate(long repositoryId) {
        pendingUpdates.add(repositoryId);
        AndroidUtilities.cancelRunOnUIThread(postUpdatesRunnable);
        AndroidUtilities.runOnUIThread(postUpdatesRunnable, UPDATE_DEBOUNCE_MS);
    }

    /** Catalog queue: creates the built-in repository, or points it at a new sg_plugins_catalog_url. */
    private void ensureDefaultRepository() {
        String url = getDefaultCatalogUrl();
        String channel = getDefaultCatalogChannel();
        PluginRepositoryEntity current = getDao().getRepository(DEFAULT_REPOSITORY_ID);
        if (current != null && url.equals(current.getUrl()) && current.getSource() == PluginRepositoryEntity.SOURCE_FOLDER) {
            if (!java.util.Objects.equals(channel, current.getUsername())) {
                getDao().upsertRepositories(Collections.singletonList(current.withUsername(channel)));
            }
            return;
        }
        if (current != null) {
            getDao().removeRepository(DEFAULT_REPOSITORY_ID);
            saveEtag(DEFAULT_REPOSITORY_ID, null, null);
            getPreferences().edit().remove(KEY_SOURCES_SYNCED_AT).remove(KEY_MAIN_SOURCE_REFS).remove(KEY_MAIN_UNCOVERED).apply();
        }
        getDao().upsertRepositories(Collections.singletonList(new PluginRepositoryEntity(DEFAULT_REPOSITORY_ID, channel,
                current != null ? current.getTitle() : null, PluginRepositoryEntity.SOURCE_FOLDER, 0,
                current != null ? current.getAddedAt() : System.currentTimeMillis(), 0, 0, url, null)));
    }

    public void loadRepositories(Utilities.Callback<List<PluginRepositoryEntity>> callback) {
        getCatalogQueue().postRunnable(() -> {
            ensureDefaultRepository();
            List<PluginRepositoryEntity> repositories = getDao().getRepositories();
            defaultRepositoryHidden = computeDefaultHidden(repositories);
            AndroidUtilities.runOnUIThread(() -> callback.run(repositories));
        });
    }

    /** Catalog queue: every plugin of the built-in index belongs to a source that is in the list. */
    private boolean computeDefaultHidden(List<PluginRepositoryEntity> repositories) {
        SharedPreferences p = getPreferences();
        String refs = p.getString(KEY_MAIN_SOURCE_REFS, null);
        if (refs == null || p.getBoolean(KEY_MAIN_UNCOVERED, true)) {
            return false;
        }
        HashSet<String> present = new HashSet<>();
        for (PluginRepositoryEntity r : repositories) {
            if (isSourceRepository(r.getRepositoryId())) {
                present.add(sourceIdOf(r.getRepositoryId()));
            }
        }
        if (present.isEmpty()) {
            return false;
        }
        for (String ref : TextUtils.split(refs, ",")) {
            if (!ref.isEmpty() && !present.contains(ref)) {
                return false;
            }
        }
        return true;
    }

    public void loadCatalog(long repositoryId, Utilities.Callback<List<CatalogPlugin>> callback) {
        getCatalogQueue().postRunnable(() -> {
            List<CatalogPlugin> plugins = getVisiblePlugins(repositoryId);
            PluginRepositoryEntity repository = getDao().getRepository(repositoryId);
            boolean countChanged = repository != null && repository.getSyncedAt() != 0 && repository.getPluginsCount() != plugins.size();
            if (countChanged) {
                getDao().updatePluginsCount(repositoryId, plugins.size());
            }
            AndroidUtilities.runOnUIThread(() -> {
                callback.run(plugins);
                if (countChanged) {
                    postUpdate(ALL_REPOSITORIES);
                }
            });
        });
    }

    private List<CatalogPlugin> getVisiblePlugins(long repositoryId) {
        ArrayList<CatalogPlugin> all = new ArrayList<>();
        for (CatalogEntryWithMetadata row : getDao().getEntries(repositoryId)) {
            if (isSupported(row.getEntry().getFileName())) {
                all.add(CatalogPlugin.from(row));
            }
        }
        ArrayList<CatalogPlugin> visible = new ArrayList<>();
        for (CatalogPlugin plugin : CatalogPlugin.latestVersions(all)) {
            if (!plugin.isFailed()) {
                visible.add(plugin);
            }
        }
        return visible;
    }

    public boolean isSyncing(long repositoryId) {
        return syncingRepositories.contains(repositoryId);
    }

    public boolean isMetadataAvailable() {
        return ExteraConfig.getPluginsEngine();
    }

    /** Adds an index repository. Callback: (ADD_* code, the new repository or null). */
    public void addGitRepository(String url, Utilities.Callback2<Integer, PluginRepositoryEntity> callback) {
        getCatalogQueue().postRunnable(() -> {
            ensureDefaultRepository();
            if (getDao().getRepositoryByUrl(url) != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(ADD_EXISTS, null));
                return;
            }
            getNetworkExecutor().execute(() -> {
                Object[] loaded = loadGitIndex(url, null, null);
                GitPluginRepository.IndexResult result = (GitPluginRepository.IndexResult) loaded[0];
                String source = (String) loaded[1];
                if (!(result instanceof GitPluginRepository.IndexResult.Loaded)) {
                    int code = result == GitPluginRepository.IndexResult.UNSUPPORTED ? ADD_UNSUPPORTED : ADD_FAILED;
                    AndroidUtilities.runOnUIThread(() -> callback.run(code, null));
                    return;
                }
                GitPluginRepository.IndexResult.Loaded index = (GitPluginRepository.IndexResult.Loaded) result;
                getCatalogQueue().postRunnable(() -> {
                    if (getDao().getRepositoryByUrl(url) != null) {
                        AndroidUtilities.runOnUIThread(() -> callback.run(ADD_EXISTS, null));
                        return;
                    }
                    int userRepositories = 0;
                    for (PluginRepositoryEntity r : getDao().getRepositories()) {
                        if (r.getSource() == PluginRepositoryEntity.SOURCE_USER) {
                            userRepositories++;
                        }
                    }
                    PluginRepositoryEntity repository = new PluginRepositoryEntity(nextGitRepositoryId(), null, index.index.name,
                            PluginRepositoryEntity.SOURCE_USER, userRepositories, System.currentTimeMillis(), 0, 0, url, index.index.homepage);
                    getDao().upsertRepositories(Collections.singletonList(repository));
                    saveEtag(repository.getRepositoryId(), source, index.etag);
                    ArrayList<CatalogEntryEntity> entries = new ArrayList<>();
                    ArrayList<PluginMetadataEntity> metadata = new ArrayList<>();
                    buildGitEntries(repository, index.index, entries, metadata);
                    AndroidUtilities.runOnUIThread(() -> {
                        syncingRepositories.add(repository.getRepositoryId());
                        finishSync(repository.getRepositoryId(), entries, metadata, index.index);
                        callback.run(ADD_OK, repository);
                        postUpdate(ALL_REPOSITORIES);
                    });
                });
            });
        });
    }

    /** Removes a repository the user added (the built-in one cannot be removed). */
    public void removeRepository(long repositoryId) {
        getCatalogQueue().postRunnable(() -> {
            PluginRepositoryEntity repository = getDao().getRepository(repositoryId);
            if (repository == null || repository.getSource() != PluginRepositoryEntity.SOURCE_USER) {
                return;
            }
            getDao().removeRepository(repositoryId);
            saveEtag(repositoryId, null, null);
            AndroidUtilities.runOnUIThread(() -> postUpdate(ALL_REPOSITORIES));
        });
    }

    public void syncRepository(long repositoryId, boolean force) {
        syncRepository(repositoryId, force, null);
    }

    /**
     * Refreshes a repository (at most every 10 minutes unless forced). The callback gets the number of new
     * plugins/versions, or null when the refresh failed.
     */
    public void syncRepository(long repositoryId, boolean force, Utilities.Callback<Integer> callback) {
        if (callback != null) {
            ArrayList<Utilities.Callback<Integer>> list = syncCallbacks.get(repositoryId);
            if (list == null) {
                syncCallbacks.put(repositoryId, list = new ArrayList<>());
            }
            list.add(callback);
        }
        if (!syncingRepositories.add(repositoryId)) {
            if (force) {
                pendingForcedSyncs.add(repositoryId);
            }
            return;
        }
        postUpdate(repositoryId);
        getCatalogQueue().postRunnable(() -> {
            ensureDefaultRepository();
            if (repositoryId == DEFAULT_REPOSITORY_ID) {
                syncSources(force);
            }
            PluginRepositoryEntity repository = getDao().getRepository(repositoryId);
            boolean fresh = repository != null && (System.currentTimeMillis() - repository.getSyncedAt() < SYNC_INTERVAL
                    || repository.getSyncedAt() != 0 && isSourceRepository(repositoryId) && isSourceUnchanged(repositoryId));
            if (repository == null || repository.getUrl() == null || (!force && fresh)) {
                AndroidUtilities.runOnUIThread(() -> {
                    syncingRepositories.remove(repositoryId);
                    postUpdate(repositoryId);
                    completeSync(repositoryId, repository != null ? 0 : null);
                });
                return;
            }
            syncGitRepository(repository);
        });
    }

    /** The source's updated_at in sources.json equals the one its index was last synced at. */
    private static boolean isSourceUnchanged(long repositoryId) {
        String updated = getPreferences().getString(sourceUpdatedKey(repositoryId), null);
        return updated != null && updated.equals(getPreferences().getString(sourceSyncedUpdatedKey(repositoryId), null));
    }

    /** sources.json next to the built-in index (its last path segment replaced), or null when not HTTPS. */
    private String getSourcesUrl() {
        HttpUrl index = HttpUrl.parse(getDefaultCatalogUrl());
        HttpUrl sources = index != null ? index.resolve("sources.json") : null;
        return sources != null && sources.isHttps() ? sources.toString() : null;
    }

    /** Catalog queue: refreshes sources.json (at most every 10 minutes unless forced) and materializes the sources. */
    private void syncSources(boolean force) {
        if (sourcesSyncing) {
            return;
        }
        SharedPreferences p = getPreferences();
        String url = getSourcesUrl();
        if (url == null) {
            if (applySources(Collections.emptyList())) {
                AndroidUtilities.runOnUIThread(() -> postUpdate(ALL_REPOSITORIES));
            }
            return;
        }
        if (!force && url.equals(p.getString(KEY_SOURCES_SYNCED_URL, null)) && System.currentTimeMillis() - p.getLong(KEY_SOURCES_SYNCED_AT, 0) < SYNC_INTERVAL) {
            return;
        }
        sourcesSyncing = true;
        String etag = url.equals(p.getString(KEY_SOURCES_ETAG_URL, null)) ? p.getString(KEY_SOURCES_ETAG, null) : null;
        getNetworkExecutor().execute(() -> {
            GitPluginRepository.SourcesResult result = GitPluginRepository.loadSources(url, etag);
            getCatalogQueue().postRunnable(() -> {
                sourcesSyncing = false;
                if (result == GitPluginRepository.SourcesResult.FAILED || !url.equals(getSourcesUrl())) {
                    return; // keep the current list, retry on the next sync
                }
                SharedPreferences.Editor editor = p.edit().putLong(KEY_SOURCES_SYNCED_AT, System.currentTimeMillis()).putString(KEY_SOURCES_SYNCED_URL, url);
                boolean changed = false;
                if (result instanceof GitPluginRepository.SourcesResult.Loaded) {
                    GitPluginRepository.SourcesResult.Loaded loaded = (GitPluginRepository.SourcesResult.Loaded) result;
                    if (loaded.etag != null) {
                        editor.putString(KEY_SOURCES_ETAG, loaded.etag).putString(KEY_SOURCES_ETAG_URL, url);
                    } else {
                        editor.remove(KEY_SOURCES_ETAG).remove(KEY_SOURCES_ETAG_URL);
                    }
                    changed = applySources(loaded.sources);
                } else if (result != GitPluginRepository.SourcesResult.NOT_MODIFIED) {
                    editor.remove(KEY_SOURCES_ETAG).remove(KEY_SOURCES_ETAG_URL);
                    changed = applySources(Collections.emptyList());
                }
                editor.apply();
                if (changed) {
                    AndroidUtilities.runOnUIThread(() -> postUpdate(ALL_REPOSITORIES));
                }
            });
        });
    }

    /**
     * Catalog queue: makes the source repositories match the list (order, title, channel, index URL) and removes
     * the ones that are gone. User repositories are not touched. Returns whether anything changed.
     */
    private boolean applySources(List<GitPluginRepository.Source> sources) {
        java.util.LinkedHashMap<Long, GitPluginRepository.Source> wanted = new java.util.LinkedHashMap<>();
        for (GitPluginRepository.Source source : sources) {
            long id = sourceRepositoryId(source.id);
            if (!wanted.containsKey(id)) {
                wanted.put(id, source);
            }
        }
        boolean changed = false;
        SharedPreferences.Editor editor = getPreferences().edit();
        for (PluginRepositoryEntity r : getDao().getRepositories()) {
            long id = r.getRepositoryId();
            if (isSourceRepository(id) && !wanted.containsKey(id)) {
                getDao().removeRepository(id);
                saveEtag(id, null, null);
                editor.remove(sourceUpdatedKey(id)).remove(sourceSyncedUpdatedKey(id));
                changed = true;
            }
        }
        ArrayList<PluginRepositoryEntity> upserts = new ArrayList<>();
        int order = 1;
        long now = System.currentTimeMillis();
        for (Map.Entry<Long, GitPluginRepository.Source> e : wanted.entrySet()) {
            long id = e.getKey();
            GitPluginRepository.Source source = e.getValue();
            PluginRepositoryEntity current = getDao().getRepository(id);
            PluginRepositoryEntity next;
            if (current == null || current.getSource() != PluginRepositoryEntity.SOURCE_FOLDER || !source.indexUrl.equals(current.getUrl())) {
                if (current != null) {
                    getDao().removeRepository(id);
                    saveEtag(id, null, null);
                    editor.remove(sourceSyncedUpdatedKey(id));
                }
                next = new PluginRepositoryEntity(id, source.channel, source.title, PluginRepositoryEntity.SOURCE_FOLDER, order,
                        current != null ? current.getAddedAt() : now, 0, source.plugins, source.indexUrl, null);
            } else {
                // after the first sync the count is the number of plugins this client can show
                next = new PluginRepositoryEntity(id, source.channel, source.title, PluginRepositoryEntity.SOURCE_FOLDER, order,
                        current.getAddedAt(), current.getSyncedAt(), current.getSyncedAt() != 0 ? current.getPluginsCount() : source.plugins,
                        current.getUrl(), current.getHomepage());
            }
            if (!next.equals(current)) {
                upserts.add(next);
                changed = true;
            }
            if (source.updatedAt != null) {
                editor.putString(sourceUpdatedKey(id), source.updatedAt);
            } else {
                editor.remove(sourceUpdatedKey(id));
            }
            order++;
        }
        editor.apply();
        if (!upserts.isEmpty()) {
            getDao().upsertRepositories(upserts);
        }
        return changed;
    }

    private void syncGitRepository(PluginRepositoryEntity repository) {
        String url = repository.getUrl();
        long repositoryId = repository.getRepositoryId();
        String etag = getPreferences().getString(etagKey(repositoryId), null);
        String etagUrl = getPreferences().getString(etagUrlKey(repositoryId), null);
        if (repositoryId == DEFAULT_REPOSITORY_ID && !getPreferences().contains(KEY_MAIN_SOURCE_REFS)) {
            etag = null; // parse the full index once to learn which sources its plugins belong to
        }
        String sourceUpdated = isSourceRepository(repositoryId) ? getPreferences().getString(sourceUpdatedKey(repositoryId), null) : null;
        String requestEtag = etag;
        getNetworkExecutor().execute(() -> {
            Object[] loaded = loadGitIndex(url, etagUrl, requestEtag);
            GitPluginRepository.IndexResult result = (GitPluginRepository.IndexResult) loaded[0];
            String source = (String) loaded[1];
            if (result instanceof GitPluginRepository.IndexResult.Loaded) {
                GitPluginRepository.IndexResult.Loaded index = (GitPluginRepository.IndexResult.Loaded) result;
                ArrayList<CatalogEntryEntity> entries = new ArrayList<>();
                ArrayList<PluginMetadataEntity> metadata = new ArrayList<>();
                buildGitEntries(repository, index.index, entries, metadata);
                String mainRefs;
                boolean mainUncovered = false;
                if (repositoryId == DEFAULT_REPOSITORY_ID) {
                    java.util.TreeSet<String> refs = new java.util.TreeSet<>();
                    for (GitPluginRepository.IndexPlugin plugin : index.index.plugins) {
                        if (plugin.source == null || plugin.source.indexOf(',') >= 0) {
                            mainUncovered = true;
                        } else {
                            refs.add(plugin.source);
                        }
                    }
                    mainRefs = TextUtils.join(",", refs);
                } else {
                    mainRefs = null;
                }
                boolean uncovered = mainUncovered;
                getCatalogQueue().postRunnable(() -> {
                    if (getDao().getRepository(repositoryId) != null) {
                        saveEtag(repositoryId, source, index.etag);
                        SharedPreferences.Editor editor = getPreferences().edit();
                        if (mainRefs != null) {
                            editor.putString(KEY_MAIN_SOURCE_REFS, mainRefs).putBoolean(KEY_MAIN_UNCOVERED, uncovered);
                        }
                        if (sourceUpdated != null) {
                            editor.putString(sourceSyncedUpdatedKey(repositoryId), sourceUpdated);
                        }
                        editor.apply();
                    }
                });
                AndroidUtilities.runOnUIThread(() -> finishSync(repositoryId, entries, metadata, index.index));
            } else if (result == GitPluginRepository.IndexResult.NOT_MODIFIED) {
                getCatalogQueue().postRunnable(() -> {
                    if (sourceUpdated != null && getDao().getRepository(repositoryId) != null) {
                        getPreferences().edit().putString(sourceSyncedUpdatedKey(repositoryId), sourceUpdated).apply();
                    }
                    getDao().updateSyncState(repositoryId, System.currentTimeMillis(), getVisiblePlugins(repositoryId).size());
                    AndroidUtilities.runOnUIThread(() -> {
                        syncingRepositories.remove(repositoryId);
                        postUpdate(repositoryId);
                        postUpdate(ALL_REPOSITORIES);
                        completeSync(repositoryId, 0);
                    });
                });
            } else {
                AndroidUtilities.runOnUIThread(() -> finishSync(repositoryId, null, null, null));
            }
        });
    }

    /** Network thread: tries every index URL of the repository; [result, the URL that answered]. */
    private Object[] loadGitIndex(String url, String etagUrl, String etag) {
        GitPluginRepository.IndexResult last = GitPluginRepository.IndexResult.FAILED;
        for (String next : GitPluginRepository.getIndexUrls(url)) {
            GitPluginRepository.IndexResult result = GitPluginRepository.loadIndex(next, next.equals(etagUrl) ? etag : null);
            if (result != GitPluginRepository.IndexResult.FAILED) {
                return new Object[]{result, next};
            }
            last = result;
        }
        return new Object[]{last, null};
    }

    private void buildGitEntries(PluginRepositoryEntity repository, GitPluginRepository.Index index, List<CatalogEntryEntity> entries, List<PluginMetadataEntity> metadata) {
        int now = (int) (System.currentTimeMillis() / 1000);
        HashSet<Integer> usedIds = new HashSet<>();
        HttpUrl indexUrl = repository.getUrl() != null ? HttpUrl.parse(repository.getUrl()) : null;
        boolean sameHostOnly = isSourceRepository(repository.getRepositoryId());
        for (GitPluginRepository.IndexPlugin plugin : index.plugins) {
            if (sameHostOnly) {
                // a source is trusted like the built-in catalog: its files must come from the same host
                HttpUrl fileUrl = HttpUrl.parse(plugin.fileUrl);
                if (indexUrl == null || fileUrl == null || !fileUrl.host().equalsIgnoreCase(indexUrl.host())) {
                    continue;
                }
            }
            int entryId = plugin.id.hashCode();
            while (!usedIds.add(entryId)) {
                entryId++;
            }
            long documentId = GitPluginRepository.documentId(repository.getRepositoryId(), plugin.id, plugin.sha256);
            int updatedAt = plugin.updatedAt != null ? plugin.updatedAt : now;
            entries.add(new CatalogEntryEntity(repository.getRepositoryId(), entryId, repository.getSyncedAt() == 0 ? updatedAt : now,
                    documentId, plugin.fileName, plugin.size, null, plugin.downloads, updatedAt, plugin.fileUrl, plugin.sha256));
            metadata.add(new PluginMetadataEntity(documentId, plugin.id, plugin.name, plugin.author, plugin.description, plugin.version,
                    plugin.icon, plugin.appVersion, plugin.sdkVersion, plugin.requirements, plugin.categories, null, System.currentTimeMillis(), null));
        }
    }

    private long nextGitRepositoryId() {
        long min = DEFAULT_REPOSITORY_ID;
        for (PluginRepositoryEntity r : getDao().getRepositories()) {
            if (!isSourceRepository(r.getRepositoryId()) && r.getRepositoryId() < min) {
                min = r.getRepositoryId();
            }
        }
        long id = Math.min(min, getPreferences().getLong(KEY_LAST_GIT_REPOSITORY_ID, 0)) - 1;
        getPreferences().edit().putLong(KEY_LAST_GIT_REPOSITORY_ID, id).apply();
        return id;
    }

    private void saveEtag(long repositoryId, String source, String etag) {
        SharedPreferences.Editor editor = getPreferences().edit();
        if (source == null || etag == null) {
            editor.remove(etagKey(repositoryId)).remove(etagUrlKey(repositoryId));
        } else {
            editor.putString(etagKey(repositoryId), etag).putString(etagUrlKey(repositoryId), source);
        }
        editor.apply();
    }

    /** UI thread. entries == null: the sync failed. */
    private void finishSync(long repositoryId, List<CatalogEntryEntity> entries, List<PluginMetadataEntity> metadata, GitPluginRepository.Index index) {
        if (entries == null) {
            syncingRepositories.remove(repositoryId);
            postUpdate(repositoryId);
            completeSync(repositoryId, null);
            return;
        }
        getCatalogQueue().postRunnable(() -> {
            int changed = 0;
            PluginRepositoryEntity repository = getDao().getRepository(repositoryId);
            if (repository != null) {
                HashMap<Integer, CatalogEntryVersion> previous = new HashMap<>();
                for (CatalogEntryVersion version : getDao().getEntryVersions(repositoryId)) {
                    previous.put(version.getEntryId(), version);
                }
                for (CatalogEntryEntity entry : entries) {
                    CatalogEntryVersion old = previous.get(entry.getEntryId());
                    if (old != null) {
                        entry.setDate(old.getDate());
                    }
                    if (old == null) {
                        if ((long) entry.getDate() * 1000 > repository.getSyncedAt() && isSupported(entry.getFileName())) {
                            changed++;
                        }
                    } else if (old.getDocumentId() == entry.getDocumentId()) {
                        entry.setUpdatedAt(old.getUpdatedAt());
                    } else if (isSupported(entry.getFileName())) {
                        changed++;
                    }
                }
                getDao().replaceEntries(repositoryId, entries, metadata);
                if (index != null) {
                    getDao().updateGitInfo(repositoryId, index.name, index.homepage, index.channel);
                }
                getDao().updateSyncState(repositoryId, System.currentTimeMillis(), getVisiblePlugins(repositoryId).size());
            }
            int result = changed;
            AndroidUtilities.runOnUIThread(() -> {
                syncingRepositories.remove(repositoryId);
                postUpdate(repositoryId);
                postUpdate(ALL_REPOSITORIES);
                completeSync(repositoryId, result);
            });
        });
    }

    private void completeSync(long repositoryId, Integer changed) {
        if (pendingForcedSyncs.remove(repositoryId)) {
            syncRepository(repositoryId, true, null);
            return;
        }
        ArrayList<Utilities.Callback<Integer>> callbacks = syncCallbacks.remove(repositoryId);
        if (callbacks != null) {
            for (Utilities.Callback<Integer> callback : callbacks) {
                callback.run(changed);
            }
        }
    }

    /**
     * Downloads a catalog plugin (size and SHA-256 pinned by the index) and checks that the file is the plugin
     * the index describes. Callback (UI thread): the file or null.
     */
    public void downloadPlugin(CatalogPlugin plugin, Utilities.Callback<File> callback) {
        getCatalogQueue().postRunnable(() -> {
            CatalogEntryEntity entry = getDao().getEntry(plugin.getRepositoryId(), plugin.getEntryId());
            if (entry == null || entry.getFileUrl() == null || entry.getSha256() == null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(null));
                return;
            }
            PluginMetadataEntity metadata = plugin.getMetadata();
            getNetworkExecutor().execute(() -> {
                File file = GitPluginRepository.downloadFile(entry.getFileUrl(), entry.getFileName(), entry.getSha256(), entry.getSize());
                if (file == null || metadata == null) {
                    AndroidUtilities.runOnUIThread(() -> callback.run(null));
                    return;
                }
                PluginsController.runOnPluginsQueue(() -> {
                    boolean matches = matchesIndex(file, entry, metadata);
                    if (!matches) {
                        FileLog.e("PluginCatalog: " + entry.getFileName() + " does not match its index entry");
                    }
                    AndroidUtilities.runOnUIThread(() -> callback.run(matches ? file : null));
                });
            });
        });
    }

    /**
     * Plugins queue. exteraGram compares the id and version parsed from the file with the index entry.
     * StaticGram does the same for .plugin files when the Python engine is running; Elyx archives and an
     * engine that is off are left to the install sheet, which parses the file itself (the bytes are already
     * pinned by the SHA-256 of the index).
     */
    private static boolean matchesIndex(File file, CatalogEntryEntity entry, PluginMetadataEntity metadata) {
        try {
            PluginsController.PluginsEngine engine = PluginsController.getPluginEngine(file);
            if (engine == null) {
                return false;
            }
            if (!(engine instanceof PythonPluginsEngine) || !engine.isEngineAvailable()) {
                return true;
            }
            Map<String, String> parsed = ((PythonPluginsEngine) engine).parsePluginMetadata(file.getAbsolutePath());
            String id = parsed != null ? parsed.get("id") : null;
            String version = parsed != null ? parsed.get("version") : null;
            return id != null && id.equals(metadata.getPluginId()) && version != null && version.trim().equals(metadata.getVersion());
        } catch (Throwable e) {
            FileLog.e("PluginCatalog: failed to parse " + entry.getFileName(), e);
            return false;
        }
    }
}
