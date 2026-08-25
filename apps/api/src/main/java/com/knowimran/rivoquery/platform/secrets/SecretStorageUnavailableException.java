package com.knowimran.rivoquery.platform.secrets;

import java.io.IOException;
import java.util.Objects;

/**
 * The underlying storage medium (local filesystem or a production provider)
 * could not complete a read, write, or delete. Fail-closed: never a partial
 * success and never a plaintext fallback.
 */
public final class SecretStorageUnavailableException extends SecretProviderException {
    public SecretStorageUnavailableException() {
        super(SecretFailureCategory.STORAGE_UNAVAILABLE, "secret store is unavailable");
    }

    public SecretStorageUnavailableException(Throwable cause) {
        super(SecretFailureCategory.STORAGE_UNAVAILABLE, "secret store is unavailable", cause);
    }

    /**
     * Wraps a lower-level filesystem/provider {@link IOException} whose own
     * message (e.g. "file already exists", "no such file") routinely embeds
     * an absolute path - and for this package's local adapter, that path
     * contains the opaque secret ID. Attaching such a message unredacted
     * would reopen exactly the "does not reveal whether an opaque ID exists"
     * guarantee this package otherwise enforces via a single
     * {@link SecretIntegrityException} outcome for resolve. Only the failing
     * exception's type and stack trace are preserved for operator diagnosis;
     * the original message is discarded.
     */
    public static SecretStorageUnavailableException redacting(IOException cause) {
        Objects.requireNonNull(cause, "cause");
        var safeCause = new IOException(cause.getClass().getSimpleName());
        safeCause.setStackTrace(cause.getStackTrace());
        return new SecretStorageUnavailableException(safeCause);
    }
}
