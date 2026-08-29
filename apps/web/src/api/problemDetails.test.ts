import { describe, expect, it } from "vitest";

import { normalizeEtag, normalizeRequestId, toSafeProblem } from "./problemDetails";

describe("normalizeRequestId", () => {
  it("keeps a well-formed request ID", () => {
    expect(normalizeRequestId("request-01")).toBe("request-01");
  });

  it("rejects non-string and malformed values", () => {
    expect(normalizeRequestId(undefined)).toBeNull();
    expect(normalizeRequestId(null)).toBeNull();
    expect(normalizeRequestId(42)).toBeNull();
    expect(normalizeRequestId("")).toBeNull();
    expect(normalizeRequestId("<script>")).toBeNull();
    expect(normalizeRequestId("request id")).toBeNull();
  });
});

describe("normalizeEtag", () => {
  it("keeps a quoted ETag", () => {
    expect(normalizeEtag('"ab.cd-12"')).toBe('"ab.cd-12"');
  });

  it("rejects weak and malformed ETags", () => {
    expect(normalizeEtag(undefined)).toBeNull();
    expect(normalizeEtag(null)).toBeNull();
    expect(normalizeEtag(7)).toBeNull();
    expect(normalizeEtag("unquoted")).toBeNull();
    expect(normalizeEtag('W/"weak"')).toBeNull();
    expect(normalizeEtag('"with space"')).toBeNull();
  });
});

describe("toSafeProblem", () => {
  it("maps a structurally valid problem object", () => {
    const problem = toSafeProblem({
      code: "VALIDATION_FAILED",
      title: "Validation failed",
      detail: "The request body was invalid.",
      status: 400,
      instance: "/api/v1/connections",
      requestId: "problem-request-01",
      type: "https://rivoquery.example/problems/validation-failed",
    });

    expect(problem).toEqual({
      code: "VALIDATION_FAILED",
      title: "Validation failed",
      detail: "The request body was invalid.",
      status: 400,
      instance: "/api/v1/connections",
      requestId: "problem-request-01",
    });
  });

  it("returns null for non-objects", () => {
    expect(toSafeProblem(null)).toBeNull();
    expect(toSafeProblem(undefined)).toBeNull();
    expect(toSafeProblem("nope")).toBeNull();
    expect(toSafeProblem(42)).toBeNull();
  });

  it("returns null when code is not a stable problem code", () => {
    expect(toSafeProblem({ code: "not-valid code", title: "x", detail: "y", status: 400, instance: "/" })).toBeNull();
  });

  it("returns null when bounded fields exceed their limits", () => {
    expect(
      toSafeProblem({ code: "VALIDATION_FAILED", title: "", detail: "y", status: 400, instance: "/" }),
    ).toBeNull();
    expect(
      toSafeProblem({ code: "VALIDATION_FAILED", title: "x", detail: "", status: 400, instance: "/" }),
    ).toBeNull();
    expect(
      toSafeProblem({ code: "VALIDATION_FAILED", title: "x", detail: "y", status: 400, instance: 7 }),
    ).toBeNull();
    expect(
      toSafeProblem({ code: "VALIDATION_FAILED", title: "x", detail: "y", status: 99, instance: "/" }),
    ).toBeNull();
    expect(
      toSafeProblem({ code: "VALIDATION_FAILED", title: "x", detail: "y", status: 600, instance: "/" }),
    ).toBeNull();
    expect(
      toSafeProblem({ code: "VALIDATION_FAILED", title: "x", detail: "y", status: 400.5, instance: "/" }),
    ).toBeNull();
  });

  it("nullifies a malformed request ID inside an otherwise valid problem", () => {
    const problem = toSafeProblem({
      code: "INTERNAL_ERROR",
      title: "Internal error",
      detail: "Something failed.",
      status: 500,
      instance: "/",
      requestId: "<bad>",
    });
    expect(problem?.requestId).toBeNull();
  });
});