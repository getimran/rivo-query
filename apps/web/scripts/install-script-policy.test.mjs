import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { verifyInstallScriptPolicy } from "./install-script-policy.mjs";

const reviewedManifest = {
  allowScripts: {
    "build-tool@1.2.3": true,
  },
};

const reviewedLock = {
  packages: {
    "node_modules/build-tool": {
      hasInstallScript: true,
      version: "1.2.3",
    },
  },
};

describe("install-script policy", () => {
  it("accepts an exact-version reviewed lifecycle script in strict mode", () => {
    assert.deepEqual(
      verifyInstallScriptPolicy({
        npmConfiguration: "strict-allow-scripts=true\n",
        packageLock: reviewedLock,
        packageManifest: reviewedManifest,
      }),
      ["build-tool@1.2.3"],
    );
  });

  it("rejects a lifecycle script introduced only through the lockfile", () => {
    assert.throws(
      () =>
        verifyInstallScriptPolicy({
          npmConfiguration: "strict-allow-scripts=true\n",
          packageLock: {
            packages: {
              ...reviewedLock.packages,
              "node_modules/unreviewed-tool": {
                hasInstallScript: true,
                version: "9.9.9",
              },
            },
          },
          packageManifest: reviewedManifest,
        }),
      /Lifecycle-script review drift/u,
    );
  });

  it("rejects configuration that does not fail closed", () => {
    assert.throws(
      () =>
        verifyInstallScriptPolicy({
          npmConfiguration: "strict-allow-scripts=false\n",
          packageLock: reviewedLock,
          packageManifest: reviewedManifest,
        }),
      /strict-allow-scripts=true/u,
    );
  });
});
