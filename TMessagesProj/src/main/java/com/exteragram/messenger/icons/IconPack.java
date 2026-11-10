package com.exteragram.messenger.icons;

import android.util.SparseIntArray;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * exteraGram icon pack (IconPack.kt): a built-in ("base.*") pack with a drawable replacement map, or a custom pack
 * stored in files/icon_packs/&lt;id&gt; whose {@link #getIcons()} maps resource entry names to files of the pack.
 * {@link #getLocation()} is set for a pack previewed from a temporary directory (install sheet).
 */
public final class IconPack {

    private final String id;
    private final String name;
    private final String author;
    private final String version;
    private final Map<String, String> icons;
    private final SparseIntArray preinstalledMap;
    private final File location;

    public IconPack(String id, String name, String author, SparseIntArray preinstalledMap) {
        this(id, name, author, "1.0", Collections.emptyMap(), preinstalledMap, null);
    }

    public IconPack(String id, String name, String author, String version, Map<String, String> icons, SparseIntArray preinstalledMap) {
        this(id, name, author, version, icons, preinstalledMap, null);
    }

    public IconPack(String id, String name, String author, String version, Map<String, String> icons, SparseIntArray preinstalledMap, File location) {
        this.id = id;
        this.name = name;
        this.author = author;
        this.version = version;
        this.icons = icons != null ? icons : Collections.emptyMap();
        this.preinstalledMap = preinstalledMap;
        this.location = location;
    }

    /** Kotlin data-class copy with a new icon map (IconPack.copy$default(..., icons, ...)). */
    public IconPack copyWithIcons(Map<String, String> newIcons) {
        return new IconPack(id, name, author, version, newIcons, preinstalledMap, location);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getAuthor() {
        return author;
    }

    public String getVersion() {
        return version;
    }

    public Map<String, String> getIcons() {
        return icons;
    }

    public SparseIntArray getPreinstalledMap() {
        return preinstalledMap;
    }

    public File getLocation() {
        return location;
    }

    public boolean isBase() {
        return id.startsWith("base.");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof IconPack)) {
            return false;
        }
        IconPack other = (IconPack) o;
        return id.equals(other.id) && Objects.equals(name, other.name) && Objects.equals(author, other.author)
                && Objects.equals(version, other.version) && icons.equals(other.icons)
                && Objects.equals(preinstalledMap, other.preinstalledMap) && Objects.equals(location, other.location);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, author, version, icons, preinstalledMap, location);
    }

    @Override
    public String toString() {
        return "IconPack(id=" + id + ", name=" + name + ", author=" + author + ", version=" + version + ", icons=" + icons.size() + ", location=" + location + ")";
    }
}
