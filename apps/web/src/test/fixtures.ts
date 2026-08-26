import type { components } from "../api/generated/rivoquery";

export type WorkspaceSummary = components["schemas"]["WorkspaceSummary"];
export type ConnectionSummary = components["schemas"]["ConnectionSummary"];
export type ConnectionDetail = components["schemas"]["ConnectionDetail"];
export type Operation = components["schemas"]["Operation"];

export const workspaceOne: WorkspaceSummary = {
  workspaceId: "wsp_one",
  displayName: "Acme Analytics",
  role: "ADMINISTRATOR",
};

export const workspaceTwo: WorkspaceSummary = {
  workspaceId: "wsp_two",
  displayName: "Globex Research",
  role: "MEMBER",
};

export function connectionSummary(overrides: Partial<ConnectionSummary> = {}): ConnectionSummary {
  return {
    connectionId: "con_one",
    name: "warehouse-read",
    kind: "POSTGRESQL",
    state: "ACTIVE",
    version: 3,
    createdAt: "2026-08-01T10:00:00Z",
    updatedAt: "2026-08-20T10:00:00Z",
    effectivePermissions: ["VIEW_SCHEMA", "QUERY"],
    ...overrides,
  };
}

export function connectionDetail(overrides: Partial<ConnectionDetail> = {}): ConnectionDetail {
  return {
    connectionId: "con_one",
    name: "warehouse-read",
    kind: "POSTGRESQL",
    state: "ACTIVE",
    version: 3,
    createdAt: "2026-08-01T10:00:00Z",
    updatedAt: "2026-08-20T10:00:00Z",
    credentialState: "CURRENT",
    secretConfigured: true,
    effectivePermissions: ["VIEW_SCHEMA", "QUERY"],
    endpoint: { host: "db.example.com", port: 5432, database: "analytics" },
    tls: {
      mode: "VERIFY_FULL",
      customCaConfigured: false,
      clientCertificateConfigured: false,
    },
    connectorOptions: { tcpKeepAlive: true },
    objectPolicy: { allowedSchemas: ["public"], allowedTables: [] },
    limits: { statementTimeoutMs: 15000, maxRows: 200 },
    links: { self: "/api/v1/connections/con_one" },
    ...overrides,
  };
}

export function operation(overrides: Partial<Operation> = {}): Operation {
  return {
    operationId: "op_delete_one",
    action: "CONNECTION_DELETE",
    state: "PENDING",
    createdAt: "2026-08-27T10:00:00Z",
    updatedAt: "2026-08-27T10:00:00Z",
    connectionId: "con_one",
    links: { self: "/api/v1/operations/op_delete_one" },
    ...overrides,
  };
}

export function problemBody(
  status: number,
  overrides: Partial<Record<"code" | "title" | "detail" | "instance" | "requestId", string>> = {},
): Record<string, string | number> {
  return {
    type: "https://rivoquery.example/problems/validation-failed",
    title: overrides.title ?? "Validation failed",
    status,
    detail: overrides.detail ?? "The request body was invalid.",
    instance: overrides.instance ?? "/api/v1/connections",
    code: overrides.code ?? "VALIDATION_FAILED",
    requestId: overrides.requestId ?? "problem-request-01",
  };
}

export function jsonResponse(
  body: unknown,
  status: number,
  options: { requestId?: string; etag?: string; contentType?: string } = {},
): Response {
  const headers = new Headers({
    "Content-Type": options.contentType ?? "application/json",
  });
  if (options.requestId !== undefined) {
    headers.set("X-Request-Id", options.requestId);
  }
  if (options.etag !== undefined) {
    headers.set("ETag", options.etag);
  }
  return new Response(JSON.stringify(body), { headers, status });
}

export function problemResponse(
  status: number,
  overrides: Parameters<typeof problemBody>[1] = {},
  options: { requestId?: string } = {},
): Response {
  const headers = new Headers({
    "Content-Type": "application/problem+json",
  });
  if (options.requestId !== undefined) {
    headers.set("X-Request-Id", options.requestId);
  }
  return new Response(JSON.stringify(problemBody(status, overrides)), { headers, status });
}

export function requestPath(request: Request): string {
  return new URL(request.url).pathname;
}