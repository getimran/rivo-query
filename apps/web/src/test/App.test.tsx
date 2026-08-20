import { act, fireEvent, render, screen } from "@testing-library/react";
import axe from "axe-core";
import { describe, expect, it, vi } from "vitest";

import type { PlatformHealthClient, PlatformHealthResult } from "../api/health";
import { App } from "../app/App";

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
});
