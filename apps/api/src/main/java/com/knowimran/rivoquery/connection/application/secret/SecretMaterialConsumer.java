package com.knowimran.rivoquery.connection.application.secret;

@FunctionalInterface
public interface SecretMaterialConsumer {
    void use(byte[] borrowedMaterial);
}
