package com.exteragram.messenger.translator;

import android.os.SystemClock;

import com.exteragram.messenger.translator.core.BaseTranslator;
import com.exteragram.messenger.translator.core.HttpTranslator;
import com.exteragram.messenger.translator.core.TranslationError;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.TranslateAlert2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Glue between TranslateController (TL_textWithEntities batches) and {@link FormattedTranslation} / {@link ChatTranslator}. */
public final class ChatTranslationBridge {

    private static final HashMap<Long, Long> lastFailureNotice = new HashMap<>();

    private interface Done {
        void run(List<TLRPC.TL_textWithEntities> results, boolean cancelled);
    }

    private ChatTranslationBridge() {
    }

    public static void translateMessages(long dialogId, boolean isTranscription, List<Integer> ids, List<TLRPC.TL_textWithEntities> sources, List<Utilities.Callback4<Boolean, Integer, TLRPC.TL_textWithEntities, String>> callbacks, String toLang, Runnable onFinished) {
        translate(dialogId, sources, toLang, (results, cancelled) -> {
            if (!cancelled) {
                for (int i = 0; i < callbacks.size(); i++) {
                    TLRPC.TL_textWithEntities result = results != null && i < results.size() ? results.get(i) : null;
                    callbacks.get(i).run(isTranscription, ids.get(i), result, toLang);
                }
            }
            onFinished.run();
        });
    }

    /** onDone receives null unless every source was translated. */
    public static void translateTexts(long dialogId, List<TLRPC.TL_textWithEntities> sources, String toLang, Utilities.Callback<List<TLRPC.TL_textWithEntities>> onDone) {
        translate(dialogId, sources, toLang, (results, cancelled) -> {
            List<TLRPC.TL_textWithEntities> full = null;
            if (results != null) {
                ArrayList<TLRPC.TL_textWithEntities> nonNull = new ArrayList<>();
                for (TLRPC.TL_textWithEntities r : results) {
                    if (r != null) {
                        nonNull.add(r);
                    }
                }
                if (nonNull.size() == sources.size()) {
                    full = nonNull;
                }
            }
            onDone.run(full);
        });
    }

    private static void translate(long dialogId, List<TLRPC.TL_textWithEntities> sources, String toLang, Done onDone) {
        BaseTranslator current = TranslatorUtils.getCurrentTranslator();
        if (!(current instanceof HttpTranslator)) {
            onDone.run(null, false);
            return;
        }
        // exteraGram 13.1.1: entities are kept through HTML markup (FormattedTranslation)
        FormattedTranslation.translate(dialogId, sources, "auto", toLang, (HttpTranslator) current, false, (translated, error) -> {
            boolean cancelled = error instanceof TranslationError.Cancelled;
            ArrayList<TLRPC.TL_textWithEntities> results = null;
            if (!cancelled) {
                results = new ArrayList<>(sources.size());
                for (int i = 0; i < sources.size(); i++) {
                    TLRPC.TL_textWithEntities received = translated != null && i < translated.size() ? translated.get(i) : null;
                    results.add(received != null ? TranslateAlert2.preprocess(null, received) : null);
                }
            }
            onDone.run(results, cancelled);
            if (error != null && !cancelled) {
                notifyFailure(dialogId, error);
            }
        });
    }

    private static void notifyFailure(long dialogId, TranslationError error) {
        long now = SystemClock.elapsedRealtime();
        Long last = lastFailureNotice.get(dialogId);
        if (last == null || now - last >= 60_000) {
            lastFailureNotice.put(dialogId, now);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.showBulletin, Bulletin.TYPE_ERROR,
                LocaleController.getString(error instanceof TranslationError.RateLimited ? R.string.TranslationFailedAlert1 : R.string.TranslationFailedAlert2));
        }
    }
}
