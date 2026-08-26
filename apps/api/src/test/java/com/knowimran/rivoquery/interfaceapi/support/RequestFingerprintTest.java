package com.knowimran.rivoquery.interfaceapi.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    @Test
    void identicalInputsProduceTheSameFingerprint() {
        assertThat(RequestFingerprint.of("con_1", "\"3\"")).isEqualTo(RequestFingerprint.of("con_1", "\"3\""));
    }

    @Test
    void differentInputsProduceDifferentFingerprints() {
        assertThat(RequestFingerprint.of("con_1", "\"3\"")).isNotEqualTo(RequestFingerprint.of("con_1", "\"4\""));
    }

    @Test
    void fingerprintIsALowercaseHexSha256Digest() {
        String fingerprint = RequestFingerprint.of("anything");

        assertThat(fingerprint).matches("^[0-9a-f]{64}$");
    }

    @Test
    void nullPartsAreCanonicalizedConsistently() {
        assertThat(RequestFingerprint.of("con_1", (Object) null)).isEqualTo(RequestFingerprint.of("con_1", (Object) null));
    }

    record SamplePayload(String field) {}

    @Test
    void nonStringPartsAreCanonicalizedByValueNotIdentity() {
        assertThat(RequestFingerprint.of(new SamplePayload("value")))
                .isEqualTo(RequestFingerprint.of(new SamplePayload("value")));
    }

    record SecretBearingPayload(String host, String password) {}

    @Test
    void secretFieldsAreExcludedFromTheDigestSoChangingOnlyASecretKeepsTheSameFingerprint() {
        assertThat(RequestFingerprint.of(new SecretBearingPayload("db.internal", "alpha-secret")))
                .isEqualTo(RequestFingerprint.of(new SecretBearingPayload("db.internal", "beta-secret")));
    }

    @Test
    void nonSecretFieldsStillShapeTheDigestAlongsideRedactedSecrets() {
        assertThat(RequestFingerprint.of(new SecretBearingPayload("db.internal", "alpha-secret")))
                .isNotEqualTo(RequestFingerprint.of(new SecretBearingPayload("db.other", "alpha-secret")));
    }
}
