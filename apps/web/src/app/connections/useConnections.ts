import { useCallback, useEffect, useState } from "react";

import type { RivoqueryApi } from "../../api/rivoqueryApi";
import type { SafeProblem } from "../../api/problemDetails";
import type { components } from "../../api/generated/rivoquery";

type ConnectionSummary = components["schemas"]["ConnectionSummary"];

export type ConnectionsViewState =
  | { readonly kind: "loading" }
  | {
      readonly kind: "ready";
      readonly items: readonly ConnectionSummary[];
      readonly nextCursor: string | null;
      readonly requestId: string | null;
    }
  | { readonly kind: "problem"; readonly problem: SafeProblem }
  | { readonly kind: "error" };

export function useConnections(api: RivoqueryApi | null, workspaceId: string | null) {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<ConnectionsViewState>({ kind: "loading" });

  useEffect(() => {
    if (api === null || workspaceId === null) {
      return;
    }

    const controller = new AbortController();

    void api.listConnections(workspaceId, { signal: controller.signal }).then((result) => {
      if (controller.signal.aborted) {
        return;
      }
      if (result.kind === "ok") {
        setState({
          kind: "ready",
          items: result.value.items,
          nextCursor: result.value.nextCursor,
          requestId: result.requestId,
        });
      } else if (result.kind === "problem") {
        setState({ kind: "problem", problem: result.problem });
      } else {
        setState({ kind: "error" });
      }
    });

    return () => { controller.abort(); };
  }, [api, workspaceId, attempt]);

  const reload = useCallback(() => {
    setState({ kind: "loading" });
    setAttempt((current) => current + 1);
  }, []);

  return { state, reload } as const;
}
