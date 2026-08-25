package com.knowimran.rivoquery.platform.destination;

import com.knowimran.rivoquery.connection.application.destination.DestinationNonceSource;
import java.security.SecureRandom;
import java.util.Base64;

/** Unguessable per-capability nonce, per ADR-0013. */
public final class SecureRandomDestinationNonceSource implements DestinationNonceSource {
    private static final int NONCE_BYTES = 24;

    private final SecureRandom random = new SecureRandom();

    @Override
    public String newNonce() {
        byte[] bytes = new byte[NONCE_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
