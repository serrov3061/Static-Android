package com.exteragram.messenger.utils.network;

import java.util.concurrent.TimeUnit;
import kotlin.Lazy;
import kotlin.LazyKt;
import kotlin.jvm.functions.Function0;
import okhttp3.OkHttpClient;

public final class ExteraHttpClient {
    public static final ExteraHttpClient INSTANCE = new ExteraHttpClient();

    /* JADX INFO: renamed from: client$delegate, reason: from kotlin metadata */
    private static final Lazy client = LazyKt.lazy(new Function0() { // from class: com.exteragram.messenger.utils.network.ExteraHttpClient$$ExternalSyntheticLambda0
        @Override // kotlin.jvm.functions.Function0
        public final Object invoke() {
            return ExteraHttpClient.$r8$lambda$wzbHlla98iZftcZ62WKlPGXexp8();
        }
    });

    private ExteraHttpClient() {
    }

    public final OkHttpClient getClient() {
        return (OkHttpClient) client.getValue();
    }

    public static OkHttpClient $r8$lambda$wzbHlla98iZftcZ62WKlPGXexp8() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        TimeUnit timeUnit = TimeUnit.SECONDS;
        return builder.connectTimeout(10L, timeUnit).readTimeout(10L, timeUnit).writeTimeout(10L, timeUnit).build();
    }
}
