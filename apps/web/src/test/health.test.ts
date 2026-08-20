import { describe, expect, it, vi } from "vitest";

import { createPlatformHealthClient } from "../api/health";

interface ProblemOverrides {
  readonly code?: string;
  readonly requestId?: string;
}

function jsonResponse(
  body: unknown,
  status: number,
  requestId?: string,
  contentType = "application/json",
) {
  const headers = new Headers({ "Content-Type": contentType });
  if (requestId !== undefined) {
    headers.set("X-Request-Id", requestId);
  }
  return new Response(JSON.stringify(body), { headers, status });
}

function problemResponse(status: number, overrides: ProblemOverrides = {}) {
  return jsonResponse(
    {
      type: "https://rivoquery.example/problems/control-database-unavailable",
      title: "Control database unavailable",
      status,
      detail: "The platform cannot currently complete readiness checks.",
      instance: "/api/v1/health/ready",
      code: overrides.code ?? "CONTROL_DATABASE_UNAVAILABLE",
      requestId: overrides.requestId ?? "problem-request-01",
    },
    status,
    undefined,
    "application/problem+json",
  );
}

function requestPath(request: Request) {
  return new URL(request.url).pathname;
}

describe("generated-contract platform health client", () => {
  it("calls both generated health paths and returns the readiness request ID", async () => {
    const fetch = vi.fn((request: Request) => {
      const path = requestPath(request);
      return Promise.resolve(
        jsonResponse(
          { status: "UP" },
          200,
          path.endsWith("/ready") ? "ready-request-02" : "live-request-01",
        ),
      );
    });
    const client = createPlatformHealthClient({ baseUrl: "https://api.example", fetch });

    await expect(client.check()).resolves.toEqual({
      kind: "ready",
      requestId: "ready-request-02",
    });
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(fetch.mock.calls.map(([request]) => requestPath(request))).toEqual([
      "/api/v1/health/live",
      "/api/v1/health/ready",
    ]);
  });

  it("falls back to the validated liveness request ID when readiness returns an invalid value", async () => {
    const fetch = vi.fn((request: Request) =>
      Promise.resolve(
        jsonResponse(
          { status: "UP" },
          200,
          requestPath(request).endsWith("/ready") ? "<invalid>" : "live-request-03",
        ),
      ),
    );
    const client = createPlatformHealthClient({ baseUrl: "https://api.example", fetch });

    await expect(client.check()).resolves.toEqual({
      kind: "ready",
      requestId: "live-request-03",
    });
  });

  it("maps a typed 503 problem to not-ready and validates response identifiers", async () => {
    const fetch = vi.fn((request: Request) => {
      if (requestPath(request).endsWith("/live")) {
        return Promise.resolve(jsonResponse({ status: "UP" }, 200, "live-request-01"));
      }
      const response = problemResponse(503, {
        code: "invalid-code",
        requestId: "problem-request-02",
      });
      response.headers.set("X-Request-Id", "<invalid-header-value>");
      return Promise.resolve(response);
    });
    const client = createPlatformHealthClient({ baseUrl: "https://api.example", fetch });

    await expect(client.check()).resolves.toEqual({
      kind: "not-ready",
      code: "CONTROL_DATABASE_UNAVAILABLE",
      requestId: "problem-request-02",
    });
  });

  it("uses a validated header request ID for an API error without exposing problem detail", async () => {
    const fetch = vi.fn((request: Request) => {
      if (requestPath(request).endsWith("/live")) {
        const response = problemResponse(500, { requestId: "body-request-01" });
        response.headers.set("X-Request-Id", "header-request-02");
        return Promise.resolve(response);
      }
      return Promise.resolve(jsonResponse({ status: "UP" }, 200, "ready-request-01"));
    });
    const client = createPlatformHealthClient({ baseUrl: "https://api.example", fetch });

    await expect(client.check()).resolves.toEqual({
      kind: "error",
      requestId: "header-request-02",
    });
  });

  it("maps a contracted 406 readiness response to a safe error state", async () => {
    const fetch = vi.fn((request: Request) =>
      Promise.resolve(
        requestPath(request).endsWith("/live")
          ? jsonResponse({ status: "UP" }, 200, "live-request-01")
          : problemResponse(406, {
              code: "NOT_ACCEPTABLE",
              requestId: "ready-error-request-02",
            }),
      ),
    );
    const client = createPlatformHealthClient({ baseUrl: "https://api.example", fetch });

    await expect(client.check()).resolves.toEqual({
      kind: "error",
      requestId: "ready-error-request-02",
    });
  });

  it("fails safely when the API cannot be reached", async () => {
    const fetch = vi.fn(() =>
      Promise.reject(new Error("internal network details must not reach the UI")),
    );
    const client = createPlatformHealthClient({ baseUrl: "https://api.example", fetch });

    await expect(client.check()).resolves.toEqual({ kind: "error", requestId: null });
  });
});
