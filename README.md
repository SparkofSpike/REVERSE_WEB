# RESERVE_WEB (codenamed TEST)

A cyberpunk tabletop battle assistant (Web edition) that replaces manual dice
rolling and number crunching in text-based tabletop RPGs.

Core capabilities: two-sided turn-based combat adjudication (speed
resolution, damage calculation, performance triggers), account system, deck
management, training dummy battles and battle report statistics.

- Rules blueprint: `TEST.游戏玩法.pdf` (design doc kept locally; not tracked
  in this repository — see `.gitignore`)
- Current stage: rules validation and framework scaffolding (Harness /
  prototype). Battle mode is single-player vs training dummy; PVP is planned
  for a later stage.

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

```
backend/    # Spring Boot backend (battle state machine, adjudication, accounts and decks)
frontend/   # Vue 3 frontend (display and command forwarding only, no battle logic)
ship.py     # local one-click deploy script (build, upload, verify, restart)
```

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
  on round end); the last-dash (生死时速) moment bursts outward from the
  center. All art is preloaded and served with a 7-day Cache-Control.
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
# Backend: 8 test classes / 52 tests (combat, dice, auth, build, card pack)
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
checks `http://111.229.241.95/` returns 200. Requirements: Node 20+, JDK 21,
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
