import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { RivoqueryApi } from "../../api/rivoqueryApi";
import { connectionDetail } from "../../test/fixtures";
import { useConnection } from "./useConnection";

const detail = connectionDetail();

describe("useConnection", () => {
  it("loads a connection detail together with its ETag", async () => {
    const getConnection = vi.fn().mockResolvedValue({
      kind: "ok",
      value: detail,
      etag: '"v3"',
      requestId: "detail-request-01",
    });
    const api = { getConnection } as unknown as RivoqueryApi;

    const { result } = renderHook(() => useConnection(api, "wsp_one", "con_one"));
    await waitFor(() => { expect(result.current.state.kind).toBe("ready"); });
    if (result.current.state.kind === "ready") {
      expect(result.current.state.connection).toEqual(detail);
      expect(result.current.state.etag).toBe('"v3"');
    }
    expect(getConnection).toHaveBeenCalledWith("wsp_one", "con_one", expect.any(Object));
  });

  it("reload re-fetches the connection", async () => {
    const getConnection = vi.fn().mockResolvedValue({
      kind: "ok",
      value: detail,
      etag: '"v3"',
      requestId: "detail-request-01",
    });
    const api = { getConnection } as unknown as RivoqueryApi;

    const { result } = renderHook(() => useConnection(api, "wsp_one", "con_one"));
    await waitFor(() => { expect(result.current.state.kind).toBe("ready"); });

    act(() => { result.current.reload(); });
    await waitFor(() => { expect(getConnection).toHaveBeenCalledTimes(2); });
  });

  it("ignores a result that arrives after abort", async () => {
    let resolve: ((value: unknown) => void) | undefined;
    const getConnection = vi.fn(
      () =>
        new Promise((r) => {
          resolve = r;
        }),
    );
    const api = { getConnection } as unknown as RivoqueryApi;

    const { unmount } = renderHook(() => useConnection(api, "wsp_one", "con_one"));
    unmount();

    await act(async () => {
      resolve?.({ kind: "ok", value: detail, etag: '"v3"', requestId: null });
      await Promise.resolve();
    });
    expect(getConnection).toHaveBeenCalledOnce();
  });

  it("replace swaps in an updated connection and etag", async () => {
    const api = {
      getConnection: vi.fn().mockResolvedValue({
        kind: "ok",
        value: detail,
        etag: '"v3"',
        requestId: "detail-request-01",
      }),
    } as unknown as RivoqueryApi;

    const { result } = renderHook(() => useConnection(api, "wsp_one", "con_one"));
    await waitFor(() => { expect(result.current.state.kind).toBe("ready"); });

    act(() => { result.current.replace(connectionDetail({ name: "renamed", version: 4 }), '"v4"'); });
    if (result.current.state.kind === "ready") {
      expect(result.current.state.connection.name).toBe("renamed");
      expect(result.current.state.etag).toBe('"v4"');
      expect(result.current.state.requestId).toBeNull();
    }
  });

  it("surfaces problems and errors", async () => {
    const api = {
      getConnection: vi.fn().mockResolvedValue({
        kind: "problem",
        problem: {
          code: "CONNECTION_NOT_FOUND",
          title: "Connection not found",
          detail: "No such connection.",
          status: 404,
          instance: "/api/v1/connections/con_one",
          requestId: "detail-problem-01",
        },
        requestId: "detail-problem-01",
      }),
    } as unknown as RivoqueryApi;

    const { result } = renderHook(() => useConnection(api, "wsp_one", "con_one"));
    await waitFor(() => { expect(result.current.state.kind).toBe("problem"); });

    const errorApi = {
      getConnection: vi.fn().mockResolvedValue({ kind: "error", requestId: null }),
    } as unknown as RivoqueryApi;
    const errorHook = renderHook(() => useConnection(errorApi, "wsp_one", "con_one"));
    await waitFor(() => { expect(errorHook.result.current.state.kind).toBe("error"); });
  });
});