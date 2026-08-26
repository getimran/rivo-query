/**
 * Development-only bearer-token storage.
 *
 * The browser cannot mint RivoQuery JWTs (the HMAC signing secret lives only in
 * server config). For local development the operator supplies a pre-minted
 * token, either through the Vite env var `VITE_RIVOQUERY_DEV_TOKEN` or by
 * pasting one into the sign-in view. When a pasted token is remembered it is
 * kept in `sessionStorage` only, so it disappears with the browser session.
 *
 * Connection secrets (passwords, PEM material) must never go through this
 * module or any other persistence layer.
 */

export const DEV_TOKEN_STORAGE_KEY = "rivoquery.dev.token";

export interface DevTokenStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

function trimToken(value: string): string | null {
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}

export function readEnvDevToken(
  isDev: boolean = import.meta.env.DEV,
): string | null {
  // The browser cannot mint tokens. `VITE_*` values are inlined by Vite at
  // build time, so a production/preview bundle would otherwise ship a
  // long-lived bearer token to every visitor. Honor the env token only for
  // local development builds.
  if (!isDev) {
    return null;
  }
  const env = import.meta.env as Readonly<Record<string, string | undefined>>;
  const value = env.VITE_RIVOQUERY_DEV_TOKEN;
  if (typeof value !== "string") {
    return null;
  }
  return trimToken(value);
}

export function readStoredDevToken(
  storage: Pick<DevTokenStorage, "getItem">,
): string | null {
  const value = storage.getItem(DEV_TOKEN_STORAGE_KEY);
  if (typeof value !== "string") {
    return null;
  }
  return trimToken(value);
}

export function storeDevToken(
  storage: Pick<DevTokenStorage, "setItem">,
  token: string,
): void {
  storage.setItem(DEV_TOKEN_STORAGE_KEY, token);
}

export function clearDevToken(
  storage: Pick<DevTokenStorage, "removeItem">,
): void {
  storage.removeItem(DEV_TOKEN_STORAGE_KEY);
}
