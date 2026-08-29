/**
 * Safe RFC 9457 problem-detail normalization for browser display.
 *
 * The contract guarantees the server never places credentials, certificates,
 * connection URLs, resolved addresses, policy rules, driver messages, or stack
 * traces inside a problem object. This module still validates every field before
 * it is allowed near the UI, so an unexpected or malformed body cannot smuggle
 * unsafe text into the page.
 */

const problemCodePattern = /^[A-Z][A-Z0-9_]{0,63}$/;
const requestIdPattern = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;
const etagPattern = /^"[A-Za-z0-9._:-]+"$/;

export interface SafeProblem {
  readonly code: string;
  readonly title: string;
  readonly detail: string;
  readonly status: number;
  readonly instance: string;
  readonly requestId: string | null;
}

export function normalizeRequestId(value: unknown): string | null {
  if (typeof value !== "string") {
    return null;
  }
  return requestIdPattern.test(value) ? value : null;
}

export function normalizeEtag(value: unknown): string | null {
  if (typeof value !== "string") {
    return null;
  }
  return etagPattern.test(value) ? value : null;
}

function isBoundedString(value: unknown, min: number, max: number): value is string {
  return typeof value === "string" && value.length >= min && value.length <= max;
}

/**
 * Return a display-safe problem view, or `null` when the value is not a
 * structurally valid RFC 9457 + RivoQuery problem object.
 */
export function toSafeProblem(value: unknown): SafeProblem | null {
  if (typeof value !== "object" || value === null) {
    return null;
  }

  const record = value as Record<string, unknown>;
  const code = record.code;
  const title = record.title;
  const detail = record.detail;
  const status = record.status;
  const instance = record.instance;
  const requestId = record.requestId;

  if (
    typeof code !== "string" ||
    !problemCodePattern.test(code) ||
    !isBoundedString(title, 1, 128) ||
    !isBoundedString(detail, 1, 1024) ||
    typeof status !== "number" ||
    !Number.isInteger(status) ||
    status < 100 ||
    status > 599 ||
    typeof instance !== "string"
  ) {
    return null;
  }

  return {
    code,
    title,
    detail,
    status,
    instance,
    requestId: normalizeRequestId(requestId),
  };
}
