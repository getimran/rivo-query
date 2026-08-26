package com.knowimran.rivoquery.platform;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * S6 regression: {@code secretStore()} must fail closed unless {@code
 * rivoquery.secrets.provider} is explicitly {@code "local"} - see this
 * class's own Javadoc for why. The success path (a real {@link
 * com.knowimran.rivoquery.connection.application.secret.SecretStore} bean)
 * is exercised end to end by every full-{@code ApplicationContext} IT (e.g.
 * {@code IdentityAndConnectionPersistenceIT}), which already sets {@code
 * RIVOQUERY_SECRETS_PROVIDER=local} via the failsafe plugin configuration.
 */
class ConnectionLifecycleWiringConfigurationTest {

    private final ConnectionLifecycleWiringConfiguration configuration = new ConnectionLifecycleWiringConfiguration();

    @Test
    void secretStoreFailsClosedWhenProviderIsUnset() {
        assertThatThrownBy(() -> configuration.secretStore(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rivoquery.secrets.provider")
                .hasMessageContaining("<unset>");
    }

    @Test
    void secretStoreFailsClosedWhenProviderIsAnythingOtherThanLocal() {
        assertThatThrownBy(() -> configuration.secretStore("kms"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("\"kms\"");
    }
}
