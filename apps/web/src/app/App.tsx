import { platformHealthClient } from "../api/health";
import type { PlatformHealthClient } from "../api/health";
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
}

export function App({ healthClient = platformHealthClient }: AppProps = {}) {
  const { retry, state } = usePlatformHealth(healthClient);

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
        <span className="phase-label">Platform foundation</span>
      </header>

      <div className="shell-body">
        <aside className="sidebar" aria-label="Workspace navigation">
          <p className="sidebar-eyebrow">Workspace</p>
          <nav aria-label="Primary navigation">
            <ul className="navigation-list">
              <li>
                <a aria-current="page" href="/">
                  Overview
                </a>
              </li>
            </ul>
          </nav>

          <div className="planned-section" aria-labelledby="planned-title">
            <h2 id="planned-title">Coming in later phases</h2>
            <ul>
              <li>Connections</li>
              <li>Query workspace</li>
              <li>Audit activity</li>
            </ul>
          </div>
        </aside>

        <main id="main-content" tabIndex={-1}>
          <section className="hero" aria-labelledby="page-title">
            <p className="eyebrow">Security-first database retrieval</p>
            <h1 id="page-title">Ask naturally. Verify before anything runs.</h1>
            <p className="hero-copy">
              RivoQuery is being built to turn questions into transparent,
              policy-approved, read-only database queries.
            </p>
            <HealthStatus state={state} onRetry={retry} />
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
        </main>
      </div>

      <footer>
        <p>RivoQuery platform foundation</p>
        <p>Credentials and result rows never belong in browser storage.</p>
      </footer>
    </div>
  );
}
