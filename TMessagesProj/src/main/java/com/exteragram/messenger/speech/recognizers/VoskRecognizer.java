package com.exteragram.messenger.speech.recognizers;

import android.os.StatFs;
import android.text.TextUtils;

import com.exteragram.messenger.speech.VoiceRecognitionController;
import com.exteragram.messenger.speech.utils.FormatConverter;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Vosk (Kaldi) offline recognizer. Models are small Vosk models downloaded from alphacephei.com
 * (same list as exteraGram) into files/vosk-models/&lt;lang&gt;; nothing is bundled in the APK.
 */
public class VoskRecognizer implements VoiceRecognitionController.RecognitionProvider, AutoCloseable {

    private static final String MODELS_HOST = "alphacephei.com";
    private static final String MODELS_PREFIX = "https://alphacephei.com/vosk/models/";

    private static volatile Boolean abiSupported;
    private static volatile boolean nativeFailed;
    private static volatile boolean nativeLoaded;

    private final List<VoiceRecognitionController.RecognitionModel> models;
    private final Map<String, Model> loadedModels = new ConcurrentHashMap<>();
    private final Set<String> cancelledDownloads = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Object modelLock = new Object();

    public VoskRecognizer() {
        ArrayList<VoiceRecognitionController.RecognitionModel> list = new ArrayList<>();
        list.add(model("ca", "vosk-model-small-ca-0.4.zip", 43405881L));
        list.add(model("cs", "vosk-model-small-cs-0.4-rhasspy.zip", 46088666L));
        list.add(model("de", "vosk-model-small-de-0.15.zip", 46499967L));
        list.add(model("en", "vosk-model-small-en-us-0.15.zip", 41205931L));
        list.add(model("eo", "vosk-model-small-eo-0.42.zip", 43839401L));
        list.add(model("es", "vosk-model-small-es-0.42.zip", 39817833L));
        list.add(model("fa", "vosk-model-small-fa-0.42.zip", 53431220L));
        list.add(model("fr", "vosk-model-small-fr-0.22.zip", 42233323L));
        list.add(model("gu", "vosk-model-small-gu-0.42.zip", 108054987L));
        list.add(model("hi", "vosk-model-small-hi-0.22.zip", 44458845L));
        list.add(model("it", "vosk-model-small-it-0.22.zip", 49665141L));
        list.add(model("ja", "vosk-model-small-ja-0.22.zip", 49704573L));
        list.add(model("kk", "vosk-model-small-kz-0.42.zip", 59697294L));
        list.add(model("ko", "vosk-model-small-ko-0.22.zip", 86914329L));
        list.add(model("nl", "vosk-model-small-nl-0.22.zip", 40441176L));
        list.add(model("pl", "vosk-model-small-pl-0.22.zip", 52979372L));
        list.add(model("pt", "vosk-model-small-pt-0.3.zip", 32453112L));
        list.add(model("ru", "vosk-model-small-ru-0.22.zip", 46236750L));
        list.add(model("tg", "vosk-model-small-tg-0.22.zip", 51879043L));
        list.add(model("tr", "vosk-model-small-tr-0.3.zip", 36855784L));
        list.add(model("uk", "vosk-model-small-uk-v3-small.zip", 143914407L));
        list.add(model("uz", "vosk-model-small-uz-0.22.zip", 51061189L));
        list.add(model("vi", "vosk-model-small-vn-0.4.zip", 33656337L));
        list.add(model("zh", "vosk-model-small-cn-0.22.zip", 43898754L));
        models = Collections.unmodifiableList(list);
    }

    private static VoiceRecognitionController.RecognitionModel model(String language, String file, long size) {
        return new VoiceRecognitionController.RecognitionModel(language, MODELS_PREFIX + file, size);
    }

    /** libvosk.so is shipped for arm64-v8a and armeabi-v7a only; x86 builds and load failures disable the feature. */
    public static boolean isSupported() {
        if (nativeFailed) {
            return false;
        }
        Boolean supported = abiSupported;
        if (supported == null) {
            boolean result = false;
            try {
                String dir = ApplicationLoader.applicationContext.getApplicationInfo().nativeLibraryDir;
                if (dir != null) {
                    String abi = new File(dir).getName();
                    result = abi.startsWith("arm");
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            abiSupported = supported = result;
        }
        return supported;
    }

    /** Loads JNA + libvosk once. Must be called off the main thread. */
    private static boolean ensureNative() {
        if (nativeLoaded) {
            return true;
        }
        if (!isSupported()) {
            return false;
        }
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            nativeLoaded = true;
            return true;
        } catch (Throwable e) {
            FileLog.e("Vosk native library is unavailable", e);
            nativeFailed = true;
            return false;
        }
    }

    private static File getModelsDir() {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), "vosk-models");
    }

    private File getModelDir(String language) {
        return new File(getModelsDir(), language);
    }

    private VoiceRecognitionController.RecognitionModel findModel(String language) {
        for (VoiceRecognitionController.RecognitionModel model : models) {
            if (model.getLanguage().equals(language)) {
                return model;
            }
        }
        return null;
    }

    @Override
    public List<VoiceRecognitionController.RecognitionModel> listAvailableModels() {
        return models;
    }

    @Override
    public List<VoiceRecognitionController.RecognitionModel> listDownloadedModels() {
        ArrayList<VoiceRecognitionController.RecognitionModel> result = new ArrayList<>();
        for (VoiceRecognitionController.RecognitionModel model : models) {
            if (isDownloaded(model.getLanguage())) {
                result.add(model);
            }
        }
        return result;
    }

    private boolean isDownloaded(String language) {
        File dir = getModelDir(language);
        if (!dir.isDirectory()) {
            return false;
        }
        String[] list = dir.list();
        return list != null && list.length > 0;
    }

    @Override
    public void cancelDownload(String language) {
        cancelledDownloads.add(language);
    }

    private void checkCancelled(String language) {
        if (cancelledDownloads.contains(language)) {
            throw new CancellationException("Download cancelled: " + language);
        }
    }

    @Override
    public void downloadModel(String language, VoiceRecognitionController.DownloadModelCallback callback) {
        VoiceRecognitionController.RecognitionModel model = findModel(language);
        if (model == null) {
            callback.onError(new IllegalArgumentException("Model not found: " + language));
            return;
        }
        cancelledDownloads.remove(language);
        File modelsDir = getModelsDir();
        File tmpDir = new File(modelsDir, ".tmp-" + language);
        File zipFile = new File(tmpDir, "model.zip");
        File unpackDir = new File(tmpDir, "unpacked");
        HttpURLConnection connection = null;
        try {
            deleteDirectory(tmpDir);
            if (!tmpDir.mkdirs() && !tmpDir.isDirectory()) {
                throw new IOException("Cannot create " + tmpDir);
            }
            try {
                long available = new StatFs(modelsDir.getAbsolutePath()).getAvailableBytes();
                if (available < model.getSize() * 3) {
                    throw new IOException("Not enough free space: " + available);
                }
            } catch (IllegalArgumentException ignore) {
            }

            URL url = new URL(model.getUrl());
            if (!"https".equals(url.getProtocol()) || !MODELS_HOST.equals(url.getHost())) {
                throw new IOException("Unexpected model url: " + url);
            }
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept-Encoding", "identity");
            int code = connection.getResponseCode();
            URL finalUrl = connection.getURL();
            if (finalUrl == null || !finalUrl.getHost().endsWith(MODELS_HOST)) {
                throw new IOException("Unexpected redirect: " + finalUrl);
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " for " + url);
            }
            long contentLength = connection.getContentLength();
            if (contentLength <= 0) {
                contentLength = model.getSize();
            }
            long downloaded = 0;
            long lastProgressTime = 0;
            try (InputStream in = new BufferedInputStream(connection.getInputStream()); OutputStream out = new FileOutputStream(zipFile)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    checkCancelled(language);
                    out.write(buffer, 0, read);
                    downloaded += read;
                    long now = System.currentTimeMillis();
                    if (now - lastProgressTime > 100) {
                        lastProgressTime = now;
                        // 1.0 is reserved for the unzip phase
                        callback.onProgress(Math.min(0.99f, downloaded / (float) contentLength));
                    }
                }
            }
            connection.disconnect();
            connection = null;
            checkCancelled(language);
            callback.onProgress(1f);
            unpackZip(zipFile, unpackDir, language);
            checkCancelled(language);

            File target = getModelDir(language);
            synchronized (modelLock) {
                Model loaded = loadedModels.remove(language);
                if (loaded != null) {
                    loaded.close();
                }
                deleteDirectory(target);
                if (!unpackDir.renameTo(target)) {
                    throw new IOException("Cannot move model to " + target);
                }
            }
            deleteDirectory(tmpDir);
            callback.onCompleted();
        } catch (Throwable e) {
            if (!(e instanceof CancellationException)) {
                FileLog.e("Failed to download vosk model " + language, e);
            }
            deleteDirectory(tmpDir);
            callback.onError(e instanceof Exception ? (Exception) e : new Exception(e));
        } finally {
            cancelledDownloads.remove(language);
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Exception ignore) {
                }
            }
        }
    }

    /** Unpacks the archive, stripping its single top-level folder (vosk-model-small-xx/...). */
    private void unpackZip(File zipFile, File targetDir, String language) throws IOException {
        if (!targetDir.mkdirs() && !targetDir.isDirectory()) {
            throw new IOException("Cannot create " + targetDir);
        }
        String targetPath = targetDir.getCanonicalPath() + File.separator;
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                checkCancelled(language);
                String name = entry.getName().replace('\\', '/');
                int slash = name.indexOf('/');
                String relative = slash >= 0 ? name.substring(slash + 1) : name;
                if (relative.isEmpty()) {
                    continue;
                }
                File file = new File(targetDir, relative);
                if (!file.getCanonicalPath().startsWith(targetPath)) {
                    throw new IOException("Bad zip entry: " + name);
                }
                if (entry.isDirectory()) {
                    file.mkdirs();
                    continue;
                }
                File parent = file.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (OutputStream out = new FileOutputStream(file)) {
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    @Override
    public void deleteModel(String language) throws IOException {
        if (findModel(language) == null) {
            throw new IllegalArgumentException("Model not found: " + language);
        }
        synchronized (modelLock) {
            Model loaded = loadedModels.remove(language);
            if (loaded != null) {
                loaded.close();
            }
            File dir = getModelDir(language);
            if (dir.exists()) {
                deleteDirectory(dir);
                if (dir.exists()) {
                    throw new IOException("Failed to delete " + dir);
                }
            }
        }
    }

    private static void deleteDirectory(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteDirectory(child);
                }
            }
        }
        if (!file.delete()) {
            FileLog.e("Failed to delete file or directory: " + file.getAbsolutePath());
        }
    }

    private static String parseText(String json) {
        if (TextUtils.isEmpty(json)) {
            return "";
        }
        try {
            return new JSONObject(json).optString("text", "");
        } catch (Exception e) {
            FileLog.e(e);
            return "";
        }
    }

    @Override
    public void recognize(String path, String language, VoiceRecognitionController.RecognitionCallback callback) {
        if (findModel(language) == null) {
            callback.onLanguageNotSupported(language);
            return;
        }
        if (!isDownloaded(language)) {
            callback.onLanguageNotDownloaded(language);
            return;
        }
        if (!ensureNative()) {
            callback.onError(new IllegalStateException("Vosk is not available on this device"));
            return;
        }
        Recognizer recognizer = null;
        FormatConverter.PcmInputStream pcm = null;
        try {
            Model model;
            synchronized (modelLock) {
                model = loadedModels.get(language);
                if (model == null) {
                    FileLog.d("Loading vosk model: " + language);
                    model = new Model(getModelDir(language).getAbsolutePath());
                    loadedModels.put(language, model);
                }
                pcm = FormatConverter.extractAndConvertToPcm(path, false);
                recognizer = new Recognizer(model, FormatConverter.TARGET_SAMPLE_RATE);
                byte[] buffer = new byte[8192];
                int read;
                while ((read = pcm.read(buffer, 0, buffer.length)) > 0) {
                    if (recognizer.acceptWaveForm(buffer, read)) {
                        callback.onChunk(parseText(recognizer.getResult()));
                    }
                }
                String result = parseText(recognizer.getFinalResult());
                callback.onCompleted(result);
            }
        } catch (Throwable e) {
            if (e instanceof UnsatisfiedLinkError || e instanceof NoClassDefFoundError || e instanceof ExceptionInInitializerError) {
                nativeFailed = true;
            }
            FileLog.e("Failed to recognize", e);
            callback.onError(e instanceof Exception ? (Exception) e : new Exception(e));
        } finally {
            if (recognizer != null) {
                try {
                    recognizer.close();
                } catch (Throwable ignore) {
                }
            }
            if (pcm != null) {
                try {
                    pcm.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    @Override
    public void unloadModels() {
        synchronized (modelLock) {
            for (Model model : loadedModels.values()) {
                try {
                    model.close();
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            loadedModels.clear();
        }
    }

    @Override
    public boolean hasLoadedModels() {
        return !loadedModels.isEmpty();
    }

    @Override
    public void close() {
        unloadModels();
    }
}
