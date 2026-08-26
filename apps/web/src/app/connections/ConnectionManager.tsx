import { useState } from "react";

import type { components } from "../../api/generated/rivoquery";
import type { RivoqueryApi } from "../../api/rivoqueryApi";
import { ConnectionCreateForm } from "./ConnectionCreateForm";
import { ConnectionDetailPanel } from "./ConnectionDetailPanel";
import { ConnectionList } from "./ConnectionList";
import { useConnections } from "./useConnections";

type WorkspaceSummary = components["schemas"]["WorkspaceSummary"];

type Route =
  | { readonly name: "list" }
  | { readonly name: "create" }
  | { readonly name: "detail"; readonly connectionId: string };

interface ConnectionManagerProps {
  readonly api: RivoqueryApi;
  readonly workspace: WorkspaceSummary;
  readonly onChangeWorkspace: () => void;
}

export function ConnectionManager({
  api,
  workspace,
  onChangeWorkspace,
}: ConnectionManagerProps) {
  const [route, setRoute] = useState<Route>({ name: "list" });
  const { state, reload } = useConnections(api, workspace.workspaceId);

  if (route.name === "create") {
    return (
      <ConnectionCreateForm
        api={api}
        workspaceId={workspace.workspaceId}
        onCancel={() => { setRoute({ name: "list" }); }}
        onCreated={(outcome) => {
          reload();
          if ("name" in outcome) {
            setRoute({ name: "detail", connectionId: outcome.connectionId });
          } else {
            setRoute({ name: "list" });
          }
        }}
      />
    );
  }

  if (route.name === "detail") {
    return (
      <ConnectionDetailPanel
        key={route.connectionId}
        api={api}
        workspaceId={workspace.workspaceId}
        connectionId={route.connectionId}
        onBack={() => { setRoute({ name: "list" }); }}
        onListChanged={reload}
      />
    );
  }

  return (
    <div className="workspace-scope">
      <div className="workspace-bar">
        <span className="workspace-name" data-testid="active-workspace">
          {workspace.displayName}
        </span>
        <button type="button" className="secondary-button" onClick={onChangeWorkspace}>
          Change workspace
        </button>
      </div>
      <ConnectionList
        state={state}
        onRetry={reload}
        onCreate={() => { setRoute({ name: "create" }); }}
        onOpen={(connectionId) => { setRoute({ name: "detail", connectionId }); }}
      />
    </div>
  );
}
