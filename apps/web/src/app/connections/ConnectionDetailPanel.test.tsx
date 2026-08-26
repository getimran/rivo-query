import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { RivoqueryApi } from "../../api/rivoqueryApi";
import { connectionDetail, operation } from "../../test/fixtures";
import { ConnectionDetailPanel } from "./ConnectionDetailPanel";

interface ApiOverrides {
  getConnection?: RivoqueryApi["getConnection"];
  disableConnection?: RivoqueryApi["disableConnection"];
  deleteConnection?: RivoqueryApi["deleteConnection"];
  updateConnection?: RivoqueryApi["updateConnection"];
}

function apiWith(overrides: ApiOverrides): RivoqueryApi {
  return {
    getConnection: overrides.getConnection ?? vi.fn(),
    disableConnection: overrides.disableConnection ?? vi.fn(),
    deleteConnection: overrides.deleteConnection ?? vi.fn(),
    updateConnection: overrides.updateConnection ?? vi.fn(),
  } as RivoqueryApi;
}

const okDetail = {
  kind: "ok" as const,
  value: connectionDetail(),
  etag: '"v3"',
  requestId: "detail-request-01",
};

describe("ConnectionDetailPanel", () => {
  it("renders the loaded connection detail without secret material", async () => {
    const api = apiWith({ getConnection: vi.fn().mockResolvedValue(okDetail) });
    render(
      <ConnectionDetailPanel
        api={api}
        workspaceId="wsp_one"
        connectionId="con_one"
        onBack={vi.fn()}
        onListChanged={vi.fn()}
      />,
    );

    expect(await screen.findByRole("heading", { name: "warehouse-read" })).toBeVisible();
    expect(screen.getByText("db.example.com")).toBeVisible();
    expect(screen.getByText("5432")).toBeVisible();
    expect(screen.getByText("detail-request-01")).toBeVisible();
  });

  it("shows not-yet-supported actions as disabled with explanations", async () => {
    const api = apiWith({ getConnection: vi.fn().mockResolvedValue(okDetail) });
    render(
      <ConnectionDetailPanel
        api={api}
        workspaceId="wsp_one"
        connectionId="con_one"
        onBack={vi.fn()}
        onListChanged={vi.fn()}
      />,
    );

    await screen.findByRole("heading", { name: "warehouse-read" });
    expect(screen.getByRole("button", { name: "Enable" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Test connection" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Rotate secret" })).toBeDisabled();
    expect(screen.getAllByText(/not supported by the API yet/i)).toHaveLength(3);
  });

  it("disables a connection and replaces the loaded detail", async () => {
    const disable = vi.fn().mockResolvedValue({
      kind: "ok",
      value: connectionDetail({ state: "DISABLED", version: 4 }),
      etag: '"v4"',
      requestId: "disable-request-01",
    });
    const onListChanged = vi.fn();
    const api = apiWith({ getConnection: vi.fn().mockResolvedValue(okDetail), disableConnection: disable });
    render(
      <ConnectionDetailPanel
        api={api}
        workspaceId="wsp_one"
        connectionId="con_one"
        onBack={vi.fn()}
        onListChanged={onListChanged}
      />,
    );

    await screen.findByRole("heading", { name: "warehouse-read" });
    fireEvent.click(screen.getByRole("button", { name: "Disable" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirm disable" }));

    await waitFor(() => { expect(onListChanged).toHaveBeenCalled(); });
    const disableArgs = disable.mock.calls[0] as unknown as [
      string,
      string,
      { readonly idempotencyKey: string; readonly ifMatch: string },
    ];
    expect(disableArgs[0]).toBe("wsp_one");
    expect(disableArgs[1]).toBe("con_one");
    expect(disableArgs[2].ifMatch).toBe('"v3"');
    expect(disableArgs[2].idempotencyKey).toMatch(/^[A-Za-z0-9][A-Za-z0-9._:-]{15,127}$/);
    expect(await screen.findByRole("status")).toHaveTextContent("Connection disabled.");
  });

  it("deletes a connection and navigates back", async () => {
    const del = vi.fn().mockResolvedValue({ kind: "ok", value: null, etag: null, requestId: null });
    const onBack = vi.fn();
    const api = apiWith({ getConnection: vi.fn().mockResolvedValue(okDetail), deleteConnection: del });
    render(
      <ConnectionDetailPanel
        api={api}
        workspaceId="wsp_one"
        connectionId="con_one"
        onBack={onBack}
        onListChanged={vi.fn()}
      />,
    );

    await screen.findByRole("heading", { name: "warehouse-read" });
    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirm delete" }));

    await waitFor(() => { expect(onBack).toHaveBeenCalled(); });
    const deleteArgs = del.mock.calls[0] as unknown as [
      string,
      string,
      { readonly idempotencyKey: string; readonly ifMatch: string },
    ];
    expect(deleteArgs[0]).toBe("wsp_one");
    expect(deleteArgs[1]).toBe("con_one");
    expect(deleteArgs[2].ifMatch).toBe('"v3"');
    expect(deleteArgs[2].idempotencyKey).toMatch(/^[A-Za-z0-9][A-Za-z0-9._:-]{15,127}$/);
  });

  it("shows a loading state initially", () => {
    const api = apiWith({
      getConnection: vi.fn(() => new Promise<never>(() => undefined)),
    });
    render(
      <ConnectionDetailPanel
        api={api}
        workspaceId="wsp_one"
        connectionId="con_one"
        onBack={vi.fn()}
        onListChanged={vi.fn()}
      />,
    );
    expect(screen.getByRole("heading", { name: "Loading connection…" })).toBeVisible();
  });

  it("renders a problem state with retry", async () => {
    const api = apiWith({
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
    });
    render(
      <ConnectionDetailPanel
        api={api}
        workspaceId="wsp_one"
        connectionId="con_one"
        onBack={vi.fn()}
        onListChanged={vi.fn()}
      />,
    );

    expect(await screen.findByRole("alert", { name: "Connection not found" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Try again" })).toBeVisible();
  });

  it("renders an error state", async () => {
    const api = apiWith({ getConnection: vi.fn().mockResolvedValue({ kind: "error", requestId: null }) });
    render(
      <ConnectionDetailPanel
        api={api}
        workspaceId="wsp_one"
        connectionId="con_one"
        onBack={vi.fn()}
        onListChanged={vi.fn()}
      />,
    );

    expect(await screen.findByRole("alert", { name: "Unable to load connection" })).toBeVisible();
  });

  it("disables mutable actions when the connection is already disabled", async () => {
    const api = apiWith({
      getConnection: vi.fn().mockResolvedValue({
        kind: "ok",
        value: connectionDetail({ state: "DISABLED" }),
        etag: '"v4"',
        requestId: "detail-request-01",
      }),
    });
    render(
      <ConnectionDetailPanel
        api={api}
        workspaceId="wsp_one"
        connectionId="con_one"
        onBack={vi.fn()}
        onListChanged={vi.fn()}
      />,
    );

    await screen.findByRole("heading", { name: "warehouse-read" });
    expect(screen.getByRole("button", { name: "Disable" })).toBeDisabled();
  });

  it("disables delete while the connection is deleting", async () => {
    const api = apiWith({
      getConnection: vi.fn().mockResolvedValue({
        kind: "ok",
        value: connectionDetail({ state: "DELETING" }),
        etag: '"v4"',
        requestId: "detail-request-01",
      }),
    });
    render(
      <ConnectionDetailPanel
        api={api}
        workspaceId="wsp_one"
        connectionId="con_one"
        onBack={vi.fn()}
        onListChanged={vi.fn()}
      />,
    );

    await screen.findByRole("heading", { name: "warehouse-read" });
    expect(screen.getByRole("button", { name: "Disable" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Delete" })).toBeDisabled();
  });

  it("opens the edit form and cancels back to the detail view", async () => {
    const api = apiWith({ getConnection: vi.fn().mockResolvedValue(okDetail) });
    render(
      <ConnectionDetailPanel
        api={api}
        workspaceId="wsp_one"
        connectionId="con_one"
        onBack={vi.fn()}
        onListChanged={vi.fn()}
      />,
    );

    await screen.findByRole("heading", { name: "warehouse-read" });
    fireEvent.click(screen.getByRole("button", { name: "Edit" }));
    expect(screen.getByRole("heading", { name: "warehouse-read" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Save changes" })).toBeVisible();

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(await screen.findByRole("button", { name: "Edit" })).toBeVisible();
  });

  it("cancels a disable confirmation without calling the API", async () => {
    const disable = vi.fn();
    const api = apiWith({ getConnection: vi.fn().mockResolvedValue(okDetail), disableConnection: disable });
    render(
      <ConnectionDetailPanel
        api={api}
        workspaceId="wsp_one"
        connectionId="con_one"
        onBack={vi.fn()}
        onListChanged={vi.fn()}
      />,
    );

    await screen.findByRole("heading", { name: "warehouse-read" });
    fireEvent.click(screen.getByRole("button", { name: "Disable" }));
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(disable).not.toHaveBeenCalled();
  });

  it("surfaces a problem from a disable attempt", async () => {
    const disable = vi.fn().mockResolvedValue({
      kind: "problem",
      problem: {
        code: "CONNECTION_STATE_CONFLICT",
        title: "State conflict",
        detail: "Already disabled.",
        status: 409,
        instance: "/api/v1/connections/con_one/disable",
        requestId: "disable-problem-01",
      },
      requestId: "disable-problem-01",
    });
    const api = apiWith({ getConnection: vi.fn().mockResolvedValue(okDetail), disableConnection: disable });
    render(
      <ConnectionDetailPanel
        api={api}
        workspaceId="wsp_one"
        connectionId="con_one"
        onBack={vi.fn()}
        onListChanged={vi.fn()}
      />,
    );

    await screen.findByRole("heading", { name: "warehouse-read" });
    fireEvent.click(screen.getByRole("button", { name: "Disable" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirm disable" }));

    expect(await screen.findByRole("alert", { name: "State conflict" })).toBeVisible();
  });

  it("surfaces a fatal error from a delete attempt", async () => {
    const del = vi.fn().mockResolvedValue({ kind: "error", requestId: null });
    const api = apiWith({ getConnection: vi.fn().mockResolvedValue(okDetail), deleteConnection: del });
    render(
      <ConnectionDetailPanel
        api={api}
        workspaceId="wsp_one"
        connectionId="con_one"
        onBack={vi.fn()}
        onListChanged={vi.fn()}
      />,
    );

    await screen.findByRole("heading", { name: "warehouse-read" });
    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirm delete" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "RivoQuery could not complete that action.",
    );
  });

  it("reloads the detail instead of crash-rendering an operation accepted for update", async () => {
    const update = vi.fn().mockResolvedValue({
      kind: "ok",
      value: operation({ action: "CONNECTION_UPDATE", operationId: "op_update_one" }),
      etag: null,
      requestId: "update-op-request-01",
    });
    const getConnection = vi.fn()
      .mockResolvedValueOnce(okDetail)
      .mockResolvedValueOnce({
        kind: "ok",
        value: connectionDetail({ version: 4 }),
        etag: '"v4"',
        requestId: "detail-request-02",
      });
    const api = apiWith({ getConnection, updateConnection: update });
    render(
      <ConnectionDetailPanel
        api={api}
        workspaceId="wsp_one"
        connectionId="con_one"
        onBack={vi.fn()}
        onListChanged={vi.fn()}
      />,
    );

    await screen.findByRole("heading", { name: "warehouse-read" });
    fireEvent.click(screen.getByRole("button", { name: "Edit" }));
    fireEvent.change(screen.getByLabelText("Connection name"), { target: { value: "renamed" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    await waitFor(() => { expect(getConnection).toHaveBeenCalledTimes(2); });
    expect(await screen.findByText(/Update accepted/i)).toBeVisible();
    expect(await screen.findByRole("heading", { name: "warehouse-read" })).toBeVisible();
  });

  it("disables defense actions and explains when no ETag is available", async () => {
    const api = apiWith({
      getConnection: vi.fn().mockResolvedValue({
        kind: "ok",
        value: connectionDetail(),
        etag: null,
        requestId: "detail-request-01",
      }),
    });
    render(
      <ConnectionDetailPanel
        api={api}
        workspaceId="wsp_one"
        connectionId="con_one"
        onBack={vi.fn()}
        onListChanged={vi.fn()}
      />,
    );

    await screen.findByRole("heading", { name: "warehouse-read" });
    expect(screen.getByRole("button", { name: "Disable" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Delete" })).toBeDisabled();
    expect(screen.getByText(/no version token \(ETag\) is available/i)).toBeVisible();
  });

  it("moves focus into the confirm bar and returns it on cancel", async () => {
    const disable = vi.fn();
    const api = apiWith({ getConnection: vi.fn().mockResolvedValue(okDetail), disableConnection: disable });
    render(
      <ConnectionDetailPanel
        api={api}
        workspaceId="wsp_one"
        connectionId="con_one"
        onBack={vi.fn()}
        onListChanged={vi.fn()}
      />,
    );

    await screen.findByRole("heading", { name: "warehouse-read" });
    fireEvent.click(screen.getByRole("button", { name: "Disable" }));

    expect(screen.getByRole("dialog", { name: "Confirm disable" })).toBeVisible();
    expect(document.activeElement).toBe(screen.getByRole("dialog", { name: "Confirm disable" }));

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(disable).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Disable" }));
  });
});