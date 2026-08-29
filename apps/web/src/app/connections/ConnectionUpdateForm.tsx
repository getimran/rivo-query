import { useState } from "react";
import type { SubmitEvent } from "react";

import type { components } from "../../api/generated/rivoquery";
import type { RivoqueryApi } from "../../api/rivoqueryApi";
import type { SafeProblem } from "../../api/problemDetails";
import { ProblemAlert } from "../shared/feedback";
import { buildUpdateInput } from "./formValues";
import type { ConnectionEditDraft } from "./formValues";
import { generateIdempotencyKey } from "./idempotency";

type ConnectionDetail = components["schemas"]["ConnectionDetail"];
type Operation = components["schemas"]["Operation"];
type TlsMode = components["schemas"]["TlsMode"];

interface ConnectionUpdateFormProps {
  readonly api: RivoqueryApi;
  readonly workspaceId: string;
  readonly connection: ConnectionDetail;
  readonly etag: string | null;
  readonly onCancel: () => void;
  readonly onUpdated: (outcome: ConnectionDetail | Operation, etag: string | null) => void;
}

function draftFrom(connection: ConnectionDetail): ConnectionEditDraft {
  return {
    name: connection.name,
    host: connection.endpoint.host,
    port: String(connection.endpoint.port),
    database: connection.endpoint.database,
    tlsMode: connection.tls.mode,
    tcpKeepAlive: connection.connectorOptions.tcpKeepAlive,
    allowedSchemas: connection.objectPolicy.allowedSchemas.join("\n"),
    allowedTables: connection.objectPolicy.allowedTables.join("\n"),
    statementTimeoutMs: String(connection.limits.statementTimeoutMs),
    maxRows: String(connection.limits.maxRows),
  };
}

export function ConnectionUpdateForm({
  api,
  workspaceId,
  connection,
  etag,
  onCancel,
  onUpdated,
}: ConnectionUpdateFormProps) {
  const [draft, setDraft] = useState<ConnectionEditDraft>(() => draftFrom(connection));
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [problem, setProblem] = useState<SafeProblem | null>(null);
  const [fatal, setFatal] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  function updateDraft<Key extends keyof ConnectionEditDraft>(
    key: Key,
    value: ConnectionEditDraft[Key],
  ) {
    setDraft((current) => ({ ...current, [key]: value }));
  }

  function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    setFieldError(null);
    setProblem(null);
    setFatal(false);

    if (etag === null) {
      setFieldError("This connection cannot be updated because no version token (ETag) is available.");
      return;
    }

    const built = buildUpdateInput(draft);
    if (built.kind === "invalid") {
      setFieldError(built.message);
      return;
    }

    setSubmitting(true);
    const idempotencyKey = generateIdempotencyKey();

    void api
      .updateConnection(workspaceId, connection.connectionId, built.input, {
        idempotencyKey,
        ifMatch: etag,
      })
      .then((result) => {
        setSubmitting(false);
        if (result.kind === "ok") {
          onUpdated(result.value, result.etag);
        } else if (result.kind === "problem") {
          setProblem(result.problem);
        } else {
          setFatal(true);
        }
      });
  }

  return (
    <form className="connection-form" onSubmit={handleSubmit} noValidate>
      <div className="section-heading">
        <div>
          <p className="eyebrow">Edit connection</p>
          <h2>{connection.name}</h2>
        </div>
        <button type="button" className="secondary-button" onClick={onCancel}>
          Cancel
        </button>
      </div>

      {fieldError !== null ? (
        <p className="form-alert" role="alert">
          {fieldError}
        </p>
      ) : null}
      {problem !== null ? <ProblemAlert problem={problem} /> : null}
      {fatal ? (
        <p className="form-alert" role="alert">
          RivoQuery could not update the connection. Please try again.
        </p>
      ) : null}

      <fieldset className="form-section">
        <legend>Identity</legend>
        <div className="field-grid">
          <label className="field" htmlFor="edit-name">
            <span className="field-label">Connection name</span>
            <input
              id="edit-name"
              className="field-input"
              value={draft.name}
              onChange={(event) => { updateDraft("name", event.target.value); }}
            />
          </label>
        </div>
      </fieldset>

      <fieldset className="form-section">
        <legend>Endpoint</legend>
        <div className="field-grid">
          <label className="field field-wide" htmlFor="edit-host">
            <span className="field-label">Host</span>
            <input
              id="edit-host"
              className="field-input"
              value={draft.host}
              autoComplete="off"
              onChange={(event) => { updateDraft("host", event.target.value); }}
            />
          </label>
          <label className="field" htmlFor="edit-port">
            <span className="field-label">Port</span>
            <input
              id="edit-port"
              className="field-input"
              inputMode="numeric"
              value={draft.port}
              onChange={(event) => { updateDraft("port", event.target.value); }}
            />
          </label>
          <label className="field" htmlFor="edit-database">
            <span className="field-label">Database name</span>
            <input
              id="edit-database"
              className="field-input"
              value={draft.database}
              autoComplete="off"
              onChange={(event) => { updateDraft("database", event.target.value); }}
            />
          </label>
        </div>
      </fieldset>

      <fieldset className="form-section">
        <legend>TLS</legend>
        <div className="field-grid">
          <label className="field" htmlFor="edit-tls-mode">
            <span className="field-label">TLS mode</span>
            <select
              id="edit-tls-mode"
              className="field-input"
              value={draft.tlsMode}
              onChange={(event) => { updateDraft("tlsMode", event.target.value as TlsMode); }}
            >
              <option value="VERIFY_FULL">Verify full (recommended)</option>
              <option value="DISABLED">Disabled (local synthetic only)</option>
            </select>
          </label>
        </div>
        <p className="field-hint">
          Credentials and TLS certificates are rotated through the rotate-secret
          operation, which is not available yet.
        </p>
      </fieldset>

      <fieldset className="form-section">
        <legend>Access policy</legend>
        <div className="field-grid">
          <label className="field field-wide" htmlFor="edit-schemas">
            <span className="field-label">Allowed schemas (one per line)</span>
            <textarea
              id="edit-schemas"
              className="field-input field-textarea"
              autoComplete="off"
              spellCheck={false}
              rows={3}
              value={draft.allowedSchemas}
              onChange={(event) => { updateDraft("allowedSchemas", event.target.value); }}
            />
          </label>
          <label className="field field-wide" htmlFor="edit-tables">
            <span className="field-label">Allowed tables (one per line; empty means all)</span>
            <textarea
              id="edit-tables"
              className="field-input field-textarea"
              autoComplete="off"
              spellCheck={false}
              rows={3}
              value={draft.allowedTables}
              onChange={(event) => { updateDraft("allowedTables", event.target.value); }}
            />
          </label>
        </div>
      </fieldset>

      <fieldset className="form-section">
        <legend>Limits</legend>
        <div className="field-grid">
          <label className="field" htmlFor="edit-timeout">
            <span className="field-label">Statement timeout (ms)</span>
            <input
              id="edit-timeout"
              className="field-input"
              inputMode="numeric"
              value={draft.statementTimeoutMs}
              onChange={(event) => { updateDraft("statementTimeoutMs", event.target.value); }}
            />
          </label>
          <label className="field" htmlFor="edit-max-rows">
            <span className="field-label">Maximum rows</span>
            <input
              id="edit-max-rows"
              className="field-input"
              inputMode="numeric"
              value={draft.maxRows}
              onChange={(event) => { updateDraft("maxRows", event.target.value); }}
            />
          </label>
          <label className="field field-checkbox" htmlFor="edit-keepalive">
            <input
              id="edit-keepalive"
              type="checkbox"
              checked={draft.tcpKeepAlive}
              onChange={(event) => { updateDraft("tcpKeepAlive", event.target.checked); }}
            />
            <span className="field-label">TCP keep-alive</span>
          </label>
        </div>
      </fieldset>

      <div className="form-actions">
        <button className="primary-button" type="submit" disabled={submitting}>
          {submitting ? "Saving…" : "Save changes"}
        </button>
      </div>
    </form>
  );
}
