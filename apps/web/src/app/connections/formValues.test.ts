import { describe, expect, it } from "vitest";

import { buildCreateInput, buildUpdateInput, parseIntegerInRange, parseList } from "./formValues";
import type { ConnectionFormDraft, ConnectionEditDraft } from "./formValues";
import type { ConnectionSecretFields } from "./useTransientSecrets";

const emptySecrets: ConnectionSecretFields = {
  password: "",
  caCertificatePem: "",
  clientCertificatePem: "",
  clientPrivateKeyPem: "",
};

const validDraft: ConnectionFormDraft = {
  name: "  warehouse-read ",
  kind: "POSTGRESQL",
  host: " db.example.com ",
  port: "5432",
  database: "analytics",
  username: "reader",
  tlsMode: "VERIFY_FULL",
  tcpKeepAlive: true,
  allowedSchemas: "public\nreporting",
  allowedTables: "public.events",
  statementTimeoutMs: "15000",
  maxRows: "250",
};

const validEditDraft: ConnectionEditDraft = {
  name: "warehouse-read",
  host: "db.example.com",
  port: "5432",
  database: "analytics",
  tlsMode: "VERIFY_FULL",
  tcpKeepAlive: false,
  allowedSchemas: "public",
  allowedTables: "",
  statementTimeoutMs: "20000",
  maxRows: "500",
};

describe("parseList", () => {
  it("splits on newlines and commas and de-duplicates", () => {
    expect(parseList("a, b\nc, a\n\n d ")).toEqual(["a", "b", "c", "d"]);
  });

  it("returns an empty array for blank input", () => {
    expect(parseList("  \n , \n ")).toEqual([]);
  });
});

describe("parseIntegerInRange", () => {
  it("parses in-range integers", () => {
    expect(parseIntegerInRange("5", 1, 10)).toBe(5);
  });

  it("rejects out-of-range, non-integer, and non-numeric input", () => {
    expect(parseIntegerInRange("0", 1, 10)).toBeNull();
    expect(parseIntegerInRange("11", 1, 10)).toBeNull();
    expect(parseIntegerInRange("5.5", 1, 10)).toBeNull();
    expect(parseIntegerInRange("abc", 1, 10)).toBeNull();
    expect(parseIntegerInRange("", 1, 10)).toBeNull();
  });
});

describe("buildCreateInput", () => {
  it("builds a full create payload including PEM material", () => {
    const result = buildCreateInput(validDraft, {
      password: "s3cret",
      caCertificatePem: "CA",
      clientCertificatePem: "CERT",
      clientPrivateKeyPem: "KEY",
    });

    expect(result.kind).toBe("ok");
    if (result.kind === "ok") {
      expect(result.input).toEqual({
        name: "warehouse-read",
        kind: "POSTGRESQL",
        endpoint: { host: "db.example.com", port: 5432, database: "analytics" },
        secret: { username: "reader", password: "s3cret" },
        tls: {
          mode: "VERIFY_FULL",
          caCertificatePem: "CA",
          clientCertificatePem: "CERT",
          clientPrivateKeyPem: "KEY",
        },
        connectorOptions: { tcpKeepAlive: true },
        objectPolicy: { allowedSchemas: ["public", "reporting"], allowedTables: ["public.events"] },
        limits: { statementTimeoutMs: 15000, maxRows: 250 },
      });
    }
  });

  it("omits optional PEM fields when they are empty", () => {
    const result = buildCreateInput(validDraft, { ...emptySecrets, password: "p" });
    expect(result.kind).toBe("ok");
    if (result.kind === "ok") {
      expect(result.input.tls).toEqual({ mode: "VERIFY_FULL" });
    }
  });

  it.each([
    ["missing username", { ...validDraft, username: "  " }, emptySecrets, "Database username is required."],
    ["missing password", validDraft, { ...emptySecrets, password: "" }, "Database password is required."],
    ["mismatched client cert", validDraft, { ...emptySecrets, password: "p", clientCertificatePem: "CERT", clientPrivateKeyPem: "" }, "Client certificate and private key must be supplied together."],
    ["mismatched client key", validDraft, { ...emptySecrets, password: "p", clientCertificatePem: "", clientPrivateKeyPem: "KEY" }, "Client certificate and private key must be supplied together."],
  ])("rejects %s", (_label, draft, secrets, message) => {
    const result = buildCreateInput(draft, secrets);
    expect(result).toEqual({ kind: "invalid", message });
  });

  it("rejects invalid common fields via the shared validation", () => {
    const result = buildCreateInput({ ...validDraft, port: "0" }, { ...emptySecrets, password: "p" });
    expect(result.kind).toBe("invalid");
    if (result.kind === "invalid") {
      expect(result.message).toBe("Port must be a whole number between 1 and 65535.");
    }
  });
});

describe("buildUpdateInput", () => {
  it("builds an update payload", () => {
    const result = buildUpdateInput(validEditDraft);
    expect(result.kind).toBe("ok");
    if (result.kind === "ok") {
      expect(result.input).toEqual({
        name: "warehouse-read",
        endpoint: { host: "db.example.com", port: 5432, database: "analytics" },
        tlsMode: "VERIFY_FULL",
        connectorOptions: { tcpKeepAlive: false },
        objectPolicy: { allowedSchemas: ["public"], allowedTables: [] },
        limits: { statementTimeoutMs: 20000, maxRows: 500 },
      });
    }
  });

  it.each([
    ["blank name", { ...validEditDraft, name: "   " }, "Name is required."],
    ["blank host", { ...validEditDraft, host: " " }, "Host is required."],
    ["blank database", { ...validEditDraft, database: "" }, "Database name is required."],
    ["no schemas", { ...validEditDraft, allowedSchemas: " " }, "At least one allowed schema is required."],
    ["low timeout", { ...validEditDraft, statementTimeoutMs: "99" }, "Statement timeout must be between 100 and 60000 ms."],
    ["high rows", { ...validEditDraft, maxRows: "1001" }, "Maximum rows must be between 1 and 1000."],
  ])("rejects %s", (_label, draft, message) => {
    expect(buildUpdateInput(draft)).toEqual({ kind: "invalid", message });
  });
});