package com.exteragram.messenger.preferences.chats.components;

import java.util.List;
import java.util.Objects;

/** exteraGram 13.1.1: data of one {@link InlineFeatureCell}; {@code docs} is non-null when the cell is expanded. */
public final class InlineFeature {
    private final int icon;
    private final CharSequence title;
    private final CharSequence subtitle;
    private final CharSequence badge;
    private final List<? extends InlineDocBlock> docs;

    public InlineFeature(int icon, CharSequence title, CharSequence subtitle, CharSequence badge, List<? extends InlineDocBlock> docs) {
        this.icon = icon;
        this.title = title;
        this.subtitle = subtitle;
        this.badge = badge;
        this.docs = docs;
    }

    public int getIcon() {
        return icon;
    }

    public CharSequence getTitle() {
        return title;
    }

    public CharSequence getSubtitle() {
        return subtitle;
    }

    public CharSequence getBadge() {
        return badge;
    }

    public List<? extends InlineDocBlock> getDocs() {
        return docs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InlineFeature)) return false;
        InlineFeature other = (InlineFeature) o;
        return icon == other.icon && Objects.equals(title, other.title) && Objects.equals(subtitle, other.subtitle) && Objects.equals(badge, other.badge) && Objects.equals(docs, other.docs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(icon, title, subtitle, badge, docs);
    }
}
