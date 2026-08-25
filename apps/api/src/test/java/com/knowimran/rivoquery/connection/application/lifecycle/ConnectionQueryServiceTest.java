package com.knowimran.rivoquery.connection.application.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionPermissionGrant;
import com.knowimran.rivoquery.connection.ConnectionState;
import com.knowimran.rivoquery.connection.DatabaseConnection;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.secret.BoundSecretReference;
import com.knowimran.rivoquery.connection.application.secret.SecretBinding;
import com.knowimran.rivoquery.connection.application.secret.SecretPurpose;
import com.knowimran.rivoquery.connection.application.secret.SecretReference;
import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.GrantablePermission;
import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConnectionQueryServiceTest {
    private static final String ULID = "01J9RK3H0RT8N4YB2C6W9V1MFP";
    private static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_" + ULID);
    private static final WorkspaceId OTHER_WORKSPACE = new WorkspaceId("wsp_01J9RK3H0RT8N4YB2C6W9V1MFQ");
    private static final ActorId ADMIN_ACTOR = new ActorId("act_" + ULID);
    private static final ActorId MEMBER_ACTOR = new ActorId("act_01J9RK3H0RT8N4YB2C6W9V1MFQ");
    private static final MembershipId MEMBER_MEMBERSHIP = new MembershipId("mem_01J9RK3H0RT8N4YB2C6W9V1MFQ");

    @Test
    void administratorSeesEveryPublicConnectionAndNothingHidden() {
        var connections = new LifecycleTestFixtures.FakeConnectionRepository();
        var permissions = new LifecycleTestFixtures.FakeConnectionPermissionRepository();
        var service = new ConnectionQueryService(connections, permissions);
        var visible = activeConnection(connections, WORKSPACE, "con_01J9RK3H0RT8N4YB2C6W9V1MFR");
        var pending = DatabaseConnection.reserve(
                WORKSPACE, new ConnectionId("con_01J9RK3H0RT8N4YB2C6W9V1MFS"), DatabaseKind.POSTGRESQL);
        connections.insert(pending);

        var admin = LifecycleTestFixtures.administrator(WORKSPACE, ADMIN_ACTOR);
        var page = service.list(admin, Optional.empty(), 50);
        assertThat(page).extracting(DatabaseConnection::id).containsExactly(visible.id());
        assertThat(service.get(admin, visible.id())).isEqualTo(visible);
        assertThatThrownBy(() -> service.get(admin, pending.id())).isInstanceOf(ConnectionNotFoundException.class);
    }

    @Test
    void memberSeesOnlyConnectionsWithAnExplicitEffectiveGrant() {
        var connections = new LifecycleTestFixtures.FakeConnectionRepository();
        var permissions = new LifecycleTestFixtures.FakeConnectionPermissionRepository();
        var service = new ConnectionQueryService(connections, permissions);
        var granted = activeConnection(connections, WORKSPACE, "con_01J9RK3H0RT8N4YB2C6W9V1MFT");
        var notGranted = activeConnection(connections, WORKSPACE, "con_01J9RK3H0RT8N4YB2C6W9V1MFV");
        permissions.put(new ConnectionPermissionGrant(
                WORKSPACE, granted.id(), MEMBER_MEMBERSHIP, Set.of(GrantablePermission.VIEW_SCHEMA), 1));

        var member = LifecycleTestFixtures.member(WORKSPACE, MEMBER_ACTOR, MEMBER_MEMBERSHIP);
        var page = service.list(member, Optional.empty(), 50);
        assertThat(page).extracting(DatabaseConnection::id).containsExactly(granted.id());
        assertThat(service.get(member, granted.id())).isEqualTo(granted);
        assertThatThrownBy(() -> service.get(member, notGranted.id())).isInstanceOf(ConnectionNotFoundException.class);
    }

    @Test
    void listRejectsALimitAboveTheContractsPageSizeCeiling() {
        var connections = new LifecycleTestFixtures.FakeConnectionRepository();
        var permissions = new LifecycleTestFixtures.FakeConnectionPermissionRepository();
        var service = new ConnectionQueryService(connections, permissions);
        var admin = LifecycleTestFixtures.administrator(WORKSPACE, ADMIN_ACTOR);

        assertThatThrownBy(() -> service.list(admin, Optional.empty(), ConnectionQueryService.MAX_PAGE_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class);

        // The ceiling itself remains a valid, accepted limit.
        assertThat(service.list(admin, Optional.empty(), ConnectionQueryService.MAX_PAGE_SIZE)).isEmpty();
    }

    @Test
    void crossTenantAndUnknownConnectionsAreIndistinguishableNotFound() {
        var connections = new LifecycleTestFixtures.FakeConnectionRepository();
        var permissions = new LifecycleTestFixtures.FakeConnectionPermissionRepository();
        var service = new ConnectionQueryService(connections, permissions);
        var foreignActive = activeConnection(connections, OTHER_WORKSPACE, "con_01J9RK3H0RT8N4YB2C6W9V1MFW");

        var admin = LifecycleTestFixtures.administrator(WORKSPACE, ADMIN_ACTOR);
        assertThatThrownBy(() -> service.get(admin, foreignActive.id())).isInstanceOf(ConnectionNotFoundException.class);
        assertThatThrownBy(() -> service.get(admin, new ConnectionId("con_01J9RK3H0RT8N4YB2C6W9V1MFX")))
                .isInstanceOf(ConnectionNotFoundException.class);
    }

    /**
     * Builds and durably persists (into {@code connections}) a fully ACTIVE
     * connection via the same reserve -> reserveNextSecretVersion ->
     * attachCurrentSecret -> transition sequence the real lifecycle saga
     * uses, so every returned instance is backed by a row the fake
     * repository's own optimistic-concurrency checks will accept.
     */
    private static DatabaseConnection activeConnection(
            LifecycleTestFixtures.FakeConnectionRepository connections, WorkspaceId workspaceId, String connectionId) {
        var reserved = DatabaseConnection.reserve(workspaceId, new ConnectionId(connectionId), DatabaseKind.POSTGRESQL);
        connections.insert(reserved);
        var versionReserved = reserved.reserveNextSecretVersion(reserved.version());
        if (!connections.compareAndSet(versionReserved, reserved.version())) {
            throw new IllegalStateException("test setup failed");
        }
        var binding = new SecretBinding(workspaceId, reserved.id(), SecretPurpose.TARGET_DATABASE_CONNECTION, 1);
        var reference = new SecretReference("local-fake", "opaqueaaaaaaaaaaaaaaaaa", 1, "fingerprintaaaaaaaaaaaaa");
        var secret = new BoundSecretReference(binding, reference);
        var pendingValidation = versionReserved.attachCurrentSecret(versionReserved.version(), secret);
        var active = pendingValidation.transition(pendingValidation.version(), ConnectionState.ACTIVE);
        // The fake repository (like the real one) only ever holds the last
        // persisted row, so this combined attach+transition write must be
        // guarded by the version that IS stored (after reserveNextSecretVersion),
        // not the further-advanced in-memory pendingValidation version.
        if (!connections.compareAndSet(active, versionReserved.version())) {
            throw new IllegalStateException("test setup failed");
        }
        return active;
    }
}
