import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { RivoqueryApi } from "../../api/rivoqueryApi";
import { connectionDetail, connectionSummary, operation, workspaceOne } from "../../test/fixtures";
import { ConnectionManager } from "./ConnectionManager";

function fullApi(overrides: Partial<RivoqueryApi> = {}): RivoqueryApi {
  return {
    listConnections: vi.fn().mockResolvedValue({
      kind: "ok",
      value: { items: [connectionSummary()], nextCursor: null },
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
  } as RivoqueryApi;
}

function renderManager(api: RivoqueryApi, onChangeWorkspace = vi.fn()) {
  return render(
    <ConnectionManager
      api={api}
      workspace={workspaceOne}
      onChangeWorkspace={onChangeWorkspace}
    />,
  );
}

function fillAndSubmitCreate() {
  fireEvent.change(screen.getByLabelText("Connection name"), { target: { value: "warehouse-read" } });
  fireEvent.change(screen.getByLabelText("Host"), { target: { value: "db.example.com" } });
  fireEvent.change(screen.getByLabelText("Port"), { target: { value: "5432" } });
  fireEvent.change(screen.getByLabelText("Database name"), { target: { value: "analytics" } });
  fireEvent.change(screen.getByLabelText("Database username"), { target: { value: "reader" } });
  fireEvent.change(screen.getByLabelText("Database password"), { target: { value: "s3cret" } });
  fireEvent.change(screen.getByLabelText("Allowed schemas (one per line)"), {
    target: { value: "public" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Create connection" }));
}

describe("ConnectionManager", () => {
  it("routes a created connection detail to the detail view", async () => {
    const create = vi.fn().mockResolvedValue({
      kind: "ok",
      value: connectionDetail(),
      etag: '"v3"',
      requestId: "create-request-01",
    });
    const getConnection = vi.fn().mockResolvedValue({
      kind: "ok",
      value: connectionDetail(),
      etag: '"v3"',
      requestId: "detail-request-01",
    });
    const api = fullApi({ createConnection: create, getConnection });
    renderManager(api);

    await screen.findByRole("list", { name: "Connections" });
    fireEvent.click(screen.getByRole("button", { name: "New connection" }));
    fillAndSubmitCreate();

    expect(await screen.findByRole("heading", { name: "warehouse-read" })).toBeVisible();
    expect(getConnection).toHaveBeenCalledWith("wsp_one", "con_one", expect.any(Object));
  });

  it("runs an async 202 create back to the refreshed list", async () => {
    const create = vi.fn().mockResolvedValue({
      kind: "ok",
      value: operation({ operationId: "op_create", action: "CONNECTION_CREATE" }),
      etag: null,
      requestId: "create-op-request-01",
    });
    const listConnections = vi.fn().mockResolvedValue({
      kind: "ok",
      value: { items: [connectionSummary()], nextCursor: null },
      etag: null,
      requestId: "list-request-01",
    });
    const api = fullApi({ createConnection: create, listConnections });
    renderManager(api);

    await screen.findByRole("list", { name: "Connections" });
    fireEvent.click(screen.getByRole("button", { name: "New connection" }));
    fillAndSubmitCreate();

    await waitFor(() => { expect(listConnections).toHaveBeenCalledTimes(2); });
    expect(await screen.findByRole("list", { name: "Connections" })).toBeVisible();
  });

  it("changes workspace via the workspace bar", async () => {
    const onChangeWorkspace = vi.fn();
    const api = fullApi();
    renderManager(api, onChangeWorkspace);

    await screen.findByTestId("active-workspace");
    fireEvent.click(screen.getByRole("button", { name: "Change workspace" }));
    expect(onChangeWorkspace).toHaveBeenCalledOnce();
  });
});