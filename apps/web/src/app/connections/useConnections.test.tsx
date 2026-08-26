import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { RivoqueryApi } from "../../api/rivoqueryApi";
import { connectionSummary } from "../../test/fixtures";
import { useConnections } from "./useConnections";

const summary = connectionSummary();

describe("useConnections", () => {
  it("loads connections with pagination info", async () => {
    const listConnections = vi.fn().mockResolvedValue({
      kind: "ok",
      value: { items: [summary], nextCursor: "cursor-01" },
      etag: null,
      requestId: "list-request-01",
    });
    const api = { listConnections } as unknown as RivoqueryApi;
    const { result } = renderHook(() => useConnections(api, "wsp_one"));
    await waitFor(() => { expect(result.current.state.kind).toBe("ready"); });
    if (result.current.state.kind === "ready") {
      expect(result.current.state.items).toEqual([summary]);
      expect(result.current.state.nextCursor).toBe("cursor-01");
    }
    expect(listConnections).toHaveBeenCalledWith("wsp_one", expect.anything());
  });

  it("passes a signal and scopes calls to the workspace", async () => {
    const listConnections = vi.fn().mockResolvedValue({ kind: "error", requestId: null });
    const api = { listConnections } as unknown as RivoqueryApi;
    renderHook(() => useConnections(api, "wsp_one"));
    await waitFor(() => { expect(listConnections).toHaveBeenCalled(); });
    expect(listConnections).toHaveBeenCalledWith("wsp_one", expect.any(Object));
  });

  it("stays loading when the workspace is null", () => {
    const listConnections = vi.fn();
    const api = { listConnections } as unknown as RivoqueryApi;
    const { result } = renderHook(() => useConnections(api, null));
    expect(result.current.state.kind).toBe("loading");
    expect(listConnections).not.toHaveBeenCalled();
  });

  it("reloads on demand", async () => {
    const listConnections = vi.fn().mockResolvedValue({
      kind: "ok",
      value: { items: [summary], nextCursor: null },
      etag: null,
      requestId: "list-request-01",
    });
    const api = { listConnections } as unknown as RivoqueryApi;
    const { result } = renderHook(() => useConnections(api, "wsp_one"));
    await waitFor(() => { expect(result.current.state.kind).toBe("ready"); });

    act(() => { result.current.reload(); });
    await waitFor(() => { expect(listConnections).toHaveBeenCalledTimes(2); });
  });
});