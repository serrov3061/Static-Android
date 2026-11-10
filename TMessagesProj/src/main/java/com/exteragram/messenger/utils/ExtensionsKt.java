package com.exteragram.messenger.utils;

import java.util.List;

public abstract class ExtensionsKt {
    public static final <T> void addIf(List<T> list, boolean z, T t) {
        if (z) {
            list.add(t);
        }
    }
}
