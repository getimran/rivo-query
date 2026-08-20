import { useCallback, useEffect, useState } from "react";

import type { PlatformHealthClient, PlatformHealthResult } from "../api/health";

export type PlatformHealthViewState =
  | { readonly kind: "loading" }
  | PlatformHealthResult;

export function usePlatformHealth(client: PlatformHealthClient) {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<PlatformHealthViewState>({ kind: "loading" });

  useEffect(() => {
    const controller = new AbortController();

    void client.check(controller.signal).then(
      (result) => {
        if (!controller.signal.aborted) {
          setState(result);
        }
      },
      () => {
        if (!controller.signal.aborted) {
          setState({ kind: "error", requestId: null });
        }
      },
    );

    return () => {
      controller.abort();
    };
  }, [attempt, client]);

  const retry = useCallback(() => {
    setState({ kind: "loading" });
    setAttempt((currentAttempt) => currentAttempt + 1);
  }, []);

  return { retry, state } as const;
}
