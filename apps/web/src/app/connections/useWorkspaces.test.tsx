import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { RivoqueryApi } from "../../api/rivoqueryApi";
import { workspaceOne, workspaceTwo } from "../../test/fixtures";
import { useWorkspaces } from "./useWorkspaces";

function apiReturning(value: Awaited<ReturnType<RivoqueryApi["discoverWorkspaces"]>>) {
  return { discoverWorkspaces: vi.fn().mockResolvedValue(value) } as unknown as RivoqueryApi;
}

const readyResult = (nextCursor: string | null = null) => ({
  kind: "ok" as const,
  value: { items: [workspaceOne, workspaceTwo], nextCursor },
  etag: null,
  requestId: "ws-request-01",
});

describe("useWorkspaces", () => {
  it("loads workspaces into the ready state", async () => {
    const api = apiReturning(readyResult());
    const { result } = renderHook(() => useWorkspaces(api));
    expect(result.current.state.kind).toBe("loading");

    await waitFor(() => { expect(result.current.state.kind).toBe("ready"); });
    if (result.current.state.kind === "ready") {
      expect(result.current.state.workspaces).toEqual([workspaceOne, workspaceTwo]);
      expect(result.current.state.requestId).toBe("ws-request-01");
    }
  });

  it("surfaces a problem", async () => {
    const api = apiReturning({
      kind: "problem",
      problem: {
        code: "AUTHENTICATION_REQUIRED",
        title: "Authentication required",
        detail: "Supply a bearer token.",
        status: 401,
        instance: "/api/v1/workspaces",
        requestId: "ws-problem-01",
      },
      requestId: "ws-problem-01",
    });
    const { result } = renderHook(() => useWorkspaces(api));
    await waitFor(() => { expect(result.current.state.kind).toBe("problem"); });
    if (result.current.state.kind === "problem") {
      expect(result.current.state.problem.code).toBe("AUTHENTICATION_REQUIRED");
    }
  });

  it("surfaces a generic error", async () => {
    const api = apiReturning({ kind: "error", requestId: null });
    const { result } = renderHook(() => useWorkspaces(api));
    await waitFor(() => { expect(result.current.state.kind).toBe("error"); });
  });

  it("stays loading when the api is null", () => {
    const { result } = renderHook(() => useWorkspaces(null));
    expect(result.current.state.kind).toBe("loading");
  });

  it("retry triggers another load", async () => {
    const discoverWorkspaces = vi.fn().mockResolvedValue(readyResult());
    const api = { discoverWorkspaces } as unknown as RivoqueryApi;
    const { result } = renderHook(() => useWorkspaces(api));
    await waitFor(() => { expect(result.current.state.kind).toBe("ready"); });

    act(() => { result.current.retry(); });
    expect(discoverWorkspaces).toHaveBeenCalledTimes(2);
  });

  it("aborts the in-flight request on unmount", () => {
    let capturedSignal: AbortSignal | undefined;
    const api = {
      discoverWorkspaces: vi.fn(
        (options?: { signal?: AbortSignal }) =>
          new Promise(() => {
            capturedSignal = options?.signal;
          }),
      ),
    } as unknown as RivoqueryApi;

    const { unmount } = renderHook(() => useWorkspaces(api));
    unmount();
    expect(capturedSignal?.aborted).toBe(true);
  });

  it("ignores a result that resolves after abort", async () => {
    let resolve: ((value: unknown) => void) | undefined;
    const discoverWorkspaces = vi.fn(
      () =>
        new Promise((r) => {
          resolve = r;
        }),
    );
    const api = { discoverWorkspaces } as unknown as RivoqueryApi;

    const { unmount } = renderHook(() => useWorkspaces(api));
    unmount();

    await act(async () => {
      resolve?.(readyResult());
      await Promise.resolve();
    });
    expect(discoverWorkspaces).toHaveBeenCalledOnce();
  });
});