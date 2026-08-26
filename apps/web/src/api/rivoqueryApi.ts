import createClient from "openapi-fetch";

import type { components, paths } from "./generated/rivoquery";
import type { SafeProblem } from "./problemDetails";
import { normalizeEtag, normalizeRequestId, toSafeProblem } from "./problemDetails";

type ConnectionSummary = components["schemas"]["ConnectionSummary"];
type ConnectionDetail = components["schemas"]["ConnectionDetail"];
type Operation = components["schemas"]["Operation"];
type WorkspaceSummary = components["schemas"]["WorkspaceSummary"];
type WorkspacePage = components["schemas"]["WorkspacePage"];
type ConnectionPage = components["schemas"]["ConnectionPage"];

/**
 * Recursive mutable projection of a generated (readonly-immutable) schema type.
 * openapi-fetch expects mutable request bodies; this keeps every DTO shape
 * sourced exclusively from the generated client instead of a handwritten copy.
 */
type DeepWritable<T> = T extends readonly (infer U)[]
  ? DeepWritable<U>[]
  : T extends object
    ? { -readonly [K in keyof T]: DeepWritable<T[K]> }
    : T;

export type CreateConnectionInput = DeepWritable<
  components["schemas"]["CreateConnectionRequest"]
>;
export type UpdateConnectionInput = DeepWritable<
  components["schemas"]["UpdateConnectionRequest"]
>;

export interface PageValue<T> {
  readonly items: readonly T[];
  readonly nextCursor: string | null;
}

export type CreateConnectionOutcome = ConnectionDetail | Operation;

export type UpdateConnectionOutcome = ConnectionDetail | Operation;

export type RivoqueryResult<T> =
  | {
      readonly kind: "ok";
      readonly value: T;
      readonly etag: string | null;
      readonly requestId: string | null;
    }
  | {
      readonly kind: "problem";
      readonly problem: SafeProblem;
      readonly requestId: string | null;
    }
  | {
      readonly kind: "error";
      readonly requestId: string | null;
    };

export interface MutationOptions {
  readonly idempotencyKey: string;
  readonly ifMatch?: string;
  readonly signal?: AbortSignal;
}

export interface RivoqueryApi {
  discoverWorkspaces(options?: {
    readonly signal?: AbortSignal;
  }): Promise<RivoqueryResult<PageValue<WorkspaceSummary>>>;

  listConnections(
    workspaceId: string,
    options?: { readonly signal?: AbortSignal },
  ): Promise<RivoqueryResult<PageValue<ConnectionSummary>>>;

  getConnection(
    workspaceId: string,
    connectionId: string,
    options?: { readonly signal?: AbortSignal },
  ): Promise<RivoqueryResult<ConnectionDetail>>;

  createConnection(
    workspaceId: string,
    input: CreateConnectionInput,
    options: { readonly idempotencyKey: string; readonly signal?: AbortSignal },
  ): Promise<RivoqueryResult<CreateConnectionOutcome>>;

  updateConnection(
    workspaceId: string,
    connectionId: string,
    input: UpdateConnectionInput,
    options: { readonly idempotencyKey: string; readonly ifMatch: string; readonly signal?: AbortSignal },
  ): Promise<RivoqueryResult<UpdateConnectionOutcome>>;

  disableConnection(
    workspaceId: string,
    connectionId: string,
    options: { readonly idempotencyKey: string; readonly ifMatch: string; readonly signal?: AbortSignal },
  ): Promise<RivoqueryResult<ConnectionDetail>>;

  deleteConnection(
    workspaceId: string,
    connectionId: string,
    options: { readonly idempotencyKey: string; readonly ifMatch: string; readonly signal?: AbortSignal },
  ): Promise<RivoqueryResult<Operation>>;
}

interface RivoqueryApiOptions {
  readonly baseUrl?: string;
  readonly token: string;
  readonly fetch?: (input: Request) => Promise<Response>;
}

function requestIdFromResponse(
  response: Response,
  problem: SafeProblem | null = null,
): string | null {
  return (
    normalizeRequestId(response.headers.get("X-Request-Id")) ??
    problem?.requestId ??
    null
  );
}

function mapResult<T>(
  data: T | undefined,
  error: unknown,
  response: Response,
): RivoqueryResult<T> {
  if (data !== undefined) {
    return {
      kind: "ok",
      value: data,
      etag: normalizeEtag(response.headers.get("ETag")),
      requestId: requestIdFromResponse(response),
    };
  }

  const problem = toSafeProblem(error);
  if (problem !== null) {
    return {
      kind: "problem",
      problem,
      requestId: requestIdFromResponse(response, problem),
    };
  }

  return { kind: "error", requestId: requestIdFromResponse(response) };
}

function requestInit(signal: AbortSignal | undefined): { readonly signal?: AbortSignal } {
  return signal === undefined ? {} : { signal };
}

function pageValue<T>(items: readonly T[], nextCursor: string | null | undefined): PageValue<T> {
  return { items, nextCursor: nextCursor ?? null };
}

const defaultPageLimit = 100;

export function createRivoqueryApi(options: RivoqueryApiOptions): RivoqueryApi {
  const client = createClient<paths>({
    baseUrl: options.baseUrl ?? "",
    headers: { Authorization: `Bearer ${options.token}` },
    ...(options.fetch === undefined ? {} : { fetch: options.fetch }),
  });

  return {
    async discoverWorkspaces({ signal } = {}) {
      try {
        const result = await client.GET("/api/v1/workspaces", {
          params: { query: { limit: defaultPageLimit } },
          ...requestInit(signal),
        });
        const page = result.data as WorkspacePage | undefined;
        return mapResult(
          page === undefined ? undefined : pageValue(page.items, page.nextCursor),
          result.error,
          result.response,
        );
      } catch {
        return { kind: "error", requestId: null };
      }
    },

    async listConnections(workspaceId, { signal } = {}) {
      try {
        const result = await client.GET("/api/v1/connections", {
          params: {
            query: { limit: defaultPageLimit },
            header: { "X-RivoQuery-Workspace-Id": workspaceId },
          },
          ...requestInit(signal),
        });
        const page = result.data as ConnectionPage | undefined;
        return mapResult(
          page === undefined ? undefined : pageValue(page.items, page.nextCursor),
          result.error,
          result.response,
        );
      } catch {
        return { kind: "error", requestId: null };
      }
    },

    async getConnection(workspaceId, connectionId, { signal } = {}) {
      try {
        const result = await client.GET("/api/v1/connections/{connectionId}", {
          params: {
            path: { connectionId },
            header: { "X-RivoQuery-Workspace-Id": workspaceId },
          },
          ...requestInit(signal),
        });
        return mapResult(result.data as ConnectionDetail | undefined, result.error, result.response);
      } catch {
        return { kind: "error", requestId: null };
      }
    },

    async createConnection(workspaceId, input, options) {
      try {
        const result = await client.POST("/api/v1/connections", {
          params: {
            header: {
              "X-RivoQuery-Workspace-Id": workspaceId,
              "Idempotency-Key": options.idempotencyKey,
            },
          },
          body: input,
          ...requestInit(options.signal),
        });
        return mapResult(
          result.data as CreateConnectionOutcome | undefined,
          result.error,
          result.response,
        );
      } catch {
        return { kind: "error", requestId: null };
      }
    },

    async updateConnection(workspaceId, connectionId, input, options) {
      try {
        const result = await client.PATCH("/api/v1/connections/{connectionId}", {
          params: {
            path: { connectionId },
            header: {
              "X-RivoQuery-Workspace-Id": workspaceId,
              "Idempotency-Key": options.idempotencyKey,
              "If-Match": options.ifMatch,
            },
          },
          body: input,
          ...requestInit(options.signal),
        });
        return mapResult(result.data as UpdateConnectionOutcome | undefined, result.error, result.response);
      } catch {
        return { kind: "error", requestId: null };
      }
    },

    async disableConnection(workspaceId, connectionId, options) {
      try {
        const result = await client.POST("/api/v1/connections/{connectionId}/disable", {
          params: {
            path: { connectionId },
            header: {
              "X-RivoQuery-Workspace-Id": workspaceId,
              "Idempotency-Key": options.idempotencyKey,
              "If-Match": options.ifMatch,
            },
          },
          ...requestInit(options.signal),
        });
        return mapResult(result.data as ConnectionDetail | undefined, result.error, result.response);
      } catch {
        return { kind: "error", requestId: null };
      }
    },

    async deleteConnection(workspaceId, connectionId, options) {
      try {
        const result = await client.DELETE("/api/v1/connections/{connectionId}", {
          params: {
            path: { connectionId },
            header: {
              "X-RivoQuery-Workspace-Id": workspaceId,
              "Idempotency-Key": options.idempotencyKey,
              "If-Match": options.ifMatch,
            },
          },
          ...requestInit(options.signal),
        });
        return mapResult(result.data as Operation | undefined, result.error, result.response);
      } catch {
        return { kind: "error", requestId: null };
      }
    },
  };
}
