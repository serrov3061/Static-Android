package com.exteragram.messenger.preferences;

import android.view.View;

import com.exteragram.messenger.preferences.utils.SettingsRegistry;
import com.exteragram.messenger.utils.text.LocaleUtils;

import org.telegram.messenger.LocaleController;
import org.telegram.ui.Cells.TextCheckCell2;
import org.telegram.ui.Components.UItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Expandable switch with a group of round checkboxes (exteraGram SwitchGroup.kt).
 * The master switch toggles every visible child; "N/M" shows how many are enabled.
 */
public final class SwitchGroup {

    public interface Setter {
        void set(boolean checked);
    }

    private final BasePreferencesActivity fragment;
    private final int id;
    private final int titleRes;
    private final CharSequence title;
    private final String linkAlias;
    private final boolean searchable;
    private final List<Child> children = new ArrayList<>();
    private boolean expanded;
    private Runnable onChanged;

    public SwitchGroup(BasePreferencesActivity fragment, int id, int titleRes, CharSequence title, String linkAlias, boolean searchable) {
        this.fragment = fragment;
        this.id = id;
        this.titleRes = titleRes;
        this.title = title;
        this.linkAlias = linkAlias;
        this.searchable = searchable;
    }

    public SwitchGroup(BasePreferencesActivity fragment, Enum<?> item, int titleRes, CharSequence title, String linkAlias, boolean searchable) {
        this(fragment, item.ordinal() + 1, titleRes, title, linkAlias, searchable);
    }

    /** Kotlin defaults: title = null, linkAlias given, searchable = true. */
    public SwitchGroup(BasePreferencesActivity fragment, Enum<?> item, int titleRes, String linkAlias) {
        this(fragment, item, titleRes, null, linkAlias, true);
    }

    public int getId() {
        return id;
    }

    public SwitchGroup setExpanded(boolean value) {
        this.expanded = value;
        return this;
    }

    public SwitchGroup onChanged(Runnable runnable) {
        this.onChanged = runnable;
        return this;
    }

    public SwitchGroup markNew(String alias) {
        children.get(children.size() - 1).newFeatureAlias = alias;
        return this;
    }

    public SwitchGroup add(Enum<?> item, int textRes, BooleanSupplier getter, Setter setter) {
        return add(item, textRes, getter, setter, null);
    }

    public SwitchGroup add(Enum<?> item, int textRes, BooleanSupplier getter, Setter setter, CharSequence text) {
        children.add(new Child(item.ordinal() + 1, textRes, text, null, getter, setter));
        return this;
    }

    public SwitchGroup addIf(BooleanSupplier visible, Enum<?> item, int textRes, BooleanSupplier getter, Setter setter) {
        return addIf(visible, item, textRes, getter, setter, null);
    }

    public SwitchGroup addIf(BooleanSupplier visible, Enum<?> item, int textRes, BooleanSupplier getter, Setter setter, CharSequence text) {
        children.add(new Child(item.ordinal() + 1, textRes, text, visible, getter, setter));
        return this;
    }

    public void fill(ArrayList<UItem> items) {
        int enabled = count(true);
        UItem item = UItem.asExteraExpandableSwitch(id, title(), String.format(Locale.US, "%d/%d", enabled, count(false)), this::onSwitchClick);
        item.setChecked(enabled > 0);
        item.setCollapsed(!expanded);
        if (searchable) {
            item.setSearchable(fragment);
        }
        if (linkAlias != null) {
            item.setLinkAlias(linkAlias, fragment);
        }
        items.add(item);
        if (expanded) {
            for (Child child : children) {
                if (child.isVisible()) {
                    items.add(UItem.asRoundCheckbox(child.id, child.text()).setChecked(child.getter.getAsBoolean()).pad());
                }
            }
        }
    }

    public void onClick(UItem item) {
        if (item.id == id) {
            boolean wasExpanded = expanded;
            expanded = !wasExpanded;
            item.setCollapsed(wasExpanded);
            fragment.getListView$TMessagesProj().adapter.update(true);
            return;
        }
        Child target = null;
        for (Child child : children) {
            if (child.id == item.id) {
                target = child;
                break;
            }
        }
        if (target == null) {
            return;
        }
        final Child child = target;
        fragment.toggleBooleanSettingAndRefresh$TMessagesProj(item, checked -> child.setter.set(checked));
        if (onChanged != null) {
            onChanged.run();
        }
    }

    private void onSwitchClick(View view) {
        UItem item = fragment.getListView$TMessagesProj().findItemByItemId(((TextCheckCell2) view).id);
        boolean checked = !item.checked;
        for (Child child : children) {
            if (child.isVisible()) {
                child.setter.set(checked);
            }
        }
        item.setChecked(checked);
        fragment.getListView$TMessagesProj().adapter.update(true);
        if (onChanged != null) {
            onChanged.run();
        }
    }

    private CharSequence title() {
        return title == null ? LocaleController.getString(titleRes) : title;
    }

    private int count(boolean enabledOnly) {
        int n = 0;
        for (Child child : children) {
            if (child.isVisible() && (!enabledOnly || child.getter.getAsBoolean())) {
                n++;
            }
        }
        return n;
    }

    private static final class Child {
        final int id;
        final int textRes;
        final CharSequence text;
        final BooleanSupplier visible;
        final BooleanSupplier getter;
        final Setter setter;
        String newFeatureAlias;

        Child(int id, int textRes, CharSequence text, BooleanSupplier visible, BooleanSupplier getter, Setter setter) {
            this.id = id;
            this.textRes = textRes;
            this.text = text;
            this.visible = visible;
            this.getter = getter;
            this.setter = setter;
        }

        boolean isVisible() {
            return visible == null || visible.getAsBoolean();
        }

        CharSequence text() {
            CharSequence s = text == null ? LocaleController.getString(textRes) : text;
            return newFeatureAlias != null && SettingsRegistry.markAsNewFeature(newFeatureAlias) ? LocaleUtils.applyNewSpan(s) : s;
        }
    }
}
