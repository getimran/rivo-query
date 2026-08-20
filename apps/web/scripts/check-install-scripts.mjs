import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { stdout } from "node:process";
import { fileURLToPath } from "node:url";

import { verifyInstallScriptPolicy } from "./install-script-policy.mjs";

const projectDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const npmConfiguration = readFileSync(resolve(projectDirectory, ".npmrc"), "utf8");
const packageManifest = JSON.parse(
  readFileSync(resolve(projectDirectory, "package.json"), "utf8"),
);
const packageLock = JSON.parse(
  readFileSync(resolve(projectDirectory, "package-lock.json"), "utf8"),
);

const reviewedScripts = verifyInstallScriptPolicy({
  npmConfiguration,
  packageLock,
  packageManifest,
});

stdout.write(
  `Install-script policy verified for ${reviewedScripts.join(", ") || "no packages"}.\n`,
);
