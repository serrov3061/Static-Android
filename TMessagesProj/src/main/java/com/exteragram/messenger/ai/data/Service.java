package com.exteragram.messenger.ai.data;

import android.text.TextUtils;

import com.exteragram.messenger.ai.AiConfig;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * A user-configured AI service: OpenAI-compatible endpoint + model + the user's own API key.
 * Stored as JSON in the local "aiConfig" SharedPreferences ("services"); never uploaded anywhere.
 */
public class Service implements Serializable {
    private String id;
    private String key;
    private Kind kind;
    private String model;
    private boolean reasoningEnabled;
    private String url;

    public enum Kind {
        REST,
        ON_DEVICE
    }

    public Service(String url, String model, String key, boolean reasoningEnabled) {
        this(UUID.randomUUID().toString(), url, model, key, reasoningEnabled);
    }

    public Service(String id, String url, String model, String key) {
        this(id, url, model, key, false);
    }

    public Service(String id, String url, String model, String key, boolean reasoningEnabled) {
        this.id = id;
        this.url = url;
        this.model = model;
        this.key = key;
        this.reasoningEnabled = reasoningEnabled;
    }

    public Kind getKind() {
        return kind == null ? Kind.REST : kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public boolean isOnDevice() {
        return getKind() == Kind.ON_DEVICE;
    }

    public String getId() {
        ensureId();
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public boolean ensureId() {
        if (id != null && !id.isEmpty()) {
            return false;
        }
        id = UUID.randomUUID().toString();
        return true;
    }

    public String getUrl() {
        return url;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getShortModel() {
        if (model == null) {
            return "";
        }
        String[] parts = model.split("/");
        String last = parts[parts.length - 1];
        int idx = last.indexOf(':');
        return idx != -1 ? last.substring(0, idx) : last;
    }

    public String getKey() {
        return key;
    }

    public boolean isReasoningEnabled() {
        return reasoningEnabled;
    }

    public void setReasoningEnabled(boolean reasoningEnabled) {
        this.reasoningEnabled = reasoningEnabled;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj != null && getClass() == obj.getClass()) {
            Service service = (Service) obj;
            return getKind() == service.getKind() && Objects.equals(url, service.url) && Objects.equals(model, service.model) && Objects.equals(key, service.key);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(getKind(), url, model, key);
    }

    public int getLegacyHash() {
        return (url + model + key).hashCode();
    }

    public boolean isSelected() {
        String selectedServiceId = AiConfig.getSelectedServiceId();
        if (!TextUtils.isEmpty(selectedServiceId)) {
            return Objects.equals(selectedServiceId, getId());
        }
        return Objects.equals(AiConfig.getSelectedService().getId(), getId());
    }
}
