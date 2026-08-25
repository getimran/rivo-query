package com.knowimran.rivoquery.connection.application.secret;

import java.util.Objects;
import java.util.regex.Pattern;

public final class SecretReference {
    private static final Pattern NAMESPACE = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");
    private static final Pattern OPAQUE_ID = Pattern.compile("^[A-Za-z0-9_-]{16,128}$");

    private final String providerNamespace;
    private final String opaqueId;
    private final long version;
    private final String bindingFingerprint;

    public SecretReference(String providerNamespace, String opaqueId, long version, String bindingFingerprint) {
        this.providerNamespace = requireMatch(providerNamespace, NAMESPACE);
        this.opaqueId = requireMatch(opaqueId, OPAQUE_ID);
        this.bindingFingerprint = requireMatch(bindingFingerprint, OPAQUE_ID);
        if (version < 1) {
            throw new IllegalArgumentException("secret version must be positive");
        }
        this.version = version;
    }

    private static String requireMatch(String value, Pattern pattern) {
        Objects.requireNonNull(value, "value");
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("secret reference is invalid");
        }
        return value;
    }

    public String providerNamespace() { return providerNamespace; }
    public String opaqueId() { return opaqueId; }
    public long version() { return version; }
    public String bindingFingerprint() { return bindingFingerprint; }

    @Override
    public boolean equals(Object other) {
        return other instanceof SecretReference that
                && providerNamespace.equals(that.providerNamespace)
                && opaqueId.equals(that.opaqueId)
                && version == that.version
                && bindingFingerprint.equals(that.bindingFingerprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(providerNamespace, opaqueId, version, bindingFingerprint);
    }

    @Override
    public String toString() {
        return "SecretReference[REDACTED]";
    }
}
