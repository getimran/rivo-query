import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { SafeProblem } from "../../api/problemDetails";
import { EmptyState, LoadingStatus, ProblemAlert, RequestId } from "./feedback";

const problem: SafeProblem = {
  code: "VALIDATION_FAILED",
  title: "Validation failed",
  detail: "The request body was invalid.",
  status: 400,
  instance: "/api/v1/connections",
  requestId: "problem-request-01",
};

describe("RequestId", () => {
  it("renders nothing for null", () => {
    const { container } = render(<RequestId value={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("renders the request ID in a code element", () => {
    render(<RequestId value="req-01" />);
    expect(screen.getByText("req-01")).toBeInstanceOf(HTMLElement);
  });
});

describe("LoadingStatus", () => {
  it("renders a polite live status", () => {
    render(<LoadingStatus>Loading…</LoadingStatus>);
    const status = screen.getByRole("status");
    expect(status).toHaveAttribute("aria-live", "polite");
    expect(status).toHaveTextContent("Loading…");
  });
});

describe("ProblemAlert", () => {
  it("shows title, code, detail, and request ID", () => {
    render(<ProblemAlert problem={problem} />);
    expect(screen.getByRole("alert", { name: "Validation failed" })).toHaveTextContent(
      "VALIDATION_FAILED",
    );
    expect(screen.getByText("The request body was invalid.")).toBeVisible();
    expect(screen.getByText("problem-request-01")).toBeVisible();
  });

  it("omits the retry button when no handler is supplied", () => {
    render(<ProblemAlert problem={problem} />);
    expect(screen.queryByRole("button")).toBeNull();
  });

  it("renders and wires a retry button when supplied", () => {
    const onRetry = vi.fn();
    render(<ProblemAlert problem={problem} onRetry={onRetry} />);
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(onRetry).toHaveBeenCalledOnce();
  });
});

describe("EmptyState", () => {
  it("renders a title and children", () => {
    render(
      <EmptyState title="No connections yet">
        <p>Create one to begin.</p>
      </EmptyState>,
    );
    expect(screen.getByRole("heading", { name: "No connections yet" })).toBeVisible();
    expect(screen.getByText("Create one to begin.")).toBeVisible();
  });
});