import { describe, expect, it, vi } from "vitest";

import { createRivoqueryApi } from "./rivoqueryApi";
import {
  connectionDetail,
  connectionSummary,
  jsonResponse,
  operation,
  problemResponse,
  workspaceOne,
} from "../test/fixtures";

function requestHeaders(request: Request): Headers {
  return request.headers;
}

function requestMethod(request: Request): string {
  return request.method;
}

function requestUrl(request: Request): URL {
  return new URL(request.url);
}

function firstRequest(fetch: { mock: { calls: unknown[] } }): Request {
  const calls = fetch.mock.calls as [Request][];
  const first = calls[0];
  if (first === undefined) {
    throw new Error("expected the fetch mock to have been called");
  }
  return first[0];
}

describe("createRivoqueryApi", () => {
  it("discovers workspaces without a workspace header and build a paged value", async () => {
    const fetch = vi.fn(() =>
      Promise.resolve(
        jsonResponse({ items: [workspaceOne], selectionRequired: true }, 200, {
          requestId: "discover-request-01",
        }),
      ),
    );
    const api = createRivoqueryApi({ baseUrl: "https://api.example", token: "tkn", fetch });

    await expect(api.discoverWorkspaces()).resolves.toEqual({
      kind: "ok",
      value: { items: [workspaceOne], nextCursor: null },
      etag: null,
      requestId: "discover-request-01",
    });

    expect(fetch).toHaveBeenCalledTimes(1);
    const request = firstRequest(fetch);
    expect(request).toBeInstanceOf(Request);
    expect(requestMethod(request)).toBe("GET");
    expect(requestUrl(request).pathname).toBe("/api/v1/workspaces");
    expect(requestUrl(request).searchParams.get("limit")).toBe("100");
    expect(requestHeaders(request).get("Authorization")).toBe("Bearer tkn");
    expect(requestHeaders(request).get("X-RivoQuery-Workspace-Id")).toBeNull();
  });

  it("listConnections scopes the request with the workspace header", async () => {
    const fetch = vi.fn(() =>
      Promise.resolve(
        jsonResponse({ items: [connectionSummary()] }, 200, { requestId: "list-request-01" }),
      ),
    );
    const api = createRivoqueryApi({ baseUrl: "https://api.example", token: "tkn", fetch });

    await expect(api.listConnections("wsp_one")).resolves.toEqual({
      kind: "ok",
      value: { items: [connectionSummary()], nextCursor: null },
      etag: null,
      requestId: "list-request-01",
    });

    const request = firstRequest(fetch);
    expect(requestHeaders(request).get("X-RivoQuery-Workspace-Id")).toBe("wsp_one");
    expect(requestUrl(request).pathname).toBe("/api/v1/connections");
  });

  it("getConnection returns the detail and a validated ETag", async () => {
    const fetch = vi.fn(() =>
      Promise.resolve(
        jsonResponse(connectionDetail(), 200, { requestId: "detail-request-01", etag: '"v3"' }),
      ),
    );
    const api = createRivoqueryApi({ baseUrl: "https://api.example", token: "tkn", fetch });

    const result = await api.getConnection("wsp_one", "con_one");
    expect(result.kind).toBe("ok");
    if (result.kind === "ok") {
      expect(result.value.connectionId).toBe("con_one");
      expect(result.etag).toBe('"v3"');
    }
    const request = firstRequest(fetch);
    expect(requestUrl(request).pathname).toBe("/api/v1/connections/con_one");
    expect(requestHeaders(request).get("X-RivoQuery-Workspace-Id")).toBe("wsp_one");
  });

  it("createConnection posts with an idempotency key returning a detail", async () => {
    const fetch = vi.fn(() =>
      Promise.resolve(jsonResponse(connectionDetail(), 201, { requestId: "create-request-01" })),
    );
    const api = createRivoqueryApi({ baseUrl: "https://api.example", token: "tkn", fetch });

    const result = await api.createConnection(
      "wsp_one",
      {
        name: "warehouse-read",
        kind: "POSTGRESQL",
        endpoint: { host: "db.example.com", port: 5432, database: "analytics" },
        secret: { username: "reader", password: "s3cret" },
        tls: { mode: "VERIFY_FULL" },
        connectorOptions: { tcpKeepAlive: true },
        objectPolicy: { allowedSchemas: ["public"], allowedTables: [] },
        limits: { statementTimeoutMs: 15000, maxRows: 200 },
      },
      { idempotencyKey: "idem-01J6MZ4H9ST5N7YQ2C8W3V0KFX" },
    );

    expect(result.kind).toBe("ok");
    if (result.kind === "ok" && "connectionId" in result.value) {
      expect(result.value.connectionId).toBe("con_one");
    }

    const request = firstRequest(fetch);
    expect(requestMethod(request)).toBe("POST");
    expect(requestHeaders(request).get("Idempotency-Key")).toBe("idem-01J6MZ4H9ST5N7YQ2C8W3V0KFX");
  });

  it("createConnection maps a 202 operation response", async () => {
    const fetch = vi.fn(() =>
      Promise.resolve(jsonResponse(operation(), 202, { requestId: "create-op-request-01" })),
    );
    const api = createRivoqueryApi({ baseUrl: "https://api.example", token: "tkn", fetch });

    const result = await api.createConnection(
      "wsp_one",
      {
        name: "x",
        kind: "MYSQL",
        endpoint: { host: "h", port: 3306, database: "d" },
        secret: { username: "u", password: "p" },
        tls: { mode: "VERIFY_FULL" },
        connectorOptions: { tcpKeepAlive: true },
        objectPolicy: { allowedSchemas: ["s"], allowedTables: [] },
        limits: { statementTimeoutMs: 15000, maxRows: 200 },
      },
      { idempotencyKey: "idem-01J6MZ4H9ST5N7YQ2C8W3V0KFX" },
    );

    expect(result.kind).toBe("ok");
    if (result.kind === "ok" && "operationId" in result.value) {
      expect(result.value.operationId).toBe("op_delete_one");
    }
  });

  it("updateConnection sends If-Match and Idempotency-Key", async () => {
    const fetch = vi.fn(() =>
      Promise.resolve(
        jsonResponse(connectionDetail({ version: 4 }), 200, { requestId: "update-request-01", etag: '"v4"' }),
      ),
    );
    const api = createRivoqueryApi({ baseUrl: "https://api.example", token: "tkn", fetch });

    const result = await api.updateConnection(
      "wsp_one",
      "con_one",
      { name: "renamed" },
      { idempotencyKey: "idem-update", ifMatch: '"v3"' },
    );

    expect(result.kind).toBe("ok");
    if (result.kind === "ok") {
      expect(result.etag).toBe('"v4"');
    }
    const request = firstRequest(fetch);
    expect(requestMethod(request)).toBe("PATCH");
    expect(requestHeaders(request).get("If-Match")).toBe('"v3"');
    expect(requestHeaders(request).get("Idempotency-Key")).toBe("idem-update");
  });

  it("updateConnection surfaces a 202 operation as an accepted outcome", async () => {
    const fetch = vi.fn(() =>
      Promise.resolve(
        jsonResponse(
          operation({ operationId: "op_update_one", action: "CONNECTION_UPDATE" }),
          202,
          { requestId: "update-op-request-01" },
        ),
      ),
    );
    const api = createRivoqueryApi({ baseUrl: "https://api.example", token: "tkn", fetch });

    const result = await api.updateConnection(
      "wsp_one",
      "con_one",
      { name: "renamed" },
      { idempotencyKey: "idem-update", ifMatch: '"v3"' },
    );

    expect(result.kind).toBe("ok");
    if (result.kind === "ok" && "operationId" in result.value) {
      expect(result.value.operationId).toBe("op_update_one");
      expect(result.value.action).toBe("CONNECTION_UPDATE");
    }
  });

  it("disableConnection posts to the disable sub-resource", async () => {
    const fetch = vi.fn(() =>
      Promise.resolve(
        jsonResponse(connectionDetail({ state: "DISABLED" }), 200, { requestId: "disable-request-01", etag: '"v5"' }),
      ),
    );
    const api = createRivoqueryApi({ baseUrl: "https://api.example", token: "tkn", fetch });

    const result = await api.disableConnection("wsp_one", "con_one", {
      idempotencyKey: "idem-disable",
      ifMatch: '"v4"',
    });

    expect(result.kind).toBe("ok");
    const request = firstRequest(fetch);
    expect(requestMethod(request)).toBe("POST");
    expect(requestUrl(request).pathname).toBe("/api/v1/connections/con_one/disable");
  });

  it("deleteConnection returns an operation", async () => {
    const fetch = vi.fn(() => Promise.resolve(jsonResponse(operation(), 202)));
    const api = createRivoqueryApi({ baseUrl: "https://api.example", token: "tkn", fetch });

    const result = await api.deleteConnection("wsp_one", "con_one", {
      idempotencyKey: "idem-delete",
      ifMatch: '"v4"',
    });

    expect(result.kind).toBe("ok");
    if (result.kind === "ok") {
      expect(result.value).toEqual(operation());
    }
    const request = firstRequest(fetch);
    expect(requestMethod(request)).toBe("DELETE");
    expect(requestUrl(request).pathname).toBe("/api/v1/connections/con_one");
  });

  it("maps a problem response to a safe problem with header request ID", async () => {
    const fetch = vi.fn(() =>
      Promise.resolve(
        problemResponse(400, { code: "VALIDATION_FAILED" }, { requestId: "header-request-02" }),
      ),
    );
    const api = createRivoqueryApi({ baseUrl: "https://api.example", token: "tkn", fetch });

    const result = await api.getConnection("wsp_one", "con_one");
    expect(result.kind).toBe("problem");
    if (result.kind === "problem") {
      expect(result.problem.code).toBe("VALIDATION_FAILED");
      expect(result.requestId).toBe("header-request-02");
    }
  });

  it("falls back to the problem body request ID when the header is absent", async () => {
    const fetch = vi.fn(() => Promise.resolve(problemResponse(400, { requestId: "body-request-03" })));
    const api = createRivoqueryApi({ baseUrl: "https://api.example", token: "tkn", fetch });

    const result = await api.listConnections("wsp_one");
    expect(result.kind).toBe("problem");
    if (result.kind === "problem") {
      expect(result.requestId).toBe("body-request-03");
    }
  });

  it("returns a safe error when the network rejects", async () => {
    const fetch = vi.fn(() => Promise.reject(new Error("private network failure")));
    const api = createRivoqueryApi({ baseUrl: "https://api.example", token: "tkn", fetch });

    await expect(api.discoverWorkspaces()).resolves.toEqual({ kind: "error", requestId: null });
  });

  it.each(["updateConnection", "disableConnection", "deleteConnection"] as const)(
    "%s converts a rejection into a safe error",
    async (method) => {
      const fetch = vi.fn(() => Promise.reject(new Error("network down")));
      const api = createRivoqueryApi({ baseUrl: "https://api.example", token: "tkn", fetch });
      const args = {
        idempotencyKey: "idem-01J6MZ4H9ST5N7YQ2C8W3V0KFX",
        ifMatch: '"v3"',
      };

      const result =
        method === "updateConnection"
          ? await api.updateConnection("wsp_one", "con_one", { name: "x" }, args)
          : method === "disableConnection"
            ? await api.disableConnection("wsp_one", "con_one", args)
            : await api.deleteConnection("wsp_one", "con_one", args);

      expect(result).toEqual({ kind: "error", requestId: null });
    },
  );

  it("forwards an abort signal to the underlying request", async () => {
    const fetch = vi.fn(() =>
      Promise.resolve(jsonResponse({ items: [], selectionRequired: true }, 200)),
    );
    const api = createRivoqueryApi({ baseUrl: "https://api.example", token: "tkn", fetch });
    const controller = new AbortController();

    await api.discoverWorkspaces({ signal: controller.signal });

    const request = firstRequest(fetch);
    expect(request.signal).toBeInstanceOf(AbortSignal);
    expect(request.signal.aborted).toBe(false);
    controller.abort();
    expect(request.signal.aborted).toBe(true);
  });

  it("does not attach an If-Match header when discovering workspaces", async () => {
    const fetch = vi.fn(() =>
      Promise.resolve(jsonResponse({ items: [], selectionRequired: true }, 200)),
    );
    const api = createRivoqueryApi({ baseUrl: "https://api.example", token: "tkn", fetch });

    await api.discoverWorkspaces();

    const headers = requestHeaders(firstRequest(fetch));
    expect(headers.get("If-Match")).toBeNull();
    expect(headers.get("Idempotency-Key")).toBeNull();
  });
});