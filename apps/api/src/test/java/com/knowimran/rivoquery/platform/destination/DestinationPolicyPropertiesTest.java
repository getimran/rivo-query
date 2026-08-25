package com.knowimran.rivoquery.platform.destination;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.connection.DatabaseKind;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class DestinationPolicyPropertiesTest {

    @Test
    void rejectsNonPositiveDnsTimeout() {
        assertThatThrownBy(() -> properties(Duration.ZERO, 8, 60, 5, "v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveChainDepth() {
        assertThatThrownBy(() -> properties(Duration.ofSeconds(2), 0, 60, 5, "v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsFallbackTtlExceedingMaxCapabilityTtl() {
        assertThatThrownBy(() -> properties(Duration.ofSeconds(2), 8, 10, 20, "v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveFallbackOrMaxTtl() {
        assertThatThrownBy(() -> properties(Duration.ofSeconds(2), 8, 0, 0, "v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankPolicyVersion() {
        assertThatThrownBy(() -> properties(Duration.ofSeconds(2), 8, 60, 5, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidPrivateRule() {
        assertThatThrownBy(() -> new DestinationPolicyProperties.PrivateRule(
                        DatabaseKind.POSTGRESQL, "db.internal.example.com", 5000, 4000, List.of("10.0.0.0/8")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DestinationPolicyProperties properties(
            Duration dnsTimeout, int maxChainDepth, long maxTtl, long fallbackTtl, String policyVersion) {
        return new DestinationPolicyProperties(dnsTimeout, maxChainDepth, maxTtl, fallbackTtl, policyVersion, null, List.of());
    }
}
