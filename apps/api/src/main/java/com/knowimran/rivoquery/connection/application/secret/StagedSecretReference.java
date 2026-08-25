package com.knowimran.rivoquery.connection.application.secret;

import java.util.Objects;

public final class StagedSecretReference {
    private final BoundSecretReference value;

    public StagedSecretReference(BoundSecretReference value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public BoundSecretReference value() {
        return value;
    }

    @Override
    public String toString() {
        return "StagedSecretReference[REDACTED]";
    }
}
