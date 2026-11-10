package com.exteragram.messenger.ai.data;

import com.exteragram.messenger.ai.AiConfig;

import java.io.Serializable;
import java.util.Objects;

/** AI role (system prompt). Port of exteraGram's Role. */
public class Role implements Comparable<Role>, Serializable {
    private long emojiId;
    private boolean isSuggestion;
    private String name;
    private String prompt;

    public Role(String name, String prompt) {
        this.name = name;
        this.prompt = prompt;
    }

    @Override
    public int compareTo(Role role) {
        return name.compareTo(role.getName());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        return Objects.equals(name, ((Role) obj).name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }

    public String getName() {
        return name;
    }

    public String getPrompt() {
        return prompt;
    }

    public long getEmojiId() {
        return emojiId;
    }

    public Role setEmojiId(long emojiId) {
        this.emojiId = emojiId;
        return this;
    }

    public boolean isSuggestion() {
        return isSuggestion;
    }

    public Role setSuggestion(boolean suggestion) {
        isSuggestion = suggestion;
        return this;
    }

    public boolean isSelected() {
        return Objects.equals(AiConfig.getSelectedRole(), name);
    }
}
