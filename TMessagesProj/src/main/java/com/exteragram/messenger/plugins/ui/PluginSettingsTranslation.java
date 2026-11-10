package com.exteragram.messenger.plugins.ui;

import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.translator.ChatTranslator;
import com.exteragram.messenger.translator.TranslatorUtils;
import com.exteragram.messenger.translator.core.HttpTranslator;
import com.exteragram.messenger.translator.core.TranslationError;
import com.exteragram.messenger.translator.providers.GoogleTranslator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import kotlin.Unit;
import kotlin.collections.ArraysKt;
import kotlin.collections.CollectionsKt;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.internal.Intrinsics;
import kotlin.text.StringsKt;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LanguageDetector;
import org.telegram.messenger.TranslateController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

public final class PluginSettingsTranslation {
    private static final int DETECTION_SAMPLE_LENGTH = 2000;
    private static final int MAX_TEXTS_PER_REQUEST = 20;
    private static final long TRANSLATION_TAG = -9223372036854775807L;
    private String detectedLanguage;
    private boolean detecting;
    private boolean enabled;
    private final HashSet<String> failed;
    private final ArrayList<Listener> listeners;
    private final HashSet<String> pending;
    private String targetLanguage;
    private final HashMap<String, String> translations;

    public static final Companion Companion = new Companion();
    private static final HashMap<String, PluginSettingsTranslation> instances = new HashMap<>();

    public interface Listener {
        void onTranslationChanged(boolean failed);
    }


    public static final PluginSettingsTranslation of(String str) {
        return Companion.of(str);
    }

    private PluginSettingsTranslation() {
        this.listeners = new ArrayList<>();
        this.translations = new HashMap<>();
        this.pending = new HashSet<>();
        this.failed = new HashSet<>();
    }

    public final boolean getEnabled() {
        return this.enabled;
    }

    public final boolean isLoading() {
        return !this.pending.isEmpty();
    }

    public final boolean isTranslatable() {
        String str = this.detectedLanguage;
        if (str != null) {
            return !TranslatorUtils.isRestrictedLanguage(str);
        }
        return false;
    }

    public final void addListener(Listener listener) {
        this.listeners.add(listener);
    }

    public final void removeListener(Listener listener) {
        this.listeners.remove(listener);
    }

    public final void resetFailed() {
        this.failed.clear();
    }

    public final void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        if (enabled) {
            this.failed.clear();
        }
        notifyListeners(false);
    }

    public final String text(String original) {
        String str;
        return (!this.enabled || original == null || (str = this.translations.get(original)) == null) ? original : str;
    }

    public final void check(Collection<String> texts) {
        String resolvedTargetLanguageCode = TranslatorUtils.getResolvedTargetLanguageCode();
        if (!Intrinsics.areEqual(resolvedTargetLanguageCode, this.targetLanguage)) {
            this.targetLanguage = resolvedTargetLanguageCode;
            this.translations.clear();
            this.failed.clear();
        }
        if (this.detectedLanguage == null && !this.detecting && !texts.isEmpty() && LanguageDetector.hasSupport()) {
            detect(texts);
        }
        if (this.enabled) {
            ArrayList<String> arrayList = new ArrayList<>();
            for (String str : texts) {
                if (!str.trim().isEmpty() && !this.translations.containsKey(str) && !this.pending.contains(str) && !this.failed.contains(str)) {
                    arrayList.add(str);
                }
            }
            if (arrayList.isEmpty()) {
                return;
            }
            request(arrayList, resolvedTargetLanguageCode);
        }
    }

    private final void detect(Collection<String> texts) {
        this.detecting = true;
        String sample = android.text.TextUtils.join("\n", texts);
        if (sample.length() > 2000) {
            sample = sample.substring(0, 2000);
        }
        LanguageDetector.detectLanguage(sample, language -> AndroidUtilities.runOnUIThread(() -> PluginSettingsTranslation.$r8$lambda$PTUdpZwtDYtdYwXecXf41YtewTI(language, this)), null);
    }

    public static void $r8$lambda$PTUdpZwtDYtdYwXecXf41YtewTI(String str, PluginSettingsTranslation pluginSettingsTranslation) {
        if (str == null || str.length() == 0 || Intrinsics.areEqual(str, TranslateController.UNKNOWN_LANGUAGE)) {
            return;
        }
        pluginSettingsTranslation.detectedLanguage = str;
        pluginSettingsTranslation.notifyListeners(false);
    }

    private final void request(final List<String> texts, final String language) {
        this.pending.addAll(texts);
        translate(texts, language, results -> PluginSettingsTranslation.m1588$r8$lambda$GmVCDGYKoJLxcaC_JAKQEYoydA(this, texts, language, results));
    }

    public static Unit m1588$r8$lambda$GmVCDGYKoJLxcaC_JAKQEYoydA(PluginSettingsTranslation pluginSettingsTranslation, List<String> list, String str, List<String> list2) {
        pluginSettingsTranslation.pending.removeAll(list);
        if (!Intrinsics.areEqual(str, pluginSettingsTranslation.targetLanguage)) {
            pluginSettingsTranslation.notifyListeners(false);
            return Unit.INSTANCE;
        }
        boolean z = false;
        for (int i = 0; i < list.size(); i++) {
            String str2 = list.get(i);
            String str3 = list2 != null && i < list2.size() ? list2.get(i) : null;
            if (str3 == null || str3.trim().isEmpty()) {
                pluginSettingsTranslation.failed.add(str2);
                z = true;
            } else {
                pluginSettingsTranslation.translations.put(str2, str3);
            }
        }
        boolean z2 = z && pluginSettingsTranslation.translations.isEmpty();
        if (z2) {
            pluginSettingsTranslation.enabled = false;
        }
        pluginSettingsTranslation.notifyListeners(z2);
        return Unit.INSTANCE;
    }

    private final void translate(List<String> texts, String language, final Function1<? super List<String>, Unit> done) {
        if (TranslatorUtils.getCurrentTranslator() instanceof HttpTranslator) {
            ChatTranslator.translate(TRANSLATION_TAG, texts, language, (results, error) -> done.invoke(results));
        } else {
            final String[] strArr = new String[texts.size()];
            // the Telegram translator accepts at most 20 texts per request
            ArrayList<List<Integer>> chunks = new ArrayList<>();
            for (int start = 0; start < texts.size(); start += 20) {
                ArrayList<Integer> chunk = new ArrayList<>();
                for (int i = start; i < Math.min(start + 20, texts.size()); i++) {
                    chunk.add(i);
                }
                chunks.add(chunk);
            }
            requestEach(chunks, texts, language, strArr, true, 0, () -> {
                done.invoke(java.util.Arrays.asList(strArr));
                return Unit.INSTANCE;
            });
        }
    }

    private final void requestEach(final List<? extends List<Integer>> chunks, final List<String> texts, final String language, final String[] results, final boolean retry, final int position, final Function0<Unit> done) {
        if (position == chunks.size()) {
            done.invoke();
        } else {
            requestChunk(chunks.get(position), texts, language, results, retry, () -> {
                requestEach(chunks, texts, language, results, retry, position + 1, done);
                return Unit.INSTANCE;
            });
        }
    }

    private final void requestChunk(final List<Integer> chunk, final List<String> texts, final String language, final String[] results, final boolean retry, final Function0<Unit> done) {
        TLRPC.TL_messages_translateText tL_messages_translateText = new TLRPC.TL_messages_translateText();
        tL_messages_translateText.flags |= 2;
        Iterator<Integer> it = chunk.iterator();
        while (it.hasNext()) {
            int iIntValue = it.next().intValue();
            ArrayList<TLRPC.TL_textWithEntities> arrayList = tL_messages_translateText.text;
            TLRPC.TL_textWithEntities tL_textWithEntities = new TLRPC.TL_textWithEntities();
            tL_textWithEntities.text = texts.get(iIntValue);
            arrayList.add(tL_textWithEntities);
        }
        tL_messages_translateText.to_lang = language;
        ConnectionsManager.getInstance(UserConfig.selectedAccount).sendRequest(tL_messages_translateText, (response, error) -> AndroidUtilities.runOnUIThread(() -> PluginSettingsTranslation.$r8$lambda$3KJzwSQq1gHWVma1Zl0vBkVJAcM(response, chunk, retry, done, this, texts, language, results, error)));
    }

    public static void $r8$lambda$3KJzwSQq1gHWVma1Zl0vBkVJAcM(TLObject tLObject, final List<Integer> list, boolean z, final Function0<Unit> function0, PluginSettingsTranslation pluginSettingsTranslation, List<String> list2, String str, final String[] strArr, TLRPC.TL_error tL_error) {
        if (!(tLObject instanceof TLRPC.TL_messages_translateResult)) {
            if (tL_error == null || !StringsKt.equals("TRANSLATIONS_DISABLED_ALT", tL_error.text, true)) {
                function0.invoke();
                return;
            }
            ArrayList<String> arrayList = new ArrayList<>();
            for (Integer index : list) {
                arrayList.add(list2.get(index));
            }
            ChatTranslator.translate(TRANSLATION_TAG, arrayList, str, GoogleTranslator.getInstance(), (results, error) -> PluginSettingsTranslation.$r8$lambda$BhislwJ_w_bX3GhCIz9I8vwOc8g(list, function0, strArr, results, error));
            return;
        }
        ArrayList<Integer> missing = new ArrayList<>();
        TLRPC.TL_messages_translateResult result = (TLRPC.TL_messages_translateResult) tLObject;
        if (result.result.size() == list.size()) {
            for (int i = 0; i < list.size(); i++) {
                int index = list.get(i);
                TLRPC.TL_textWithEntities translated = result.result.get(i);
                String text = translated != null ? translated.text : null;
                if (text == null || text.trim().isEmpty()) {
                    missing.add(index);
                } else {
                    strArr[index] = text;
                }
            }
        } else {
            missing.addAll(list);
        }
        if (!z || missing.isEmpty()) {
            function0.invoke();
            return;
        }
        // retry the failed texts one by one
        ArrayList<List<Integer>> singles = new ArrayList<>();
        for (Integer index : missing) {
            singles.add(java.util.Collections.singletonList(index));
        }
        pluginSettingsTranslation.requestEach(singles, list2, str, strArr, false, 0, function0);
    }

    public static void $r8$lambda$BhislwJ_w_bX3GhCIz9I8vwOc8g(List<Integer> list, Function0<Unit> function0, String[] strArr, List<String> list2, TranslationError translationError) {
        for (int i = 0; i < list.size(); i++) {
            strArr[list.get(i)] = list2 != null && i < list2.size() ? list2.get(i) : null;
        }
        function0.invoke();
    }

    private final void notifyListeners(boolean failed) {
        for (Listener listener : new ArrayList<>(this.listeners)) {
            listener.onTranslationChanged(failed);
        }
    }

    public static final class Companion {
        private Companion() {
        }

        public final PluginSettingsTranslation of(String pluginId) {
            PluginSettingsTranslation translation = instances.get(pluginId);
            if (translation == null) {
                translation = new PluginSettingsTranslation();
                instances.put(pluginId, translation);
            }
            return translation;
        }
    }
}
