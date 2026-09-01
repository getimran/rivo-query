package com.knowimran.rivoquery.connection.application.lifecycle;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.OperationId;
import com.knowimran.rivoquery.connection.QueryRequestId;
import java.security.SecureRandom;

/**
 * Generates canonical ids matching {@link ConnectionId}/{@link OperationId}'s
 * required shape ({@code <prefix>_} followed by 26 Crockford-Base32
 * characters) from a cryptographically strong random source. Purely random,
 * not time-ordered: nothing in this codebase requires id ordering, only
 * global uniqueness and the exact validated shape.
 */
public final class SecureRandomCanonicalIdGenerator implements CanonicalIdGenerator {
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int LENGTH = 26;

    private final SecureRandom random = new SecureRandom();

    @Override
    public ConnectionId newConnectionId() {
        return new ConnectionId("con_" + randomSuffix());
    }

    @Override
    public OperationId newOperationId() {
        return new OperationId("op_" + randomSuffix());
    }

    @Override
    public String newSnapshotId() {
        return "snap_" + randomSuffix();
    }

    @Override
    public QueryRequestId newQueryRequestId() {
        return new QueryRequestId("qry_" + randomSuffix());
    }

    private String randomSuffix() {
        var builder = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            builder.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return builder.toString();
    }
}
