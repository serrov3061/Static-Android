package com.exteragram.messenger.preferences.chats.components;

import java.util.List;
import java.util.Objects;

/** exteraGram 13.1.1: one block of the inline results documentation shown inside {@link InlineFeatureCell}. */
public interface InlineDocBlock {

    final class Text implements InlineDocBlock {
        private final CharSequence text;

        public Text(CharSequence text) {
            this.text = text;
        }

        public CharSequence getText() {
            return text;
        }

        @Override
        public boolean equals(Object o) {
            return this == o || o instanceof Text && Objects.equals(text, ((Text) o).text);
        }

        @Override
        public int hashCode() {
            return text.hashCode();
        }
    }

    /** Function names rendered as monospace chips. */
    final class Codes implements InlineDocBlock {
        private final List<String> codes;

        public Codes(List<String> codes) {
            this.codes = codes;
        }

        public List<String> getCodes() {
            return codes;
        }

        @Override
        public boolean equals(Object o) {
            return this == o || o instanceof Codes && Objects.equals(codes, ((Codes) o).codes);
        }

        @Override
        public int hashCode() {
            return codes.hashCode();
        }
    }

    /** Tappable examples (copied to the clipboard) with the live result next to them. */
    final class Examples implements InlineDocBlock {
        private final List<InlineDocExample> examples;

        public Examples(List<InlineDocExample> examples) {
            this.examples = examples;
        }

        public List<InlineDocExample> getExamples() {
            return examples;
        }

        @Override
        public boolean equals(Object o) {
            return this == o || o instanceof Examples && Objects.equals(examples, ((Examples) o).examples);
        }

        @Override
        public int hashCode() {
            return examples.hashCode();
        }
    }
}
