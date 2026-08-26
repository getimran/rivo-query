import { useCallback, useState } from "react";

import type { DevTokenStorage } from "./devSession";
import { clearDevToken, readEnvDevToken, readStoredDevToken, storeDevToken } from "./devSession";

export interface DevSession {
  readonly token: string | null;
  readonly signIn: (token: string) => void;
  readonly signOut: () => void;
}

interface UseDevSessionOptions {
  readonly storage?: DevTokenStorage;
}

function initialToken(storage: DevTokenStorage): string | null {
  // A pasted, session-scoped token wins over the build-time env token so the
  // operator can always override it for the current session.
  return readStoredDevToken(storage) ?? readEnvDevToken();
}

export function useDevSession(options: UseDevSessionOptions = {}): DevSession {
  const storage = options.storage ?? window.sessionStorage;
  const [token, setToken] = useState<string | null>(() => initialToken(storage));

  const signIn = useCallback(
    (candidate: string) => {
      const trimmed = candidate.trim();
      if (trimmed.length === 0) {
        return;
      }
      storeDevToken(storage, trimmed);
      setToken(trimmed);
    },
    [storage],
  );

  const signOut = useCallback(() => {
    clearDevToken(storage);
    setToken(null);
  }, [storage]);

  return { token, signIn, signOut };
}
