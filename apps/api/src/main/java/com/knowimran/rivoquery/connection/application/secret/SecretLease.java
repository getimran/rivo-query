package com.knowimran.rivoquery.connection.application.secret;

public interface SecretLease extends AutoCloseable {
    void consume(SecretMaterialConsumer consumer);

    @Override
    void close();
}
