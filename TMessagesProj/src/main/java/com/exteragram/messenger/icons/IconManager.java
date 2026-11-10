package com.exteragram.messenger.icons;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.os.ext.SdkExtensions;
import android.provider.MediaStore;
import android.util.LongSparseArray;
import android.util.LruCache;
import android.util.SparseArray;
import android.util.SparseIntArray;

import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.drawable.IconCompat;

import com.caverock.androidsvg.SVG;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.IconPackType;
import com.exteragram.messenger.export.output.FileManager;
import com.exteragram.messenger.icons.ui.components.InstallIconPackBottomSheet;
import com.exteragram.messenger.icons.ui.components.ReplaceIconBottomSheet;
import com.exteragram.messenger.icons.ui.picker.IconPickerController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * exteraGram icon pack manager (IconManager.kt). Built-in ("base.*") packs swap drawable ids
 * ({@link #getIcon}); custom packs (files/icon_packs, see {@link IconPackStorage}) replace drawables by resource
 * entry name with decoded PNG/WebP/JPEG/SVG files ({@link #getDrawable}). The active packs follow
 * ExteraConfig.iconPacksLayout: the first custom pack in the list wins, the single "base.*" id is the base pack.
 *
 * exteraGram runs this on coroutines (IO scope, single-thread mutation dispatcher, 2-thread decode dispatcher,
 * single-thread prewarm); StaticGram uses executors with the same roles. Unlike exteraGram the active packs are
 * loaded synchronously by {@link #initialize}: the first load happens in ApplicationLoader.postInitApplication
 * ({@link #onPostInitApplication}); before that a plain initialize(false) (ExteraResources constructor during
 * Activity attach, Application.getResources from AndroidUtilities.&lt;clinit&gt; inside Application.onCreate, ...) is a
 * no-op, so the packs are never latched from a half-initialized process. A failed load is not latched either.
 * The resource-name index (R.drawable reflection), UI reload and prewarm run in the background afterwards.
 */
public final class IconManager {

    public static final IconManager INSTANCE = new IconManager();

    private static final int PICKER_REQUEST_CODE = 43;

    public enum ActivePacksUpdate {
        STALE,
        UNCHANGED,
        CHANGED,
        INITIAL
    }

    private static final Set<String> blacklistedIcons = new HashSet<>(Arrays.asList("system", "smiles_popup", "camera_btn", "cancel_big", "chats_archive_box", "chats_archive_arrow", "chats_archive_muted", "chats_archive_pin", "chats_widget_preview", "circle_big", "contacts_widget_preview", "etg_splash", "ev_minus", "ev_plus", "filled_chatlink_large", "field_carret_empty", "dino_pic", "circle", "widgets_light_badgebg", "greydivider", "greydivider_bottom", "greydivider_top", "groups_limit1", "ic_ab_new", "ic_foreground", "ic_foreground_monet", "ic_foreground_solid", "ic_player", "ic_reply_icon", "icon_background_clip", "icon_background_clip_round", "icon_plane", "icplaceholder", "large_ads_info", "large_away", "large_greeting", "large_log_actions", "large_monetize", "large_quickreplies", "list_selector_ex", "livepin", "load_big", "location_empty", "login_arrow1", "login_phone1", "logo_middle", "map_pin", "map_pin2", "map_pin3", "map_pin_circle", "map_pin_cone2", "map_pin_photo", "msg_media_gallery", "music_empty", "no_passport", "no_password", "nophotos", "nophotos3", "paint_elliptical_brush", "paint_neon_brush", "paint_radial_brush", "phone_activate", "photo_placeholder_in", "photo_tooltip2", "photoview_placeholder", "screencast_big", "scrollbar_vertical_thumb", "scrollbar_vertical_thumb_inset", "newmsg_divider", "ic_launcher_dr", "smiles_info", "sms_bubble", "sms_devices", "stats_tooltip", "sticker", "story_camera", "theme_preview_image", "transparent", "venue_tooltip", "videopreview", "bluecounter", "photobadge", "photos_rounded", "calendar_date", "menu_copy", "redcircle", "tooltip_arrow", "tooltip_arrow_up", "newyear", "cards_chat", "community_cards", "ic_call_notification_answer", "ic_call_notification_decline", "shortcut_compose", "shortcut_user", "slide_dot_big", "slide_dot_small", "cocoon_logo", "cocoon_text", "telegram_logo", "telegram_logo_2", "keeper_icon", "diamond"));

    private final ExecutorService ioExecutor = newExecutor("IconManagerIO", 1);
    private final ExecutorService mutationExecutor = newExecutor("IconManagerMutation", 1);
    private final ExecutorService decodeExecutor = newExecutor("IconManagerDecode", 2);
    private final ExecutorService prewarmExecutor = newExecutor("IconManagerPrewarm", 1);

    private final CopyOnWriteArrayList<IconPack> activePacks = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, IconPack> iconOwnerMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> systemIcons = new ConcurrentHashMap<>();
    private volatile IntStringMap systemNames;
    private volatile SparseIntArray basePreinstalledMap;
    private final CountDownLatch initialPacksLoaded = new CountDownLatch(1);
    private volatile long initializationGeneration;
    private volatile boolean initializationTimedOut;
    private boolean activePacksPublished;
    private Future<?> prewarmJob;
    private final SparseArray<Utilities.Callback<Uri>> resultCallbacks = new SparseArray<>();

    private final ResolvedIndex resolvedCache = new ResolvedIndex(2048);
    private final LruCache<SourceCacheKey, Bitmap> sourceCache;

    private volatile boolean initialized;
    private volatile boolean appReady; // set by ApplicationLoader.postInitApplication
    private boolean initializing; // guards re-entrance: loading the config may ask the (wrapped) application Resources for something

    private IconManager() {
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        sourceCache = new LruCache<SourceCacheKey, Bitmap>(Math.max(1024, maxMemory / 8)) {
            @Override
            protected int sizeOf(SourceCacheKey key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    private static ExecutorService newExecutor(String name, int threads) {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newFixedThreadPool(threads, r -> {
            Thread thread = new Thread(r, name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }

    // region Helper types

    /** Open-addressing int -> String map (resource id -> entry name) built once from R.drawable. */
    public static final class IntStringMap {
        private int[] keys = new int[0];
        private String[] values = new String[0];
        private int mask;
        private int shift;
        private int size;

        public IntStringMap(int expected) {
            int capacity = 4;
            while (capacity < expected * 2) {
                capacity <<= 1;
            }
            allocate(capacity);
        }

        private void allocate(int capacity) {
            keys = new int[capacity];
            values = new String[capacity];
            mask = capacity - 1;
            shift = 32 - Integer.numberOfTrailingZeros(capacity);
        }

        public String get(int key) {
            if (key == 0) {
                return null;
            }
            int i = (-1640531527 * key) >>> shift;
            while (true) {
                int k = keys[i];
                if (k == key) {
                    return values[i];
                }
                if (k == 0) {
                    return null;
                }
                i = (i + 1) & mask;
            }
        }

        public void put(int key, String value) {
            if (key == 0) {
                return;
            }
            if ((size + 1) * 2 > keys.length) {
                grow();
            }
            int i = (-1640531527 * key) >>> shift;
            while (true) {
                int k = keys[i];
                if (k == 0) {
                    keys[i] = key;
                    values[i] = value;
                    size++;
                    return;
                }
                if (k == key) {
                    values[i] = value;
                    return;
                }
                i = (i + 1) & mask;
            }
        }

        private void grow() {
            int[] oldKeys = keys;
            String[] oldValues = values;
            allocate(oldKeys.length << 1);
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] != 0) {
                    put(oldKeys[i], oldValues[i]);
                }
            }
        }
    }

    /** Key of a decoded icon in the bitmap LRU cache. */
    public static final class SourceCacheKey {
        private final long generation;
        private final String packId;
        private final String location;
        private final String fileName;
        private final int resId;
        private final int density;

        public SourceCacheKey(long generation, String packId, String location, String fileName, int resId, int density) {
            this.generation = generation;
            this.packId = packId;
            this.location = location;
            this.fileName = fileName;
            this.resId = resId;
            this.density = density;
        }

        public String getPackId() {
            return packId;
        }

        public int getResId() {
            return resId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof SourceCacheKey)) {
                return false;
            }
            SourceCacheKey k = (SourceCacheKey) o;
            return generation == k.generation && packId.equals(k.packId) && java.util.Objects.equals(location, k.location)
                    && fileName.equals(k.fileName) && resId == k.resId && density == k.density;
        }

        @Override
        public int hashCode() {
            int h = (Long.hashCode(generation) * 31 + packId.hashCode()) * 31;
            h = (h + (location == null ? 0 : location.hashCode())) * 31;
            return ((h + fileName.hashCode()) * 31 + resId) * 31 + density;
        }

        @Override
        public String toString() {
            return "SourceCacheKey(generation=" + generation + ", packId=" + packId + ", location=" + location + ", fileName=" + fileName + ", resId=" + resId + ", density=" + density + ")";
        }
    }

    /** (resId, density) -> cache key of the bitmap that currently replaces it; cleared when it gets too big. */
    public static final class ResolvedIndex {
        private final int capacity;
        private final LongSparseArray<SourceCacheKey> entries = new LongSparseArray<>();

        public ResolvedIndex(int capacity) {
            this.capacity = capacity;
        }

        public synchronized SourceCacheKey get(long key) {
            return entries.get(key);
        }

        public synchronized void put(long key, SourceCacheKey value) {
            if (entries.size() >= capacity) {
                entries.clear();
            }
            entries.put(key, value);
        }

        public synchronized void clear() {
            entries.clear();
        }

        public synchronized void removeByResId(int resId) {
            for (int i = entries.size() - 1; i >= 0; i--) {
                if ((int) (entries.keyAt(i) >> 32) == resId) {
                    entries.removeAt(i);
                }
            }
        }
    }

    // endregion

    private static long resolvedCacheKey(int resId, int density) {
        return (((long) resId) << 32) | (((long) density) & 4294967295L);
    }

    public boolean isBlacklisted(String name) {
        return blacklistedIcons.contains(name) || name.contains("avd") || name.endsWith("_solar") || name.endsWith("_remix")
                || name.contains("$") || name.contains("animationpin") || name.contains("googlepay") || name.contains("shadow")
                || name.startsWith("ic_monochrome") || name.startsWith("nocover") || name.startsWith("gradient_")
                || name.startsWith("stickers_back_") || name.startsWith("media_doc_") || name.startsWith("loading_animation")
                || name.startsWith("intro_") || name.startsWith("minibubble_") || name.startsWith("book_") || name.startsWith("call_")
                || name.startsWith("dice") || name.startsWith("msg_other_new_filled") || name.startsWith("profile_level")
                || name.startsWith("widget_") || name.startsWith("zoom_slide") || name.startsWith("zoom_round")
                || name.startsWith("popup_fixed_alert") || name.startsWith("search_dark") || name.startsWith("bar_selector");
    }

    /** Drawable entry name -> id of every drawable an icon pack may replace (filled in the background after the first load). */
    public ConcurrentHashMap<String, Integer> getSystemIcons() {
        return systemIcons;
    }

    private SourceCacheKey sourceCacheKey(IconPack pack, String fileName, int resId, int density) {
        File location = pack.getLocation();
        return new SourceCacheKey(initializationGeneration, pack.getId(), location != null ? location.getAbsolutePath() : null, fileName, resId, density);
    }

    private void invalidateIconCaches(String packId, int resId) {
        resolvedCache.removeByResId(resId);
        for (SourceCacheKey key : new ArrayList<>(sourceCache.snapshot().keySet())) {
            if (key.getPackId().equals(packId) && key.getResId() == resId) {
                sourceCache.remove(key);
            }
        }
    }

    private synchronized boolean publishBitmap(long generation, String resourceName, IconPack pack, int resId, int density, Bitmap bitmap) {
        IconPack owner = iconOwnerMap.get(resourceName);
        if (generation != initializationGeneration || owner == null || (owner != pack && !owner.equals(pack))) {
            return false;
        }
        String fileName = pack.getIcons().get(resourceName);
        if (fileName == null) {
            return false;
        }
        SourceCacheKey key = sourceCacheKey(pack, fileName, resId, density);
        sourceCache.put(key, bitmap);
        resolvedCache.put(resolvedCacheKey(resId, density), key);
        return true;
    }

    private String resourceName(int resId) {
        IntStringMap names = systemNames;
        String name = names != null ? names.get(resId) : null;
        if (name == null) {
            try {
                name = ApplicationLoader.applicationContext.getResources().getResourceEntryName(resId);
            } catch (Exception ignore) {
                return null;
            }
        }
        return name;
    }

    // region Initialization

    public static void initialize$default(IconManager manager, boolean update, int mask, Object unused) {
        manager.initialize((mask & 1) == 0 && update);
    }

    /** ExteraConfig.init (exteraGram: ExteraConfig.init -> IconManager.prefetchCustomPacks): hides newly found packs. */
    public void prefetchCustomPacks() {
        ioExecutor.execute(() -> {
            try {
                syncInstalledCustomPacks(IconPackStorage.INSTANCE.getCustomPacks());
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** Installed packs that are neither enabled nor hidden yet go to the hidden list. */
    private synchronized boolean syncInstalledCustomPacks(List<IconPack> packs) {
        boolean changed = false;
        for (IconPack pack : packs) {
            String id = pack.getId();
            if (!ExteraConfig.getIconPacksLayout().contains(id) && !ExteraConfig.getIconPacksHidden().contains(id)) {
                ExteraConfig.getIconPacksHidden().add(id);
                changed = true;
            }
        }
        if (changed) {
            ExteraConfig.saveIconPacksLayout();
        }
        return changed;
    }

    private void rebuildOwnerMap() {
        iconOwnerMap.clear();
        for (int i = activePacks.size() - 1; i >= 0; i--) {
            IconPack pack = activePacks.get(i);
            if (!pack.isBase()) {
                for (String name : pack.getIcons().keySet()) {
                    iconOwnerMap.put(name, pack);
                }
            }
        }
        rebuildBasePreinstalledMap();
    }

    private void rebuildBasePreinstalledMap() {
        SparseIntArray map = new SparseIntArray();
        for (IconPack pack : activePacks) {
            SparseIntArray preinstalled = pack.getPreinstalledMap();
            if (preinstalled == null || !pack.isBase()) {
                continue;
            }
            for (int i = 0, n = preinstalled.size(); i < n; i++) {
                int key = preinstalled.keyAt(i);
                if (map.indexOfKey(key) < 0) {
                    map.put(key, preinstalled.valueAt(i));
                }
            }
        }
        basePreinstalledMap = map.size() == 0 ? null : map;
    }

    private synchronized ActivePacksUpdate updateActivePacks(long generation, List<IconPack> newActivePacks) {
        if (generation != initializationGeneration) {
            return ActivePacksUpdate.STALE;
        }
        boolean published = activePacksPublished;
        activePacksPublished = true;
        if (published && activePacks.equals(newActivePacks)) {
            return ActivePacksUpdate.UNCHANGED;
        }
        activePacks.clear();
        activePacks.addAll(newActivePacks);
        rebuildOwnerMap();
        resolvedCache.clear();
        sourceCache.evictAll();
        return published ? ActivePacksUpdate.CHANGED : ActivePacksUpdate.INITIAL;
    }

    /**
     * Rebuilds the active packs from ExteraConfig; with {@code update} the UI is reloaded when they changed
     * (and caches are dropped and iconPackUpdated is posted when they did not).
     */
    public synchronized void initialize(boolean update) {
        if (initializing || (!update && (initialized || !appReady))) {
            return;
        }
        initializing = true;
        ArrayList<IconPack> packs = new ArrayList<>();
        boolean loaded = false;
        try {
            ExteraConfig.loadConfig();
            for (String id : ExteraConfig.getIconPacksLayout()) {
                if (id == null) {
                    continue;
                }
                IconPack pack = id.startsWith("base.") ? BaseIconPacks.INSTANCE.getBasePack(id) : IconPackStorage.INSTANCE.findPackById(id);
                if (pack != null) {
                    packs.add(pack);
                }
            }
            loaded = true;
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            initializing = false;
        }
        if (!loaded) {
            return;
        }
        if (prewarmJob != null) {
            prewarmJob.cancel(true);
            prewarmJob = null;
        }
        long generation = ++initializationGeneration;
        ActivePacksUpdate result = updateActivePacks(generation, packs);
        initialized = true;
        initialPacksLoaded.countDown();
        if (BuildVars.LOGS_ENABLED) {
            StringBuilder ids = new StringBuilder();
            for (IconPack pack : packs) {
                ids.append(ids.length() == 0 ? "" : ",").append(pack.getId());
            }
            SparseIntArray map = basePreinstalledMap;
            FileLog.d("IconManager: initialize(update=" + update + ") layout=" + ExteraConfig.getIconPacksLayout()
                    + " active=[" + ids + "] replacements=" + (map == null ? 0 : map.size()) + "+" + iconOwnerMap.size() + " result=" + result);
        }
        ioExecutor.execute(() -> {
            buildSystemIconsIfNeeded();
            if (result == ActivePacksUpdate.UNCHANGED) {
                if (update) {
                    resolvedCache.clear();
                    sourceCache.evictAll();
                    AndroidUtilities.runOnUIThread(() -> {
                        if (generation == initializationGeneration) {
                            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.iconPackUpdated);
                        }
                    });
                }
            } else if (result == ActivePacksUpdate.CHANGED || result == ActivePacksUpdate.INITIAL) {
                AndroidUtilities.runOnUIThread(() -> applyActivePacks(generation, result));
            }
            if (result != ActivePacksUpdate.STALE) {
                launchPrewarm(generation);
            }
        });
    }

    /** ApplicationLoader.postInitApplication hook (exteraGram ApplicationLoader.smali:1959 IconManager.initialize(false)). */
    public void onPostInitApplication() {
        appReady = true;
        initialize(false);
    }

    private void buildSystemIconsIfNeeded() {
        if (!systemIcons.isEmpty()) {
            return;
        }
        try {
            Field[] fields = R.drawable.class.getFields();
            IntStringMap names = new IntStringMap(2048);
            HashMap<String, Integer> icons = new HashMap<>(fields.length * 2);
            for (Field field : fields) {
                String name = field.getName();
                if (!isBlacklisted(name)) {
                    int id = field.getInt(null);
                    icons.put(name, id);
                    names.put(id, name);
                }
            }
            systemIcons.putAll(icons);
            systemNames = names;
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** UI part of the initialization job (IconManager$initialize$1$2): icon picker state and the resource reload. */
    private void applyActivePacks(long generation, ActivePacksUpdate result) {
        if (generation != initializationGeneration) {
            return;
        }
        String editingId = ExteraConfig.getEditingIconPackId();
        if (editingId != null) {
            IconPack editing = null;
            IconPack firstCustom = null;
            for (IconPack pack : activePacks) {
                if (editing == null && pack.getId().equals(editingId)) {
                    editing = pack;
                }
                if (firstCustom == null && !pack.isBase()) {
                    firstCustom = pack;
                }
            }
            BaseFragment fragment = LaunchActivity.getSafeLastFragment();
            Activity activity = fragment != null ? fragment.getParentActivity() : null;
            LaunchActivity launchActivity = activity instanceof LaunchActivity ? (LaunchActivity) activity : null;
            if (editing != null && firstCustom != null && editing.getId().equals(firstCustom.getId())) {
                IconPickerController.setActive(launchActivity, true);
            } else {
                ExteraConfig.setEditingIconPackId(null);
                IconPickerController.setActive(launchActivity, false);
            }
        }
        if (result == ActivePacksUpdate.INITIAL && !initializationTimedOut) {
            return;
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.iconPackUpdated);
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null) {
            return;
        }
        Activity activity = fragment.getParentActivity();
        if (activity != null) {
            Theme.reloadAllResources(activity);
        }
        if (result == ActivePacksUpdate.INITIAL) {
            if (activity instanceof LaunchActivity) {
                ((LaunchActivity) activity).rebuildAllFragments(true);
            }
        } else {
            INavigationLayout layout = fragment.getParentLayout();
            if (layout != null) {
                layout.rebuildFragments(0);
            }
        }
    }

    public void awaitInitialization() {
        initialize(false);
    }

    /** Decodes up to 256 replaced icons (150 ms budget) so the first frames after a change are already cached. */
    private synchronized void launchPrewarm(long generation) {
        if (generation != initializationGeneration) {
            return;
        }
        if (prewarmJob != null) {
            prewarmJob.cancel(true);
        }
        prewarmJob = prewarmExecutor.submit(() -> {
            int density = AndroidUtilities.displayMetrics.densityDpi;
            long deadline = SystemClock.elapsedRealtime() + 150;
            int count = 0;
            for (Map.Entry<String, IconPack> entry : iconOwnerMap.entrySet()) {
                if (Thread.currentThread().isInterrupted() || generation != initializationGeneration) {
                    return;
                }
                String name = entry.getKey();
                Integer resId = systemIcons.get(name);
                if (resId == null) {
                    continue;
                }
                if (count++ >= 256 || SystemClock.elapsedRealtime() >= deadline) {
                    return;
                }
                IconPack pack = entry.getValue();
                Bitmap bitmap = getPackIconBitmap(pack, resId, density, null, name, false);
                if (bitmap != null) {
                    if (Thread.currentThread().isInterrupted() || generation != initializationGeneration) {
                        return;
                    }
                    publishBitmap(generation, name, pack, resId, density, bitmap);
                }
            }
        });
    }

    // endregion

    // region Drawables

    /**
     * True when some drawable is replaced, i.e. the application context has to serve {@link ExteraResources}.
     * Every base pack (also "base.default" with its popup_fixed_alert pairs) has replacements, so this is true
     * once initialized, matching exteraGram where LaunchActivity.getResources() always returns ExteraResources.
     */
    public boolean hasReplacements() {
        return basePreinstalledMap != null || !iconOwnerMap.isEmpty(); // never initializes: getResources() asks this at any time
    }

    public int getIcon(int resId) {
        SparseIntArray map = basePreinstalledMap;
        if (map == null) {
            return resId;
        }
        int mapped = map.get(resId, -1);
        return mapped == -1 ? resId : mapped;
    }

    private Drawable toIconDrawable(Bitmap bitmap, Resources resources) {
        BitmapDrawable drawable = new BitmapDrawable(resources, bitmap);
        int appDensity = ApplicationLoader.applicationContext.getResources().getDisplayMetrics().densityDpi;
        int targetDensity = resources.getDisplayMetrics().densityDpi;
        if (bitmap.getDensity() != 0 && appDensity != targetDensity) {
            drawable.setTargetDensity((targetDensity * bitmap.getDensity()) / appDensity);
        }
        return drawable;
    }

    public Drawable getDrawable(int resId, int density, Resources.Theme theme) {
        return getDrawable(resId, density, theme, ApplicationLoader.applicationContext.getResources());
    }

    /** Replacement of {@code resId} from a custom pack, or null (ExteraResources then asks {@link #getIcon}). */
    public Drawable getDrawable(int resId, int density, Resources.Theme theme, Resources resources) {
        if (iconOwnerMap.isEmpty()) {
            return null;
        }
        if (density == 0) {
            density = AndroidUtilities.displayMetrics.densityDpi;
        }
        SourceCacheKey key = resolvedCache.get(resolvedCacheKey(resId, density));
        Bitmap cached = key != null ? sourceCache.get(key) : null;
        if (cached != null) {
            return toIconDrawable(cached, resources);
        }
        String name = resourceName(resId);
        if (name == null) {
            return null;
        }
        IconPack pack = iconOwnerMap.get(name);
        if (pack == null) {
            return null;
        }
        long generation = initializationGeneration;
        Bitmap bitmap = getPackIconBitmap(pack, resId, density, theme, name, false);
        if (bitmap == null) {
            return null;
        }
        publishBitmap(generation, name, pack, resId, density, bitmap);
        return toIconDrawable(bitmap, resources);
    }

    public Drawable getCachedDrawable(int resId) {
        if (iconOwnerMap.isEmpty()) {
            return null;
        }
        SourceCacheKey key = resolvedCache.get(resolvedCacheKey(resId, AndroidUtilities.displayMetrics.densityDpi));
        Bitmap bitmap = key != null ? sourceCache.get(key) : null;
        return bitmap != null ? new BitmapDrawable(ApplicationLoader.applicationContext.getResources(), bitmap) : null;
    }

    public Drawable getPackIconDrawable(IconPack pack, int resId) {
        Bitmap bitmap = getPackIconBitmap(pack, resId, AndroidUtilities.displayMetrics.densityDpi, null, null, true);
        return bitmap != null ? new BitmapDrawable(ApplicationLoader.applicationContext.getResources(), bitmap) : null;
    }

    private Bitmap cachedPackIconBitmap(IconPack pack, int resId, int density) {
        IntStringMap names = systemNames;
        String name = names != null ? names.get(resId) : null;
        String fileName = name != null ? pack.getIcons().get(name) : null;
        return fileName != null ? sourceCache.get(sourceCacheKey(pack, fileName, resId, density)) : null;
    }

    public Drawable getCachedPackIconDrawable(IconPack pack, int resId) {
        Bitmap bitmap = cachedPackIconBitmap(pack, resId, AndroidUtilities.displayMetrics.densityDpi);
        return bitmap != null ? new BitmapDrawable(ApplicationLoader.applicationContext.getResources(), bitmap) : null;
    }

    public void requestPackIconDrawable(IconPack pack, int resId, Utilities.Callback<Drawable> callback) {
        int density = AndroidUtilities.displayMetrics.densityDpi;
        Bitmap cached = cachedPackIconBitmap(pack, resId, density);
        if (cached != null) {
            callback.run(new BitmapDrawable(ApplicationLoader.applicationContext.getResources(), cached));
            return;
        }
        decodeExecutor.execute(() -> {
            Bitmap bitmap = getPackIconBitmap(pack, resId, density, null, null, true);
            if (bitmap != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(new BitmapDrawable(ApplicationLoader.applicationContext.getResources(), bitmap)));
            }
        });
    }

    public void requestDrawable(int resId, Utilities.Callback<Drawable> callback) {
        if (iconOwnerMap.isEmpty()) {
            return;
        }
        Drawable cached = getCachedDrawable(resId);
        if (cached != null) {
            callback.run(cached);
            return;
        }
        int density = AndroidUtilities.displayMetrics.densityDpi;
        decodeExecutor.execute(() -> {
            Drawable drawable = getDrawable(resId, density, null, ApplicationLoader.applicationContext.getResources());
            if (drawable != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(drawable));
            }
        });
    }

    /** Decodes the replacement of {@code resId} at {@code sizePx} (big previews: player covers, folder icons). */
    public void requestDrawableForSize(int resId, int sizePx, Utilities.Callback<Drawable> callback) {
        IntStringMap names = systemNames;
        if (iconOwnerMap.isEmpty() || sizePx <= 0 || names == null) {
            return;
        }
        String name = names.get(resId);
        IconPack pack = name != null ? iconOwnerMap.get(name) : null;
        String fileName = pack != null ? pack.getIcons().get(name) : null;
        if (fileName == null) {
            return;
        }
        decodeExecutor.execute(() -> {
            File file = resolvePackIconFile(pack, fileName);
            if (file == null) {
                return;
            }
            Bitmap bitmap = decodeIconFile(file.getAbsolutePath(), sizePx, sizePx, AndroidUtilities.displayMetrics.densityDpi);
            if (bitmap != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(new BitmapDrawable(ApplicationLoader.applicationContext.getResources(), bitmap)));
            }
        });
    }

    private File resolvePackIconFile(IconPack pack, String iconFileName) {
        File location = pack.getLocation();
        if (location == null) {
            location = new File(IconPackStorage.INSTANCE.getIconPacksDirectory(), pack.getId());
        }
        try {
            File root = location.getCanonicalFile();
            File file = new File(root, iconFileName).getCanonicalFile();
            if (!file.getPath().startsWith(root.getPath() + File.separator)) {
                return null;
            }
            return file;
        } catch (Exception e) {
            FileLog.e("Failed to resolve icon file for pack " + pack.getId(), e);
            return null;
        }
    }

    private Bitmap getPackIconBitmap(IconPack pack, int resId, int density, Resources.Theme theme, String knownResourceName, boolean cacheResult) {
        String name = knownResourceName != null ? knownResourceName : resourceName(resId);
        if (name == null) {
            return null;
        }
        String fileName = pack.getIcons().get(name);
        if (fileName == null) {
            return null;
        }
        SourceCacheKey key = sourceCacheKey(pack, fileName, resId, density);
        Bitmap cached = sourceCache.get(key);
        if (cached != null) {
            return cached;
        }
        File file = resolvePackIconFile(pack, fileName);
        if (file == null) {
            return null;
        }
        Bitmap bitmap = createBitmapFromFile(file.getAbsolutePath(), resId, density, theme);
        if (bitmap != null && cacheResult) {
            sourceCache.put(key, bitmap);
        }
        return bitmap;
    }

    /** Decodes an icon file at the intrinsic size of the drawable it replaces (24dp when unknown). */
    public Bitmap createBitmapFromFile(String path, int originalResId, int density, Resources.Theme theme) {
        try {
            Resources resources = ApplicationLoader.applicationContext.getResources();
            Drawable original = resources instanceof ExteraResources ? ((ExteraResources) resources).getOriginalDrawable(originalResId) : null;
            if (original == null && !(resources instanceof ExteraResources)) {
                original = ResourcesCompat.getDrawableForDensity(resources, originalResId, density, theme);
            }
            int width = Math.max(1, original != null ? original.getIntrinsicWidth() : AndroidUtilities.dp(24));
            int height = Math.max(1, original != null ? original.getIntrinsicHeight() : AndroidUtilities.dp(24));
            return decodeIconFile(path, width, height, density);
        } catch (Exception e) {
            FileLog.e("Error loading icon bitmap: " + path, e);
            return null;
        } catch (OutOfMemoryError e) {
            FileLog.e("Out of memory loading icon bitmap: " + path, e);
            return null;
        }
    }

    private Bitmap decodeIconFile(String path, int targetW, int targetH, int density) {
        try {
            File file = new File(path);
            Bitmap stored = DecodedIconStore.INSTANCE.get(file, targetW, targetH, density);
            if (stored != null) {
                return stored;
            }
            if (path.toLowerCase(java.util.Locale.ROOT).endsWith(".svg")) {
                SVG svg;
                try (FileInputStream in = new FileInputStream(path)) {
                    svg = SVG.getFromInputStream(in);
                }
                Bitmap bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                svg.setDocumentWidth(targetW);
                svg.setDocumentHeight(targetH);
                svg.renderToCanvas(canvas);
                bitmap.setDensity(density);
                DecodedIconStore.INSTANCE.put(file, targetW, targetH, density, bitmap);
                return bitmap;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);
            int width = options.outWidth;
            int height = options.outHeight;
            if (width <= 0 || height <= 0 || (long) width * height > 100000000L) {
                return null;
            }
            options.inSampleSize = 1;
            if (height > targetH || width > targetW) {
                int halfH = height / 2;
                int halfW = width / 2;
                while (halfH / options.inSampleSize >= targetH && halfW / options.inSampleSize >= targetW) {
                    options.inSampleSize *= 2;
                }
            }
            long maxPixels = Math.max((long) targetW * targetH, 1048576L);
            while (true) {
                long sample = options.inSampleSize;
                if (((width + sample - 1) / sample) * ((height + sample - 1) / sample) <= maxPixels) {
                    break;
                }
                options.inSampleSize *= 2;
            }
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded = BitmapFactory.decodeFile(path, options);
            if (decoded == null) {
                return null;
            }
            Bitmap result;
            if (decoded.getWidth() != targetW || decoded.getHeight() != targetH) {
                result = Bitmap.createScaledBitmap(decoded, targetW, targetH, true);
                if (result != decoded) {
                    decoded.recycle();
                }
            } else {
                result = decoded;
            }
            result.setDensity(density);
            DecodedIconStore.INSTANCE.put(file, targetW, targetH, density, result);
            return result;
        } catch (Exception e) {
            FileLog.e("Error loading icon bitmap: " + path, e);
            return null;
        } catch (OutOfMemoryError e) {
            FileLog.e("Out of memory loading icon bitmap: " + path, e);
            return null;
        }
    }

    // endregion

    // region Editing

    private static void deleteQuietly(File file) {
        try {
            if (file != null && file.exists()) {
                file.delete();
            }
        } catch (Throwable ignore) {
        }
    }

    private void replaceActivePack(IconPack updated) {
        for (int i = 0; i < activePacks.size(); i++) {
            if (activePacks.get(i).getId().equals(updated.getId())) {
                activePacks.set(i, updated);
                rebuildOwnerMap();
                return;
            }
        }
    }

    private int indexOfActivePack(String id) {
        for (int i = 0; i < activePacks.size(); i++) {
            if (activePacks.get(i).getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** Copies {@code tempFile} into the pack as the replacement of {@code resId} (replace-icon sheet "Save"). */
    public void saveCustomIcon(String packId, int resId, File tempFile, String originalName) {
        String resourceName = resourceName(resId);
        if (resourceName == null) {
            return;
        }
        mutationExecutor.execute(() -> {
            File target = null;
            try {
                IconPack pack = IconPackStorage.INSTANCE.findPackById(packId);
                if (pack == null) {
                    return;
                }
                String extension = IconPackStorage.extensionOf(tempFile);
                String userName = originalName != null ? FileManager.fileNameFromUserString(originalName) : null;
                String baseName = userName == null || userName.isEmpty() ? resourceName : userName;
                int dot = baseName.lastIndexOf('.');
                String stem = dot >= 0 ? baseName.substring(0, dot) : baseName;
                if (stem.isEmpty()) {
                    stem = resourceName;
                }
                if (stem.length() > 64) {
                    stem = stem.substring(0, 64);
                }
                if (!stem.isEmpty() && Character.isHighSurrogate(stem.charAt(stem.length() - 1))) {
                    stem = stem.substring(0, stem.length() - 1);
                }
                File dir = new File(IconPackStorage.INSTANCE.getIconPacksDirectory(), pack.getId());
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    return;
                }
                do {
                    target = new File(dir, stem + "_" + UUID.randomUUID() + "." + extension);
                } while (target.exists());
                copyFile(tempFile, target);
                HashMap<String, String> icons = new HashMap<>(pack.getIcons());
                String previous = icons.put(resourceName, target.getName());
                IconPack updated = pack.copyWithIcons(icons);
                if (!IconPackStorage.INSTANCE.saveIconPackMetadata(updated)) {
                    deleteQuietly(target);
                    return;
                }
                target = null; // saved: keep it
                if (previous != null && !icons.containsValue(previous)) {
                    deleteQuietly(resolvePackIconFile(pack, previous));
                }
                int density = AndroidUtilities.displayMetrics.densityDpi;
                File saved = new File(dir, icons.get(resourceName));
                Bitmap preDecoded = createBitmapFromFile(saved.getAbsolutePath(), resId, density, null);
                AndroidUtilities.runOnUIThread(() -> {
                    if (indexOfActivePack(updated.getId()) != -1) {
                        replaceActivePack(updated);
                        invalidateIconCaches(updated.getId(), resId);
                        if (preDecoded != null) {
                            publishBitmap(initializationGeneration, resourceName, updated, resId, density, preDecoded);
                        }
                    }
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.iconPackUpdated);
                });
            } catch (Exception e) {
                FileLog.e("Failed to save custom icon", e);
                deleteQuietly(target);
            } finally {
                deleteQuietly(tempFile);
            }
        });
    }

    private static void copyFile(File from, File to) throws java.io.IOException {
        try (java.io.InputStream in = new FileInputStream(from); java.io.OutputStream out = new java.io.FileOutputStream(to)) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    /** Removes the replacement of {@code resId} from the pack (replace-icon sheet "Reset"). */
    public void resetCustomIcon(String packId, int resId) {
        String resourceName = resourceName(resId);
        if (resourceName == null) {
            return;
        }
        mutationExecutor.execute(() -> {
            IconPack pack = IconPackStorage.INSTANCE.findPackById(packId);
            String fileName = pack != null ? pack.getIcons().get(resourceName) : null;
            if (fileName == null) {
                return;
            }
            HashMap<String, String> icons = new HashMap<>(pack.getIcons());
            icons.remove(resourceName);
            IconPack updated = pack.copyWithIcons(icons);
            if (!IconPackStorage.INSTANCE.saveIconPackMetadata(updated)) {
                return;
            }
            if (!icons.containsValue(fileName)) {
                try {
                    deleteQuietly(resolvePackIconFile(pack, fileName));
                } catch (Throwable e) {
                    FileLog.e("Failed to delete old icon", e);
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (indexOfActivePack(updated.getId()) != -1) {
                    replaceActivePack(updated);
                    invalidateIconCaches(updated.getId(), resId);
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.iconPackUpdated);
            });
        });
    }

    // endregion

    // region Notification icon

    /** Small notification icon: the "notification" icon of the first enabled custom pack, else the base pack's one. */
    public static IconCompat getNotificationIcon() {
        try {
            ExteraConfig.loadConfig();
            IconPack custom = null;
            for (String id : new ArrayList<>(ExteraConfig.getIconPacksLayout())) {
                if (id.startsWith("base.")) {
                    continue;
                }
                IconPack pack = IconPackStorage.INSTANCE.findPackById(id);
                if (pack != null && pack.getIcons().containsKey("notification")) {
                    custom = pack;
                    break;
                }
            }
            if (custom != null) {
                IconCompat icon = null;
                if (Build.VERSION.SDK_INT >= 31) {
                    Uri uri = IconPackProvider.getIconUri(custom.getId(), "notification");
                    if (uri != null) {
                        try {
                            ApplicationLoader.applicationContext.grantUriPermission("com.android.systemui", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                        icon = IconCompat.createWithContentUri(uri);
                    }
                } else {
                    Bitmap bitmap = INSTANCE.getPackIconBitmap(custom, R.drawable.notification, AndroidUtilities.displayMetrics.densityDpi, null, "notification", true);
                    if (bitmap != null) {
                        icon = IconCompat.createWithBitmap(bitmap);
                    }
                }
                if (icon != null) {
                    return icon;
                }
            }
            int resId = R.drawable.notification;
            for (String id : new ArrayList<>(ExteraConfig.getIconPacksLayout())) {
                if (!id.startsWith("base.")) {
                    continue;
                }
                IconPack base = BaseIconPacks.INSTANCE.getBasePack(id);
                SparseIntArray map = base != null ? base.getPreinstalledMap() : null;
                int mapped = map != null ? map.get(R.drawable.notification, -1) : -1;
                if (mapped != -1) {
                    resId = mapped;
                    break;
                }
            }
            return IconCompat.createWithResource(ApplicationLoader.applicationContext, resId);
        } catch (Exception e) {
            FileLog.e("Failed to resolve notification icon", e);
            return IconCompat.createWithResource(ApplicationLoader.applicationContext, R.drawable.notification);
        }
    }

    public static Icon getNotificationSystemIcon() {
        return getNotificationIcon().toIcon(ApplicationLoader.applicationContext);
    }

    // endregion

    // region Packs

    public void setActiveCustomPack(String packId) {
        if (packId == null || ExteraConfig.getIconPacksLayout().contains(packId)) {
            return;
        }
        ExteraConfig.getIconPacksLayout().add(packId);
        ExteraConfig.getIconPacksHidden().remove(packId);
        ExteraConfig.saveIconPacksLayout();
        initialize(true);
    }

    public IconPack findPackById(String packId) {
        if (packId != null && packId.startsWith("base.")) {
            return BaseIconPacks.INSTANCE.getBasePack(packId);
        }
        return IconPackStorage.INSTANCE.findPackById(packId);
    }

    public File bundlePackBlocking(String packId) {
        return IconPackStorage.INSTANCE.bundlePackBlocking(packId);
    }

    /** Saves name/author/version of a pack (keeping the icons on disk); blocking, call it off the main thread. */
    public boolean saveIconPackMetadata(IconPack iconPack) {
        IconPack saved;
        try {
            saved = mutationExecutor.submit(() -> {
                IconPack stored = IconPackStorage.INSTANCE.findPackById(iconPack.getId());
                IconPack toSave = stored != null ? iconPack.copyWithIcons(stored.getIcons()) : iconPack;
                return IconPackStorage.INSTANCE.saveIconPackMetadata(toSave) ? toSave : null;
            }).get();
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
        if (saved == null) {
            return false;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (indexOfActivePack(saved.getId()) != -1) {
                replaceActivePack(saved);
                resolvedCache.clear();
                sourceCache.evictAll();
            }
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.iconPackUpdated);
        });
        return true;
    }

    public void deletePack(String packId) {
        mutationExecutor.execute(() -> {
            IconPackStorage.INSTANCE.deletePack(packId);
            AndroidUtilities.runOnUIThread(() -> {
                if (ExteraConfig.getIconPacksLayout().contains(packId) || ExteraConfig.getIconPacksHidden().contains(packId)) {
                    ExteraConfig.getIconPacksLayout().remove(packId);
                    ExteraConfig.getIconPacksHidden().remove(packId);
                    ExteraConfig.saveIconPacksLayout();
                }
                initialize(true);
            });
        });
    }

    public boolean isIconPack(String path, MessageObject messageObject) {
        return messageObject != null && messageObject.getDocumentName() != null && path != null && !path.isEmpty() && path.endsWith(".icons");
    }

    private String iconPackErrorText(IconPackStorageError error) {
        int res;
        switch (error) {
            case INVALID_ARCHIVE:
                res = R.string.IconPackErrorInvalidArchive;
                break;
            case MISSING_METADATA:
                res = R.string.IconPackErrorMissingMetadata;
                break;
            case METADATA_TOO_LARGE:
                res = R.string.IconPackErrorMetadataTooLarge;
                break;
            case INVALID_METADATA:
                res = R.string.IconPackErrorInvalidMetadata;
                break;
            case TOO_MANY_FILES:
                res = R.string.IconPackErrorTooManyFiles;
                break;
            case ARCHIVE_TOO_LARGE:
                res = R.string.IconPackErrorArchiveTooLarge;
                break;
            case FILE_TOO_LARGE:
                res = R.string.IconPackErrorFileTooLarge;
                break;
            case COMPRESSION_RATIO_TOO_HIGH:
                res = R.string.IconPackErrorCompressionRatioTooHigh;
                break;
            case STORAGE_ERROR:
                res = R.string.IconPackErrorStorage;
                break;
            default:
                res = R.string.UnknownError;
                break;
        }
        return LocaleController.getString(res);
    }

    private void showIconPackError(BaseFragment fragment, IconPackStorageError error) {
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, iconPackErrorText(error)).show();
    }

    /** ExteraFileHandler.open for a chat/shared-media document: a downloaded ".icons" file opens the install sheet. */
    public static boolean openIconPack(BaseFragment fragment, MessageObject messageObject) {
        if (fragment == null || messageObject == null) {
            return false;
        }
        String name = messageObject.getDocumentName();
        if (name == null || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".icons")) {
            return false;
        }
        String path = com.exteragram.messenger.utils.chats.ChatUtils.getInstance().getPathToMessage(messageObject);
        if (path == null || path.isEmpty() || !new File(path).exists()) {
            return false;
        }
        INSTANCE.handleIconPack(fragment, path);
        return true;
    }

    /** Opens the install sheet for a ".icons" file (chat attachment, shared or opened file). */
    public void handleIconPack(BaseFragment fragment, String path) {
        ioExecutor.execute(() -> {
            File file = new File(path);
            IconPackStorageResult<IconPack> result = IconPackStorage.INSTANCE.parsePackFromZip(file);
            AndroidUtilities.runOnUIThread(() -> {
                if (result instanceof IconPackStorageResult.Success) {
                    IconPack pack = ((IconPackStorageResult.Success<IconPack>) result).getValue();
                    fragment.showDialog(new InstallIconPackBottomSheet(fragment.getParentActivity(), pack, (enable, update) -> ioExecutor.execute(() -> {
                        IconPackStorageResult<Void> install = IconPackStorage.INSTANCE.installPack(file);
                        AndroidUtilities.runOnUIThread(() -> {
                            if (install instanceof IconPackStorageResult.Success) {
                                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, LocaleController.formatString(update ? R.string.PluginUpdated : R.string.PluginInstalled, pack.getName())).show();
                                if (enable) {
                                    setActiveCustomPack(pack.getId());
                                } else {
                                    syncInstalledCustomPacks(Collections.singletonList(pack));
                                    initialize(true);
                                }
                            } else if (install instanceof IconPackStorageResult.Failure) {
                                showIconPackError(fragment, ((IconPackStorageResult.Failure<Void>) install).getError());
                            }
                        });
                    })));
                } else if (result instanceof IconPackStorageResult.Failure) {
                    showIconPackError(fragment, ((IconPackStorageResult.Failure<IconPack>) result).getError());
                }
            });
        });
    }

    /** BaseFragment.onActivityResultFragment hook: result of {@link #startIconPicker}. */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        Utilities.Callback<Uri> callback = resultCallbacks.get(requestCode);
        if (callback == null) {
            return false;
        }
        resultCallbacks.remove(requestCode);
        callback.run(data != null ? data.getData() : null);
        return true;
    }

    private static boolean isPhotoPickerAvailable() {
        if (Build.VERSION.SDK_INT >= 33) {
            return true;
        }
        return Build.VERSION.SDK_INT >= 30 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2;
    }

    /** Picks an image for the replace-icon sheet: the system photo picker, or a document picker for PNG/SVG files. */
    public void startIconPicker(Activity activity, boolean selectFromFiles, Utilities.Callback<Uri> callback) {
        resultCallbacks.put(PICKER_REQUEST_CODE, callback);
        Intent intent;
        if (!selectFromFiles && isPhotoPickerAvailable()) {
            intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
            intent.setType("image/*");
        } else {
            intent = new Intent(selectFromFiles ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "image/svg+xml"});
            if (selectFromFiles) {
                intent.addCategory(Intent.CATEGORY_OPENABLE);
            }
        }
        try {
            activity.startActivityForResult(intent, PICKER_REQUEST_CODE);
        } catch (Exception e) {
            FileLog.e(e);
            resultCallbacks.remove(PICKER_REQUEST_CODE);
            callback.run(null);
        }
    }

    public void showReplaceAlert(Context context, int resId) {
        showReplaceAlert(context, resId, null);
    }

    /** Replace-icon sheet for {@code resId} in {@code iconPack} (default: the pack being edited, else the first custom one). */
    public void showReplaceAlert(Context context, int resId, IconPack iconPack) {
        if (iconPack == null) {
            String editingId = ExteraConfig.getEditingIconPackId();
            for (IconPack pack : activePacks) {
                if (editingId != null ? pack.getId().equals(editingId) : !pack.isBase()) {
                    iconPack = pack;
                    break;
                }
            }
            if (iconPack == null) {
                return;
            }
        }
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null) {
            return;
        }
        fragment.showDialog(new ReplaceIconBottomSheet(context, resId, iconPack));
    }

    // endregion

    public List<IconPack> getActivePacks() {
        return activePacks;
    }

    public boolean isBasePackOnly(IconPackType basePackType) {
        if (ExteraConfig.getIconPack() != basePackType) {
            return false;
        }
        for (IconPack pack : activePacks) {
            if (!pack.isBase()) {
                return false;
            }
        }
        return true;
    }
}
