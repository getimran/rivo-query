import { useState } from "react";

import type { RivoqueryApi } from "../../api/rivoqueryApi";
import { LoadingStatus, ProblemAlert } from "../shared/feedback";
import { ConnectionManager } from "./ConnectionManager";
import { useWorkspaces } from "./useWorkspaces";
import { WorkspaceSelector } from "./WorkspaceSelector";

interface ConnectionsAppProps {
  readonly api: RivoqueryApi;
}

export function ConnectionsApp({ api }: ConnectionsAppProps) {
  const workspaces = useWorkspaces(api);
  const [workspaceId, setWorkspaceId] = useState<string | null>(null);

  if (workspaces.state.kind === "loading") {
    return <LoadingStatus>Loading workspaces…</LoadingStatus>;
  }

  if (workspaces.state.kind === "problem") {
    return <ProblemAlert problem={workspaces.state.problem} onRetry={workspaces.retry} />;
  }

  if (workspaces.state.kind === "error") {
    return (
      <section className="problem-alert" role="alert" aria-label="Unable to load workspaces">
        <p className="problem-title">Unable to load workspaces</p>
        <p className="problem-detail">RivoQuery could not reach the API. Try again shortly.</p>
        <button type="button" className="secondary-button" onClick={workspaces.retry}>
          Try again
        </button>
      </section>
    );
  }

  const selected =
    workspaceId === null
      ? null
      : workspaces.state.workspaces.find((workspace) => workspace.workspaceId === workspaceId) ??
        null;

  if (selected === null) {
    return (
      <WorkspaceSelector
        workspaces={workspaces.state.workspaces}
        nextCursor={workspaces.state.nextCursor}
        onSelect={setWorkspaceId}
      />
    );
  }

  return (
    <ConnectionManager
      key={selected.workspaceId}
      api={api}
      workspace={selected}
      onChangeWorkspace={() => { setWorkspaceId(null); }}
    />
  );
}
