package com.exteragram.messenger.components.supporter;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import com.exteragram.messenger.badges.BadgesConfig;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * exteraGram 13.1.1 SupporterPerks: the «Your Perks» rows of the supporter sheets.
 * exteraGram hardcodes four rows (Numbered Badge, Now Playing, Registration Dates, Early Builds).
 * StaticGram has no Now Playing / registration dates, so the list is a table of {@link Perk}s: add a
 * {@link Perk} to {@link #PERKS} to show a new perk. A perk with an action shows a chevron and is
 * clickable when the sheet passes a handler for its key; a perk whose {@link Perk#visible} is false is skipped.
 */
public abstract class SupporterPerks {

    /** Numbered badge next to the name. Action: open the own profile (and land the badge there). */
    public static final String BADGE = "badge";
    /** Supporters' early builds channel; shown only when app config has sg_badges_early_builds_url. */
    public static final String EARLY_BUILDS = "early_builds";

    public static final class Perk {
        public final String key;
        public final int icon;
        public final int title;
        public final int info;
        public final BooleanSupplier visible;

        public Perk(String key, int icon, int title, int info, BooleanSupplier visible) {
            this.key = key;
            this.icon = icon;
            this.title = title;
            this.info = info;
            this.visible = visible;
        }
    }

    /** The perks in display order. */
    public static final List<Perk> PERKS;

    static {
        ArrayList<Perk> perks = new ArrayList<>();
        perks.add(new Perk(BADGE, R.drawable.menu_supporter_badge, R.string.SupporterPerkBadge, R.string.SupporterPerkBadgeInfo, null));
        // exteraGram also lists SupporterPerkNowPlaying (msg_voice_headphones) and SupporterPerkRegDate (msg_calendar2):
        // not offered by StaticGram, so they are not in the table.
        perks.add(new Perk(EARLY_BUILDS, R.drawable.msg_channel, R.string.SupporterPerkEarlyBuilds, R.string.SupporterPerkEarlyBuildsInfo,
                () -> !TextUtils.isEmpty(BadgesConfig.getEarlyBuildsUrl())));
        PERKS = Collections.unmodifiableList(perks);
    }

    /** Handler lookup for a perk key; null (or a null handler) makes the row read-only. */
    public interface Actions {
        Runnable get(String key);
    }

    public static void addTo(Context context, Utilities.Callback<View> callback, Theme.ResourcesProvider resourcesProvider, Actions actions) {
        for (Perk perk : PERKS) {
            if (perk.visible != null && !perk.visible.getAsBoolean()) {
                continue;
            }
            SupporterFeatureCell cell = new SupporterFeatureCell(context, perk.icon, LocaleController.getString(perk.title), LocaleController.getString(perk.info), resourcesProvider);
            Runnable action = actions != null ? actions.get(perk.key) : null;
            if (action != null) {
                cell.setAction(action);
            }
            callback.run(cell);
        }
    }

    /** exteraGram's signature (badge, now playing, early builds handlers); the now playing handler is ignored. */
    public static void addTo(Context context, Utilities.Callback<View> callback, Theme.ResourcesProvider resourcesProvider, Runnable onBadge, Runnable onNowPlaying, Runnable onEarlyBuilds) {
        addTo(context, callback, resourcesProvider, key -> BADGE.equals(key) ? onBadge : EARLY_BUILDS.equals(key) ? onEarlyBuilds : null);
    }
}
