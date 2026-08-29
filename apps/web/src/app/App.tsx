import { useMemo } from "react";

import { platformHealthClient } from "../api/health";
import type { PlatformHealthClient } from "../api/health";
import { createRivoqueryApi } from "../api/rivoqueryApi";
import type { RivoqueryApi } from "../api/rivoqueryApi";
import { SignInForm } from "./auth/SignInForm";
import { useDevSession } from "./auth/useDevSession";
import { ConnectionsApp } from "./connections/ConnectionsApp";
import { HealthStatus } from "./HealthStatus";
import { usePlatformHealth } from "./usePlatformHealth";

const foundationPrinciples = [
  {
    title: "Preview before execution",
    description:
      "Generated SQL remains visible and untrusted until deterministic policy approves it.",
  },
  {
    title: "Read-only by design",
    description:
      "RivoQuery layers authorization, validation, database permissions, and execution limits.",
  },
  {
    title: "Bounded results",
    description:
      "Server-owned time, row, and payload limits keep every retrieval controlled.",
  },
] as const;

interface AppProps {
  readonly healthClient?: PlatformHealthClient;
  readonly createApi?: (token: string) => RivoqueryApi;
}

export function App({
  healthClient = platformHealthClient,
  createApi = (token) => createRivoqueryApi({ token }),
}: AppProps = {}) {
  const session = useDevSession();
  const { retry, state } = usePlatformHealth(healthClient);

  const api = useMemo(
    () => (session.token === null ? null : createApi(session.token)),
    [createApi, session.token],
  );

  const signedIn = api !== null;

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">
        Skip to main content
      </a>

      <header className="site-header">
        <a className="brand" href="/" aria-label="RivoQuery home">
          <span className="brand-mark" aria-hidden="true">
            RQ
          </span>
          <span>RivoQuery</span>
        </a>
        <div className="header-actions">
          <span className="phase-label">Connection control plane</span>
          {signedIn ? (
            <button className="secondary-button" type="button" onClick={session.signOut}>
              Sign out
            </button>
          ) : null}
        </div>
      </header>

      <div className="shell-body">
        <aside className="sidebar" aria-label="Workspace navigation">
          <p className="sidebar-eyebrow">{signedIn ? "Console" : "Workspace"}</p>
          <nav aria-label="Primary navigation">
            <ul className="navigation-list">
              {signedIn ? (
                <li>
                  <a aria-current="page" href="#main-content">
                    Connections
                  </a>
                </li>
              ) : (
                <li>
                  <a aria-current="page" href="/">
                    Overview
                  </a>
                </li>
              )}
            </ul>
          </nav>
        </aside>

        <main id="main-content" tabIndex={-1}>
          {signedIn ? (
            <ConnectionsApp api={api} />
          ) : (
            <>
              <section className="hero" aria-labelledby="page-title">
                <p className="eyebrow">Security-first database retrieval</p>
                <h1 id="page-title">Ask naturally. Verify before anything runs.</h1>
                <p className="hero-copy">
                  RivoQuery is being built to turn questions into transparent,
                  policy-approved, read-only database queries.
                </p>
                <HealthStatus state={state} onRetry={retry} />

                <section className="sign-in-card" aria-labelledby="sign-in-title">
                  <SignInForm onSignIn={session.signIn} />
                </section>
              </section>

              <section className="principles" aria-labelledby="principles-title">
                <div className="section-heading">
                  <p className="eyebrow">Foundation principles</p>
                  <h2 id="principles-title">Trust comes from enforceable controls.</h2>
                </div>

                <div className="principle-grid">
                  {foundationPrinciples.map((principle, index) => (
                    <article className="principle-card" key={principle.title}>
                      <span className="card-number" aria-hidden="true">
                        {String(index + 1).padStart(2, "0")}
                      </span>
                      <h3>{principle.title}</h3>
                      <p>{principle.description}</p>
                    </article>
                  ))}
                </div>
              </section>
            </>
          )}
        </main>
      </div>

      <footer>
        <p>RivoQuery platform foundation</p>
        <p>Credentials and result rows never belong in browser storage.</p>
      </footer>
    </div>
  );
}
