import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, beforeEach } from "vitest";

beforeEach(() => {
  // Isolate the dev-auth session between tests. sessionStorage is the only
  // persistence the sign-in flow uses; localStorage is not provided by jsdom
  // in this environment and is deliberately never read or written.
  sessionStorage.clear();
});

afterEach(() => {
  cleanup();
});
