package com.exteragram.messenger.preferences.chats.components;

import java.util.Objects;

/** exteraGram 13.1.1: an inline results example — the input and what the inline result shows for it (null if nothing). */
public final class InlineDocExample {
    private final String input;
    private final String result;

    public InlineDocExample(String input, String result) {
        this.input = input;
        this.result = result;
    }

    public String getInput() {
        return input;
    }

    public String getResult() {
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InlineDocExample)) return false;
        InlineDocExample other = (InlineDocExample) o;
        return Objects.equals(input, other.input) && Objects.equals(result, other.result);
    }

    @Override
    public int hashCode() {
        return input.hashCode() * 31 + (result == null ? 0 : result.hashCode());
    }
}
