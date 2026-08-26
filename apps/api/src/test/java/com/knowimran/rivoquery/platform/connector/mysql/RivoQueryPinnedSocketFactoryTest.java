package com.knowimran.rivoquery.platform.connector.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mysql.cj.conf.DefaultPropertySet;
import com.mysql.cj.conf.PropertySet;
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
 * connect/retry/deadline logic in isolation, exercising {@link RivoQueryPinnedSocketFactory#connect}
 * directly against a {@link PropertySet} built the same way Connector/J itself builds one ({@link
 * DefaultPropertySet#initializeProperties(Properties)}, empirically the exact mechanism that
 * preserves this connector's internal, non-{@code PropertyKey} connection properties - see {@link
 * MysqlPhysicalJdbcConnector}'s class Javadoc). TLS hostname verification against a real MySQL
 * server is separately proven end to end by {@code MysqlPhysicalJdbcConnectorIT}.
 *
 * <p>The IPv4 and IPv6 loopback addresses ({@code 127.0.0.1} / {@code ::1}, both always
 * loopback-routable with no extra host configuration) stand in for "an unreachable vetted address"
 * and "a reachable one" at the same port, so a retry-across-candidates scenario is exercised
 * deterministically - mirrors {@code platform.connector.postgresql.RivoQueryPinnedSocketFactoryTest}.
 */
class RivoQueryPinnedSocketFactoryTest {

    // Computed at test-run time (never a hardcoded calendar date): the production no-arg
    // constructor uses the real system clock, so deadlines below must always be relative to "now".
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
        var factory = new RivoQueryPinnedSocketFactory();
        PropertySet pset = properties(List.of(LOOPBACK_1), listening.getLocalPort(), NOW.plusSeconds(30));

        try (Socket socket = factory.connect("ignored-hostname.example.invalid", 0, pset, 0)) {
            assertThat(socket.isConnected()).isTrue();
            assertThat(socket.getInetAddress()).isEqualTo(LOOPBACK_1);
        }
    }

    @Test
    void triesTheNextPinnedAddressWhenTheFirstRefusesTheConnectionWithoutAnyNewDnsAnswer() throws Exception {
        listening = bind(LOOPBACK_2);
        int sharedPort = listening.getLocalPort();
        // LOOPBACK_1 at sharedPort has nothing listening (fast ECONNREFUSED); LOOPBACK_2 does.
        var factory = new RivoQueryPinnedSocketFactory();
        PropertySet pset = properties(List.of(LOOPBACK_1, LOOPBACK_2), sharedPort, NOW.plusSeconds(30));

        try (Socket socket = factory.connect("ignored-hostname.example.invalid", 0, pset, 0)) {
            assertThat(socket.isConnected()).isTrue();
            assertThat(socket.getInetAddress()).isEqualTo(LOOPBACK_2);
        }
    }

    @Test
    void deadlineAlreadyElapsedBeforeTheFirstAttemptFailsClosedWithoutConnecting() {
        var pastDeadlineClock = Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC);
        var factory = new RivoQueryPinnedSocketFactory(pastDeadlineClock);
        PropertySet pset = properties(List.of(LOOPBACK_1), 1, NOW.plusSeconds(30));

        assertThatThrownBy(() -> factory.connect("ignored-hostname.example.invalid", 0, pset, 0))
                .isInstanceOf(SocketTimeoutException.class);
    }

    @Test
    void deadlineExhaustedBetweenCandidatesStopsBeforeTryingTheSecondOne() throws Exception {
        listening = bind(LOOPBACK_2); // would succeed if ever dialed
        int sharedPort = listening.getLocalPort();
        var deadline = NOW.plusMillis(10);
        var advancing = advancingClock(NOW, deadline.plusSeconds(5));
        var factory = new RivoQueryPinnedSocketFactory(advancing);
        PropertySet pset = properties(List.of(LOOPBACK_1, LOOPBACK_2), sharedPort, deadline);

        assertThatThrownBy(() -> factory.connect("ignored-hostname.example.invalid", 0, pset, 0))
                .isInstanceOf(SocketTimeoutException.class);
    }

    @Test
    void appliesTcpNoDelayAndTcpKeepAliveSocketOptionsBeforeConnecting() throws Exception {
        // S2: RivoQueryPinnedSocketFactory bypasses StandardSocketFactory#configureSocket entirely
        // (it dials the pinned address itself), so without its own equivalent call, tcpNoDelay/
        // tcpKeepAlive - the latter explicitly set true by MysqlConnectorDialect and advertised via
        // ConnectorOption.TCP_KEEP_ALIVE - would silently never be applied to the real socket.
        listening = bind(LOOPBACK_1);
        var factory = new RivoQueryPinnedSocketFactory();
        PropertySet pset = properties(List.of(LOOPBACK_1), listening.getLocalPort(), NOW.plusSeconds(30));
        pset.getBooleanProperty("tcpNoDelay").setValue(true);
        pset.getBooleanProperty("tcpKeepAlive").setValue(true);

        try (Socket socket = factory.connect("ignored-hostname.example.invalid", 0, pset, 0)) {
            assertThat(socket.getTcpNoDelay()).isTrue();
            assertThat(socket.getKeepAlive()).isTrue();
        }
    }

    @Test
    void missingPinnedAddressesFailsClosedRatherThanFallingBackToTheGivenHostname() {
        var factory = new RivoQueryPinnedSocketFactory();
        PropertySet empty = new DefaultPropertySet();

        assertThatThrownBy(() -> factory.connect("db.example.com", 3306, empty, 0))
                .isInstanceOf(IllegalStateException.class);
    }

    private static PropertySet properties(List<InetAddress> addresses, int port, Instant deadline) {
        var props = new Properties();
        String encodedAddresses = addresses.stream()
                .map(address -> (address instanceof java.net.Inet6Address ? "IPV6" : "IPV4")
                        + ':'
                        + java.util.HexFormat.of().formatHex(address.getAddress()))
                .collect(java.util.stream.Collectors.joining(","));
        props.setProperty(MysqlPhysicalJdbcConnector.PINNED_ADDRESSES_PROPERTY, encodedAddresses);
        props.setProperty(MysqlPhysicalJdbcConnector.PINNED_PORT_PROPERTY, String.valueOf(port));
        props.setProperty(MysqlPhysicalJdbcConnector.DEADLINE_PROPERTY, deadline.toString());
        props.setProperty("connectTimeout", "5000");
        PropertySet pset = new DefaultPropertySet();
        pset.initializeProperties(props);
        return pset;
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
            return InetAddress.getByAddress(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1});
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
