package com.knowimran.rivoquery.platform.connector.mysql;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Reflective bridge into the already-registered JVM-wide {@code
 * platform.connector.postgresql.TrappedTldResolverProvider} - the sole {@code
 * java.net.spi.InetAddressResolverProvider} configured for this module's tests (see {@code
 * apps/api/src/test/resources/META-INF/services/java.net.spi.InetAddressResolverProvider}).
 *
 * <p><b>Why this bridges into another package's class instead of registering a second provider.</b>
 * Empirically confirmed (a standalone two-provider JDK experiment, outside this module): when more
 * than one {@code InetAddressResolverProvider} is discoverable via {@code ServiceLoader}, the JDK
 * installs only the <em>first</em> one it finds and never even instantiates the others - for a
 * single Surefire/Failsafe JVM fork shared across this module's tests (the default), registering a
 * second provider for MySQL's own resolver trap would be a coin toss at best and, at worst, would
 * silently make {@code postgresql.TrappedTldResolverProvider} never load at all - which would
 * silently defeat {@code PostgresqlPhysicalJdbcConnectorIT}'s own zero-lookups proof (an already
 * merged, already-reviewed test this task must not weaken). Reusing the single already-registered
 * provider - which is a behavior-preserving pass-through for every hostname except its own trapped
 * suffix - is therefore the only safe way to get the same definitive proof for MySQL without
 * touching anything under {@code platform/connector/postgresql/**} (this task's own explicit
 * constraint) or risking that regression.
 *
 * <p>{@code TrappedTldResolverProvider.TRAP_SUFFIX}/{@code reset()}/{@code recordedLookups()} are
 * package-private, so this class reaches them via reflection ({@code setAccessible(true)}) rather
 * than a public API change to that class - this module has no {@code module-info.java} (classpath
 * mode), so no {@code --add-opens} is required.
 */
final class TrappedTldResolverProviderBridge {

    /**
     * S4: reflectively reads the real package-private {@code
     * platform.connector.postgresql.TrappedTldResolverProvider#TRAP_SUFFIX} constant instead of
     * duplicating it as a bare literal - a future change to the trap suffix on that class can no
     * longer silently desync the two. Also under the RFC 2606 {@code .invalid} TLD, so every hostname
     * built from this suffix gets both the indirect ("would fail if resolution were attempted") and
     * the direct ("was never attempted") proof.
     */
    static final String TRAP_SUFFIX = readTrapSuffix();

    private static final String PROVIDER_CLASS_NAME =
            "com.knowimran.rivoquery.platform.connector.postgresql.TrappedTldResolverProvider";

    private TrappedTldResolverProviderBridge() {}

    static void reset() {
        invokeStatic("reset");
    }

    @SuppressWarnings("unchecked")
    static List<String> recordedLookups() {
        return (List<String>) invokeStatic("recordedLookups");
    }

    /**
     * S4 positive control: proves the trap provider genuinely loaded and is active by deliberately
     * triggering a lookup for a trapped hostname and asserting it was actually recorded, then resets
     * the recorded-lookups state so a caller's own real proof runs clean. Without this, a silently
     * uninstalled trap (e.g. {@code ServiceLoader} provider-ordering fallout, or any future classpath
     * change) would make a "zero recorded lookups" assertion elsewhere pass vacuously with nothing
     * actually checked.
     */
    static void assertTrapIsGenuinelyActiveThenReset() {
        reset();
        String probeHostname = "rivoquery-positive-control" + TRAP_SUFFIX;
        try {
            InetAddress.getByName(probeHostname);
            throw new IllegalStateException(
                    "the resolver trap did not reject a lookup for " + probeHostname
                            + " - it is not genuinely active");
        } catch (UnknownHostException expected) {
            // The trap's own UnknownHostException, not a real DNS failure - see TrappedTldResolverProvider.
        }
        if (!recordedLookups().contains(probeHostname)) {
            throw new IllegalStateException(
                    "the resolver trap rejected " + probeHostname + " but did not record it as looked up");
        }
        reset();
    }

    private static String readTrapSuffix() {
        try {
            Class<?> providerClass = Class.forName(PROVIDER_CLASS_NAME);
            Field field = providerClass.getDeclaredField("TRAP_SUFFIX");
            field.setAccessible(true);
            return (String) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to reflect into " + PROVIDER_CLASS_NAME + "#TRAP_SUFFIX", e);
        }
    }

    private static Object invokeStatic(String methodName) {
        try {
            Class<?> providerClass = Class.forName(PROVIDER_CLASS_NAME);
            Method method = providerClass.getDeclaredMethod(methodName);
            method.setAccessible(true);
            return method.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "failed to reflect into " + PROVIDER_CLASS_NAME + "#" + methodName, e);
        }
    }
}
