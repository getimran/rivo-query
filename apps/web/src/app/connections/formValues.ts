import type { components } from "../../api/generated/rivoquery";
import type { CreateConnectionInput, UpdateConnectionInput } from "../../api/rivoqueryApi";
import type { ConnectionSecretFields } from "./useTransientSecrets";

type DatabaseKind = components["schemas"]["DatabaseKind"];
type TlsMode = components["schemas"]["TlsMode"];

export interface ConnectionFormDraft {
  readonly name: string;
  readonly kind: DatabaseKind;
  readonly host: string;
  readonly port: string;
  readonly database: string;
  readonly username: string;
  readonly tlsMode: TlsMode;
  readonly tcpKeepAlive: boolean;
  readonly allowedSchemas: string;
  readonly allowedTables: string;
  readonly statementTimeoutMs: string;
  readonly maxRows: string;
}

export interface ConnectionEditDraft {
  readonly name: string;
  readonly host: string;
  readonly port: string;
  readonly database: string;
  readonly tlsMode: TlsMode;
  readonly tcpKeepAlive: boolean;
  readonly allowedSchemas: string;
  readonly allowedTables: string;
  readonly statementTimeoutMs: string;
  readonly maxRows: string;
}

export type ConnectionDraftResult =
  | { readonly kind: "ok"; readonly input: CreateConnectionInput }
  | { readonly kind: "invalid"; readonly message: string };

export type ConnectionEditResult =
  | { readonly kind: "ok"; readonly input: UpdateConnectionInput }
  | { readonly kind: "invalid"; readonly message: string };

export function parseList(value: string): string[] {
  const parts = value
    .split(/[\n,]+/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0);

  return [...new Set(parts)];
}

export function parseIntegerInRange(
  value: string,
  min: number,
  max: number,
): number | null {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < min || parsed > max) {
    return null;
  }
  return parsed;
}

interface CommonFields {
  readonly name: string;
  readonly host: string;
  readonly port: number;
  readonly database: string;
  readonly allowedSchemas: string[];
  readonly allowedTables: string[];
  readonly limits: { readonly statementTimeoutMs: number; readonly maxRows: number };
}

type CommonValidation = { readonly ok: false; readonly message: string } | { readonly ok: true; readonly fields: CommonFields };

function validateCommon(draft: ConnectionEditDraft): CommonValidation {
  if (draft.name.trim().length === 0) {
    return { ok: false, message: "Name is required." };
  }
  const host = draft.host.trim();
  if (host.length === 0) {
    return { ok: false, message: "Host is required." };
  }
  const port = parseIntegerInRange(draft.port, 1, 65535);
  if (port === null) {
    return { ok: false, message: "Port must be a whole number between 1 and 65535." };
  }
  const database = draft.database.trim();
  if (database.length === 0) {
    return { ok: false, message: "Database name is required." };
  }
  const allowedSchemas = parseList(draft.allowedSchemas);
  if (allowedSchemas.length === 0) {
    return { ok: false, message: "At least one allowed schema is required." };
  }
  const statementTimeoutMs = parseIntegerInRange(draft.statementTimeoutMs, 100, 60000);
  if (statementTimeoutMs === null) {
    return { ok: false, message: "Statement timeout must be between 100 and 60000 ms." };
  }
  const maxRows = parseIntegerInRange(draft.maxRows, 1, 1000);
  if (maxRows === null) {
    return { ok: false, message: "Maximum rows must be between 1 and 1000." };
  }

  return {
    ok: true,
    fields: {
      name: draft.name.trim(),
      host,
      port,
      database,
      allowedSchemas,
      allowedTables: parseList(draft.allowedTables),
      limits: { statementTimeoutMs, maxRows },
    },
  };
}

export function buildCreateInput(
  draft: ConnectionFormDraft,
  secrets: ConnectionSecretFields,
): ConnectionDraftResult {
  if (draft.username.trim().length === 0) {
    return { kind: "invalid", message: "Database username is required." };
  }
  if (secrets.password.length === 0) {
    return { kind: "invalid", message: "Database password is required." };
  }
  const hasClientCertificate = secrets.clientCertificatePem.length > 0;
  const hasClientKey = secrets.clientPrivateKeyPem.length > 0;
  if (hasClientCertificate !== hasClientKey) {
    return {
      kind: "invalid",
      message: "Client certificate and private key must be supplied together.",
    };
  }

  const common = validateCommon(draft);
  if (!common.ok) {
    return { kind: "invalid", message: common.message };
  }

  const { name, host, port, database, allowedSchemas, allowedTables, limits } = common.fields;

  return {
    kind: "ok",
    input: {
      name,
      kind: draft.kind,
      endpoint: { host, port, database },
      secret: { username: draft.username.trim(), password: secrets.password },
      tls: {
        mode: draft.tlsMode,
        ...(secrets.caCertificatePem.length > 0
          ? { caCertificatePem: secrets.caCertificatePem }
          : {}),
        ...(secrets.clientCertificatePem.length > 0
          ? { clientCertificatePem: secrets.clientCertificatePem }
          : {}),
        ...(secrets.clientPrivateKeyPem.length > 0
          ? { clientPrivateKeyPem: secrets.clientPrivateKeyPem }
          : {}),
      },
      connectorOptions: { tcpKeepAlive: draft.tcpKeepAlive },
      objectPolicy: { allowedSchemas, allowedTables },
      limits: { statementTimeoutMs: limits.statementTimeoutMs, maxRows: limits.maxRows },
    },
  };
}

export function buildUpdateInput(draft: ConnectionEditDraft): ConnectionEditResult {
  const common = validateCommon(draft);
  if (!common.ok) {
    return { kind: "invalid", message: common.message };
  }

  const { name, host, port, database, allowedSchemas, allowedTables, limits } = common.fields;

  return {
    kind: "ok",
    input: {
      name,
      endpoint: { host, port, database },
      tlsMode: draft.tlsMode,
      connectorOptions: { tcpKeepAlive: draft.tcpKeepAlive },
      objectPolicy: { allowedSchemas, allowedTables },
      limits: { statementTimeoutMs: limits.statementTimeoutMs, maxRows: limits.maxRows },
    },
  };
}
