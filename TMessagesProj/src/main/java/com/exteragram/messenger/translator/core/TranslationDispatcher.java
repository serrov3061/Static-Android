package com.exteragram.messenger.translator.core;

import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-provider request queue with concurrency limit, min interval, cooldown after 429 and exponential backoff.
 * Port of exteraGram's TranslationDispatcher: OkHttp async calls are replaced by blocking HttpURLConnection calls
 * on a small worker pool; scheduling still happens on {@link Utilities#globalQueue}; completions go to the UI thread.
 */
public final class TranslationDispatcher {

    public interface Completion {
        void onDone(List<String> texts, TranslationError error);
    }

    private static final Object lock = new Object();
    private static final HashMap<String, Gate> gates = new HashMap<>();
    private static final AtomicInteger threadCounter = new AtomicInteger();
    private static final ExecutorService network = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "translator-http-" + threadCounter.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private TranslationDispatcher() {
    }

    private static final class Job {
        final HttpTranslator translator;
        final long tag;
        final List<String> texts;
        final String fromLang;
        final String toLang;
        final boolean html;
        final Completion completion;
        int attempt;
        boolean cancelled;
        TranslatorHttp.Call call;

        Job(HttpTranslator translator, long tag, List<String> texts, String fromLang, String toLang, boolean html, Completion completion) {
            this.translator = translator;
            this.tag = tag;
            this.texts = texts;
            this.fromLang = fromLang;
            this.toLang = toLang;
            this.html = html;
            this.completion = completion;
        }
    }

    private static final class Gate {
        final ProviderLimits limits;
        final ArrayDeque<Job> queue = new ArrayDeque<>();
        final ArrayList<Job> running = new ArrayList<>();
        long lastStartedAt;
        long cooldownUntil;
        long scheduledPumpAt;
        Runnable pumpRunnable;

        Gate(ProviderLimits limits) {
            this.limits = limits;
        }
    }

    private interface JobMatcher {
        boolean matches(Job job);
    }

    private static Gate gateOf(HttpTranslator translator) {
        String name = translator.getClass().getName();
        Gate gate = gates.get(name);
        if (gate == null) {
            final Gate g = new Gate(translator.getLimits());
            g.pumpRunnable = () -> pump(g);
            gates.put(name, g);
            gate = g;
        }
        return gate;
    }

    public static void enqueue(HttpTranslator translator, long tag, List<String> texts, String fromLang, String toLang, boolean priority, Completion completion) {
        enqueue(translator, tag, texts, fromLang, toLang, priority, false, completion);
    }

    public static void enqueue(HttpTranslator translator, long tag, List<String> texts, String fromLang, String toLang, boolean priority, boolean html, Completion completion) {
        if (texts.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> completion.onDone(Collections.emptyList(), null));
            return;
        }
        final Gate gate;
        synchronized (lock) {
            gate = gateOf(translator);
            Job job = new Job(translator, tag, texts, fromLang, toLang, html, completion);
            if (priority) {
                gate.queue.addFirst(job);
            } else {
                gate.queue.addLast(job);
            }
        }
        Utilities.globalQueue.postRunnable(() -> pump(gate));
    }

    public static void cancel(long tag) {
        cancelMatching(job -> job.tag == tag);
    }

    public static void cancelAll() {
        cancelMatching(job -> true);
    }

    private static void cancelMatching(JobMatcher matcher) {
        ArrayList<TranslatorHttp.Call> calls = new ArrayList<>();
        final ArrayList<Job> removed = new ArrayList<>();
        synchronized (lock) {
            for (Gate gate : gates.values()) {
                Iterator<Job> it = gate.queue.iterator();
                while (it.hasNext()) {
                    Job job = it.next();
                    if (matcher.matches(job)) {
                        it.remove();
                        removed.add(job);
                    }
                }
                for (Job job : gate.running) {
                    if (matcher.matches(job)) {
                        job.cancelled = true;
                        if (job.call != null) {
                            calls.add(job.call);
                        }
                    }
                }
            }
        }
        for (TranslatorHttp.Call call : calls) {
            call.cancel();
        }
        if (!removed.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> {
                for (Job job : removed) {
                    job.completion.onDone(null, TranslationError.Cancelled.INSTANCE);
                }
            });
        }
    }

    private static void pump(Gate gate) {
        Job job;
        while ((job = nextJob(gate)) != null) {
            start(gate, job);
        }
    }

    private static Job nextJob(Gate gate) {
        synchronized (lock) {
            ProviderLimits limits = gate.limits;
            if (gate.queue.isEmpty() || gate.running.size() >= limits.getMaxConcurrent()) {
                return null;
            }
            long now = SystemClock.elapsedRealtime();
            long allowedAt = Math.max(gate.cooldownUntil, gate.lastStartedAt + limits.getMinIntervalMs());
            if (now < allowedAt) {
                schedulePump(gate, allowedAt - now);
                return null;
            }
            Job job = gate.queue.removeFirst();
            gate.lastStartedAt = now;
            gate.running.add(job);
            return job;
        }
    }

    private static void schedulePump(Gate gate, long delay) {
        long now = SystemClock.elapsedRealtime();
        long at = now + delay;
        long scheduled = gate.scheduledPumpAt;
        if (now + 1 > scheduled || scheduled > at) {
            gate.scheduledPumpAt = at;
            Utilities.globalQueue.cancelRunnable(gate.pumpRunnable);
            Utilities.globalQueue.postRunnable(gate.pumpRunnable, delay);
        }
    }

    private static void start(Gate gate, Job job) {
        final TranslatorHttp.Call call;
        try {
            call = new TranslatorHttp.Call(job.translator.buildRequest(job.texts, job.fromLang, job.toLang, job.html));
        } catch (Exception e) {
            FileLog.e(e);
            finish(gate, job, null, TranslationError.Fatal.INSTANCE);
            return;
        }
        boolean cancelled;
        synchronized (lock) {
            if (!job.cancelled) {
                job.call = call;
            }
            cancelled = job.cancelled;
        }
        if (cancelled) {
            finish(gate, job, null, null);
            return;
        }
        network.execute(() -> {
            TranslatorHttp.Response response;
            try {
                response = call.execute();
            } catch (IOException e) {
                if (call.isCanceled()) {
                    finish(gate, job, null, null);
                } else {
                    FileLog.e(e);
                    retryOrFail(gate, job, TranslationError.Transient.INSTANCE);
                }
                return;
            } catch (Exception e) {
                FileLog.e(e);
                finish(gate, job, null, TranslationError.Fatal.INSTANCE);
                return;
            }
            ProviderResponse result;
            try {
                result = job.translator.parseResponse(response, job.texts.size());
            } catch (Exception e) {
                FileLog.e(e);
                result = new ProviderResponse.Failure(TranslationError.Transient.INSTANCE);
            }
            if (result instanceof ProviderResponse.Success) {
                finish(gate, job, ((ProviderResponse.Success) result).getTexts(), null);
            } else {
                retryOrFail(gate, job, ((ProviderResponse.Failure) result).getError());
            }
        });
    }

    private static void retryOrFail(Gate gate, Job job, TranslationError error) {
        job.attempt++;
        ProviderLimits limits = gate.limits;
        if (!error.isRetryable() || job.attempt >= limits.getMaxAttempts()) {
            finish(gate, job, null, error);
            return;
        }
        long delay;
        if (error instanceof TranslationError.RateLimited && ((TranslationError.RateLimited) error).getRetryAfterMs() > 0) {
            delay = ((TranslationError.RateLimited) error).getRetryAfterMs();
        } else {
            delay = backoff(limits, job.attempt);
        }
        boolean cancelled;
        synchronized (lock) {
            gate.running.remove(job);
            job.call = null;
            if (!job.cancelled) {
                if (error instanceof TranslationError.RateLimited) {
                    gate.cooldownUntil = Math.max(gate.cooldownUntil, SystemClock.elapsedRealtime() + delay);
                }
                gate.queue.addFirst(job);
                schedulePump(gate, delay);
            }
            cancelled = job.cancelled;
        }
        if (cancelled) {
            AndroidUtilities.runOnUIThread(() -> job.completion.onDone(null, TranslationError.Cancelled.INSTANCE));
            Utilities.globalQueue.postRunnable(() -> pump(gate));
            return;
        }
        FileLog.d("translator: retrying in " + delay + "ms after " + error + " (attempt " + job.attempt + ")");
    }

    private static long backoff(ProviderLimits limits, int attempt) {
        int shift = Math.max(0, Math.min(attempt - 1, 16));
        long base = Math.min(limits.getBaseBackoffMs() << shift, limits.getMaxBackoffMs());
        return base + Utilities.random.nextInt(Math.max((int) (base / 4), 1));
    }

    private static void finish(Gate gate, Job job, List<String> texts, TranslationError error) {
        final boolean cancelled;
        synchronized (lock) {
            gate.running.remove(job);
            job.call = null;
            cancelled = job.cancelled;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (cancelled) {
                job.completion.onDone(null, TranslationError.Cancelled.INSTANCE);
            } else {
                job.completion.onDone(texts, error);
            }
        });
        Utilities.globalQueue.postRunnable(() -> pump(gate));
    }
}
