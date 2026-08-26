package com.knowimran.rivoquery.interfaceapi.support;

import com.knowimran.rivoquery.identity.WorkspaceId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Reads the raw {@code X-RivoQuery-Workspace-Id} header directly from the
 * servlet request (never through a generated single-{@code String}
 * {@code @RequestHeader} parameter) so that zero, more-than-one, or a
 * malformed value is always rejected explicitly - see PD2-003 step 2 and the
 * P2-130 task's own note that generated Java validation does not reliably
 * enforce "exactly one, exactly canonical" semantics for a multi-valued
 * header. The value is matched exactly against the canonical pattern: never
 * trimmed, split, or case-folded.
 */
public final class WorkspaceHeaderResolver {
    public static final String HEADER_NAME = "X-RivoQuery-Workspace-Id";
    private static final Pattern CANONICAL = Pattern.compile("^wsp_[0-9A-HJKMNP-TV-Z]{26}$");

    private WorkspaceHeaderResolver() {}

    /** For workspace-scoped operations: exactly one canonical value is required. */
    public static WorkspaceId requireExactly(HttpServletRequest request) {
        List<String> values = Collections.list(Objects.requireNonNull(request, "request").getHeaders(HEADER_NAME));
        if (values.size() != 1 || !CANONICAL.matcher(values.get(0)).matches()) {
            throw new WorkspaceSelectorInvalidException();
        }
        return new WorkspaceId(values.get(0));
    }

    /** For the unscoped workspace-discovery operation: the header must be entirely absent. */
    public static void requireAbsent(HttpServletRequest request) {
        List<String> values = Collections.list(Objects.requireNonNull(request, "request").getHeaders(HEADER_NAME));
        if (!values.isEmpty()) {
            throw new WorkspaceSelectorNotAllowedException();
        }
    }
}
