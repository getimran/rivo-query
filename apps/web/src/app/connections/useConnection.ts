import { useCallback, useEffect, useState } from "react";

import type { RivoqueryApi } from "../../api/rivoqueryApi";
import type { SafeProblem } from "../../api/problemDetails";
import type { components } from "../../api/generated/rivoquery";

type ConnectionDetail = components["schemas"]["ConnectionDetail"];

export type ConnectionViewState =
  | { readonly kind: "loading" }
  | {
      readonly kind: "ready";
      readonly connection: ConnectionDetail;
      readonly etag: string | null;
      readonly requestId: string | null;
    }
  | { readonly kind: "problem"; readonly problem: SafeProblem }
  | { readonly kind: "error" };

export function useConnection(
  api: RivoqueryApi,
  workspaceId: string,
  connectionId: string,
) {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<ConnectionViewState>({ kind: "loading" });

  useEffect(() => {
    const controller = new AbortController();

    void api.getConnection(workspaceId, connectionId, { signal: controller.signal }).then(
      (result) => {
        if (controller.signal.aborted) {
          return;
        }
        if (result.kind === "ok") {
          setState({
            kind: "ready",
            connection: result.value,
            etag: result.etag,
            requestId: result.requestId,
          });
        } else if (result.kind === "problem") {
          setState({ kind: "problem", problem: result.problem });
        } else {
          setState({ kind: "error" });
        }
      },
    );

    return () => { controller.abort(); };
  }, [api, workspaceId, connectionId, attempt]);

  const reload = useCallback(() => {
    setState({ kind: "loading" });
    setAttempt((current) => current + 1);
  }, []);

  const replace = useCallback((connection: ConnectionDetail, etag: string | null) => {
    setState({ kind: "ready", connection, etag, requestId: null });
  }, []);

  return { state, reload, replace } as const;
}
