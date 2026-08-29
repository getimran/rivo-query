import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { DevTokenStorage } from "./devSession";
import { DEV_TOKEN_STORAGE_KEY } from "./devSession";
import { useDevSession } from "./useDevSession";

function storageWith(initial: Readonly<Record<string, string>> = {}): DevTokenStorage {
  const data = new Map<string, string>(Object.entries(initial));
  return {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => {
      data.set(key, value);
    },
    removeItem: (key: string) => {
      data.delete(key);
    },
  };
}

describe("useDevSession", () => {
  it("prefers a stored session token over the env token", () => {
    vi.stubEnv("VITE_RIVOQUERY_DEV_TOKEN", "env-tkn");
    const storage = storageWith({ [DEV_TOKEN_STORAGE_KEY]: "stored-tkn" });
    const { result } = renderHook(() => useDevSession({ storage }));
    expect(result.current.token).toBe("stored-tkn");
    vi.unstubAllEnvs();
  });

  it("falls back to the env token when nothing is stored", () => {
    vi.stubEnv("VITE_RIVOQUERY_DEV_TOKEN", "env-tkn");
    const { result } = renderHook(() => useDevSession({ storage: storageWith() }));
    expect(result.current.token).toBe("env-tkn");
    vi.unstubAllEnvs();
  });

  it("signIn stores a trimmed token and exposes it", () => {
    const storage = storageWith();
    const { result } = renderHook(() => useDevSession({ storage }));

    act(() => { result.current.signIn("  pasted-tkn "); });
    expect(result.current.token).toBe("pasted-tkn");
    expect(storage.getItem(DEV_TOKEN_STORAGE_KEY)).toBe("pasted-tkn");
  });

  it("signIn ignores a blank token", () => {
    const storage = storageWith();
    const { result } = renderHook(() => useDevSession({ storage }));

    act(() => { result.current.signIn("   "); });
    expect(result.current.token).toBeNull();
    expect(storage.getItem(DEV_TOKEN_STORAGE_KEY)).toBeNull();
  });

  it("signOut clears the token and storage", () => {
    const storage = storageWith({ [DEV_TOKEN_STORAGE_KEY]: "tkn" });
    const { result } = renderHook(() => useDevSession({ storage }));

    act(() => { result.current.signOut(); });
    expect(result.current.token).toBeNull();
    expect(storage.getItem(DEV_TOKEN_STORAGE_KEY)).toBeNull();
  });
});