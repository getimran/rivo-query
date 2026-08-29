import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { connectionSummary } from "../../test/fixtures";
import type { ConnectionsViewState } from "./useConnections";
import { ConnectionList } from "./ConnectionList";

function renderList(state: ConnectionsViewState, onOpen = vi.fn()) {
  return render(
    <ConnectionList state={state} onRetry={vi.fn()} onCreate={vi.fn()} onOpen={onOpen} />,
  );
}

describe("ConnectionList", () => {
  it("renders the loading state", () => {
    renderList({ kind: "loading" });
    expect(screen.getByRole("status")).toHaveTextContent("Loading connections…");
  });

  it("renders a ready list and opens a connection", () => {
    const onOpen = vi.fn();
    renderList(
      {
        kind: "ready",
        items: [connectionSummary(), connectionSummary({ connectionId: "con_two", name: "orders" })],
        nextCursor: null,
        requestId: "list-request-01",
      },
      onOpen,
    );

    expect(screen.getByRole("list", { name: "Connections" })).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: /warehouse-read/ }));
    expect(onOpen).toHaveBeenCalledWith("con_one");
    expect(screen.getByText("list-request-01")).toBeVisible();
  });

  it("shows the empty state when the list is empty", () => {
    renderList({ kind: "ready", items: [], nextCursor: null, requestId: null });
    expect(screen.getByRole("heading", { name: "No connections yet" })).toBeVisible();
  });

  it("announces pagination with a next cursor", () => {
    renderList({
      kind: "ready",
      items: [connectionSummary()],
      nextCursor: "cursor-01",
      requestId: null,
    });
    expect(screen.getByText("Only the first page of connections is shown.")).toBeVisible();
  });

  it("renders a problem alert with retry", () => {
    renderList({
      kind: "problem",
      problem: {
        code: "WORKSPACE_ACCESS_DENIED",
        title: "Access denied",
        detail: "You cannot access this workspace.",
        status: 403,
        instance: "/api/v1/connections",
        requestId: "list-problem-01",
      },
    });
    expect(screen.getByRole("alert", { name: "Access denied" })).toHaveTextContent(
      "WORKSPACE_ACCESS_DENIED",
    );
    expect(screen.getByRole("button", { name: "Try again" })).toBeVisible();
  });

  it("renders a generic error with retry", () => {
    renderList({ kind: "error" });
    expect(screen.getByRole("alert", { name: "Unable to load connections" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Try again" })).toBeVisible();
  });

  it("wires the create button", () => {
    const onCreate = vi.fn();
    render(
      <ConnectionList
        state={{ kind: "loading" }}
        onRetry={vi.fn()}
        onCreate={onCreate}
        onOpen={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "New connection" }));
    expect(onCreate).toHaveBeenCalledOnce();
  });
});