import { useCallback, useEffect, useState } from "react";

import type { RivoqueryApi } from "../../api/rivoqueryApi";
import type { SafeProblem } from "../../api/problemDetails";
import type { components } from "../../api/generated/rivoquery";

type WorkspaceSummary = components["schemas"]["WorkspaceSummary"];

export type WorkspacesViewState =
  | { readonly kind: "loading" }
  | {
      readonly kind: "ready";
      readonly workspaces: readonly WorkspaceSummary[];
      readonly nextCursor: string | null;
      readonly requestId: string | null;
    }
  | { readonly kind: "problem"; readonly problem: SafeProblem }
  | { readonly kind: "error" };

export function useWorkspaces(api: RivoqueryApi | null) {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<WorkspacesViewState>({ kind: "loading" });

  useEffect(() => {
    if (api === null) {
      return;
    }

    const controller = new AbortController();

    void api.discoverWorkspaces({ signal: controller.signal }).then((result) => {
      if (controller.signal.aborted) {
        return;
      }
      if (result.kind === "ok") {
        setState({
          kind: "ready",
          workspaces: result.value.items,
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
  }, [api, attempt]);

  const retry = useCallback(() => {
    setState({ kind: "loading" });
    setAttempt((current) => current + 1);
  }, []);

  return { state, retry } as const;
}
