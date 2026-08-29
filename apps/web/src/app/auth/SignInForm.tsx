import { useState } from "react";
import type { SubmitEvent } from "react";

interface SignInFormProps {
  readonly onSignIn: (token: string) => void;
}

export function SignInForm({ onSignIn }: SignInFormProps) {
  const [token, setToken] = useState("");

  function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmed = token.trim();
    if (trimmed.length === 0) {
      return;
    }
    onSignIn(trimmed);
    setToken("");
  }

  return (
    <form className="sign-in-form" onSubmit={handleSubmit} noValidate>
      <h2 id="sign-in-title">Sign in with a development token</h2>
      <p>
        RivoQuery requires a pre-minted bearer token; the browser cannot create
        one itself. Set <code>VITE_RIVOQUERY_DEV_TOKEN</code> locally, or paste a
        token below.
      </p>
      <label htmlFor="dev-token" className="field-label">
        Development access token
      </label>
      <input
        id="dev-token"
        className="field-input"
        type="password"
        autoComplete="off"
        spellCheck={false}
        value={token}
        aria-describedby="sign-in-hint"
        onChange={(event) => { setToken(event.target.value); }}
      />
      <p id="sign-in-hint" className="field-hint">
        Stored only in this browser session and only for development. Never paste
        a production credential.
      </p>
      <button className="primary-button" type="submit" disabled={token.trim().length === 0}>
        Sign in
      </button>
    </form>
  );
}
