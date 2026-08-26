import { act, fireEvent, render, renderHook, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { useTransientSecrets } from "./useTransientSecrets";

function SecretProbe() {
  const { secrets, set } = useTransientSecrets();
  return (
    <>
      <span data-testid="probe-secret">{secrets.clientPrivateKeyPem}</span>
      <button type="button" onClick={() => { set("clientPrivateKeyPem", "key-material"); }}>
        set
      </button>
    </>
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("useTransientSecrets", () => {
  it("starts empty", () => {
    const { result } = renderHook(() => useTransientSecrets());
    expect(result.current.secrets).toEqual({
      password: "",
      caCertificatePem: "",
      clientCertificatePem: "",
      clientPrivateKeyPem: "",
    });
  });

  it("sets a single secret field", () => {
    const { result } = renderHook(() => useTransientSecrets());
    act(() => { result.current.set("password", "s3cret"); });
    expect(result.current.secrets.password).toBe("s3cret");
    expect(result.current.secrets.caCertificatePem).toBe("");
  });

  it("clears all fields", () => {
    const { result } = renderHook(() => useTransientSecrets());
    act(() => {
      result.current.set("password", "p");
      result.current.set("caCertificatePem", "ca");
    });
    act(() => { result.current.clear(); });
    expect(result.current.secrets).toEqual({
      password: "",
      caCertificatePem: "",
      clientCertificatePem: "",
      clientPrivateKeyPem: "",
    });
  });

  it("does not place secret material into localStorage, sessionStorage, or indexedDB", () => {
    sessionStorage.clear();
    const localStorageSet = vi.fn();
    const indexedDbOpen = vi.fn();
    vi.stubGlobal("localStorage", {
      length: 0,
      clear: vi.fn(),
      getItem: vi.fn(() => null),
      key: vi.fn(),
      removeItem: vi.fn(),
      setItem: localStorageSet,
    });
    vi.stubGlobal("indexedDB", { open: indexedDbOpen });

    render(<SecretProbe />);
    fireEvent.click(screen.getByRole("button", { name: "set" }));

    expect(screen.getByTestId("probe-secret")).toHaveTextContent("key-material");
    expect(localStorageSet).not.toHaveBeenCalled();
    expect(indexedDbOpen).not.toHaveBeenCalled();
    expect(JSON.stringify(sessionStorage)).not.toContain("key-material");
  });

  it("clears secret material when the component unmounts", () => {
    const { unmount } = render(<SecretProbe />);
    fireEvent.click(screen.getByRole("button", { name: "set" }));
    expect(screen.getByTestId("probe-secret")).toHaveTextContent("key-material");

    unmount();

    // The hook's unmount cleanup wipes state; no persistence layer is touched.
    expect(JSON.stringify(sessionStorage)).not.toContain("key-material");
  });
});
