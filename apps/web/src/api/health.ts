import createClient from "openapi-fetch";

import type { components, paths } from "./generated/rivoquery";

type ProblemDetails = components["schemas"]["ProblemDetails"];

export type PlatformHealthResult =
  | {
      readonly kind: "ready";
      readonly requestId: string | null;
    }
  | {
      readonly kind: "not-ready";
      readonly code: string;
      readonly requestId: string | null;
    }
  | {
      readonly kind: "error";
      readonly requestId: string | null;
    };

export interface PlatformHealthClient {
  check(signal?: AbortSignal): Promise<PlatformHealthResult>;
}

interface PlatformHealthClientOptions {
  readonly baseUrl?: string;
  readonly fetch?: (input: Request) => Promise<Response>;
}

const requestIdPattern = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;
const problemCodePattern = /^[A-Z][A-Z0-9_]{0,63}$/;

function normalizeRequestId(value: string | null | undefined): string | null {
  return value !== undefined && value !== null && requestIdPattern.test(value) ? value : null;
}

function normalizeProblemCode(value: string | undefined): string {
  return value !== undefined && problemCodePattern.test(value)
    ? value
    : "CONTROL_DATABASE_UNAVAILABLE";
}

function requestIdFrom(response: Response, problem?: ProblemDetails): string | null {
  return (
    normalizeRequestId(response.headers.get("X-Request-Id")) ??
    normalizeRequestId(problem?.requestId)
  );
}

export function createPlatformHealthClient(
  options: PlatformHealthClientOptions = {},
): PlatformHealthClient {
  const client = createClient<paths>({
    baseUrl: options.baseUrl ?? "",
    ...(options.fetch === undefined ? {} : { fetch: options.fetch }),
  });

  return {
    async check(signal) {
      try {
        const requestOptions = signal === undefined ? {} : { signal };
        const [liveness, readiness] = await Promise.all([
          client.GET("/api/v1/health/live", requestOptions),
          client.GET("/api/v1/health/ready", requestOptions),
        ]);

        if (liveness.response.status !== 200 || liveness.data?.status !== "UP") {
          return {
            kind: "error",
            requestId: requestIdFrom(liveness.response, liveness.error),
          };
        }

        if (readiness.response.status === 503) {
          return {
            kind: "not-ready",
            code: normalizeProblemCode(readiness.error?.code),
            requestId: requestIdFrom(readiness.response, readiness.error),
          };
        }

        if (readiness.response.status !== 200 || readiness.data?.status !== "UP") {
          return {
            kind: "error",
            requestId: requestIdFrom(readiness.response, readiness.error),
          };
        }

        return {
          kind: "ready",
          requestId:
            requestIdFrom(readiness.response) ?? requestIdFrom(liveness.response),
        };
      } catch {
        return { kind: "error", requestId: null };
      }
    },
  };
}

export const platformHealthClient = createPlatformHealthClient();
