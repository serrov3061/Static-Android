package com.exteragram.messenger.pillstack.core;

import java.util.Arrays;
import java.util.List;

/** exteraGram: built-in pill types (ids are stored in the "pillstackconfig" preferences). */
public enum PillType {
    WEATHER(1),
    GRAM(2),
    BTC(3),
    USD(4),
    CACHE(5),
    PROXY(6);

    private final int id;

    PillType(int id) {
        this.id = id;
    }

    public final int getId() {
        return id;
    }

    public static List<PillType> getEntries() {
        return Arrays.asList(values());
    }
}
