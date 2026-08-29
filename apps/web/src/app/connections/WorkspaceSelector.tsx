import type { components } from "../../api/generated/rivoquery";
import { EmptyState } from "../shared/feedback";

type WorkspaceSummary = components["schemas"]["WorkspaceSummary"];

interface WorkspaceSelectorProps {
  readonly workspaces: readonly WorkspaceSummary[];
  readonly nextCursor: string | null;
  readonly onSelect: (workspaceId: string) => void;
}

export function WorkspaceSelector({
  workspaces,
  nextCursor,
  onSelect,
}: WorkspaceSelectorProps) {
  return (
    <section className="workspace-selector" aria-labelledby="workspace-selector-title">
      <p className="eyebrow">Workspace</p>
      <h2 id="workspace-selector-title">Choose a workspace</h2>
      <p>Connections are scoped to a workspace. Select one to continue.</p>

      {workspaces.length === 0 ? (
        <EmptyState title="No workspaces">
          <p>Your account is not yet a member of any workspace.</p>
        </EmptyState>
      ) : (
        <ul className="workspace-list" aria-label="Available workspaces">
          {workspaces.map((workspace) => (
            <li key={workspace.workspaceId}>
              <button
                type="button"
                className="workspace-option"
                onClick={() => { onSelect(workspace.workspaceId); }}
              >
                <span className="workspace-name">{workspace.displayName}</span>
                <span className="role-badge">{workspace.role}</span>
              </button>
            </li>
          ))}
        </ul>
      )}

      {nextCursor !== null ? (
        <p className="field-hint">Only the first page of workspaces is shown.</p>
      ) : null}
    </section>
  );
}
