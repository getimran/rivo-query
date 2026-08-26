import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { RivoqueryApi } from "../../api/rivoqueryApi";
import { connectionDetail, connectionSummary, workspaceOne } from "../../test/fixtures";
import { ConnectionsApp } from "./ConnectionsApp";

const summary = connectionSummary();

function fullApi(overrides: Partial<RivoqueryApi> = {}): RivoqueryApi {
  return {
    discoverWorkspaces: vi.fn().mockResolvedValue({
      kind: "ok",
      value: { items: [workspaceOne], nextCursor: null },
      etag: null,
      requestId: "ws-request-01",
    }),
    listConnections: vi.fn().mockResolvedValue({
      kind: "ok",
      value: { items: [summary], nextCursor: null },
      etag: null,
      requestId: "list-request-01",
    }),
    getConnection: vi.fn().mockResolvedValue({
      kind: "ok",
      value: connectionDetail(),
      etag: '"v3"',
      requestId: "detail-request-01",
    }),
    createConnection: vi.fn(),
    updateConnection: vi.fn(),
    disableConnection: vi.fn(),
    deleteConnection: vi.fn(),
    ...overrides,
  };
}

describe("ConnectionsApp", () => {
  it("shows the workspace selector first", async () => {
    const discoverWorkspaces = vi.fn().mockResolvedValue({
      kind: "ok",
      value: { items: [workspaceOne], nextCursor: null },
      etag: null,
      requestId: "ws-request-01",
    });
    const listConnections = vi.fn();
    const api = fullApi({ discoverWorkspaces, listConnections });
    render(<ConnectionsApp api={api} />);

    expect(await screen.findByRole("heading", { name: "Choose a workspace" })).toBeVisible();
    expect(discoverWorkspaces).toHaveBeenCalled();
    expect(listConnections).not.toHaveBeenCalled();
  });

  it("selects a workspace and lists its connections", async () => {
    const listConnections = vi.fn().mockResolvedValue({
      kind: "ok",
      value: { items: [summary], nextCursor: null },
      etag: null,
      requestId: "list-request-01",
    });
    const api = fullApi({ listConnections });
    render(<ConnectionsApp api={api} />);

    fireEvent.click(await screen.findByRole("button", { name: /Acme Analytics/ }));

    expect(await screen.findByTestId("active-workspace")).toHaveTextContent("Acme Analytics");
    expect(await screen.findByRole("list", { name: "Connections" })).toBeVisible();
    expect(listConnections).toHaveBeenCalledWith("wsp_one", expect.any(Object));
  });

  it("opens a connection detail and returns to the list", async () => {
    const getConnection = vi.fn().mockResolvedValue({
      kind: "ok",
      value: connectionDetail(),
      etag: '"v3"',
      requestId: "detail-request-01",
    });
    const api = fullApi({ getConnection });
    render(<ConnectionsApp api={api} />);

    fireEvent.click(await screen.findByRole("button", { name: /Acme Analytics/ }));
    fireEvent.click(await screen.findByRole("button", { name: /warehouse-read/ }));

    expect(await screen.findByRole("heading", { name: "warehouse-read" })).toBeVisible();
    expect(getConnection).toHaveBeenCalledWith("wsp_one", "con_one", expect.any(Object));

    fireEvent.click(screen.getByRole("button", { name: "Back to connections" }));
    expect(await screen.findByRole("list", { name: "Connections" })).toBeVisible();
  });

  it("navigates to the create form and back", async () => {
    const api = fullApi();
    render(<ConnectionsApp api={api} />);

    fireEvent.click(await screen.findByRole("button", { name: /Acme Analytics/ }));
    fireEvent.click(await screen.findByRole("button", { name: "New connection" }));

    expect(await screen.findByRole("heading", { name: "Onboard a database connection" })).toBeVisible();

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(await screen.findByRole("list", { name: "Connections" })).toBeVisible();
  });

  it("lets the user change workspace back to the selector", async () => {
    const api = fullApi();
    render(<ConnectionsApp api={api} />);

    fireEvent.click(await screen.findByRole("button", { name: /Acme Analytics/ }));
    await screen.findByTestId("active-workspace");

    fireEvent.click(screen.getByRole("button", { name: "Change workspace" }));
    expect(await screen.findByRole("heading", { name: "Choose a workspace" })).toBeVisible();
  });

  it("shows a problem when workspace discovery fails", async () => {
    const api = fullApi({
      discoverWorkspaces: vi.fn().mockResolvedValue({
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
      }),
    });
    render(<ConnectionsApp api={api} />);

    expect(await screen.findByRole("alert", { name: "Authentication required" })).toBeVisible();
  });

  it("shows a generic error when the API is unreachable", async () => {
    const api = fullApi({
      discoverWorkspaces: vi.fn().mockResolvedValue({ kind: "error", requestId: null }),
    });
    render(<ConnectionsApp api={api} />);

    expect(await screen.findByRole("alert", { name: "Unable to load workspaces" })).toBeVisible();
  });

  it("shows the loading state while workspaces are pending", () => {
    const api = fullApi({
      discoverWorkspaces: vi.fn(() => new Promise<never>(() => undefined)),
    });
    render(<ConnectionsApp api={api} />);
    expect(screen.getByRole("status")).toHaveTextContent("Loading workspaces…");
  });

  it("shows the empty state when the workspace list is empty", async () => {
    const api = fullApi({
      discoverWorkspaces: vi.fn().mockResolvedValue({
        kind: "ok",
        value: { items: [], nextCursor: null },
        etag: null,
        requestId: "ws-request-01",
      }),
    });
    render(<ConnectionsApp api={api} />);
    expect(await screen.findByRole("heading", { name: "No workspaces" })).toBeVisible();
  });

  it("keeps the selection consistent when the workspace is gone", async () => {
    // Simulates the selected workspace disappearing on reload boundary; the
    // selector falls back to showing the list rather than rendering stale id.
    const api = fullApi();
    const { rerender } = render(<ConnectionsApp api={api} />);

    fireEvent.click(await screen.findByRole("button", { name: /Acme Analytics/ }));
    await screen.findByTestId("active-workspace");

    const emptyApi = fullApi({
      discoverWorkspaces: vi.fn().mockResolvedValue({
        kind: "ok",
        value: { items: [], nextCursor: null },
        etag: null,
        requestId: "ws-request-02",
      }),
    });
    rerender(<ConnectionsApp api={emptyApi} />);
    await waitFor(() =>
      { expect(screen.getByRole("heading", { name: "No workspaces" })).toBeVisible(); },
    );
  });
});