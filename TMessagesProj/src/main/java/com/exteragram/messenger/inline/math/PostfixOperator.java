package com.exteragram.messenger.inline.math;

import java.util.List;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.internal.DefaultConstructorMarker;

public final class PostfixOperator {
    private final Function1<Double, Double> apply;
    private final boolean percent;
    private final List<String> symbols;

    /* JADX WARN: Multi-variable type inference failed */
    public PostfixOperator(List<String> list, boolean z, Function1<? super Double, Double> function1) {
        this.symbols = list;
        this.percent = z;
        this.apply = (Function1) function1;
    }

    public PostfixOperator(List list, boolean z, Function1 function1, int i) {
        this(list, (i & 2) != 0 ? false : z, function1);
    }

    public final List<String> getSymbols() {
        return this.symbols;
    }

    public final boolean getPercent() {
        return this.percent;
    }

    public final Function1<Double, Double> getApply() {
        return this.apply;
    }
}
