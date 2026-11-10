package com.exteragram.messenger.plugins.catalog.db;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

/**
 * Storage of the plugin catalog. exteraGram 13.1.1 uses a Room database ("plugin-catalog", schema 4);
 * StaticGram has no Room/KSP in the build, and the catalog is a cache of downloaded indexes, so it is kept
 * in small JSON files instead: files/plugin_catalog/repositories.json and one entries_<id>.json per
 * repository (entries with their metadata). Writes go to a temp file and are renamed, so a crash never
 * leaves a half-written file; an unreadable file is treated as empty (the next sync rebuilds it).
 */
public final class PluginCatalogDatabase {

    private static final int VERSION = 1;

    private static volatile PluginCatalogDatabase instance;

    static final class Entries {
        final ArrayList<CatalogEntryEntity> entries = new ArrayList<>();
        final HashMap<Long, PluginMetadataEntity> metadata = new HashMap<>();
    }

    private final File dir;
    private final PluginCatalogDao dao;
    private LinkedHashMap<Long, PluginRepositoryEntity> repositories;
    private final HashMap<Long, Entries> entries = new HashMap<>();

    public static PluginCatalogDatabase getInstance() {
        PluginCatalogDatabase local = instance;
        if (local == null) {
            synchronized (PluginCatalogDatabase.class) {
                local = instance;
                if (local == null) {
                    instance = local = new PluginCatalogDatabase();
                }
            }
        }
        return local;
    }

    private PluginCatalogDatabase() {
        dir = new File(ApplicationLoader.getFilesDirFixed(), "plugin_catalog");
        dao = new PluginCatalogDao(this);
    }

    public PluginCatalogDao catalogDao() {
        return dao;
    }

    // region repositories (callers hold the dao lock)

    LinkedHashMap<Long, PluginRepositoryEntity> repositories() {
        if (repositories == null) {
            repositories = new LinkedHashMap<>();
            JSONObject json = read(new File(dir, "repositories.json"));
            JSONArray array = json != null ? json.optJSONArray("repositories") : null;
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.optJSONObject(i);
                    if (o == null || !o.has("id")) {
                        continue;
                    }
                    PluginRepositoryEntity r = new PluginRepositoryEntity(o.optLong("id"), str(o, "username"), str(o, "title"),
                            o.optInt("source", PluginRepositoryEntity.SOURCE_USER), o.optInt("sortOrder"), o.optLong("addedAt"),
                            o.optLong("syncedAt"), o.optInt("pluginsCount"), str(o, "url"), str(o, "homepage"));
                    repositories.put(r.getRepositoryId(), r);
                }
            }
        }
        return repositories;
    }

    void saveRepositories() {
        try {
            JSONArray array = new JSONArray();
            for (PluginRepositoryEntity r : repositories().values()) {
                JSONObject o = new JSONObject();
                o.put("id", r.getRepositoryId());
                o.putOpt("username", r.getUsername());
                o.putOpt("title", r.getTitle());
                o.put("source", r.getSource());
                o.put("sortOrder", r.getSortOrder());
                o.put("addedAt", r.getAddedAt());
                o.put("syncedAt", r.getSyncedAt());
                o.put("pluginsCount", r.getPluginsCount());
                o.putOpt("url", r.getUrl());
                o.putOpt("homepage", r.getHomepage());
                array.put(o);
            }
            JSONObject json = new JSONObject();
            json.put("version", VERSION);
            json.put("repositories", array);
            write(new File(dir, "repositories.json"), json);
        } catch (Exception e) {
            FileLog.e("PluginCatalog: failed to save repositories", e);
        }
    }

    // endregion

    // region entries

    private File entriesFile(long repositoryId) {
        return new File(dir, "entries_" + (repositoryId < 0 ? "m" + (-repositoryId) : String.valueOf(repositoryId)) + ".json");
    }

    Entries entries(long repositoryId) {
        Entries data = entries.get(repositoryId);
        if (data != null) {
            return data;
        }
        data = new Entries();
        JSONObject json = read(entriesFile(repositoryId));
        JSONArray array = json != null ? json.optJSONArray("entries") : null;
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                String fileName = o != null ? str(o, "fileName") : null;
                if (fileName == null) {
                    continue;
                }
                CatalogEntryEntity entry = new CatalogEntryEntity(repositoryId, o.optInt("entryId"), o.optInt("date"), o.optLong("documentId"),
                        fileName, o.optLong("size"), str(o, "caption"), o.optInt("views"), o.optInt("updatedAt"), str(o, "fileUrl"), str(o, "sha256"));
                data.entries.add(entry);
                JSONObject m = o.optJSONObject("metadata");
                if (m != null) {
                    data.metadata.put(entry.getDocumentId(), new PluginMetadataEntity(entry.getDocumentId(), str(m, "pluginId"), str(m, "name"),
                            str(m, "author"), str(m, "description"), str(m, "version"), str(m, "icon"), str(m, "appVersion"),
                            str(m, "sdkVersion"), str(m, "requirements"), str(m, "categories"), str(m, "error"), m.optLong("parsedAt"),
                            str(m, "parsedWithSdk")));
                }
            }
        }
        entries.put(repositoryId, data);
        return data;
    }

    void putEntries(long repositoryId, Entries data) {
        entries.put(repositoryId, data);
        try {
            JSONArray array = new JSONArray();
            for (CatalogEntryEntity entry : data.entries) {
                JSONObject o = new JSONObject();
                o.put("entryId", entry.getEntryId());
                o.put("date", entry.getDate());
                o.put("documentId", entry.getDocumentId());
                o.put("fileName", entry.getFileName());
                o.put("size", entry.getSize());
                o.putOpt("caption", entry.getCaption());
                o.put("views", entry.getViews());
                o.put("updatedAt", entry.getUpdatedAt());
                o.putOpt("fileUrl", entry.getFileUrl());
                o.putOpt("sha256", entry.getSha256());
                PluginMetadataEntity m = data.metadata.get(entry.getDocumentId());
                if (m != null) {
                    JSONObject mo = new JSONObject();
                    mo.putOpt("pluginId", m.getPluginId());
                    mo.putOpt("name", m.getName());
                    mo.putOpt("author", m.getAuthor());
                    mo.putOpt("description", m.getDescription());
                    mo.putOpt("version", m.getVersion());
                    mo.putOpt("icon", m.getIcon());
                    mo.putOpt("appVersion", m.getAppVersion());
                    mo.putOpt("sdkVersion", m.getSdkVersion());
                    mo.putOpt("requirements", m.getRequirements());
                    mo.putOpt("categories", m.getCategories());
                    mo.putOpt("error", m.getError());
                    mo.put("parsedAt", m.getParsedAt());
                    mo.putOpt("parsedWithSdk", m.getParsedWithSdk());
                    o.put("metadata", mo);
                }
                array.put(o);
            }
            JSONObject json = new JSONObject();
            json.put("version", VERSION);
            json.put("entries", array);
            write(entriesFile(repositoryId), json);
        } catch (Exception e) {
            FileLog.e("PluginCatalog: failed to save entries of " + repositoryId, e);
        }
    }

    void deleteEntries(long repositoryId) {
        entries.remove(repositoryId);
        File file = entriesFile(repositoryId);
        if (file.exists() && !file.delete()) {
            FileLog.e("PluginCatalog: cannot delete " + file);
        }
    }

    // endregion

    private static String str(JSONObject o, String key) {
        Object v = o.opt(key);
        return v instanceof String ? (String) v : null;
    }

    private static JSONObject read(File file) {
        if (!file.exists()) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
            }
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            FileLog.e("PluginCatalog: cannot read " + file.getName(), e);
            return null;
        }
    }

    private void write(File file, JSONObject json) throws Exception {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("cannot create " + dir);
        }
        File tmp = new File(dir, file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(file)) {
            tmp.delete();
            throw new Exception("cannot move " + tmp.getName());
        }
    }
}
