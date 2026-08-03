# RESERVE_WEB (codenamed TEST)

A cyberpunk tabletop battle assistant (Web edition) that replaces manual dice
rolling and number crunching in text-based tabletop RPGs.

Core capabilities: two-sided turn-based combat adjudication (speed
resolution, damage calculation, performance triggers), account system, deck
management, training dummy battles and battle report statistics.

- Rules blueprint: `TEST.游戏玩法.pdf` (in this repository root)
- Current stage: rules validation and framework scaffolding (Harness /
  prototype). Battle mode is single-player vs training dummy; PVP is planned
  for a later stage.

## Tech Stack

| Layer | Technology |
| --- | --- |
| Backend | Java 21 + Spring Boot 3.2 + Maven + Spring Security (JWT) + Spring Data JPA |
| Frontend | Vue 3 + Vite 5 + TypeScript + Naive UI + Pinia + Vue Router + Axios |
| Database | H2 file mode (`AUTO_SERVER=TRUE`), JPA abstraction, switchable to MySQL later |
| Data exchange | JSON, frontend and backend strictly follow the DTO contract |

## Repository Layout

```
backend/    # Spring Boot backend (battle state machine, adjudication, accounts and decks)
frontend/   # Vue 3 frontend (display and command forwarding only, no battle logic)
```

## Architecture Principles

- Fully separated frontend/backend: all victory checks, damage values and
  random dice are produced by the backend.
- The frontend only displays state and forwards commands; it never touches
  battle adjudication logic.
- The backend never cares about frontend rendering details.

## Development

```bash
# Backend (default port 5566, H2 data files under backend/data/)
cd backend
mvn spring-boot:run

# Frontend (dev server on 5173, proxies /api to the backend)
cd frontend
npm install
npm run dev
```

## CI/CD

Pushing to `main` triggers a GitHub Actions workflow that builds the backend
and frontend, packages the frontend `dist` into the backend jar, uploads it
to the deployment server and restarts the service. See
`.github/workflows/deploy.yml`.

## Git Conventions

- Atomic commits: one commit, one logical change.
- Commit messages follow the Angular Convention with English descriptions:

```
<type>(<scope>): <subject>
```

- type: feat / fix / docs / style / refactor / test / chore
- scope examples: combat, dice, auth, ui-log, api-dto
