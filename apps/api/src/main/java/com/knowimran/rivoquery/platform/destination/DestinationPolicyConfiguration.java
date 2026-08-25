package com.knowimran.rivoquery.platform.destination;

import com.knowimran.rivoquery.connection.application.destination.DestinationCapabilityAuthority;
import com.knowimran.rivoquery.connection.application.destination.DestinationNonceSource;
import com.knowimran.rivoquery.connection.application.destination.DestinationOperations;
import com.knowimran.rivoquery.connection.application.destination.DestinationPolicy;
import com.knowimran.rivoquery.platform.destination.dns.DnsResolver;
import com.knowimran.rivoquery.platform.destination.dns.DnsjavaDestinationResolver;
import com.knowimran.rivoquery.platform.destination.policy.OperatorPrivateAllowlist;
import com.knowimran.rivoquery.platform.destination.policy.PrivateDestinationRule;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.SimpleResolver;

/** Wires the destination-enforcement capability chain (ADR-0013) from external configuration. */
@Configuration
@EnableConfigurationProperties(DestinationPolicyProperties.class)
public class DestinationPolicyConfiguration {

    @Bean
    Resolver destinationDnsResolver(DestinationPolicyProperties properties) {
        try {
            return properties.resolverAddress() == null || properties.resolverAddress().isBlank()
                    ? new SimpleResolver()
                    : new SimpleResolver(properties.resolverAddress());
        } catch (UnknownHostException unreachable) {
            throw new IllegalStateException(
                    "configured rivoquery.destination.resolver-address could not be used", unreachable);
        }
    }

    @Bean
    DnsResolver dnsResolver(Resolver destinationDnsResolver, DestinationPolicyProperties properties) {
        return new DnsjavaDestinationResolver(destinationDnsResolver, properties.maxCnameChainDepth(), Clock.systemUTC());
    }

    @Bean
    OperatorPrivateAllowlist operatorPrivateAllowlist(DestinationPolicyProperties properties) {
        List<PrivateDestinationRule> rules = properties.privateAllowlist().stream()
                .map(rule -> new PrivateDestinationRule(
                        rule.connectorKind(), rule.hostname(), rule.minPort(), rule.maxPort(), rule.cidrs()))
                .toList();
        return new OperatorPrivateAllowlist(rules);
    }

    @Bean
    DestinationPolicy destinationPolicy(
            DnsResolver dnsResolver, DestinationPolicyProperties properties, OperatorPrivateAllowlist allowlist) {
        return new DestinationEnforcementPolicy(dnsResolver, properties, allowlist, Clock.systemUTC());
    }

    @Bean
    DestinationNonceSource destinationNonceSource() {
        return new SecureRandomDestinationNonceSource();
    }

    @Bean
    DestinationOperations destinationOperations(DestinationPolicy destinationPolicy, DestinationNonceSource nonceSource) {
        return new DestinationCapabilityAuthority(destinationPolicy, nonceSource, Clock.systemUTC());
    }
}
