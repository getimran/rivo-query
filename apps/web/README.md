# RivoQuery web console

React and strict TypeScript application shell for the RivoQuery web console.

## Local workflow

Use a Node.js version accepted by `package.json`, then create the initial lockfile
and run the checks from the repository root:

```bash
npm --prefix apps/web install
npm --prefix apps/web run api:check
npm --prefix apps/web run install-scripts:check
npm --prefix apps/web run lint
npm --prefix apps/web run test
npm --prefix apps/web run build
npm --prefix apps/web run dev
```

After the initial reviewed lockfile is committed, use `npm ci` instead of
`npm install` in CI and for reproducible clean installs.

Lifecycle scripts fail closed during install through `strict-allow-scripts`.
Every package with an install script must have an exact-version review entry in
`package.json`; `install-scripts:check` compares that review set with the lockfile
and confirms npm has no pending approvals. Do not use `--ignore-scripts`,
`--legacy-peer-deps`, or `--force` to bypass this policy.

## Contract boundary and local API

The OpenAPI document under `apps/api` is the API source of truth. Task P1-070
generates immutable TypeScript types into `src/api/generated`; do not add
handwritten backend DTOs or edit generated files. Regenerate after a contract
change and fail CI on drift with:

```bash
npm --prefix apps/web run api:generate
npm --prefix apps/web run api:check
```

The browser calls same-origin `/api` paths through `openapi-fetch`. During local
development Vite proxies only `/api` to the fixed loopback target
`http://127.0.0.1:8080`; it does not expose an unrestricted or secret-bearing
target setting to browser code. Production routing must forward `/api` to the
RivoQuery API at the ingress or CDN boundary.

## Toolchain baseline

- React 19.2 for the runtime and Vite 7.3 for development and production builds.
- TypeScript 5.9 with strict, unchecked-index, and exact-optional checks.
- ESLint 9 with type-aware TypeScript, React Hooks, refresh, and JSX accessibility rules.
- Vitest 4, Testing Library, jsdom, and axe-core for behavior, semantics, keyboard, and automated accessibility coverage.
- openapi-typescript 7.13 and openapi-fetch 0.17 for deterministic contract generation and typed requests.

Direct dependencies are exact-pinned. Review and commit the generated npm lockfile
before this application enters CI so transitive dependencies are reproducible too.
