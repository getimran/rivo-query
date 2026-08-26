import { useEffect, useRef, useState } from "react";

import type { components } from "../../api/generated/rivoquery";
import type { RivoqueryApi } from "../../api/rivoqueryApi";
import type { SafeProblem } from "../../api/problemDetails";
import { RequestId, ProblemAlert } from "../shared/feedback";
import { ConnectionUpdateForm } from "./ConnectionUpdateForm";
import { generateIdempotencyKey } from "./idempotency";
import { useConnection } from "./useConnection";

type ConnectionDetail = components["schemas"]["ConnectionDetail"];
type Operation = components["schemas"]["Operation"];

function isOperation(value: ConnectionDetail | Operation): value is Operation {
  return "operationId" in value;
}

interface ConnectionDetailPanelProps {
  readonly api: RivoqueryApi;
  readonly workspaceId: string;
  readonly connectionId: string;
  readonly onBack: () => void;
  readonly onListChanged: () => void;
}

type Confirmation = "disable" | "delete";

function formatTimestamp(value: string): string {
  return new Date(value).toLocaleString();
}

function EndpointBlock({ connection }: { readonly connection: ConnectionDetail }) {
  return (
    <dl className="detail-list">
      <div>
        <dt>Host</dt>
        <dd>{connection.endpoint.host}</dd>
      </div>
      <div>
        <dt>Port</dt>
        <dd>{connection.endpoint.port}</dd>
      </div>
      <div>
        <dt>Database</dt>
        <dd>{connection.endpoint.database}</dd>
      </div>
      <div>
        <dt>TLS mode</dt>
        <dd>{connection.tls.mode}</dd>
      </div>
      <div>
        <dt>Custom CA configured</dt>
        <dd>{connection.tls.customCaConfigured ? "Yes" : "No"}</dd>
      </div>
      <div>
        <dt>Client certificate configured</dt>
        <dd>{connection.tls.clientCertificateConfigured ? "Yes" : "No"}</dd>
      </div>
    </dl>
  );
}

function ObjectPolicyBlock({ connection }: { readonly connection: ConnectionDetail }) {
  const tables =
    connection.objectPolicy.allowedTables.length === 0
      ? "All non-system tables"
      : connection.objectPolicy.allowedTables.join(", ");

  return (
    <dl className="detail-list">
      <div>
        <dt>Allowed schemas</dt>
        <dd>{connection.objectPolicy.allowedSchemas.join(", ")}</dd>
      </div>
      <div>
        <dt>Allowed tables</dt>
        <dd>{tables}</dd>
      </div>
    </dl>
  );
}

export function ConnectionDetailPanel({
  api,
  workspaceId,
  connectionId,
  onBack,
  onListChanged,
}: ConnectionDetailPanelProps) {
  const { state, reload, replace } = useConnection(api, workspaceId, connectionId);
  const [editing, setEditing] = useState(false);
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [problem, setProblem] = useState<SafeProblem | null>(null);
  const [fatal, setFatal] = useState(false);
  const confirmBarRef = useRef<HTMLDivElement | null>(null);
  const disableButtonRef = useRef<HTMLButtonElement | null>(null);
  const deleteButtonRef = useRef<HTMLButtonElement | null>(null);
  const previousConfirmation = useRef<Confirmation | null>(null);

  useEffect(() => {
    const origin = previousConfirmation.current;
    previousConfirmation.current = confirmation;
    if (confirmation !== null) {
      confirmBarRef.current?.focus();
    } else if (origin !== null) {
      (origin === "disable" ? disableButtonRef : deleteButtonRef).current?.focus();
    }
  }, [confirmation]);

  function closeConfirmation() {
    setConfirmation(null);
  }

  function handleDisable() {
    if (state.kind !== "ready" || state.etag === null) {
      return;
    }
    setConfirmation(null);
    setMessage(null);
    setProblem(null);
    setFatal(false);

    void api
      .disableConnection(workspaceId, connectionId, {
        idempotencyKey: generateIdempotencyKey(),
        ifMatch: state.etag,
      })
      .then((result) => {
        if (result.kind === "ok") {
          replace(result.value, result.etag);
          onListChanged();
          setMessage("Connection disabled.");
        } else if (result.kind === "problem") {
          setProblem(result.problem);
        } else {
          setFatal(true);
        }
      });
  }

  function handleDelete() {
    if (state.kind !== "ready" || state.etag === null) {
      return;
    }
    setConfirmation(null);
    setMessage(null);
    setProblem(null);
    setFatal(false);

    void api
      .deleteConnection(workspaceId, connectionId, {
        idempotencyKey: generateIdempotencyKey(),
        ifMatch: state.etag,
      })
      .then((result) => {
        if (result.kind === "ok") {
          onListChanged();
          onBack();
        } else if (result.kind === "problem") {
          setProblem(result.problem);
        } else {
          setFatal(true);
        }
      });
  }

  if (state.kind === "loading") {
    return (
      <section aria-labelledby="detail-title">
        <p className="eyebrow">Connection</p>
        <h2 id="detail-title">Loading connection…</h2>
        <div className="status-panel" role="status">
          <span className="status-dot" aria-hidden="true" />
          <p>Fetching connection details.</p>
        </div>
      </section>
    );
  }

  if (state.kind === "problem") {
    return (
      <section aria-labelledby="detail-title">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Connection</p>
            <h2 id="detail-title">Connection unavailable</h2>
          </div>
          <button type="button" className="secondary-button" onClick={onBack}>
            Back to connections
          </button>
        </div>
        <ProblemAlert problem={state.problem} onRetry={reload} />
      </section>
    );
  }

  if (state.kind === "error") {
    return (
      <section aria-labelledby="detail-title">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Connection</p>
            <h2 id="detail-title">Connection unavailable</h2>
          </div>
          <button type="button" className="secondary-button" onClick={onBack}>
            Back to connections
          </button>
        </div>
        <section className="problem-alert" role="alert" aria-label="Unable to load connection">
          <p className="problem-title">Unable to load connection</p>
          <p className="problem-detail">RivoQuery could not reach the API. Try again shortly.</p>
          <button type="button" className="secondary-button" onClick={reload}>
            Try again
          </button>
        </section>
      </section>
    );
  }

  const connection = state.connection;

  if (editing) {
    return (
      <ConnectionUpdateForm
        api={api}
        workspaceId={workspaceId}
        connection={connection}
        etag={state.etag}
        onCancel={() => { setEditing(false); }}
        onUpdated={(outcome, nextEtag) => {
          setEditing(false);
          if (isOperation(outcome)) {
            // The update was accepted asynchronously (202): refetch the detail
            // rather than installing the Operation as the loaded connection.
            setMessage("Update accepted; RivoQuery is applying the change.");
            reload();
          } else {
            replace(outcome, nextEtag);
            onListChanged();
            setMessage("Connection updated.");
          }
        }}
      />
    );
  }

  const disabled = connection.state === "DISABLED" || connection.state === "DELETING";
  const deleting = connection.state === "DELETING";
  const noVersion = state.etag === null;

  return (
    <section className="connection-detail" aria-labelledby="detail-title">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Connection</p>
          <h2 id="detail-title">{connection.name}</h2>
        </div>
        <button type="button" className="secondary-button" onClick={onBack}>
          Back to connections
        </button>
      </div>

      {message !== null ? (
        <p className="form-alert form-alert-success" role="status">
          {message}
        </p>
      ) : null}
      {problem !== null ? <ProblemAlert problem={problem} /> : null}
      {fatal ? (
        <p className="form-alert" role="alert">
          RivoQuery could not complete that action. Please try again.
        </p>
      ) : null}

      <dl className="detail-list">
        <div>
          <dt>Connection ID</dt>
          <dd>{connection.connectionId}</dd>
        </div>
        <div>
          <dt>Kind</dt>
          <dd>{connection.kind}</dd>
        </div>
        <div>
          <dt>State</dt>
          <dd>{connection.state}</dd>
        </div>
        <div>
          <dt>Version</dt>
          <dd>{connection.version}</dd>
        </div>
        <div>
          <dt>Secret configured</dt>
          <dd>{connection.secretConfigured ? "Yes" : "No"}</dd>
        </div>
        <div>
          <dt>Credential state</dt>
          <dd>{connection.credentialState}</dd>
        </div>
        <div>
          <dt>Created</dt>
          <dd>{formatTimestamp(connection.createdAt)}</dd>
        </div>
        <div>
          <dt>Updated</dt>
          <dd>{formatTimestamp(connection.updatedAt)}</dd>
        </div>
      </dl>

      <h3>Endpoint and TLS</h3>
      <EndpointBlock connection={connection} />

      <h3>Access policy</h3>
      <ObjectPolicyBlock connection={connection} />

      <h3>Limits</h3>
      <dl className="detail-list">
        <div>
          <dt>Statement timeout</dt>
          <dd>{connection.limits.statementTimeoutMs} ms</dd>
        </div>
        <div>
          <dt>Maximum rows</dt>
          <dd>{connection.limits.maxRows}</dd>
        </div>
        <div>
          <dt>TCP keep-alive</dt>
          <dd>{connection.connectorOptions.tcpKeepAlive ? "On" : "Off"}</dd>
        </div>
      </dl>

      <RequestId value={state.requestId} />

      {noVersion ? (
        <p className="form-alert" role="note">
          This connection cannot be disabled or deleted because no version token (ETag) is available.
        </p>
      ) : null}

      <div className="action-bar">
        <button type="button" className="primary-button" onClick={() => { setEditing(true); }}>
          Edit
        </button>

        <span aria-live="polite" className="visually-hidden">
          {confirmation === null
            ? ""
            : confirmation === "disable"
              ? "Confirm disable prompt shown."
              : "Confirm delete prompt shown."}
        </span>

        {confirmation === "disable" ? (
          <div
            className="confirm-bar"
            ref={confirmBarRef}
            role="dialog"
            aria-label="Confirm disable"
            tabIndex={-1}
          >
            <span>Stop new grants and pool borrows for this connection?</span>
            <button type="button" className="danger-button" onClick={handleDisable}>
              Confirm disable
            </button>
            <button type="button" className="secondary-button" onClick={closeConfirmation}>
              Cancel
            </button>
          </div>
        ) : (
          <button
            type="button"
            className="secondary-button"
            ref={disableButtonRef}
            disabled={disabled || noVersion}
            onClick={() => { setConfirmation("disable"); }}
          >
            Disable
          </button>
        )}

        {confirmation === "delete" ? (
          <div
            className="confirm-bar"
            ref={confirmBarRef}
            role="dialog"
            aria-label="Confirm delete"
            tabIndex={-1}
          >
            <span>Tombstone this connection and schedule cleanup?</span>
            <button type="button" className="danger-button" onClick={handleDelete}>
              Confirm delete
            </button>
            <button type="button" className="secondary-button" onClick={closeConfirmation}>
              Cancel
            </button>
          </div>
        ) : (
          <button
            type="button"
            className="danger-button"
            ref={deleteButtonRef}
            disabled={deleting || noVersion}
            onClick={() => { setConfirmation("delete"); }}
          >
            Delete
          </button>
        )}
      </div>

      <div className="not-supported" aria-label="Not yet supported actions">
        <h3>Not available yet</h3>
        <ul className="not-supported-list">
          <li>
            <button type="button" disabled>
              Enable
            </button>
            <span>Enabling a validated connection is not supported by the API yet.</span>
          </li>
          <li>
            <button type="button" disabled>
              Test connection
            </button>
            <span>Running a bounded connectivity test is not supported by the API yet.</span>
          </li>
          <li>
            <button type="button" disabled>
              Rotate secret
            </button>
            <span>Rotating credentials and TLS material is not supported by the API yet.</span>
          </li>
        </ul>
      </div>
    </section>
  );
}
