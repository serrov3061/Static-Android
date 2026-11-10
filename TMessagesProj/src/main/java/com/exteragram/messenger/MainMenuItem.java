package com.exteragram.messenger;

import java.util.Iterator;
import kotlin.jvm.internal.DefaultConstructorMarker;

public enum MainMenuItem {
    DIVIDER(-1),
    PROFILE(18),
    ARCHIVE(14),
    BOTS(105),
    NEW_GROUP(2),
    CONTACTS(6),
    NEW_CHANNEL(3),
    CALLS(10),
    SAVED(11),
    SETTINGS(8),
    PLUGINS(102),
    BROWSER(101),
    QR(17),
    FEED(106),
    WALLET(107);

    public static java.util.List<MainMenuItem> getEntries() {
        return java.util.Arrays.asList(values());
    }

    /* renamed from: Companion, reason: from kotlin metadata */
    public static final Companion INSTANCE;
    private final int id;

    public static final MainMenuItem getById(int i) {
        return INSTANCE.getById(i);
    }

    private MainMenuItem(int i2) {
        this.id = i2;
    }

    public final int getId() {
        return this.id;
    }

    static {
        INSTANCE = new Companion(null);
    }

    public static final class Companion {
        public /* synthetic */ Companion(DefaultConstructorMarker defaultConstructorMarker) {
            this();
        }

        private Companion() {
        }

        public final MainMenuItem getById(int id) {
            MainMenuItem next;
            Iterator<MainMenuItem> it = MainMenuItem.getEntries().iterator();
            while (true) {
                if (!it.hasNext()) {
                    next = null;
                    break;
                }
                next = it.next();
                if (next.getId() == id) {
                    break;
                }
            }
            return next;
        }
    }
}
