package com.knowimran.rivoquery.connection.application.secret;

import java.util.Objects;

public record BoundSecretReference(SecretBinding binding, SecretReference reference) {
    public BoundSecretReference {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(reference, "reference");
        if (binding.version() != reference.version()) {
            throw new IllegalArgumentException("secret versions do not match");
        }
    }

    @Override
    public String toString() {
        return "BoundSecretReference[REDACTED]";
    }
}
