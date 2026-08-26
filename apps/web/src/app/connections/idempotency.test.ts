import { describe, expect, it } from "vitest";

import { generateIdempotencyKey, isValidIdempotencyKey } from "./idempotency";

describe("idempotency keys", () => {
  it("generates a value that satisfies the contract pattern", () => {
    const key = generateIdempotencyKey();
    expect(isValidIdempotencyKey(key)).toBe(true);
  });

  it("rejects short, empty, and malformed values", () => {
    expect(isValidIdempotencyKey("")).toBe(false);
    expect(isValidIdempotencyKey("short")).toBe(false);
    expect(isValidIdempotencyKey("no spaces allowed 12345")).toBe(false);
    expect(isValidIdempotencyKey("0-starting-letters-ok")).toBe(true);
    expect(isValidIdempotencyKey("-leading-dash-fails")).toBe(false);
    expect(isValidIdempotencyKey("a".repeat(200))).toBe(false);
  });

  it("accepts the exact documented example", () => {
    expect(isValidIdempotencyKey("idem-01J6MZ4H9ST5N7YQ2C8W3V0KFX")).toBe(true);
  });
});