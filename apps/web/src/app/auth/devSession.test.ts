import { describe, expect, it, vi } from "vitest";

import {
  DEV_TOKEN_STORAGE_KEY,
  clearDevToken,
  readEnvDevToken,
  readStoredDevToken,
  storeDevToken,
} from "./devSession";

interface MemoryStorage {
  data: Map<string, string>;
  getItem: (key: string) => string | null;
}

function memoryStorage(): MemoryStorage {
  const data = new Map<string, string>();
  return {
    data,
    getItem: (key: string) => data.get(key) ?? null,
  };
}

describe("readStoredDevToken", () => {
  it("returns a trimmed token when present", () => {
    const { data, getItem } = memoryStorage();
    data.set(DEV_TOKEN_STORAGE_KEY, "  tkn-123  ");
    expect(readStoredDevToken({ getItem })).toBe("tkn-123");
  });

  it("returns null for missing, non-string, and whitespace values", () => {
    const { getItem } = memoryStorage();
    expect(readStoredDevToken({ getItem })).toBeNull();

    const nonString = memoryStorage();
    nonString.data.set(DEV_TOKEN_STORAGE_KEY, "7");
    expect(readStoredDevToken({ getItem: () => 7 as unknown as string | null })).toBeNull();

    const blank = memoryStorage();
    blank.data.set(DEV_TOKEN_STORAGE_KEY, "   ");
    expect(readStoredDevToken({ getItem: blank.getItem })).toBeNull();
  });
});

describe("readEnvDevToken", () => {
  it("returns a trimmed token from the Vite env in development", () => {
    vi.stubEnv("VITE_RIVOQUERY_DEV_TOKEN", " env-tkn ");
    expect(readEnvDevToken(true)).toBe("env-tkn");
    vi.unstubAllEnvs();
  });

  it("ignores the env token outside development builds", () => {
    vi.stubEnv("VITE_RIVOQUERY_DEV_TOKEN", " env-tkn ");
    expect(readEnvDevToken(false)).toBeNull();
    vi.unstubAllEnvs();
  });

  it("returns null when the env var is unset or empty", () => {
    vi.stubEnv("VITE_RIVOQUERY_DEV_TOKEN", "");
    expect(readEnvDevToken(true)).toBeNull();
    vi.unstubAllEnvs();
  });
});

describe("storeDevToken and clearDevToken", () => {
  it("writes and removes the token under the session key", () => {
    const store = new Map<string, string>();
    const setItem = vi.fn((key: string, value: string) => store.set(key, value));
    const removeItem = vi.fn((key: string) => store.delete(key));

    storeDevToken({ setItem }, "tkn");
    expect(store.get(DEV_TOKEN_STORAGE_KEY)).toBe("tkn");

    clearDevToken({ removeItem });
    expect(store.has(DEV_TOKEN_STORAGE_KEY)).toBe(false);
    expect(removeItem).toHaveBeenCalledWith(DEV_TOKEN_STORAGE_KEY);
  });
});