import { act, fireEvent, render, screen } from "@testing-library/react";
import axe from "axe-core";
import { describe, expect, it, vi } from "vitest";

import type { PlatformHealthClient, PlatformHealthResult } from "../api/health";
import type { RivoqueryApi } from "../api/rivoqueryApi";
import { App } from "../app/App";
import { connectionSummary, workspaceOne } from "./fixtures";

function clientReturning(result: PlatformHealthResult): PlatformHealthClient {
  return { check: vi.fn().mockResolvedValue(result) };
}

const readyClient = clientReturning({ kind: "ready", requestId: "ready-request-01" });

describe("RivoQuery application shell", () => {
  it("presents the foundation content and typed ready state through accessible landmarks", async () => {
    render(<App healthClient={readyClient} />);

    expect(
      screen.getByRole("heading", {
        level: 1,
        name: "Ask naturally. Verify before anything runs.",
      }),
    ).toBeVisible();
    expect(screen.getByRole("navigation", { name: "Primary navigation" })).toBeVisible();
    expect(screen.getByRole("link", { current: "page", name: "Overview" })).toHaveAttribute(
      "href",
      "/",
    );
    expect(await screen.findByRole("status", { name: "Platform ready" })).toHaveTextContent(
      "The API is live and the control database is available.",
    );
    expect(screen.getByText("ready-request-01")).toBeVisible();
    expect(screen.getAllByRole("article")).toHaveLength(3);
  });

  it("provides a keyboard-accessible route to the main content", () => {
    render(<App healthClient={readyClient} />);

    const skipLink = screen.getByRole("link", { name: "Skip to main content" });
    const main = screen.getByRole("main");

    expect(skipLink).toHaveAttribute("href", "#main-content");
    expect(main).toHaveAttribute("id", "main-content");
    expect(main).toHaveAttribute("tabindex", "-1");

    skipLink.focus();
    expect(skipLink).toHaveFocus();
  });

  it("has no automatically detectable accessibility violations in jsdom", async () => {
    const { container } = render(<App healthClient={readyClient} />);
    await screen.findByRole("status", { name: "Platform ready" });
    const results = await axe.run(container, {
      rules: {
        // jsdom has no layout/canvas implementation, so contrast is reviewed in a real browser.
        "color-contrast": { enabled: false },
      },
    });

    expect(results.violations).toEqual([]);
  });

  it("announces a typed not-ready state with its safe code and request ID", async () => {
    render(
      <App
        healthClient={clientReturning({
          kind: "not-ready",
          code: "CONTROL_DATABASE_UNAVAILABLE",
          requestId: "not-ready-request-01",
        })}
      />,
    );

    const alert = await screen.findByRole("alert", { name: "Platform not ready" });
    expect(alert).toHaveTextContent("CONTROL_DATABASE_UNAVAILABLE");
    expect(alert).toHaveTextContent("not-ready-request-01");
    expect(screen.getByRole("button", { name: "Check again" })).toBeEnabled();
  });

  it("announces loading while both platform probes are pending", () => {
    const check = vi.fn(
      () =>
        new Promise<PlatformHealthResult>(() => {
          // Deliberately pending to assert the loading state.
        }),
    );

    render(<App healthClient={{ check }} />);

    expect(screen.getByRole("status", { name: "Checking platform availability" })).toHaveTextContent(
      "Contacting the RivoQuery API and control database.",
    );
  });

  it("converts a rejected client promise into a safe error without a request ID", async () => {
    const check = vi.fn(() => Promise.reject(new Error("private network failure details")));

    render(<App healthClient={{ check }} />);

    const alert = await screen.findByRole("alert", { name: "Unable to check platform" });
    expect(alert).not.toHaveTextContent("private network failure details");
    expect(alert).not.toHaveTextContent("Request ID");
  });

  it("aborts an in-flight health check and ignores its result on unmount", async () => {
    let capturedSignal: AbortSignal | undefined;
    let resolveCheck: ((result: PlatformHealthResult) => void) | undefined;
    const check = vi.fn((signal?: AbortSignal) => {
      capturedSignal = signal;
      return new Promise<PlatformHealthResult>((resolve) => {
        resolveCheck = resolve;
      });
    });
    const { unmount } = render(<App healthClient={{ check }} />);

    unmount();
    expect(capturedSignal?.aborted).toBe(true);

    await act(() => {
      resolveCheck?.({ kind: "ready", requestId: "ignored-request-01" });
      return Promise.resolve();
    });
  });

  it("retries after an error and replaces it with the latest typed state", async () => {
    const check = vi
      .fn<PlatformHealthClient["check"]>()
      .mockResolvedValueOnce({ kind: "error", requestId: "failed-request-01" })
      .mockResolvedValueOnce({ kind: "ready", requestId: "retry-request-02" });

    render(<App healthClient={{ check }} />);

    const alert = await screen.findByRole("alert", { name: "Unable to check platform" });
    expect(alert).toHaveTextContent("failed-request-01");

    fireEvent.click(screen.getByRole("button", { name: "Try again" }));

    expect(await screen.findByRole("status", { name: "Platform ready" })).toHaveTextContent(
      "retry-request-02",
    );
    expect(check).toHaveBeenCalledTimes(2);
  });

  it("signs in with a pasted token and renders the connections console", async () => {
    sessionStorage.clear();

    const api: RivoqueryApi = {
      discoverWorkspaces: vi.fn().mockResolvedValue({
        kind: "ok",
        value: { items: [workspaceOne], nextCursor: null },
        etag: null,
        requestId: "ws-request-01",
      }),
      listConnections: vi.fn().mockResolvedValue({
        kind: "ok",
        value: { items: [connectionSummary()], nextCursor: null },
        etag: null,
        requestId: "list-request-01",
      }),
      getConnection: vi.fn(),
      createConnection: vi.fn(),
      updateConnection: vi.fn(),
      disableConnection: vi.fn(),
      deleteConnection: vi.fn(),
    };
    const createApi = vi.fn((token: string) => {
      expect(token).toBe("pasted-token");
      return api;
    });

    render(<App healthClient={readyClient} createApi={createApi} />);

    expect(screen.getByRole("button", { name: "Sign in" })).toBeDisabled();

    fireEvent.change(screen.getByLabelText("Development access token"), {
      target: { value: " pasted-token " },
    });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(createApi).toHaveBeenCalledWith("pasted-token");
    expect(await screen.findByRole("heading", { name: "Choose a workspace" })).toBeVisible();

    fireEvent.click(await screen.findByRole("button", { name: /Acme Analytics/ }));
    expect(await screen.findByRole("list", { name: "Connections" })).toBeVisible();
  });

  it("falls back to the default API factory when none is injected", async () => {
    sessionStorage.setItem("rivoquery.dev.token", "stored-token");

    render(<App healthClient={readyClient} />);

    // The default factory builds a real client against the relative base URL,
    // which jsdom's fetch cannot resolve, so the console surfaces a safe error.
    expect(await screen.findByRole("alert", { name: "Unable to load workspaces" })).toBeVisible();
  });
});
