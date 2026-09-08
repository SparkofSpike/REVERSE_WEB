# Reverse_Web

The official website of the **Reverse Project** — a web property that hosts a
set of playable modules from the Reverse universe. The public root (`/`) is a
portal introducing the project and its module entries; each module is entered
from there and requires a signed-in session.

Currently published modules:

- **TEST Combat System** (subpath `/test`): a cyberpunk tabletop battle
  adjudication terminal — live and playable.
- **King's Chess** (entry stubbed as coming soon): under design; rules and
  implementation notes live in [`docs/king-chess-design.md`](./docs/king-chess-design.md).

---

## TEST Combat System

A cyberpunk tabletop battle assistant (Web edition) replacing manual dice
rolling and number crunching in text-based tabletop RPGs. The frontend only
displays state and forwards commands; all victory checks, damage values and
random dice are produced by the backend.

### Features

**Battle adjudication** (backend-owned)

- Two-sided turn-based combat resolution: speed checks, damage and clash
  calculation, and performance (skill) triggers.
- Last-dash speed ties, curtain-driven round transitions, and a stage-style
  presentation driven by structured backend event data.
- A decision panel that is locked while animations play, so a new submission
  can never interleave with a still-running animation.

**Account & access**

- JWT login and registration, profile editing, password change and avatar
  upload.
- Role-gated routes: ADMIN sees the design workspace, OP manages user
  permissions.

**Combat modes**

- Solo training, plus PVP and PVE rooms with live Server-Sent Event updates.
- Initial perk selection, extra/special perk decisions, card play, surrender
  and draft saving.

**Builds & card packs**

- Persisted builds referencing a card pack and a set of character IDs.
- Pack card pool with characters, enemies, skills, perks and effects.

**Battle reports**

- Per-user report history and a detailed record view per battle.

**Design workspace** (ADMIN)

- Create and edit card packs, characters, enemies, generic skills, perks and
  effects via structured editors.

**Admin** (OP)

- User list with role and enabled-state management.

### Architecture

```text
+--------------------+      /api      +----------------------------------+
| Vue 3 SPA          | ------------> | Spring Boot (Java 21)             |
|  portal `/`        |               |  - controllers (thin HTTP)        |
|  `/test/*`         |  <--- SSE --- |  - services (business)            |
|  Pinia + Vue Router|   (PVP/PVE)   |  - combat/ adjudication (pure)    |
|  Naive UI + Axios  |               |  - repositories (JPA)             |
+--------------------+               |  - H2 file DB (AUTO_SERVER=TRUE)  |
                                     +----------------------------------+
```

Adjudication, dice and victory checks never leave the backend; the backend is
agnostic to frontend rendering details.

### API surface

All endpoints live under `/api` (the SPA fallback forwards non-API client
routes to `index.html`). Major groups: `auth` (register/login/profile),
`builds` (CRUD), `combat` (battle loop), `packs` + `pve` (enemies/rooms),
`pvp` (rooms, SSE events URL), `records`, `design` (packs/characters/enemies),
`admin` (users), `avatar`.

---

## King's Chess

A 2–4 player eat-and-score board game driven by **secret placement, d20 speed
checks and "later drop eats earlier drop"** resolves. The first and most
standard rule set is **Ranzhong Dui**: first to 50 points wins, or drain every
other player of pieces. The public pieces land on four Fields around the
central Court; each player works the Field facing them.

Rule summary, adjudication design, open questions for the client and a
milestone split live in [`docs/king-chess-design.md`](./docs/king-chess-design.md)
(design-side only; the rules are distilled from the client's design manual).

**Status**: design phase; the homepage entry is a stub with no functionality.

---

## Credits

Custom-built for [@XuChuanRenNIUBI](https://github.com/XuChuanRenNIUBI) (game design owner and commissioner).
This repository is open-sourced with the designer's explicit permission.

## License

Released under the [MIT License](LICENSE). Copyright (c) 2026 [@SparkofSpike](https://github.com/SparkofSpike) and [@XuChuanRenNIUBI](https://github.com/XuChuanRenNIUBI).

## Tech Stack

| Layer | Technology |
| --- | --- |
| Backend | Java 21 + Spring Boot 3.2 + Maven + Spring Security (JWT) + Spring Data JPA |
| Frontend | Vue 3 + Vite 6 + TypeScript + Naive UI + Pinia + Vue Router + Axios |
| Database | H2 file mode (`AUTO_SERVER=TRUE`), JPA abstraction, switchable to MySQL later |
| Data exchange | JSON, frontend and backend strictly follow the DTO contract |

## Repository Layout

```text
Reverse_Web/
├── backend/     # Spring Boot backend (state machines, adjudication, accounts, decks)
│   └── src/main/java/com/test/engine/
│       ├── controller/   # thin HTTP layer + SPA fallback
│       ├── service/      # business logic (combat, auth, build, pvp/pve, design, admin)
│       ├── combat/       # battle state machine & adjudication (pure, unit-testable)
│       ├── dto/          # request/response DTOs (incl. dto/combat views)
│       ├── entity/       # JPA entities
│       ├── repository/   # Spring Data repositories
│       ├── security/     # JWT
│       ├── exception/    # unified error handling
│       └── utils/        # DiceRoller (single source of randomness)
├── frontend/    # Vue 3 SPA (display + command forwarding only)
│   └── src/
│       ├── api/          # typed API clients per domain
│       ├── components/   # AppNav
│       ├── router/       # vue-router config (/ portal, /test/* module)
│       ├── stores/       # Pinia (auth)
│       ├── types/        # shared TS types
│       └── views/        # pages: portal, auth, battle, pvp, builds, records, design, admin
├── assets/      # client-supplied art (stage background, portraits, transitions)
├── docs/        # module design docs (e.g. king-chess-design.md)
└── ship.py      # local one-click deploy (build, upload, verify, restart)
```

## Site Structure (frontend)

- `/` — public **portal** (Reverse Project homepage; no login; module entries
  for TEST and King's Chess)
- `/login`, `/register` — public, guest-only
- `/test/*` — TEST Combat System (login required): `/test` war-room,
  `/test/battle/:id`, `/test/pvp`, `/test/builds`, `/test/records`,
  `/test/profile`, `/test/design`, `/test/admin/users`
- Any other unknown path is redirected to `/` via the catch-all route

## Architecture Principles

- Fully separated frontend/backend: all victory checks, damage values and
  random dice are produced by the backend.
- The frontend only displays state and forwards commands; it never touches
  battle adjudication logic.
- The backend never cares about frontend rendering details.

## Battle View

The battle screen is a stage presentation, not a card grid:

- Combatants stand face-to-face in the middle of the field on the stage
  background (`/assets/fight_background.webp`), each with a portrait
  (`/assets/{templateId}.webp`, falling back to an initial-letter
  placeholder), name, a blood-red HP bar and a green EP bar below.
- Round transitions play natural curtain sweeps (rise on round start, fall
  on round end); the last-dash moment bursts outward from the center. All art
  is preloaded and served with a 7-day Cache-Control.
- Performance cues are driven by structured event data from the backend:
  action labels (Attack!/Defend!/Skill!/Heal!/...), a camera zoom anchored
  on the acting unit, step-toward movement, target shake and floating
  damage/heal numbers. A unit's actions play serially (Attack finishes
  before Chase starts) and HP settles together with each damage cue.
- The decision panel is locked while animations play, so a new submission
  can never interleave with a still-running animation.

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

## Testing

```bash
# Backend: 20 test classes / 175 tests (combat, dice, auth, PVP/PVE, build, card pack)
cd backend
mvn test

# Frontend: type-check (vue-tsc) + production build
cd frontend
npm run build
```

## CI/CD

Deployment is done from the local machine via `ship.py` (fast, domestic direct
link; avoids slow cross-border GitHub Actions uploads):

```bash
python ship.py               # full deploy: build frontend+backend, upload, verify, restart
python ship.py --upload-only # skip builds, upload existing jar only
```

The script builds the frontend, bundles `dist` into the backend jar, stops the
service, uploads via scp, verifies SHA256 + jar integrity, then restarts and
checks `http://8.133.234.22/` returns 200. Requirements: Node 20+, JDK 21,
Maven, OpenSSH (key `~/.ssh/test_deploy`).

A GitHub Actions workflow (`.github/workflows/deploy.yml`) exists but is
deprecated: cross-border 50MB scp uploads corrupt the jar and chunked upload
takes ~40 minutes per deploy. It is kept as `workflow_dispatch` (manual) only.

## Git Conventions

- Atomic commits: one commit, one logical change.
- Commit messages follow the Angular Convention with English descriptions:

```
<type>(<scope>): <subject>
```

- type: feat / fix / docs / style / refactor / test / chore
- scope examples: combat, dice, auth, ui-log, api-dto
