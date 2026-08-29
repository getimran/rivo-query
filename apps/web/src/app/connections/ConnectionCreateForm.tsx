import { useState } from "react";
import type { SubmitEvent } from "react";

import type { components } from "../../api/generated/rivoquery";
import type { CreateConnectionOutcome, RivoqueryApi } from "../../api/rivoqueryApi";
import type { SafeProblem } from "../../api/problemDetails";
import { ProblemAlert } from "../shared/feedback";
import { buildCreateInput } from "./formValues";
import type { ConnectionFormDraft } from "./formValues";
import { generateIdempotencyKey } from "./idempotency";
import { useTransientSecrets } from "./useTransientSecrets";

type DatabaseKind = components["schemas"]["DatabaseKind"];
type TlsMode = components["schemas"]["TlsMode"];

interface ConnectionCreateFormProps {
  readonly api: RivoqueryApi;
  readonly workspaceId: string;
  readonly onCancel: () => void;
  readonly onCreated: (outcome: CreateConnectionOutcome) => void;
}

const initialDraft: ConnectionFormDraft = {
  name: "",
  kind: "POSTGRESQL",
  host: "",
  port: "",
  database: "",
  username: "",
  tlsMode: "VERIFY_FULL",
  tcpKeepAlive: true,
  allowedSchemas: "",
  allowedTables: "",
  statementTimeoutMs: "15000",
  maxRows: "200",
};

export function ConnectionCreateForm({
  api,
  workspaceId,
  onCancel,
  onCreated,
}: ConnectionCreateFormProps) {
  const { secrets, set, clear } = useTransientSecrets();
  const [draft, setDraft] = useState<ConnectionFormDraft>(initialDraft);
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [problem, setProblem] = useState<SafeProblem | null>(null);
  const [fatal, setFatal] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  function updateDraft<Key extends keyof ConnectionFormDraft>(
    key: Key,
    value: ConnectionFormDraft[Key],
  ) {
    setDraft((current) => ({ ...current, [key]: value }));
  }

  function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    setFieldError(null);
    setProblem(null);
    setFatal(false);

    const built = buildCreateInput(draft, secrets);
    if (built.kind === "invalid") {
      setFieldError(built.message);
      return;
    }

    setSubmitting(true);
    const idempotencyKey = generateIdempotencyKey();

    void api
      .createConnection(workspaceId, built.input, { idempotencyKey })
      .then((result) => {
        setSubmitting(false);
        if (result.kind === "ok") {
          setDraft(initialDraft);
          clear();
          onCreated(result.value);
        } else if (result.kind === "problem") {
          setProblem(result.problem);
          clear();
        } else {
          setFatal(true);
          clear();
        }
      });
  }

  return (
    <form className="connection-form" onSubmit={handleSubmit} noValidate>
      <div className="section-heading">
        <div>
          <p className="eyebrow">New connection</p>
          <h2>Onboard a database connection</h2>
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
          RivoQuery could not create the connection. Please try again.
        </p>
      ) : null}

      <fieldset className="form-section">
        <legend>Identity</legend>
        <div className="field-grid">
          <label className="field" htmlFor="create-name">
            <span className="field-label">Connection name</span>
            <input
              id="create-name"
              className="field-input"
              value={draft.name}
              onChange={(event) => { updateDraft("name", event.target.value); }}
            />
          </label>
          <label className="field" htmlFor="create-kind">
            <span className="field-label">Database kind</span>
            <select
              id="create-kind"
              className="field-input"
              value={draft.kind}
              onChange={(event) => { updateDraft("kind", event.target.value as DatabaseKind); }}
            >
              <option value="POSTGRESQL">PostgreSQL</option>
              <option value="MYSQL">MySQL</option>
            </select>
          </label>
        </div>
      </fieldset>

      <fieldset className="form-section">
        <legend>Endpoint</legend>
        <div className="field-grid">
          <label className="field field-wide" htmlFor="create-host">
            <span className="field-label">Host</span>
            <input
              id="create-host"
              className="field-input"
              value={draft.host}
              autoComplete="off"
              onChange={(event) => { updateDraft("host", event.target.value); }}
            />
          </label>
          <label className="field" htmlFor="create-port">
            <span className="field-label">Port</span>
            <input
              id="create-port"
              className="field-input"
              inputMode="numeric"
              value={draft.port}
              onChange={(event) => { updateDraft("port", event.target.value); }}
            />
          </label>
          <label className="field" htmlFor="create-database">
            <span className="field-label">Database name</span>
            <input
              id="create-database"
              className="field-input"
              value={draft.database}
              autoComplete="off"
              onChange={(event) => { updateDraft("database", event.target.value); }}
            />
          </label>
        </div>
      </fieldset>

      <fieldset className="form-section">
        <legend>Credentials</legend>
        <p className="field-hint">
          Credentials are write-only: they are encrypted server-side and never
          returned, logged, or stored in this browser.
        </p>
        <div className="field-grid">
          <label className="field" htmlFor="create-username">
            <span className="field-label">Database username</span>
            <input
              id="create-username"
              className="field-input"
              value={draft.username}
              autoComplete="off"
              onChange={(event) => { updateDraft("username", event.target.value); }}
            />
          </label>
          <label className="field" htmlFor="create-password">
            <span className="field-label">Database password</span>
            <input
              id="create-password"
              className="field-input"
              type="password"
              autoComplete="new-password"
              value={secrets.password}
              onChange={(event) => { set("password", event.target.value); }}
            />
          </label>
        </div>
      </fieldset>

      <fieldset className="form-section">
        <legend>TLS</legend>
        <div className="field-grid">
          <label className="field" htmlFor="create-tls-mode">
            <span className="field-label">TLS mode</span>
            <select
              id="create-tls-mode"
              className="field-input"
              value={draft.tlsMode}
              onChange={(event) => { updateDraft("tlsMode", event.target.value as TlsMode); }}
            >
              <option value="VERIFY_FULL">Verify full (recommended)</option>
              <option value="DISABLED">Disabled (local synthetic only)</option>
            </select>
          </label>
          <label className="field field-wide" htmlFor="create-ca">
            <span className="field-label">CA certificate (PEM, optional)</span>
            <textarea
              id="create-ca"
              className="field-input field-textarea"
              autoComplete="off"
              spellCheck={false}
              rows={3}
              value={secrets.caCertificatePem}
              onChange={(event) => { set("caCertificatePem", event.target.value); }}
            />
          </label>
          <label className="field field-wide" htmlFor="create-client-cert">
            <span className="field-label">Client certificate (PEM, optional)</span>
            <textarea
              id="create-client-cert"
              className="field-input field-textarea"
              autoComplete="off"
              spellCheck={false}
              rows={3}
              value={secrets.clientCertificatePem}
              onChange={(event) => { set("clientCertificatePem", event.target.value); }}
            />
          </label>
          <label className="field field-wide" htmlFor="create-client-key">
            <span className="field-label">Client private key (PEM, optional)</span>
            <textarea
              id="create-client-key"
              className="field-input field-textarea"
              autoComplete="off"
              spellCheck={false}
              rows={3}
              value={secrets.clientPrivateKeyPem}
              onChange={(event) => { set("clientPrivateKeyPem", event.target.value); }}
            />
          </label>
        </div>
      </fieldset>

      <fieldset className="form-section">
        <legend>Access policy</legend>
        <div className="field-grid">
          <label className="field field-wide" htmlFor="create-schemas">
            <span className="field-label">Allowed schemas (one per line)</span>
            <textarea
              id="create-schemas"
              className="field-input field-textarea"
              autoComplete="off"
              spellCheck={false}
              rows={3}
              value={draft.allowedSchemas}
              onChange={(event) => { updateDraft("allowedSchemas", event.target.value); }}
            />
          </label>
          <label className="field field-wide" htmlFor="create-tables">
            <span className="field-label">Allowed tables (one per line; empty means all)</span>
            <textarea
              id="create-tables"
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
          <label className="field" htmlFor="create-timeout">
            <span className="field-label">Statement timeout (ms)</span>
            <input
              id="create-timeout"
              className="field-input"
              inputMode="numeric"
              value={draft.statementTimeoutMs}
              onChange={(event) => { updateDraft("statementTimeoutMs", event.target.value); }}
            />
          </label>
          <label className="field" htmlFor="create-max-rows">
            <span className="field-label">Maximum rows</span>
            <input
              id="create-max-rows"
              className="field-input"
              inputMode="numeric"
              value={draft.maxRows}
              onChange={(event) => { updateDraft("maxRows", event.target.value); }}
            />
          </label>
          <label className="field field-checkbox" htmlFor="create-keepalive">
            <input
              id="create-keepalive"
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
          {submitting ? "Creating…" : "Create connection"}
        </button>
      </div>
    </form>
  );
}
