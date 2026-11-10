package com.exteragram.messenger.icons;

import java.util.Objects;

/** exteraGram IconPackStorageResult (Kotlin sealed class): {@link Success} with a value or {@link Failure} with an error. */
public abstract class IconPackStorageResult<T> {

    private IconPackStorageResult() {
    }

    public static final class Success<T> extends IconPackStorageResult<T> {
        private final T value;

        public Success(T value) {
            this.value = value;
        }

        public T getValue() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof Success && Objects.equals(value, ((Success<?>) o).value));
        }

        @Override
        public int hashCode() {
            return value == null ? 0 : value.hashCode();
        }

        @Override
        public String toString() {
            return "Success(value=" + value + ")";
        }
    }

    public static final class Failure<T> extends IconPackStorageResult<T> {
        private final IconPackStorageError error;

        public Failure(IconPackStorageError error) {
            this.error = error;
        }

        public IconPackStorageError getError() {
            return error;
        }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof Failure && error == ((Failure<?>) o).error);
        }

        @Override
        public int hashCode() {
            return error.hashCode();
        }

        @Override
        public String toString() {
            return "Failure(error=" + error + ")";
        }
    }
}
