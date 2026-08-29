import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { RivoqueryApi } from "../../api/rivoqueryApi";
import { connectionDetail, operation } from "../../test/fixtures";
import { ConnectionCreateForm } from "./ConnectionCreateForm";

function apiWith(create: RivoqueryApi["createConnection"]) {
  return { createConnection: create } as RivoqueryApi;
}

function fillRequired() {
  fireEvent.change(screen.getByLabelText("Connection name"), { target: { value: "warehouse-read" } });
  fireEvent.change(screen.getByLabelText("Host"), { target: { value: "db.example.com" } });
  fireEvent.change(screen.getByLabelText("Port"), { target: { value: "5432" } });
  fireEvent.change(screen.getByLabelText("Database name"), { target: { value: "analytics" } });
  fireEvent.change(screen.getByLabelText("Database username"), { target: { value: "reader" } });
  fireEvent.change(screen.getByLabelText("Database password"), { target: { value: "s3cret" } });
  fireEvent.change(screen.getByLabelText("Allowed schemas (one per line)"), {
    target: { value: "public" },
  });
}

describe("ConnectionCreateForm", () => {
  it("shows a field error without calling the API when required fields are missing", async () => {
    const create = vi.fn();
    render(
      <ConnectionCreateForm
        api={apiWith(create)}
        workspaceId="wsp_one"
        onCancel={vi.fn()}
        onCreated={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Create connection" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Database username is required.");
    expect(create).not.toHaveBeenCalled();
  });

  it("creates a connection and clears transient secrets on success", async () => {
    const create = vi.fn().mockResolvedValue({
      kind: "ok",
      value: connectionDetail(),
      etag: '"v3"',
      requestId: "create-request-01",
    });
    const onCreated = vi.fn();
    render(
      <ConnectionCreateForm
        api={apiWith(create)}
        workspaceId="wsp_one"
        onCancel={vi.fn()}
        onCreated={onCreated}
      />,
    );

    fillRequired();
    fireEvent.change(screen.getByLabelText("Database password"), { target: { value: "s3cret" } });
    fireEvent.click(screen.getByRole("button", { name: "Create connection" }));

    await waitFor(() => { expect(onCreated).toHaveBeenCalled(); });
    const [workspaceIdArg, bodyArg, optionsArg] = create.mock.calls[0] as unknown as [
      string,
      { readonly name: string; readonly secret: { readonly username: string; readonly password: string } },
      { readonly idempotencyKey: string },
    ];
    expect(workspaceIdArg).toBe("wsp_one");
    expect(bodyArg.name).toBe("warehouse-read");
    expect(bodyArg.secret).toEqual({ username: "reader", password: "s3cret" });
    expect(optionsArg.idempotencyKey).toMatch(/^[A-Za-z0-9][A-Za-z0-9._:-]{15,127}$/);
    // Secrets are wiped after success.
    expect(screen.getByLabelText("Database password")).toHaveValue("");
    expect(onCreated).toHaveBeenCalledWith(connectionDetail());
  });

  it("routes a 202 operation outcome through onCreated", async () => {
    const create = vi.fn().mockResolvedValue({
      kind: "ok",
      value: operation({ operationId: "op_create", state: "PENDING", action: "CONNECTION_CREATE" }),
      etag: null,
      requestId: "create-op-request-01",
    });
    const onCreated = vi.fn();
    render(
      <ConnectionCreateForm
        api={apiWith(create)}
        workspaceId="wsp_one"
        onCancel={vi.fn()}
        onCreated={onCreated}
      />,
    );

    fillRequired();
    fireEvent.click(screen.getByRole("button", { name: "Create connection" }));

    await waitFor(() =>
      { expect(onCreated).toHaveBeenCalledWith(
        operation({ operationId: "op_create", state: "PENDING", action: "CONNECTION_CREATE" }),
      ); },
    );
  });

  it("surfaces a problem and clears secrets", async () => {
    const create = vi.fn().mockResolvedValue({
      kind: "problem",
      problem: {
        code: "DESTINATION_NOT_ALLOWED",
        title: "Destination not allowed",
        detail: "The host resolves to a disallowed address.",
        status: 422,
        instance: "/api/v1/connections",
        requestId: "create-problem-01",
      },
      requestId: "create-problem-01",
    });
    render(
      <ConnectionCreateForm
        api={apiWith(create)}
        workspaceId="wsp_one"
        onCancel={vi.fn()}
        onCreated={vi.fn()}
      />,
    );

    fillRequired();
    fireEvent.change(screen.getByLabelText("Database password"), { target: { value: "s3cret" } });
    fireEvent.click(screen.getByRole("button", { name: "Create connection" }));

    expect(await screen.findByRole("alert", { name: "Destination not allowed" })).toHaveTextContent(
      "DESTINATION_NOT_ALLOWED",
    );
    expect(screen.getByLabelText("Database password")).toHaveValue("");
  });

  it("shows a fatal message for a generic error and clears secrets", async () => {
    const create = vi.fn().mockResolvedValue({ kind: "error", requestId: null });
    render(
      <ConnectionCreateForm
        api={apiWith(create)}
        workspaceId="wsp_one"
        onCancel={vi.fn()}
        onCreated={vi.fn()}
      />,
    );

    fillRequired();
    fireEvent.change(screen.getByLabelText("Database password"), { target: { value: "s3cret" } });
    fireEvent.click(screen.getByRole("button", { name: "Create connection" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "RivoQuery could not create the connection.",
    );
    expect(screen.getByLabelText("Database password")).toHaveValue("");
  });

  it("cancels via the cancel button", () => {
    const onCancel = vi.fn();
    render(
      <ConnectionCreateForm
        api={apiWith(vi.fn())}
        workspaceId="wsp_one"
        onCancel={onCancel}
        onCreated={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(onCancel).toHaveBeenCalledOnce();
  });

  it("captures every field including certificates and limits", async () => {
    const create = vi.fn().mockResolvedValue({
      kind: "ok",
      value: connectionDetail(),
      etag: '"v3"',
      requestId: "create-request-01",
    });
    render(
      <ConnectionCreateForm
        api={apiWith(create)}
        workspaceId="wsp_one"
        onCancel={vi.fn()}
        onCreated={vi.fn()}
      />,
    );

    fireEvent.change(screen.getByLabelText("Connection name"), { target: { value: "orders" } });
    fireEvent.change(screen.getByLabelText("Database kind"), { target: { value: "MYSQL" } });
    fireEvent.change(screen.getByLabelText("Host"), { target: { value: "mysql.example.com" } });
    fireEvent.change(screen.getByLabelText("Port"), { target: { value: "3306" } });
    fireEvent.change(screen.getByLabelText("Database name"), { target: { value: "orders_db" } });
    fireEvent.change(screen.getByLabelText("Database username"), { target: { value: "root" } });
    fireEvent.change(screen.getByLabelText("Database password"), { target: { value: "pw" } });
    fireEvent.change(screen.getByLabelText("TLS mode"), { target: { value: "DISABLED" } });
    fireEvent.change(screen.getByLabelText("CA certificate (PEM, optional)"), {
      target: { value: "CA" },
    });
    fireEvent.change(screen.getByLabelText("Client certificate (PEM, optional)"), {
      target: { value: "CERT" },
    });
    fireEvent.change(screen.getByLabelText("Client private key (PEM, optional)"), {
      target: { value: "KEY" },
    });
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

    fireEvent.click(screen.getByRole("button", { name: "Create connection" }));

    await waitFor(() => { expect(create).toHaveBeenCalled(); });
    const [, input] = create.mock.calls[0] as unknown as [
      string,
      Record<string, unknown>,
      Record<string, unknown>,
    ];
    expect(input).toEqual(
      expect.objectContaining({
        name: "orders",
        kind: "MYSQL",
        endpoint: { host: "mysql.example.com", port: 3306, database: "orders_db" },
        tls: {
          mode: "DISABLED",
          caCertificatePem: "CA",
          clientCertificatePem: "CERT",
          clientPrivateKeyPem: "KEY",
        },
        objectPolicy: { allowedSchemas: ["inventory"], allowedTables: ["orders"] },
        limits: { statementTimeoutMs: 30000, maxRows: 75 },
        connectorOptions: { tcpKeepAlive: false },
      }),
    );
  });
});