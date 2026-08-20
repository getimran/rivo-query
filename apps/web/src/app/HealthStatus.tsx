import type { PlatformHealthViewState } from "./usePlatformHealth";

interface HealthStatusProps {
  readonly onRetry: () => void;
  readonly state: PlatformHealthViewState;
}

function RequestId({ value }: { readonly value: string | null }) {
  return value === null ? null : (
    <p className="request-id">
      Request ID <code>{value}</code>
    </p>
  );
}

export function HealthStatus({ onRetry, state }: HealthStatusProps) {
  if (state.kind === "loading") {
    return (
      <section className="health-status is-loading" aria-labelledby="health-title" role="status">
        <span className="status-dot" aria-hidden="true" />
        <div>
          <h2 id="health-title">Checking platform availability</h2>
          <p>Contacting the RivoQuery API and control database.</p>
        </div>
      </section>
    );
  }

  if (state.kind === "ready") {
    return (
      <section className="health-status is-ready" aria-labelledby="health-title" role="status">
        <span className="status-dot" aria-hidden="true" />
        <div>
          <h2 id="health-title">Platform ready</h2>
          <p>The API is live and the control database is available.</p>
          <RequestId value={state.requestId} />
        </div>
      </section>
    );
  }

  if (state.kind === "not-ready") {
    return (
      <section className="health-status is-warning" aria-labelledby="health-title" role="alert">
        <span className="status-dot" aria-hidden="true" />
        <div>
          <h2 id="health-title">Platform not ready</h2>
          <p>The API is live, but its control database is temporarily unavailable.</p>
          <p className="problem-code">Status code: {state.code}</p>
          <RequestId value={state.requestId} />
          <button type="button" onClick={onRetry}>
            Check again
          </button>
        </div>
      </section>
    );
  }

  return (
    <section className="health-status is-error" aria-labelledby="health-title" role="alert">
      <span className="status-dot" aria-hidden="true" />
      <div>
        <h2 id="health-title">Unable to check platform</h2>
        <p>RivoQuery could not confirm API availability. Try again shortly.</p>
        <RequestId value={state.requestId} />
        <button type="button" onClick={onRetry}>
          Try again
        </button>
      </div>
    </section>
  );
}
