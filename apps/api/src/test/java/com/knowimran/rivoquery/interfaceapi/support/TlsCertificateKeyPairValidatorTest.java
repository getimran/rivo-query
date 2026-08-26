package com.knowimran.rivoquery.interfaceapi.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Enforces the OpenAPI {@code dependentRequired} rule generated Java Bean
 * Validation cannot express (see the P2-130 task's own note).
 */
class TlsCertificateKeyPairValidatorTest {

    @Test
    void bothAbsentIsValid() {
        assertThatCode(() -> TlsCertificateKeyPairValidator.requirePaired(null, null)).doesNotThrowAnyException();
    }

    @Test
    void bothPresentIsValid() {
        assertThatCode(() -> TlsCertificateKeyPairValidator.requirePaired("cert", "key"))
                .doesNotThrowAnyException();
    }

    @Test
    void certificateWithoutKeyIsRejected() {
        assertThatThrownBy(() -> TlsCertificateKeyPairValidator.requirePaired("cert", null))
                .isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void keyWithoutCertificateIsRejected() {
        assertThatThrownBy(() -> TlsCertificateKeyPairValidator.requirePaired(null, "key"))
                .isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void blankCounterpartIsTreatedAsAbsent() {
        assertThatThrownBy(() -> TlsCertificateKeyPairValidator.requirePaired("cert", "   "))
                .isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> TlsCertificateKeyPairValidator.requirePaired("   ", "key"))
                .isInstanceOf(ValidationFailedException.class);
    }
}
