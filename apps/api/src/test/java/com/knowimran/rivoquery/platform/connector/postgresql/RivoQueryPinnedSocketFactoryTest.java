package com.knowimran.rivoquery.platform.connector.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Deterministic, no-Docker-required coverage of {@link RivoQueryPinnedSocketFactory}'s own
 * connect/retry/deadline logic in isolation. TLS hostname verification against a real PostgreSQL
 * server is separately proven end to end by {@code PostgresqlPhysicalJdbcConnectorIT}.
 *
 * <p>The IPv4 and IPv6 loopback addresses ({@code 127.0.0.1} / {@code ::1}, both always
 * loopback-routable with no extra host configuration - unlike a secondary IPv4 loopback alias such
 * as {@code 127.0.0.2}, which macOS does not bind by default) stand in for "an unreachable vetted
 * address" and "a reachable one" at the same port, so a retry-across-candidates scenario is
 * exercised deterministically - no real-network black-hole address is used, since forcing a
 * genuine TCP connect timeout portably across sandboxes/CI is not reliable.
 */
class RivoQueryPinnedSocketFactoryTest {

    // Computed at test-run time (never a hardcoded calendar date): the two tests below use the
    // PRODUCTION single-arg constructor (real system clock), so their deadlines must always be
    // relative to "now" - a fixed literal Instant would eventually fall into the past as real
    // time moves on, making the test fail purely from being run later, not from a real regression.
    private static final Instant NOW = Instant.now();
    private static final InetAddress LOOPBACK_1 = ipv4Loopback();
    private static final InetAddress LOOPBACK_2 = ipv6Loopback();

    private ServerSocket listening;

    @AfterEach
    void closeListeningSocket() throws IOException {
        if (listening != null) {
            listening.close();
        }
    }

    @Test
    void connectsDirectlyToThePinnedAddressWithoutAnyHostname() throws Exception {
        listening = bind(LOOPBACK_1);
        var factory = new RivoQueryPinnedSocketFactory(
                properties(List.of(LOOPBACK_1), listening.getLocalPort(), NOW.plusSeconds(30)));

        try (Socket socket = factory.createSocket()) {
            assertThat(socket.isConnected()).isTrue();
            assertThat(socket.getInetAddress()).isEqualTo(LOOPBACK_1);
        }
    }

    @Test
    void triesTheNextPinnedAddressWhenTheFirstRefusesTheConnectionWithoutAnyNewDnsAnswer() throws Exception {
        listening = bind(LOOPBACK_2);
        int sharedPort = listening.getLocalPort();
        // LOOPBACK_1 at sharedPort has nothing listening (fast ECONNREFUSED); LOOPBACK_2 does.
        var factory = new RivoQueryPinnedSocketFactory(
                properties(List.of(LOOPBACK_1, LOOPBACK_2), sharedPort, NOW.plusSeconds(30)));

        try (Socket socket = factory.createSocket()) {
            assertThat(socket.isConnected()).isTrue();
            assertThat(socket.getInetAddress()).isEqualTo(LOOPBACK_2);
        }
    }

    @Test
    void deadlineAlreadyElapsedBeforeTheFirstAttemptFailsClosedWithoutConnecting() {
        var pastDeadlineClock = Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC);
        var factory = new RivoQueryPinnedSocketFactory(
                properties(List.of(LOOPBACK_1), 1, NOW.plusSeconds(30)), pastDeadlineClock);

        assertThatThrownBy(factory::createSocket).isInstanceOf(SocketTimeoutException.class);
    }

    @Test
    void deadlineExhaustedBetweenCandidatesStopsBeforeTryingTheSecondOne() throws Exception {
        listening = bind(LOOPBACK_2); // would succeed if ever dialed
        int sharedPort = listening.getLocalPort();
        var deadline = NOW.plusMillis(10);
        // First clock read (checked before candidate #1, LOOPBACK_1) is still before the deadline;
        // second read (checked before candidate #2, LOOPBACK_2) has passed it - deterministically
        // simulating "time passed while the first attempt failed" without a real network hang.
        var advancing = advancingClock(NOW, deadline.plusSeconds(5));
        var factory = new RivoQueryPinnedSocketFactory(
                properties(List.of(LOOPBACK_1, LOOPBACK_2), sharedPort, deadline), advancing);

        assertThatThrownBy(factory::createSocket).isInstanceOf(SocketTimeoutException.class);
    }

    @Test
    void missingPinnedAddressesFailsClosedAtConstructionTime() {
        var empty = new Properties();
        assertThatThrownBy(() -> new RivoQueryPinnedSocketFactory(empty))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hostnameAndAddressParameterizedOverloadsAllRefuseImmediately() {
        var factory = new RivoQueryPinnedSocketFactory(
                properties(List.of(LOOPBACK_1), 1, NOW.plusSeconds(30)));

        assertThatThrownBy(() -> factory.createSocket("db.example.com", 5432))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> factory.createSocket("db.example.com", 5432, LOOPBACK_1, 0))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> factory.createSocket(LOOPBACK_1, 5432))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> factory.createSocket(LOOPBACK_1, 5432, LOOPBACK_2, 0))
                .isInstanceOf(IOException.class);
    }

    private static Properties properties(List<InetAddress> addresses, int port, Instant deadline) {
        var properties = new Properties();
        String encodedAddresses = addresses.stream()
                .map(address -> (address instanceof java.net.Inet6Address ? "IPV6" : "IPV4")
                        + ':'
                        + java.util.HexFormat.of().formatHex(address.getAddress()))
                .collect(java.util.stream.Collectors.joining(","));
        properties.setProperty(PostgresqlPhysicalJdbcConnector.PINNED_ADDRESSES_PROPERTY, encodedAddresses);
        properties.setProperty(PostgresqlPhysicalJdbcConnector.PINNED_PORT_PROPERTY, String.valueOf(port));
        properties.setProperty(PostgresqlPhysicalJdbcConnector.DEADLINE_PROPERTY, deadline.toString());
        properties.setProperty("connectTimeout", "5");
        return properties;
    }

    private static ServerSocket bind(InetAddress address) throws IOException {
        var socket = new ServerSocket();
        socket.bind(new InetSocketAddress(address, 0), 1);
        return socket;
    }

    private static Clock advancingClock(Instant first, Instant second) {
        AtomicInteger calls = new AtomicInteger();
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return calls.getAndIncrement() == 0 ? first : second;
            }
        };
    }

    private static InetAddress ipv4Loopback() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static InetAddress ipv6Loopback() {
        try {
            return InetAddress.getByAddress(
                    new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1});
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
