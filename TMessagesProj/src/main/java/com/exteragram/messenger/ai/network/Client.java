package com.exteragram.messenger.ai.network;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;

import com.exteragram.messenger.ai.AiConfig;
import com.exteragram.messenger.ai.AiController;
import com.exteragram.messenger.ai.data.Message;
import com.exteragram.messenger.ai.data.Role;
import com.exteragram.messenger.ai.data.Service;
import com.exteragram.messenger.ai.network.backend.Backend;
import com.exteragram.messenger.ai.network.backend.BackendRequest;
import com.exteragram.messenger.ai.network.backend.BackendSink;
import com.exteragram.messenger.ai.network.backend.OnDeviceBackend;
import com.exteragram.messenger.ai.network.backend.RestBackend;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Port of exteraGram's AI Client: runs one request per single-thread executor, delivers callbacks on the UI thread,
 * keeps the conversation history (when enabled) in local prefs.
 *
 * What is sent to the configured service: the role prompt (system), up to 32 previous turns of the local history
 * (when "use history" is on), the prompt text (message text / selected text / typed text) and, when the user picks
 * a photo message, the image itself (base64, max 4 MB, larger images are recompressed to JPEG).
 */
public class Client {
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;

    private final ConcurrentHashMap<String, Runnable> activeCancellables = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ExecutorService> activeRequests = new ConcurrentHashMap<>();
    private final AtomicBoolean isGenerating = new AtomicBoolean(false);
    private final OnDeviceBackend onDeviceBackend = new OnDeviceBackend();
    private final RestBackend restBackend = new RestBackend();
    private final Role roleOverride;
    private final Service serviceOverride;

    private Client(Builder builder) {
        this.serviceOverride = builder.serviceOverride;
        this.roleOverride = builder.roleOverride;
    }

    public static String getMimeType(String path) {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".heic") || lower.endsWith(".heif")) {
            return "image/heic";
        }
        return "image/jpeg";
    }

    private static ImagePayload loadImagePayload(String path) {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        File file = new File(path);
        if (!file.exists() || !file.isFile() || file.length() == 0) {
            return null;
        }
        if (file.length() > MAX_IMAGE_BYTES) {
            return compressImage(path);
        }
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(file.length(), MAX_IMAGE_BYTES))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new ImagePayload(out.toByteArray(), getMimeType(path));
        } catch (Exception e) {
            FileLog.e("Error loading image: " + path, e);
            return null;
        }
    }

    private static ImagePayload compressImage(String path) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (bounds.outWidth / options.inSampleSize > 2048 || bounds.outHeight / options.inSampleSize > 2048) {
                options.inSampleSize *= 2;
            }
            Bitmap bitmap = BitmapFactory.decodeFile(path, options);
            if (bitmap == null) {
                return null;
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                int quality = 85;
                do {
                    out.reset();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
                    quality -= 10;
                    if (out.size() <= MAX_IMAGE_BYTES) {
                        break;
                    }
                } while (quality >= 55);
                if (out.size() <= MAX_IMAGE_BYTES) {
                    return new ImagePayload(out.toByteArray(), "image/jpeg");
                }
                return null;
            } finally {
                bitmap.recycle();
            }
        } catch (Exception e) {
            FileLog.e("Error compressing image: " + path, e);
            return null;
        }
    }

    private Service getSelectedService() {
        return serviceOverride != null ? serviceOverride : AiController.getInstance().getSelected();
    }

    private Backend resolveBackend(Service service) {
        return service == null || !service.isOnDevice() ? restBackend : onDeviceBackend;
    }

    private Role getActiveRole() {
        if (roleOverride != null) {
            return roleOverride;
        }
        if (serviceOverride == null) {
            return AiController.getInstance().getSelectedRole();
        }
        return null;
    }

    public String getResponse(String prompt, GenerationCallback callback) {
        return getResponse(prompt, false, false, null, callback);
    }

    /**
     * @param useHistory send previous turns and save this turn to the local history
     * @param stream     use SSE streaming
     * @param imagePath  optional local image to attach
     * @return request id for {@link #stopRequest}
     */
    public String getResponse(String prompt, boolean useHistory, boolean stream, String imagePath, GenerationCallback callback) {
        final String requestId = UUID.randomUUID().toString();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        activeRequests.put(requestId, executor);
        executor.execute(() -> runRequest(imagePath, requestId, prompt, useHistory, stream, callback));
        return requestId;
    }

    private void runRequest(String imagePath, String requestId, String prompt, boolean useHistory, boolean stream, GenerationCallback callback) {
        isGenerating.set(true);
        try {
            Service service = getSelectedService();
            Backend backend = resolveBackend(service);
            ImagePayload image = backend.supportsImages() && AiController.canSendImage(imagePath) ? loadImagePayload(imagePath) : null;
            if (!activeRequests.containsKey(requestId)) {
                return;
            }
            Message message = new Message("user", prompt, image != null ? image.data() : null, image != null ? image.mimeType() : null);
            ArrayList<Message> history = useHistory ? historyForRequest(AiConfig.getConversationHistory(), backend) : new ArrayList<>();
            if (!activeRequests.containsKey(requestId)) {
                return;
            }
            float temperature = (AiConfig.getTemperature() / 20.0f) * backend.getMaxTemperature();
            backend.execute(new BackendRequest(service, getActiveRole(), history, message, stream, temperature, backend.getMaxOutputTokens()), new RequestSink(requestId, message, useHistory, callback));
        } catch (Throwable e) {
            if (activeRequests.containsKey(requestId)) {
                FileLog.e("AI Error: ", e);
                notifyErrorAndFinish(requestId, callback, 500, e.getMessage() != null ? e.getMessage() : "Unknown error");
            }
        }
    }

    private void sendStreamChunk(String requestId, String text, GenerationCallback callback) {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (activeRequests.containsKey(requestId) && callback != null) {
                callback.onChunk(text);
            }
        });
    }

    private void notifyThinking(String requestId, GenerationCallback callback) {
        AndroidUtilities.runOnUIThread(() -> {
            if (activeRequests.containsKey(requestId) && callback != null) {
                callback.onThinking();
            }
        });
    }

    private ArrayList<Message> historyForRequest(ArrayList<Message> history, Backend backend) {
        ArrayList<Message> result = new ArrayList<>();
        int charsLimit = backend.getHistoryCharsLimit();
        int messagesLimit = backend.getHistoryMessagesLimit();
        int chars = 0;
        for (int i = history.size() - 1; i > 0 && result.size() < messagesLimit; i--) {
            Message answer = history.get(i);
            if (answer == null || !"assistant".equals(answer.role()) || TextUtils.isEmpty(answer.content())) {
                continue;
            }
            Message question = history.get(i - 1);
            if (question == null || "assistant".equals(question.role()) || TextUtils.isEmpty(question.content())) {
                continue;
            }
            int length = question.content().length() + answer.content().length() + chars;
            if (length <= charsLimit && result.size() + 2 <= messagesLimit) {
                result.add(0, copyTextMessage(question));
                result.add(1, copyTextMessage(answer));
                chars = length;
            }
        }
        return result;
    }

    private void saveTurn(Message message, String response) {
        ArrayList<Message> history = AiConfig.getConversationHistory();
        history.add(copyTextMessage(message));
        history.add(new Message("assistant", response));
        int length = 0;
        for (Message m : history) {
            length += m.content() == null ? 0 : m.content().length();
        }
        while (history.size() > 2 && (history.size() > 64 || length > 64000)) {
            Message removed = history.remove(0);
            length -= removed.content() == null ? 0 : removed.content().length();
        }
        if (history.size() > 2 && "assistant".equals(history.get(0).role())) {
            history.remove(0);
        }
        AiConfig.saveConversationHistory(history);
    }

    private Message copyTextMessage(Message message) {
        return new Message(message.role(), message.content());
    }

    private void notifyResponseAndFinish(String requestId, GenerationCallback callback, String text) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                if (activeRequests.containsKey(requestId) && callback != null) {
                    callback.onResponse(text);
                }
            } finally {
                finishRequest(requestId);
            }
        });
    }

    private void notifyErrorAndFinish(String requestId, GenerationCallback callback, int code, String message) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                if (activeRequests.containsKey(requestId) && callback != null) {
                    callback.onError(code, message);
                }
            } finally {
                finishRequest(requestId);
            }
        });
    }

    public boolean isGenerating() {
        return isGenerating.get();
    }

    private void finishRequest(String requestId) {
        activeCancellables.remove(requestId);
        ExecutorService executor = activeRequests.remove(requestId);
        if (executor != null) {
            executor.shutdown();
        }
        if (activeRequests.isEmpty()) {
            isGenerating.set(false);
        }
    }

    public void stopRequest(String requestId) {
        if (TextUtils.isEmpty(requestId)) {
            return;
        }
        Runnable cancel = activeCancellables.remove(requestId);
        if (cancel != null) {
            // HttpURLConnection.disconnect() may do network I/O, keep it off the UI thread
            new Thread(() -> {
                try {
                    cancel.run();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }, "ai-cancel").start();
        }
        ExecutorService executor = activeRequests.remove(requestId);
        if (executor != null) {
            executor.shutdownNow();
        }
        if (activeRequests.isEmpty()) {
            isGenerating.set(false);
        }
    }

    public final class RequestSink implements BackendSink {
        private final GenerationCallback callback;
        private final Message currentMessage;
        private final String requestId;
        private final boolean saveHistory;

        public RequestSink(String requestId, Message currentMessage, boolean saveHistory, GenerationCallback callback) {
            this.requestId = requestId;
            this.currentMessage = currentMessage;
            this.saveHistory = saveHistory;
            this.callback = callback;
        }

        @Override
        public boolean isActive() {
            return activeRequests.containsKey(requestId);
        }

        @Override
        public void onCancellable(Runnable cancel) {
            if (cancel != null) {
                activeCancellables.put(requestId, cancel);
            }
        }

        @Override
        public void onThinking() {
            notifyThinking(requestId, callback);
        }

        @Override
        public void onChunk(String text) {
            sendStreamChunk(requestId, text, callback);
        }

        @Override
        public void onComplete(String text) {
            if (!isActive()) {
                return;
            }
            if (TextUtils.isEmpty(text)) {
                onError(204, "Response body is empty");
                return;
            }
            if (saveHistory) {
                saveTurn(currentMessage, text);
            }
            notifyResponseAndFinish(requestId, callback, text);
        }

        @Override
        public void onError(int code, String message) {
            notifyErrorAndFinish(requestId, callback, code, message);
        }
    }

    public static class Builder {
        private Role roleOverride;
        private Service serviceOverride;

        public Builder serviceOverride(Service service) {
            this.serviceOverride = service;
            return this;
        }

        public Builder roleOverride(Role role) {
            this.roleOverride = role;
            return this;
        }

        public Client build() {
            return new Client(this);
        }
    }

    public static final class ImagePayload {
        private final byte[] data;
        private final String mimeType;

        private ImagePayload(byte[] data, String mimeType) {
            this.data = data;
            this.mimeType = mimeType;
        }

        public byte[] data() {
            return data;
        }

        public String mimeType() {
            return mimeType;
        }
    }
}
