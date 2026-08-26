package com.knowimran.rivoquery.interfaceapi.support;

/**
 * Enforces the OpenAPI contract's {@code dependentRequired} rule on {@code
 * TlsInput}/{@code RotateSecretRequest} ({@code clientCertificatePem}
 * requires {@code clientPrivateKeyPem} and vice versa) - generated Java Bean
 * Validation has no equivalent of JSON Schema {@code dependentRequired}, so
 * this is enforced explicitly at the interface boundary.
 */
public final class TlsCertificateKeyPairValidator {
    private TlsCertificateKeyPairValidator() {}

    public static void requirePaired(String clientCertificatePem, String clientPrivateKeyPem) {
        boolean hasCertificate = clientCertificatePem != null && !clientCertificatePem.isBlank();
        boolean hasKey = clientPrivateKeyPem != null && !clientPrivateKeyPem.isBlank();
        if (hasCertificate != hasKey) {
            throw new ValidationFailedException(
                    "clientCertificatePem and clientPrivateKeyPem must be supplied together");
        }
    }
}
