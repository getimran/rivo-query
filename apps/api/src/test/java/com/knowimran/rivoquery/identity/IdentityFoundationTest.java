package com.knowimran.rivoquery.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IdentityFoundationTest {
    private static final String ULID = "01J6MZ4H9ST5N7YQ2C8W3V0KFX";
    private static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_" + ULID);
    private static final MembershipId MEMBERSHIP = new MembershipId("mem_" + ULID);
    private static final ActorId ACTOR = new ActorId("act_" + ULID);

    @Test
    void publicAndInternalIdentifiersRequireExactCanonicalValues() {
        assertThat(WORKSPACE.value()).isEqualTo("wsp_" + ULID);
        assertThat(MEMBERSHIP.value()).isEqualTo("mem_" + ULID);
        assertThat(ACTOR.value()).isEqualTo("act_" + ULID);

        for (String invalid : Set.of(
                "wsp_" + ULID.toLowerCase(),
                " wsp_" + ULID,
                "wsp_" + ULID + " ",
                "wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFI",
                "tenant-1")) {
            assertThatThrownBy(() -> new WorkspaceId(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void onlyAnActiveCurrentMembershipCanBecomeAnActorContext() {
        var active = new CurrentMembership(
                ACTOR, WORKSPACE, MEMBERSHIP, WorkspaceRole.ADMINISTRATOR, MembershipStatus.ACTIVE);
        assertThat(authority(active).resolve("issuer", "subject", WORKSPACE).workspaceId())
                .isEqualTo(WORKSPACE);

        var inactive = new CurrentMembership(
                ACTOR, WORKSPACE, MEMBERSHIP, WorkspaceRole.ADMINISTRATOR, MembershipStatus.INACTIVE);
        assertThatThrownBy(() -> authority(inactive).resolve("issuer", "subject", WORKSPACE))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void actorContextCanOnlyBeMintedByMembershipAuthority() {
        assertThat(ActorContext.class.getDeclaredConstructors()).allMatch(
                constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThatThrownBy(() -> authority(new CurrentMembership(
                                ACTOR,
                                new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFY"),
                                MEMBERSHIP,
                                WorkspaceRole.ADMINISTRATOR,
                                MembershipStatus.ACTIVE))
                        .resolve("issuer", "subject", WORKSPACE))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> authority(new CurrentMembership(
                                ACTOR, WORKSPACE, MEMBERSHIP, WorkspaceRole.ADMINISTRATOR, MembershipStatus.ACTIVE))
                        .resolve(" issuer", "subject", WORKSPACE))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void administratorHasEffectiveManageAndEveryConnectionPermission() {
        var administrator = actor(WorkspaceRole.ADMINISTRATOR);

        assertThat(PermissionMatrix.effectivePermissions(administrator, WORKSPACE, null, Set.of()))
                .containsExactlyInAnyOrder(ConnectionPermission.values());
    }

    @Test
    void memberReceivesOnlyIndependentlyGrantedNonManagePermissions() {
        var member = actor(WorkspaceRole.MEMBER);
        var effective = PermissionMatrix.effectivePermissions(
                member,
                WORKSPACE,
                MEMBERSHIP,
                Set.of(GrantablePermission.VIEW_SCHEMA, GrantablePermission.EXPORT));

        assertThat(effective)
                .containsExactlyInAnyOrder(ConnectionPermission.VIEW_SCHEMA, ConnectionPermission.EXPORT)
                .doesNotContain(ConnectionPermission.MANAGE, ConnectionPermission.QUERY);
        assertThat(GrantablePermission.values()).hasSize(3);
    }

    @Test
    void mismatchedWorkspaceIsDeniedAndMismatchedMembershipGetsNoPermissions() {
        var member = actor(WorkspaceRole.MEMBER);
        var otherWorkspace = new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFY");
        var otherMembership = new MembershipId("mem_01J6MZ4H9ST5N7YQ2C8W3V0KFY");

        assertThatThrownBy(() -> PermissionMatrix.effectivePermissions(
                        member, otherWorkspace, MEMBERSHIP, Set.of(GrantablePermission.QUERY)))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThat(PermissionMatrix.effectivePermissions(
                        member, WORKSPACE, otherMembership, Set.of(GrantablePermission.QUERY)))
                .isEmpty();
    }

    @Test
    void administratorCannotBeReboundAcrossWorkspaces() {
        var otherWorkspace = new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFY");
        assertThatThrownBy(() -> PermissionMatrix.effectivePermissions(
                        actor(WorkspaceRole.ADMINISTRATOR), otherWorkspace, null, Set.of()))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    private static ActorContext actor(WorkspaceRole role) {
        return authority(new CurrentMembership(ACTOR, WORKSPACE, MEMBERSHIP, role, MembershipStatus.ACTIVE))
                .resolve("issuer", "subject", WORKSPACE);
    }

    private static ActorContext.Authority authority(CurrentMembership membership) {
        return new ActorContext.Authority(
                new com.knowimran.rivoquery.identity.application.SingleMembershipRepository(membership));
    }
}
