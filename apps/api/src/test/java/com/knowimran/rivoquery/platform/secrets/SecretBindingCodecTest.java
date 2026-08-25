package com.knowimran.rivoquery.platform.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.application.secret.SecretBinding;
import com.knowimran.rivoquery.connection.application.secret.SecretPurpose;
import com.knowimran.rivoquery.connection.application.secret.SecretReference;
import com.knowimran.rivoquery.identity.WorkspaceId;
import org.junit.jupiter.api.Test;

class SecretBindingCodecTest {
    private static final SecretBinding BINDING = SecretStoreTestFixtures.binding(1);

    @Test
    void fingerprintIsDeterministicAndMatchesSecretReferenceFormat() {
        String first = SecretBindingCodec.fingerprint(BINDING);
        String second = SecretBindingCodec.fingerprint(BINDING);

        assertThat(first).isEqualTo(second);
        assertThat(new SecretReference("local", "opaque-id-0123456789ab", 1, first)).isNotNull();
    }

    @Test
    void fingerprintDiffersAcrossVersions() {
        var v1 = SecretStoreTestFixtures.binding(1);
        var v2 = SecretStoreTestFixtures.binding(2);
        assertThat(SecretBindingCodec.fingerprint(v1)).isNotEqualTo(SecretBindingCodec.fingerprint(v2));
    }

    @Test
    void fingerprintDiffersAcrossConnectionsEvenWithSameWorkspaceAndVersion() {
        var workspace = new WorkspaceId(SecretStoreTestFixtures.WORKSPACE.value());
        var binding1 = new SecretBinding(
                workspace, new ConnectionId("con_01J7QK3H0RT8N4YB2C6W9V1MFR"), SecretPurpose.TARGET_DATABASE_CONNECTION, 1);
        var binding2 = SecretStoreTestFixtures.binding(1);
        assertThat(SecretBindingCodec.fingerprint(binding1)).isNotEqualTo(SecretBindingCodec.fingerprint(binding2));
    }

    @Test
    void additionalDataIsDeterministicAndDiffersAcrossFormatVersions() {
        assertThat(SecretBindingCodec.additionalData(1, BINDING)).isEqualTo(SecretBindingCodec.additionalData(1, BINDING));
        assertThat(SecretBindingCodec.additionalData(1, BINDING)).isNotEqualTo(SecretBindingCodec.additionalData(2, BINDING));
    }
}
