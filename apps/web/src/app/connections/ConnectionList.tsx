import type { ConnectionsViewState } from "./useConnections";
import { EmptyState, LoadingStatus, ProblemAlert, RequestId } from "../shared/feedback";

interface ConnectionListProps {
  readonly state: ConnectionsViewState;
  readonly onRetry: () => void;
  readonly onCreate: () => void;
  readonly onOpen: (connectionId: string) => void;
}

export function ConnectionList({
  state,
  onRetry,
  onCreate,
  onOpen,
}: ConnectionListProps) {
  return (
    <section className="connection-manager" aria-labelledby="connections-title">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Connections</p>
          <h2 id="connections-title">Managed connections</h2>
        </div>
        <button type="button" className="primary-button" onClick={onCreate}>
          New connection
        </button>
      </div>

      {state.kind === "loading" ? <LoadingStatus>Loading connections…</LoadingStatus> : null}

      {state.kind === "problem" ? <ProblemAlert problem={state.problem} onRetry={onRetry} /> : null}

      {state.kind === "error" ? (
        <section className="problem-alert" role="alert" aria-label="Unable to load connections">
          <p className="problem-title">Unable to load connections</p>
          <p className="problem-detail">RivoQuery could not reach the API. Try again shortly.</p>
          <button type="button" className="secondary-button" onClick={onRetry}>
            Try again
          </button>
        </section>
      ) : null}

      {state.kind === "ready" ? (
        <>
          {state.items.length === 0 ? (
            <EmptyState title="No connections yet">
              <p>Create a connection to start querying your database.</p>
            </EmptyState>
          ) : (
            <ul className="connection-list" aria-label="Connections">
              {state.items.map((item) => (
                <li key={item.connectionId} className="connection-row">
                  <button
                    type="button"
                    className="connection-row-button"
                    onClick={() => { onOpen(item.connectionId); }}
                  >
                    <span className="connection-name">{item.name}</span>
                    <span className="connection-meta">
                      <span className="connection-kind">{item.kind}</span>
                      <span className="state-badge">{item.state}</span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
          {state.nextCursor !== null ? (
            <p className="field-hint">Only the first page of connections is shown.</p>
          ) : null}
          <RequestId value={state.requestId} />
        </>
      ) : null}
    </section>
  );
}
