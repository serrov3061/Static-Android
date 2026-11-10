package com.exteragram.messenger.utils.ui;

import android.view.View;

import com.exteragram.messenger.preferences.BasePreferencesActivity;

import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

/**
 * Placeholder screen for exteraGram screens whose feature is ported in a later stage.
 * Subclasses keep the exteraGram class name so call sites stay unchanged; the stage
 * that ports the feature replaces the subclass with the real screen.
 */
public abstract class PortPendingActivity extends BasePreferencesActivity {

    private final String title;
    private final int stage;

    protected PortPendingActivity(String title, int stage) {
        this.title = title;
        this.stage = stage;
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asShadow("Раздел переносится из exteraGram: будет доступен после этапа " + stage + " переноса."));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
    }
}
