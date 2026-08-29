import type { ReactNode } from "react";

import type { SafeProblem } from "../../api/problemDetails";

export function RequestId({ value }: { readonly value: string | null }) {
  if (value === null) {
    return null;
  }
  return (
    <p className="request-id">
      Request ID <code>{value}</code>
    </p>
  );
}

export function LoadingStatus({ children }: { readonly children: string }) {
  return (
    <section className="status-panel" role="status" aria-live="polite">
      <span className="status-dot" aria-hidden="true" />
      <p>{children}</p>
    </section>
  );
}

interface ProblemAlertProps {
  readonly problem: SafeProblem;
  readonly onRetry?: () => void;
}

export function ProblemAlert({ problem, onRetry }: ProblemAlertProps) {
  return (
    <section className="problem-alert" role="alert" aria-label={problem.title}>
      <p className="problem-title">
        {problem.title} <span className="problem-code">{problem.code}</span>
      </p>
      <p className="problem-detail">{problem.detail}</p>
      <RequestId value={problem.requestId} />
      {onRetry !== undefined ? (
        <button type="button" className="secondary-button" onClick={onRetry}>
          Try again
        </button>
      ) : null}
    </section>
  );
}

interface EmptyStateProps {
  readonly title: string;
  readonly children: ReactNode;
}

export function EmptyState({ title, children }: EmptyStateProps) {
  return (
    <section className="empty-state">
      <h3>{title}</h3>
      <div>{children}</div>
    </section>
  );
}
