package com.exteragram.messenger.ai.network.backend;

import com.exteragram.messenger.ai.data.Message;
import com.exteragram.messenger.ai.data.Role;
import com.exteragram.messenger.ai.data.Service;

import java.util.List;

public final class BackendRequest {
    private final Service service;
    private final Role role;
    private final List<Message> history;
    private final Message currentMessage;
    private final boolean stream;
    private final float temperature;
    private final int maxOutputTokens;

    public BackendRequest(Service service, Role role, List<Message> history, Message currentMessage, boolean stream, float temperature, int maxOutputTokens) {
        this.service = service;
        this.role = role;
        this.history = history;
        this.currentMessage = currentMessage;
        this.stream = stream;
        this.temperature = temperature;
        this.maxOutputTokens = maxOutputTokens;
    }

    public Service service() {
        return service;
    }

    public Role role() {
        return role;
    }

    public List<Message> history() {
        return history;
    }

    public Message currentMessage() {
        return currentMessage;
    }

    public boolean stream() {
        return stream;
    }

    public float temperature() {
        return temperature;
    }

    public int maxOutputTokens() {
        return maxOutputTokens;
    }
}
