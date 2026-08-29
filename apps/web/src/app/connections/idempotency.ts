/**
 * Idempotency keys for mutation requests.
 *
 * The contract requires an `Idempotency-Key` header (16-128 chars from a closed
 * alphabet) on create/update/disable/delete and the permission mutations. A
 * random UUID deliberately has no user or secret content and fits that pattern.
 */

const idempotencyKeyPattern = /^[A-Za-z0-9][A-Za-z0-9._:-]{15,127}$/;

export function generateIdempotencyKey(): string {
  return globalThis.crypto.randomUUID();
}

export function isValidIdempotencyKey(value: string): boolean {
  return idempotencyKeyPattern.test(value);
}
