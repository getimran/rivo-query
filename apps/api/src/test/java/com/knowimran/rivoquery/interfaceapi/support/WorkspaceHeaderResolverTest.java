package com.knowimran.rivoquery.interfaceapi.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.identity.WorkspaceId;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * PD2-003 step 2: the workspace selector header must be exactly one
 * canonical value - zero, more than one (even if identical), and malformed
 * values are all rejected explicitly.
 */
class WorkspaceHeaderResolverTest {

    private static final String CANONICAL = "wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX";
    private static final String CANONICAL_OTHER = "wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFY";

    @Test
    void exactlyOneCanonicalValueResolves() {
        var request = new MockHttpServletRequest();
        request.addHeader(WorkspaceHeaderResolver.HEADER_NAME, CANONICAL);

        assertThat(WorkspaceHeaderResolver.requireExactly(request)).isEqualTo(new WorkspaceId(CANONICAL));
    }

    @Test
    void missingHeaderIsRejected() {
        var request = new MockHttpServletRequest();

        assertThatThrownBy(() -> WorkspaceHeaderResolver.requireExactly(request))
                .isInstanceOf(WorkspaceSelectorInvalidException.class);
    }

    @Test
    void exactDuplicateValueIsRejected() {
        var request = new MockHttpServletRequest();
        request.addHeader(WorkspaceHeaderResolver.HEADER_NAME, CANONICAL);
        request.addHeader(WorkspaceHeaderResolver.HEADER_NAME, CANONICAL);

        assertThatThrownBy(() -> WorkspaceHeaderResolver.requireExactly(request))
                .isInstanceOf(WorkspaceSelectorInvalidException.class);
    }

    @Test
    void twoDifferentValuesAreRejected() {
        var request = new MockHttpServletRequest();
        request.addHeader(WorkspaceHeaderResolver.HEADER_NAME, CANONICAL);
        request.addHeader(WorkspaceHeaderResolver.HEADER_NAME, CANONICAL_OTHER);

        assertThatThrownBy(() -> WorkspaceHeaderResolver.requireExactly(request))
                .isInstanceOf(WorkspaceSelectorInvalidException.class);
    }

    @Test
    void lowercaseValueIsRejectedNeverCaseFolded() {
        var request = new MockHttpServletRequest();
        request.addHeader(WorkspaceHeaderResolver.HEADER_NAME, CANONICAL.toLowerCase(java.util.Locale.ROOT));

        assertThatThrownBy(() -> WorkspaceHeaderResolver.requireExactly(request))
                .isInstanceOf(WorkspaceSelectorInvalidException.class);
    }

    @Test
    void paddedValueIsRejectedNeverTrimmed() {
        var request = new MockHttpServletRequest();
        request.addHeader(WorkspaceHeaderResolver.HEADER_NAME, " " + CANONICAL + " ");

        assertThatThrownBy(() -> WorkspaceHeaderResolver.requireExactly(request))
                .isInstanceOf(WorkspaceSelectorInvalidException.class);
    }

    @Test
    void commaCombinedValueIsRejectedNeverSplit() {
        var request = new MockHttpServletRequest();
        request.addHeader(WorkspaceHeaderResolver.HEADER_NAME, CANONICAL + "," + CANONICAL_OTHER);

        assertThatThrownBy(() -> WorkspaceHeaderResolver.requireExactly(request))
                .isInstanceOf(WorkspaceSelectorInvalidException.class);
    }

    @Test
    void absentHeaderIsAllowedForUnscopedDiscovery() {
        var request = new MockHttpServletRequest();

        WorkspaceHeaderResolver.requireAbsent(request);
    }

    @Test
    void presentHeaderIsRejectedForUnscopedDiscovery() {
        var request = new MockHttpServletRequest();
        request.addHeader(WorkspaceHeaderResolver.HEADER_NAME, CANONICAL);

        assertThatThrownBy(() -> WorkspaceHeaderResolver.requireAbsent(request))
                .isInstanceOf(WorkspaceSelectorNotAllowedException.class);
    }
}
