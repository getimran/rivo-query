import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { RivoqueryApi } from "../../api/rivoqueryApi";
import { connectionDetail, operation } from "../../test/fixtures";
import { ConnectionUpdateForm } from "./ConnectionUpdateForm";

function apiWith(update: RivoqueryApi["updateConnection"]) {
  return { updateConnection: update } as RivoqueryApi;
}

describe("ConnectionUpdateForm", () => {
  it("prefills the form from the connection", () => {
    render(
      <ConnectionUpdateForm
        api={apiWith(vi.fn())}
        workspaceId="wsp_one"
        connection={connectionDetail()}
        etag='"v3"'
        onCancel={vi.fn()}
        onUpdated={vi.fn()}
      />,
    );

    expect(screen.getByLabelText("Connection name")).toHaveValue("warehouse-read");
    expect(screen.getByLabelText("Host")).toHaveValue("db.example.com");
    expect(screen.getByLabelText("Allowed schemas (one per line)")).toHaveValue("public");
  });

  it("blocks update when no ETag is available", async () => {
    const update = vi.fn();
    render(
      <ConnectionUpdateForm
        api={apiWith(update)}
        workspaceId="wsp_one"
        connection={connectionDetail()}
        etag={null}
        onCancel={vi.fn()}
        onUpdated={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "no version token (ETag) is available",
    );
    expect(update).not.toHaveBeenCalled();
  });

  it("updates the connection with idempotency and If-Match", async () => {
    const update = vi.fn().mockResolvedValue({
      kind: "ok",
      value: connectionDetail({ name: "renamed", version: 4 }),
      etag: '"v4"',
      requestId: "update-request-01",
    });
    const onUpdated = vi.fn();
    render(
      <ConnectionUpdateForm
        api={apiWith(update)}
        workspaceId="wsp_one"
        connection={connectionDetail()}
        etag='"v3"'
        onCancel={vi.fn()}
        onUpdated={onUpdated}
      />,
    );

    fireEvent.change(screen.getByLabelText("Connection name"), { target: { value: "renamed" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    await waitFor(() => { expect(onUpdated).toHaveBeenCalled(); });
    const updateArgs = update.mock.calls[0] as unknown as [
      string,
      string,
      { readonly name: string },
      { readonly idempotencyKey: string; readonly ifMatch: string },
    ];
    expect(updateArgs[0]).toBe("wsp_one");
    expect(updateArgs[1]).toBe("con_one");
    expect(updateArgs[2].name).toBe("renamed");
    expect(updateArgs[3].ifMatch).toBe('"v3"');
    expect(updateArgs[3].idempotencyKey).toMatch(/^[A-Za-z0-9][A-Za-z0-9._:-]{15,127}$/);
    expect(onUpdated).toHaveBeenCalledWith(
      connectionDetail({ name: "renamed", version: 4 }),
      '"v4"',
    );
  });

  it("forwards an accepted operation when the API responds 202", async () => {
    const update = vi.fn().mockResolvedValue({
      kind: "ok",
      value: operation({ action: "CONNECTION_UPDATE", operationId: "op_update_one" }),
      etag: null,
      requestId: "update-op-request-01",
    });
    const onUpdated = vi.fn();
    render(
      <ConnectionUpdateForm
        api={apiWith(update)}
        workspaceId="wsp_one"
        connection={connectionDetail()}
        etag='"v3"'
        onCancel={vi.fn()}
        onUpdated={onUpdated}
      />,
    );

    fireEvent.change(screen.getByLabelText("Connection name"), { target: { value: "renamed" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    await waitFor(() => { expect(onUpdated).toHaveBeenCalled(); });
    expect(onUpdated).toHaveBeenCalledWith(
      operation({ action: "CONNECTION_UPDATE", operationId: "op_update_one" }),
      null,
    );
  });

  it("surfaces a validation field error", async () => {
    render(
      <ConnectionUpdateForm
        api={apiWith(vi.fn())}
        workspaceId="wsp_one"
        connection={connectionDetail()}
        etag='"v3"'
        onCancel={vi.fn()}
        onUpdated={vi.fn()}
      />,
    );

    fireEvent.change(screen.getByLabelText("Connection name"), { target: { value: "  " } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Name is required.");
  });

  it("surfaces a problem response", async () => {
    const api = apiWith(
      vi.fn().mockResolvedValue({
        kind: "problem",
        problem: {
          code: "PRECONDITION_FAILED",
          title: "Precondition failed",
          detail: "The connection changed since it was loaded.",
          status: 409,
          instance: "/api/v1/connections/con_one",
          requestId: "update-problem-01",
        },
        requestId: "update-problem-01",
      }),
    );
    render(
      <ConnectionUpdateForm
        api={api}
        workspaceId="wsp_one"
        connection={connectionDetail()}
        etag='"v3"'
        onCancel={vi.fn()}
        onUpdated={vi.fn()}
      />,
    );

    fireEvent.change(screen.getByLabelText("Connection name"), { target: { value: "renamed" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    expect(await screen.findByRole("alert", { name: "Precondition failed" })).toHaveTextContent(
      "PRECONDITION_FAILED",
    );
  });

  it("shows a fatal message for a generic error", async () => {
    const api = apiWith(vi.fn().mockResolvedValue({ kind: "error", requestId: null }));
    render(
      <ConnectionUpdateForm
        api={api}
        workspaceId="wsp_one"
        connection={connectionDetail()}
        etag='"v3"'
        onCancel={vi.fn()}
        onUpdated={vi.fn()}
      />,
    );

    fireEvent.change(screen.getByLabelText("Connection name"), { target: { value: "renamed" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "RivoQuery could not update the connection.",
    );
  });

  it("captures edits to every field", async () => {
    const update = vi.fn().mockResolvedValue({
      kind: "ok",
      value: connectionDetail({ name: "renamed", version: 4 }),
      etag: '"v4"',
      requestId: "update-request-01",
    });
    render(
      <ConnectionUpdateForm
        api={apiWith(update)}
        workspaceId="wsp_one"
        connection={connectionDetail()}
        etag='"v3"'
        onCancel={vi.fn()}
        onUpdated={vi.fn()}
      />,
    );

    fireEvent.change(screen.getByLabelText("Connection name"), { target: { value: "renamed" } });
    fireEvent.change(screen.getByLabelText("Host"), { target: { value: "other.example.com" } });
    fireEvent.change(screen.getByLabelText("Port"), { target: { value: "5433" } });
    fireEvent.change(screen.getByLabelText("Database name"), { target: { value: "db2" } });
    fireEvent.change(screen.getByLabelText("TLS mode"), { target: { value: "DISABLED" } });
    fireEvent.change(screen.getByLabelText("Allowed schemas (one per line)"), {
      target: { value: "inventory" },
    });
    fireEvent.change(screen.getByLabelText("Allowed tables (one per line; empty means all)"), {
      target: { value: "orders" },
    });
    fireEvent.change(screen.getByLabelText("Statement timeout (ms)"), {
      target: { value: "30000" },
    });
    fireEvent.change(screen.getByLabelText("Maximum rows"), { target: { value: "75" } });
    fireEvent.click(screen.getByLabelText("TCP keep-alive"));

    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    await waitFor(() => { expect(update).toHaveBeenCalled(); });
    const [, , input] = update.mock.calls[0] as unknown as [
      string,
      string,
      Record<string, unknown>,
      Record<string, unknown>,
    ];
    expect(input).toEqual(
      expect.objectContaining({
        name: "renamed",
        endpoint: { host: "other.example.com", port: 5433, database: "db2" },
        tlsMode: "DISABLED",
        objectPolicy: { allowedSchemas: ["inventory"], allowedTables: ["orders"] },
        limits: { statementTimeoutMs: 30000, maxRows: 75 },
        connectorOptions: { tcpKeepAlive: false },
      }),
    );
  });
});