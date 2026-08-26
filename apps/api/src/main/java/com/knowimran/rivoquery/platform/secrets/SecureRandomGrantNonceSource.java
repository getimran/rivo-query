package com.knowimran.rivoquery.platform.secrets;

import com.knowimran.rivoquery.connection.application.secret.GrantNonceSource;
import java.security.SecureRandom;
import java.util.Base64;

/** Unguessable per-grant nonce; mirrors {@code platform.destination.SecureRandomDestinationNonceSource}. */
public final class SecureRandomGrantNonceSource implements GrantNonceSource {
    private static final int NONCE_BYTES = 24;

    private final SecureRandom random = new SecureRandom();

    @Override
    public String newNonce() {
        byte[] bytes = new byte[NONCE_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
