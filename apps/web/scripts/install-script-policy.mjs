export function verifyInstallScriptPolicy({ npmConfiguration, packageLock, packageManifest }) {
  const strictAllowScripts = npmConfiguration
    .split(/\r?\n/u)
    .some((line) => line.trim() === "strict-allow-scripts=true");

  if (!strictAllowScripts) {
    throw new Error(".npmrc must enable strict-allow-scripts=true");
  }

  const reviewEntries = Object.entries(packageManifest.allowScripts ?? {});
  if (reviewEntries.some(([, decision]) => decision !== true && decision !== false)) {
    throw new Error("Every lifecycle-script review must be an explicit boolean decision");
  }

  const reviewedScripts = reviewEntries.map(([packageVersion]) => packageVersion).sort();
  const lockedScripts = Object.entries(packageLock.packages ?? {})
    .filter(([, metadata]) => metadata.hasInstallScript === true)
    .map(([packagePath, metadata]) => {
      const packageName = packagePath.slice(packagePath.lastIndexOf("node_modules/") + 13);
      return `${packageName}@${metadata.version}`;
    })
    .sort();

  if (JSON.stringify(reviewedScripts) !== JSON.stringify(lockedScripts)) {
    throw new Error(
      `Lifecycle-script review drift: reviewed=${reviewedScripts.join(",")}; locked=${lockedScripts.join(",")}`,
    );
  }

  return reviewedScripts;
}
