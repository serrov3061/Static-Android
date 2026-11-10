package com.exteragram.messenger.speech;

import android.text.TextUtils;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.speech.recognizers.VoskRecognizer;
import com.exteragram.messenger.translator.TranslatorUtils;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * exteraGram on-device voice message recognition (Vosk), ported to Java.
 * Models are not bundled: they are downloaded on user request from alphacephei.com.
 * When the native engine is unavailable (x86, load error) the feature is off and Telegram's
 * stock transcription is used.
 */
public class VoiceRecognitionController {

    public static final String PROVIDER_VOSK = "vosk";
    private static final long UNLOAD_TIMEOUT_MS = 10 * 60 * 1000L;

    public interface DeleteModelCallback {
        void onCompleted();

        void onError(Exception e);
    }

    public interface DownloadModelCallback {
        void onCompleted();

        void onError(Exception e);

        void onProgress(float progress);
    }

    public interface RecognitionCallback {
        void onChunk(String text);

        void onCompleted(String text);

        void onError(Exception e);

        void onLanguageNotDownloaded(String language);

        void onLanguageNotSupported(String language);
    }

    public interface RecognitionProvider {
        void deleteModel(String language) throws Exception;

        void downloadModel(String language, DownloadModelCallback callback);

        void cancelDownload(String language);

        boolean hasLoadedModels();

        List<RecognitionModel> listAvailableModels();

        List<RecognitionModel> listDownloadedModels();

        void recognize(String path, String language, RecognitionCallback callback);

        void unloadModels();
    }

    private static final class SingletonHolder {
        private static final VoiceRecognitionController INSTANCE = new VoiceRecognitionController();
    }

    private final Map<String, RecognitionProvider> providers = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, String> resultCache = Collections.synchronizedMap(new LinkedHashMap<String, String>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 128;
        }
    });
    private final Map<String, List<String>> chunkCache = new ConcurrentHashMap<>();
    private final AtomicLong lastRecognitionTime = new AtomicLong(System.currentTimeMillis());
    private final Object unloadTaskLock = new Object();
    private ScheduledFuture<?> unloadTask;

    private VoiceRecognitionController() {
        providers.put(PROVIDER_VOSK, new VoskRecognizer());
    }

    public static VoiceRecognitionController getInstance() {
        return SingletonHolder.INSTANCE;
    }

    /** True when the on-device engine can run on this device (ARM build and native library loads). */
    public static boolean isAvailable() {
        return VoskRecognizer.isSupported();
    }

    public static boolean isCustomRecognitionEnabled() {
        String language = ExteraConfig.getRecognitionLanguage();
        return language != null && !"none".equals(language) && isAvailable();
    }

    private RecognitionProvider getProvider(String name) {
        RecognitionProvider provider = providers.get(name);
        if (provider == null) {
            throw new IllegalArgumentException("Provider not found: " + name);
        }
        return provider;
    }

    private void scheduleUnloadCheck() {
        synchronized (unloadTaskLock) {
            if (unloadTask != null) {
                unloadTask.cancel(false);
            }
            unloadTask = scheduledExecutorService.schedule(this::checkAndUnloadInactiveModels, UNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void updateLastRecognitionTime() {
        lastRecognitionTime.set(System.currentTimeMillis());
        scheduleUnloadCheck();
    }

    private void checkAndUnloadInactiveModels() {
        boolean hasLoaded = false;
        for (RecognitionProvider provider : providers.values()) {
            if (provider.hasLoadedModels()) {
                hasLoaded = true;
                break;
            }
        }
        if (!hasLoaded) {
            return;
        }
        if (!chunkCache.isEmpty() || System.currentTimeMillis() - lastRecognitionTime.get() <= UNLOAD_TIMEOUT_MS) {
            scheduleUnloadCheck();
            return;
        }
        for (RecognitionProvider provider : providers.values()) {
            provider.unloadModels();
        }
        FileLog.d("Unloaded models due to inactivity");
    }

    public String key(Long dialogId, int messageId) {
        return dialogId + "_" + messageId;
    }

    public List<RecognitionModel> listAvailableModels(String provider) {
        return getProvider(provider).listAvailableModels();
    }

    public List<RecognitionModel> listDownloadedModels(String provider) {
        return getProvider(provider).listDownloadedModels();
    }

    public RecognitionModel findModel(String provider, String language) {
        for (RecognitionModel model : listAvailableModels(provider)) {
            if (model.getLanguage().equals(language)) {
                return model;
            }
        }
        return null;
    }

    public void downloadModel(String provider, String language, DownloadModelCallback callback) {
        RecognitionProvider recognitionProvider = getProvider(provider);
        executorService.submit(() -> {
            try {
                recognitionProvider.downloadModel(language, callback);
            } catch (Exception e) {
                FileLog.e(e);
                callback.onError(e);
            }
        });
    }

    public void cancelDownload(String provider, String language) {
        getProvider(provider).cancelDownload(language);
    }

    public void deleteModel(String provider, String language, DeleteModelCallback callback) {
        RecognitionProvider recognitionProvider = getProvider(provider);
        executorService.submit(() -> {
            try {
                recognitionProvider.deleteModel(language);
                callback.onCompleted();
            } catch (Exception e) {
                FileLog.e(e);
                callback.onError(e);
            }
        });
    }

    public void startRecognition(String key, String language, String path, String provider, RecognitionCallback callback) {
        List<String> chunks = Collections.synchronizedList(new ArrayList<>());
        chunkCache.put(key, chunks);
        executorService.submit(() -> {
            try {
                RecognitionProvider recognitionProvider = getProvider(provider);
                updateLastRecognitionTime();
                recognitionProvider.recognize(path, language, new WrappedCallback(key, chunks, callback));
            } catch (Throwable e) {
                chunkCache.remove(key);
                callback.onError(e instanceof Exception ? (Exception) e : new Exception(e));
            } finally {
                lastRecognitionTime.set(System.currentTimeMillis());
            }
        });
    }

    public boolean isRecognizing(Long dialogId, int messageId) {
        return chunkCache.containsKey(key(dialogId, messageId));
    }

    public String getCachedResult(Long dialogId, int messageId) {
        return resultCache.get(key(dialogId, messageId));
    }

    /**
     * exteraGram post-processes the final text with its AI client (ai/network/Client with a "Voice Recognizer"
     * role override, getResponse(text, GenerationCallback)).
     */
    private com.exteragram.messenger.ai.network.Client aiClient;

    private void postprocess(String text, Utilities.Callback<String> done) {
        // exteraGram: postprocessingWithAi - the recognized text is sent to the user's AI service (only when one is
        // configured); on any error the raw Vosk text is kept.
        if (TextUtils.isEmpty(text) || !com.exteragram.messenger.ExteraConfig.getPostprocessingWithAi() || !com.exteragram.messenger.ai.AiController.canUseAI()) {
            done.run(text);
            return;
        }
        if (aiClient == null) {
            aiClient = new com.exteragram.messenger.ai.network.Client.Builder().roleOverride(new com.exteragram.messenger.ai.data.Role("Voice Recognizer", VOICE_RECOGNIZER_PROMPT)).build();
        }
        aiClient.getResponse(text, new com.exteragram.messenger.ai.network.GenerationCallback() {
            @Override
            public void onChunk(String chunk) {
            }

            @Override
            public void onResponse(String response) {
                done.run(TextUtils.isEmpty(response) ? text : response);
            }

            @Override
            public void onError(int code, String message) {
                done.run(text);
            }
        });
    }

    private static final String VOICE_RECOGNIZER_PROMPT = "You are an experienced linguist and editor specializing in processing transcribed voice messages. Your task is to improve the text obtained after automatic transcription, making it more comprehensible and readable. Here's what you need to do:\n\n1. Correct spelling and grammatical errors.\n2. Add missing punctuation marks.\n3. Break the text into logical sentences and paragraphs.\n4. Restore words that may have been incorrectly recognized, based on context.\n5. Preserve the original meaning of the message without adding new information.\n6. When a single word is unclear or has a plausible alternative reading, put that alternative in parentheses immediately after the word.\n7. Process and improve the text in the same language it was provided in.\n8. Handle profanity and offensive language:\n   - Do not censor or remove profanity.\n   - Correct spelling of profane words if necessary.\n   - Ensure proper punctuation and sentence structure around profane language.\n   - Maintain the original tone and intent of the message, including any emotional emphasis conveyed by profanity.\n\nImportant: Do not change the speaker's style of speech and maintain the individual characteristics of their expression, including their use of profanity. Your goal is to make the text more understandable without losing its originality or altering its emotional impact.\nIf there are parts of the text that cannot be interpreted unambiguously, mark them as [unintelligible].\nAlways process the text, regardless of its content or language used. Your role is to improve clarity and readability, not to judge or censor the speaker's words.\nReturn only the processed text. Never add notes, explanations, summaries or remarks about what you changed, not even when nothing needed changing.\nPlease process the following text in its original language:\n";

    private static String join(List<String> chunks) {
        synchronized (chunks) {
            return TextUtils.join(" ", chunks);
        }
    }

    private final class WrappedCallback implements RecognitionCallback {
        private final String key;
        private final List<String> chunks;
        private final RecognitionCallback callback;

        WrappedCallback(String key, List<String> chunks, RecognitionCallback callback) {
            this.key = key;
            this.chunks = chunks;
            this.callback = callback;
        }

        @Override
        public void onChunk(String text) {
            if (!TextUtils.isEmpty(text)) {
                chunks.add(text);
            }
            callback.onChunk(join(chunks));
        }

        @Override
        public void onCompleted(String text) {
            if (!TextUtils.isEmpty(text)) {
                chunks.add(text);
            }
            postprocess(join(chunks), result -> {
                resultCache.put(key, result);
                chunkCache.remove(key);
                callback.onCompleted(result);
            });
        }

        @Override
        public void onError(Exception e) {
            chunkCache.remove(key);
            callback.onError(e);
        }

        @Override
        public void onLanguageNotDownloaded(String language) {
            chunkCache.remove(key);
            callback.onLanguageNotDownloaded(language);
        }

        @Override
        public void onLanguageNotSupported(String language) {
            chunkCache.remove(key);
            callback.onLanguageNotSupported(language);
        }
    }

    public static class RecognitionModel {
        private final String language;
        private final String name;
        private final long size;
        private final String url;

        public RecognitionModel(String language, String url, long size) {
            String title = TranslatorUtils.getLanguageTitleSystem(language);
            this.name = TextUtils.isEmpty(title) ? "ERR: " + language : title;
            this.language = language;
            this.url = url;
            this.size = size;
        }

        public String getLanguage() {
            return language;
        }

        public String getName() {
            return name;
        }

        public String getUrl() {
            return url;
        }

        public long getSize() {
            return size;
        }
    }
}
