package com.knowimran.rivoquery.platform.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.connection.application.secret.SecretReference;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecretReferenceKeyTest {
    private static final SecretReferenceKey KEY = SecretReferenceKey.of(AesGcmCipher.randomBytes(32));

    @Test
    void opaqueIdIsDeterministicForTheSameBindingAndOperationId() {
        var binding = SecretStoreTestFixtures.binding(1);
        String first = KEY.opaqueId(binding, "op-1");
        String second = KEY.opaqueId(binding, "op-1");

        assertThat(first).isEqualTo(second);
        // Must satisfy SecretReference's own opaque-ID validity pattern.
        assertThat(new SecretReference("local", first, 1, KEY.opaqueId(binding, "fingerprint-stand-in-x")))
                .isNotNull();
    }

    @Test
    void opaqueIdDiffersAcrossOperationIdsForTheSameBinding() {
        var binding = SecretStoreTestFixtures.binding(1);
        assertThat(KEY.opaqueId(binding, "op-1")).isNotEqualTo(KEY.opaqueId(binding, "op-2"));
    }

    @Test
    void opaqueIdDiffersAcrossBindingsForTheSameOperationId() {
        var binding1 = SecretStoreTestFixtures.binding(1);
        var binding2 = SecretStoreTestFixtures.binding(2);
        assertThat(KEY.opaqueId(binding1, "op-1")).isNotEqualTo(KEY.opaqueId(binding2, "op-1"));
    }

    @Test
    void ofRejectsAKeyShorterThan256Bits() {
        assertThatThrownBy(() -> SecretReferenceKey.of(new byte[16]))
                .isInstanceOf(SecretKeyUnavailableException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(ex, Base64.getEncoder().encodeToString(new byte[16])));
    }

    @Test
    void fromEnvironmentFailsClosedWhenMissingOrMalformed() {
        assertThatThrownBy(() -> SecretReferenceKey.fromEnvironment(name -> null))
                .isInstanceOf(SecretKeyUnavailableException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(ex, Base64.getEncoder().encodeToString(AesGcmCipher.randomBytes(32))));
        assertThatThrownBy(() -> SecretReferenceKey.fromEnvironment(name -> "not-base64!!"))
                .isInstanceOf(SecretKeyUnavailableException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(ex, "not-base64!!"));
    }

    @Test
    void fromEnvironmentParsesAValidBase64Key() {
        String encoded = Base64.getEncoder().encodeToString(AesGcmCipher.randomBytes(32));
        Map<String, String> env = Map.of(SecretReferenceKey.ENV_VAR, encoded);
        assertThat(SecretReferenceKey.fromEnvironment(env::get)).isNotNull();
    }

    @Test
    void toStringIsRedacted() {
        assertThat(KEY.toString()).isEqualTo("SecretReferenceKey[REDACTED]");
    }
}
