package com.knowimran.rivoquery.platform.destination.dns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.destination.AddressFamily;
import com.knowimran.rivoquery.connection.application.destination.ConnectionPurpose;
import com.knowimran.rivoquery.connection.application.destination.DestinationRequest;
import com.knowimran.rivoquery.connection.application.destination.StructuredEndpoint;
import com.knowimran.rivoquery.identity.WorkspaceId;
import com.knowimran.rivoquery.platform.destination.DestinationDenialReason;
import com.knowimran.rivoquery.platform.destination.DestinationEnforcementPolicy;
import com.knowimran.rivoquery.platform.destination.DestinationPolicyProperties;
import com.knowimran.rivoquery.platform.destination.DestinationValidationException;
import com.knowimran.rivoquery.platform.destination.policy.OperatorPrivateAllowlist;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.CNAMERecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.EDNSOption;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.Section;
import org.xbill.DNS.TSIG;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

/**
 * Proves the dnsjava-backed resolver maps every ADR-0013 fail-closed condition (empty, malformed,
 * truncated, timed-out, indeterminate, chain-too-deep) correctly, and correctly performs bounded
 * CNAME chasing and TTL propagation, using a deterministic scripted {@link Resolver} test double
 * (no real DNS traffic).
 */
class DnsjavaDestinationResolverTest {
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Instant DEADLINE = NOW.plusSeconds(5);

    @Test
    void followsCnameChainAndReturnsTtlPerAnswer() throws Exception {
        Name origin = name("app.example.");
        Name target = name("app-lb.example.");
        var stub = new ScriptedResolver();
        stub.respond(origin, Type.A, cname(origin, target));
        stub.respond(target, Type.A, aRecord(target, "10.20.30.40", 30));
        stub.nxdomain(origin, Type.AAAA);

        var resolver = new DnsjavaDestinationResolver(stub, 8, CLOCK);
        DnsResolution resolution = resolver.resolveAll("app.example.", DEADLINE);

        assertThat(resolution.cnameChain()).containsExactly("app-lb.example"); // dnsjava Name.toString(true) omits the final dot
        assertThat(resolution.answers()).hasSize(1);
        DnsAnswer answer = resolution.answers().get(0);
        assertThat(answer.family()).isEqualTo(AddressFamily.IPV4);
        assertThat(answer.addressBytes()).isEqualTo(new byte[] {10, 20, 30, 40});
        assertThat(answer.ttlSeconds()).isEqualTo(30);
    }

    @Test
    void mergesBothFamiliesWhenBothPresent() throws Exception {
        Name origin = name("dual.example.");
        var stub = new ScriptedResolver();
        stub.respond(origin, Type.A, aRecord(origin, "1.2.3.4", 60));
        stub.respond(origin, Type.AAAA, aaaaRecord(origin, "2001:db8::1", 60));

        var resolver = new DnsjavaDestinationResolver(stub, 8, CLOCK);
        DnsResolution resolution = resolver.resolveAll("dual.example.", DEADLINE);

        assertThat(resolution.answers()).hasSize(2);
        assertThat(resolution.answers().stream().map(DnsAnswer::family))
                .containsExactlyInAnyOrder(AddressFamily.IPV4, AddressFamily.IPV6);
    }

    @Test
    void bothFamiliesNxdomainFailsClosedAsEmpty() throws Exception {
        Name origin = name("gone.example.");
        var stub = new ScriptedResolver();
        stub.nxdomain(origin, Type.A);
        stub.nxdomain(origin, Type.AAAA);

        var resolver = new DnsjavaDestinationResolver(stub, 8, CLOCK);
        assertThatThrownBy(() -> resolver.resolveAll("gone.example.", DEADLINE))
                .isInstanceOf(DnsResolutionException.class)
                .extracting(ex -> ((DnsResolutionException) ex).reason())
                .isEqualTo(DestinationDenialReason.EMPTY_ANSWER);
    }

    @Test
    void bothFamiliesNodataFailsClosedAsEmpty() throws Exception {
        Name origin = name("nodata.example.");
        var stub = new ScriptedResolver();
        stub.respond(origin, Type.A, noError(origin, Type.A));
        stub.respond(origin, Type.AAAA, noError(origin, Type.AAAA));

        var resolver = new DnsjavaDestinationResolver(stub, 8, CLOCK);
        assertThatThrownBy(() -> resolver.resolveAll("nodata.example.", DEADLINE))
                .isInstanceOf(DnsResolutionException.class)
                .extracting(ex -> ((DnsResolutionException) ex).reason())
                .isEqualTo(DestinationDenialReason.EMPTY_ANSWER);
    }

    @Test
    void truncatedResponseFailsClosed() throws Exception {
        Name origin = name("truncated.example.");
        Message truncated = noError(origin, Type.A);
        truncated.getHeader().setFlag(Flags.TC);
        var stub = new ScriptedResolver();
        stub.respond(origin, Type.A, truncated);
        stub.nxdomain(origin, Type.AAAA);

        var resolver = new DnsjavaDestinationResolver(stub, 8, CLOCK);
        assertThatThrownBy(() -> resolver.resolveAll("truncated.example.", DEADLINE))
                .isInstanceOf(DnsResolutionException.class)
                .extracting(ex -> ((DnsResolutionException) ex).reason())
                .isEqualTo(DestinationDenialReason.TRUNCATED_RESPONSE);
    }

    @Test
    void aFamilySucceedingThenAaaaFailingDiscardsTheAlreadyCollectedAAnswer() throws Exception {
        Name origin = name("mixed-outcome.example.");
        var stub = new ScriptedResolver();
        stub.respond(origin, Type.A, aRecord(origin, "93.184.216.34", 60));
        Message servfail = noError(origin, Type.AAAA);
        servfail.getHeader().setRcode(Rcode.SERVFAIL);
        stub.respond(origin, Type.AAAA, servfail);

        var resolver = new DnsjavaDestinationResolver(stub, 8, CLOCK);
        // The A family resolved cleanly first, but the subsequent AAAA family is indeterminate:
        // the whole call must fail closed, never returning a partial DnsResolution built only from
        // the A answer collected before the AAAA failure.
        assertThatThrownBy(() -> resolver.resolveAll("mixed-outcome.example.", DEADLINE))
                .isInstanceOf(DnsResolutionException.class)
                .extracting(ex -> ((DnsResolutionException) ex).reason())
                .isEqualTo(DestinationDenialReason.INDETERMINATE_RESPONSE);
    }

    @Test
    void truncationIsDetectedBeforeRecordsAreParsedEvenWhenAParseableAnswerIsPresent() throws Exception {
        Name origin = name("truncated-with-data.example.");
        Message truncatedWithData = aRecord(origin, "127.0.0.1", 60); // would be prohibited if ever used
        truncatedWithData.getHeader().setFlag(Flags.TC);
        var stub = new ScriptedResolver();
        stub.respond(origin, Type.A, truncatedWithData);
        stub.nxdomain(origin, Type.AAAA);

        var resolver = new DnsjavaDestinationResolver(stub, 8, CLOCK);
        // Pins the ordering in resolveFamily: the TC flag is checked before Section.ANSWER is ever
        // iterated, so a truncated response is rejected outright even when it happens to also carry
        // a syntactically valid answer record.
        assertThatThrownBy(() -> resolver.resolveAll("truncated-with-data.example.", DEADLINE))
                .isInstanceOf(DnsResolutionException.class)
                .extracting(ex -> ((DnsResolutionException) ex).reason())
                .isEqualTo(DestinationDenialReason.TRUNCATED_RESPONSE);
    }

    @Test
    void servfailFailsClosedAsIndeterminate() throws Exception {
        Name origin = name("servfail.example.");
        Message servfail = noError(origin, Type.A);
        servfail.getHeader().setRcode(Rcode.SERVFAIL);
        var stub = new ScriptedResolver();
        stub.respond(origin, Type.A, servfail);

        var resolver = new DnsjavaDestinationResolver(stub, 8, CLOCK);
        assertThatThrownBy(() -> resolver.resolveAll("servfail.example.", DEADLINE))
                .isInstanceOf(DnsResolutionException.class)
                .extracting(ex -> ((DnsResolutionException) ex).reason())
                .isEqualTo(DestinationDenialReason.INDETERMINATE_RESPONSE);
    }

    @Test
    void resolverTimeoutFailsClosed() throws Exception {
        Name origin = name("timeout.example.");
        var stub = new ScriptedResolver();
        stub.failWith(origin, Type.A, new SocketTimeoutException("simulated"));

        var resolver = new DnsjavaDestinationResolver(stub, 8, CLOCK);
        assertThatThrownBy(() -> resolver.resolveAll("timeout.example.", DEADLINE))
                .isInstanceOf(DnsResolutionException.class)
                .extracting(ex -> ((DnsResolutionException) ex).reason())
                .isEqualTo(DestinationDenialReason.RESOLVER_TIMEOUT);
    }

    @Test
    void unreachableResolverFailsClosedAsIndeterminate() throws Exception {
        Name origin = name("unreachable.example.");
        var stub = new ScriptedResolver();
        stub.failWith(origin, Type.A, new IOException("simulated"));

        var resolver = new DnsjavaDestinationResolver(stub, 8, CLOCK);
        assertThatThrownBy(() -> resolver.resolveAll("unreachable.example.", DEADLINE))
                .isInstanceOf(DnsResolutionException.class)
                .extracting(ex -> ((DnsResolutionException) ex).reason())
                .isEqualTo(DestinationDenialReason.INDETERMINATE_RESPONSE);
    }

    @Test
    void cnameChainDeeperThanBoundFailsClosed() throws Exception {
        Name origin = name("hop0.example.");
        Name hop1 = name("hop1.example.");
        Name hop2 = name("hop2.example.");
        Name hop3 = name("hop3.example.");
        var stub = new ScriptedResolver();
        stub.respond(origin, Type.A, cname(origin, hop1));
        stub.respond(hop1, Type.A, cname(hop1, hop2));
        stub.respond(hop2, Type.A, cname(hop2, hop3));

        var resolver = new DnsjavaDestinationResolver(stub, 2, CLOCK);
        assertThatThrownBy(() -> resolver.resolveAll("hop0.example.", DEADLINE))
                .isInstanceOf(DnsResolutionException.class)
                .extracting(ex -> ((DnsResolutionException) ex).reason())
                .isEqualTo(DestinationDenialReason.CHAIN_TOO_DEEP);
    }

    @Test
    void alreadyExhaustedDeadlineFailsClosedBeforeAnyQuery() throws Exception {
        var stub = new ScriptedResolver();
        var resolver = new DnsjavaDestinationResolver(stub, 8, CLOCK);
        assertThatThrownBy(() -> resolver.resolveAll("late.example.", NOW.minusSeconds(1)))
                .isInstanceOf(DnsResolutionException.class)
                .extracting(ex -> ((DnsResolutionException) ex).reason())
                .isEqualTo(DestinationDenialReason.RESOLVER_TIMEOUT);
    }

    /**
     * Regression for a prior review finding: {@code AAAARecord.getAddress()} calls {@code
     * InetAddress.getByAddress(byte[16])}, and the JDK silently collapses an IPv4-mapped 16-byte
     * address ({@code ::ffff:a.b.c.d}) into an {@code Inet4Address}, so {@code .getAddress()}
     * would return only 4 bytes for a genuinely 16-byte AAAA answer. That 4-byte value, tagged as
     * the AAAA (IPv6) family, was rejected by {@code DnsAnswer}'s constructor with a plain {@code
     * IllegalArgumentException} rather than a {@code DnsResolutionException}, so it was not caught
     * by {@code DestinationEnforcementPolicy.assess}'s fail-closed handling and would surface as an
     * unmapped 500 -- deterministically triggerable by any attacker-controlled DNS zone. This
     * proves, end to end through the real {@link DnsjavaDestinationResolver} and the real {@code
     * DestinationEnforcementPolicy}, that a scripted wire AAAA answer of {@code ::ffff:127.0.0.1}
     * is classified {@code PROHIBITED} (not an unmapped crash).
     */
    @Test
    void mappedAaaaAnswerFlowsEndToEndThroughDestinationEnforcementPolicyAsProhibited() throws Exception {
        Name origin = name("mapped.example.");
        byte[] mappedLoopback = new byte[16];
        mappedLoopback[10] = (byte) 0xFF;
        mappedLoopback[11] = (byte) 0xFF;
        mappedLoopback[12] = 127;
        mappedLoopback[15] = 1; // ::ffff:127.0.0.1
        var stub = new ScriptedResolver();
        stub.nxdomain(origin, Type.A);
        stub.respond(origin, Type.AAAA, aaaaRecordFromRawWireBytes(origin, mappedLoopback, 60));

        var dnsResolver = new DnsjavaDestinationResolver(stub, 8, CLOCK);
        var properties =
                new DestinationPolicyProperties(Duration.ofSeconds(2), 8, 60, 5, "test-policy-v1", null, List.of());
        var policy = new DestinationEnforcementPolicy(
                dnsResolver, properties, new OperatorPrivateAllowlist(List.of()), CLOCK);
        var request = new DestinationRequest(
                new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
                new ConnectionVersion(1),
                DatabaseKind.POSTGRESQL,
                ConnectionPurpose.PHYSICAL_POOL_CONNECTION,
                new StructuredEndpoint("mapped.example", 5432, "analytics"),
                new Deadline(NOW.plusSeconds(300)));

        assertThatThrownBy(() -> policy.assess(request))
                .isInstanceOf(DestinationValidationException.class)
                .extracting(ex -> ((DestinationValidationException) ex).reason())
                .isEqualTo(DestinationDenialReason.PROHIBITED_ADDRESS);
    }

    /** Regression for a prior review finding: mutating a shared {@link Resolver}'s configured
     * timeout per call. Structurally proves the fixed resolver never calls {@code setTimeout} on
     * the injected resolver at all.
     */
    @Test
    void neverMutatesTheSharedResolversConfiguredTimeout() throws Exception {
        Name origin = name("no-mutation.example.");
        var stub = new ScriptedResolver();
        stub.respond(origin, Type.A, aRecord(origin, "10.20.30.40", 60));
        stub.nxdomain(origin, Type.AAAA);

        var resolver = new DnsjavaDestinationResolver(stub, 8, CLOCK);
        resolver.resolveAll("no-mutation.example.", DEADLINE);

        assertThat(stub.setTimeoutCalls.get()).isZero();
    }

    /**
     * Regression for a prior review finding: {@code DnsjavaDestinationResolver} used to call
     * {@code resolver.setTimeout(...)} on a SINGLETON {@link Resolver} immediately before {@code
     * resolver.send(...)}. Under concurrent requests this raced: one thread's short remaining
     * budget could be overwritten by another thread's longer budget between the two calls,
     * letting a call block past its own absolute deadline. This proves two concurrent calls,
     * sharing the same underlying {@link Resolver} instance, with very different remaining
     * budgets, each honor only their OWN deadline: a call with a short budget against a query
     * that is never answered fails closed at approximately its own deadline (not after the other
     * call's much longer budget, and not hanging forever), while a call with a long budget
     * completes successfully shortly after its answer becomes available (not truncated by the
     * other call's much shorter budget).
     */
    @Test
    void concurrentCallsWithDifferentBudgetsDoNotAffectEachOther() throws Exception {
        Clock realClock = Clock.systemUTC();
        Name shortName = name("short-budget.example.");
        Name longName = name("long-budget.example.");
        var blocking = new BlockingResolver();
        blocking.gate(shortName, Type.A); // deliberately never released: simulates a stuck query
        blocking.gate(longName, Type.A);
        blocking.gate(longName, Type.AAAA);

        var resolver = new DnsjavaDestinationResolver(blocking, 8, realClock);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Instant start = realClock.instant();
            Future<Long> shortElapsedMillis = pool.submit(() -> {
                long begin = System.nanoTime();
                try {
                    resolver.resolveAll("short-budget.example.", start.plusMillis(150));
                    throw new AssertionError("expected the short-budget call to time out");
                } catch (DnsResolutionException expected) {
                    if (expected.reason() != DestinationDenialReason.RESOLVER_TIMEOUT) {
                        throw expected;
                    }
                }
                return (System.nanoTime() - begin) / 1_000_000;
            });
            Future<Long> longElapsedMillis = pool.submit(() -> {
                long begin = System.nanoTime();
                resolver.resolveAll("long-budget.example.", start.plusSeconds(5));
                return (System.nanoTime() - begin) / 1_000_000;
            });
            pool.submit(() -> {
                Thread.sleep(80); // let both calls get in flight before releasing the long one
                blocking.release(longName, Type.A, aRecord(longName, "10.20.30.40", 60));
                blocking.release(longName, Type.AAAA, noError(longName, Type.AAAA));
                return null;
            });

            long shortMillis = shortElapsedMillis.get(5, TimeUnit.SECONDS);
            long longMillis = longElapsedMillis.get(5, TimeUnit.SECONDS);

            // Bound by its own ~150ms deadline: not stuck on the never-released gate, and not
            // extended by the concurrently running call's much longer 5s budget.
            assertThat(shortMillis).isLessThan(2000);
            // Succeeds promptly once its own answer is released (~80ms): not truncated to the
            // concurrently running call's much shorter ~150ms deadline.
            assertThat(longMillis).isLessThan(2000);
            assertThat(blocking.setTimeoutCalls.get()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    private static Message aaaaRecordFromRawWireBytes(Name name, byte[] sixteenRawBytes, long ttl)
            throws IOException {
        Message message = noError(name, Type.AAAA);
        AAAARecord constructed = new AAAARecord(name, DClass.IN, ttl, sixteenRawBytes);
        // Round-trip through the actual wire format, exactly as a hostile authoritative DNS
        // server's response would be parsed, so this cannot be dismissed as an artifact of how
        // the record was constructed in the test.
        Record reparsed = Record.fromWire(constructed.toWire(Section.ANSWER), Section.ANSWER);
        message.addRecord(reparsed, Section.ANSWER);
        return message;
    }

    private static Name name(String value) throws TextParseException {
        return Name.fromString(value);
    }

    private static Message noError(Name name, int type) throws TextParseException {
        Message message = new Message();
        message.addRecord(Record.newRecord(name, type, DClass.IN), org.xbill.DNS.Section.QUESTION);
        message.getHeader().setRcode(Rcode.NOERROR);
        return message;
    }

    private static Message aRecord(Name name, String ipv4, long ttl) throws Exception {
        Message message = noError(name, Type.A);
        message.addRecord(
                new ARecord(name, DClass.IN, ttl, InetAddress.getByName(ipv4)), org.xbill.DNS.Section.ANSWER);
        return message;
    }

    private static Message aaaaRecord(Name name, String ipv6, long ttl) throws Exception {
        Message message = noError(name, Type.AAAA);
        message.addRecord(
                new AAAARecord(name, DClass.IN, ttl, InetAddress.getByName(ipv6)), org.xbill.DNS.Section.ANSWER);
        return message;
    }

    private static Message cname(Name from, Name to) throws TextParseException {
        Message message = noError(from, Type.A);
        message.addRecord(new CNAMERecord(from, DClass.IN, 300, to), org.xbill.DNS.Section.ANSWER);
        return message;
    }

    /** Deterministic, scriptable {@link Resolver} test double: no real DNS traffic. */
    private static final class ScriptedResolver implements Resolver {
        private final Map<String, Message> responses = new HashMap<>();
        private final Map<String, IOException> failures = new HashMap<>();
        final AtomicInteger setTimeoutCalls = new AtomicInteger();

        void respond(Name name, int type, Message message) {
            responses.put(key(name, type), message);
        }

        void nxdomain(Name name, int type) throws TextParseException {
            Message message = noError(name, type);
            message.getHeader().setRcode(Rcode.NXDOMAIN);
            responses.put(key(name, type), message);
        }

        void failWith(Name name, int type, IOException exception) {
            failures.put(key(name, type), exception);
        }

        private static String key(Name name, int type) {
            return name.toString(true).toLowerCase(Locale.ROOT) + "/" + type;
        }

        @Override
        public Message send(Message query) throws IOException {
            Record question = query.getQuestion();
            String k = key(question.getName(), question.getType());
            if (failures.containsKey(k)) {
                throw failures.get(k);
            }
            Message response = responses.get(k);
            if (response == null) {
                throw new IOException("no scripted response for " + k);
            }
            return response;
        }

        @Override
        public void setPort(int port) {}

        @Override
        public void setTCP(boolean flag) {}

        @Override
        public void setIgnoreTruncation(boolean flag) {}

        @Override
        public void setEDNS(int version, int payloadSize, int flags, List<EDNSOption> options) {}

        @Override
        public void setTSIGKey(TSIG key) {}

        @Override
        public void setTimeout(Duration timeout) {
            setTimeoutCalls.incrementAndGet();
        }

        @Override
        public CompletionStage<Message> sendAsync(Message query) {
            CompletableFuture<Message> future = new CompletableFuture<>();
            try {
                future.complete(send(query));
            } catch (IOException failed) {
                future.completeExceptionally(failed);
            }
            return future;
        }

        @Override
        public CompletionStage<Message> sendAsync(Message query, Executor executor) {
            throw new UnsupportedOperationException("not used by DnsjavaDestinationResolver");
        }
    }

    /**
     * A {@link Resolver} test double whose answers are held back until explicitly released by the
     * test, used to force two concurrent {@link DnsjavaDestinationResolver#resolveAll} calls to be
     * genuinely in flight at the same time against the same shared resolver instance.
     */
    private static final class BlockingResolver implements Resolver {
        private final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
        private final Map<String, Message> responses = new ConcurrentHashMap<>();
        final AtomicInteger setTimeoutCalls = new AtomicInteger();

        void gate(Name name, int type) {
            gates.put(key(name, type), new CountDownLatch(1));
        }

        void release(Name name, int type, Message response) {
            String k = key(name, type);
            responses.put(k, response);
            CountDownLatch gate = gates.get(k);
            if (gate != null) {
                gate.countDown();
            }
        }

        private static String key(Name name, int type) {
            return name.toString(true).toLowerCase(Locale.ROOT) + "/" + type;
        }

        @Override
        public CompletionStage<Message> sendAsync(Message query) {
            Record question = query.getQuestion();
            String k = key(question.getName(), question.getType());
            CompletableFuture<Message> future = new CompletableFuture<>();
            Thread worker = new Thread(
                    () -> {
                        try {
                            CountDownLatch gate = gates.get(k);
                            if (gate != null) {
                                gate.await();
                            }
                            Message response = responses.get(k);
                            if (response == null) {
                                future.completeExceptionally(new IOException("no scripted response for " + k));
                            } else {
                                future.complete(response);
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            future.completeExceptionally(interrupted);
                        }
                    },
                    "blocking-resolver-" + k);
            worker.setDaemon(true);
            worker.start();
            return future;
        }

        @Override
        public void setPort(int port) {}

        @Override
        public void setTCP(boolean flag) {}

        @Override
        public void setIgnoreTruncation(boolean flag) {}

        @Override
        public void setEDNS(int version, int payloadSize, int flags, List<EDNSOption> options) {}

        @Override
        public void setTSIGKey(TSIG key) {}

        @Override
        public void setTimeout(Duration timeout) {
            setTimeoutCalls.incrementAndGet();
        }

        @Override
        public CompletionStage<Message> sendAsync(Message query, Executor executor) {
            throw new UnsupportedOperationException("not used by DnsjavaDestinationResolver");
        }
    }
}
