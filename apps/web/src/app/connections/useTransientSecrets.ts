import { useCallback, useEffect, useState } from "react";

/**
 * Write-only connection secret material (passwords and PEM blobs).
 *
 * These values live only in React state. They are never written to
 * localStorage/sessionStorage/indexedDB, never logged, and are wiped on explicit
 * clear, on unmount, and (by the callers) on success and on error.
 */

export interface ConnectionSecretFields {
  readonly password: string;
  readonly caCertificatePem: string;
  readonly clientCertificatePem: string;
  readonly clientPrivateKeyPem: string;
}

export type ConnectionSecretKey = keyof ConnectionSecretFields;

export const connectionSecretKeys: readonly ConnectionSecretKey[] = [
  "password",
  "caCertificatePem",
  "clientCertificatePem",
  "clientPrivateKeyPem",
];

function emptySecrets(): ConnectionSecretFields {
  return {
    password: "",
    caCertificatePem: "",
    clientCertificatePem: "",
    clientPrivateKeyPem: "",
  };
}

export interface TransientSecrets {
  readonly secrets: ConnectionSecretFields;
  readonly set: (key: ConnectionSecretKey, value: string) => void;
  readonly clear: () => void;
}

export function useTransientSecrets(): TransientSecrets {
  const [secrets, setSecrets] = useState<ConnectionSecretFields>(emptySecrets);

  const set = useCallback((key: ConnectionSecretKey, value: string) => {
    setSecrets((current) => ({ ...current, [key]: value }));
  }, []);

  const clear = useCallback(() => {
    setSecrets(emptySecrets());
  }, []);

  useEffect(() => {
    return () => {
      // Fail-safe: even if a caller forgets, unmounting never leaves secret
      // material behind in a destroyed component tree.
      setSecrets(emptySecrets());
    };
  }, []);

  return { secrets, set, clear };
}
