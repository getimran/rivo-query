package com.knowimran.rivoquery.platform.destination.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.destination.AddressFamily;
import com.knowimran.rivoquery.connection.application.destination.VettedAddress;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OperatorPrivateAllowlistTest {

    @Test
    void permitsOnlyWhenNamePortAndEveryAddressMatchTheSameRule() {
        var rule = new PrivateDestinationRule(
                DatabaseKind.POSTGRESQL, "db.internal.example.com", 5432, 5432, List.of("10.20.0.0/16"));
        var allowlist = new OperatorPrivateAllowlist(List.of(rule));

        var matchingAddress = new VettedAddress(AddressFamily.IPV4, new byte[] {10, 20, 1, 1});
        assertThat(allowlist.permits(
                        DatabaseKind.POSTGRESQL, "db.internal.example.com", 5432, Set.of(matchingAddress)))
                .isTrue();
    }

    @Test
    void deniesWhenOnlySomeAddressesMatchTheRulesCidrs() {
        var rule = new PrivateDestinationRule(
                DatabaseKind.POSTGRESQL, "db.internal.example.com", 5432, 5432, List.of("10.20.0.0/16"));
        var allowlist = new OperatorPrivateAllowlist(List.of(rule));

        var matching = new VettedAddress(AddressFamily.IPV4, new byte[] {10, 20, 1, 1});
        var outsideCidr = new VettedAddress(AddressFamily.IPV4, new byte[] {10, 99, 1, 1});
        assertThat(allowlist.permits(
                        DatabaseKind.POSTGRESQL,
                        "db.internal.example.com",
                        5432,
                        Set.of(matching, outsideCidr)))
                .isFalse();
    }

    @Test
    void deniesWhenConnectorKindDiffers() {
        var rule = new PrivateDestinationRule(
                DatabaseKind.POSTGRESQL, "db.internal.example.com", 5432, 5432, List.of("10.20.0.0/16"));
        var allowlist = new OperatorPrivateAllowlist(List.of(rule));
        var address = new VettedAddress(AddressFamily.IPV4, new byte[] {10, 20, 1, 1});

        assertThat(allowlist.permits(DatabaseKind.MYSQL, "db.internal.example.com", 5432, Set.of(address)))
                .isFalse();
    }

    @Test
    void deniesWhenPortIsOutsideTheRulesRange() {
        var rule = new PrivateDestinationRule(
                DatabaseKind.POSTGRESQL, "db.internal.example.com", 5432, 5432, List.of("10.20.0.0/16"));
        var allowlist = new OperatorPrivateAllowlist(List.of(rule));
        var address = new VettedAddress(AddressFamily.IPV4, new byte[] {10, 20, 1, 1});

        assertThat(allowlist.permits(DatabaseKind.POSTGRESQL, "db.internal.example.com", 5433, Set.of(address)))
                .isFalse();
    }

    @Test
    void suffixRuleMatchesSubdomainsButNotTheBareSuffixOrUnrelatedNames() {
        var rule = new PrivateDestinationRule(
                DatabaseKind.POSTGRESQL, ".internal.example.com", 5432, 5432, List.of("10.20.0.0/16"));
        var allowlist = new OperatorPrivateAllowlist(List.of(rule));
        var address = new VettedAddress(AddressFamily.IPV4, new byte[] {10, 20, 1, 1});

        assertThat(allowlist.permits(DatabaseKind.POSTGRESQL, "db.internal.example.com", 5432, Set.of(address)))
                .isTrue();
        assertThat(allowlist.permits(DatabaseKind.POSTGRESQL, "internal.example.com", 5432, Set.of(address)))
                .isFalse();
        assertThat(allowlist.permits(DatabaseKind.POSTGRESQL, "evilinternal.example.com", 5432, Set.of(address)))
                .isFalse();
    }

    @Test
    void emptyAllowlistNeverPermitsAnything() {
        var allowlist = new OperatorPrivateAllowlist(List.of());
        var address = new VettedAddress(AddressFamily.IPV4, new byte[] {10, 20, 1, 1});

        assertThat(allowlist.permits(DatabaseKind.POSTGRESQL, "db.internal.example.com", 5432, Set.of(address)))
                .isFalse();
    }
}
