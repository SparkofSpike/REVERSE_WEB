# Reverse_Web

**Reverse 企划官方网站** —— 一个 Web 站点，承载 Reverse 企划旗下的多个可玩模块。当前站点主页（`/`）面向访客展示企划与模块入口，点击进入对应系统；各子系统均需登录后使用。

当前已上线的模块：

- **TEST 战斗系统**（子路径 `/test`）：赛博桌游战斗裁决终端，用网页取代文字桌游里手掷骰子与数值核算的环节。已上线可玩。
- **国王棋（King's Chess）**（入口占位 `敬请期待`）：待开发模块，规则与实现设计见 [`docs/king-chess-design.md`](./docs/king-chess-design.md)。

---

## TEST 战斗系统

A cyberpunk tabletop battle assistant (Web edition) that replaces manual dice
rolling and number crunching in text-based tabletop RPGs.

Core capabilities: two-sided turn-based combat adjudication (speed
resolution, damage calculation, performance triggers), account system, deck
management, solo training and PVP/PVE battles, and battle report statistics.

- Rules blueprint: `TEST.游戏玩法.pdf` (design doc kept locally; not tracked
  in this repository — see `.gitignore`)
- Current stage: playable solo training, PVP and PVE battle loops with speed
  adjudication (including last-dash ties), damage/clash rules and a stage-style
  presentation (portraits, HP/EP bars, curtain transitions and action cues).

## 国王棋（King's Chess）

2–4 人、秘密落子、d20 比速、后落吞先落的吃子争分局棋。首个棋局「岚中对」：
积满 50 分胜，或令他人无棋可用。规则要点、裁决设计、待甲方确认的规则空白与里程碑切分
见 **[`docs/king-chess-design.md`](./docs/king-chess-design.md)**（设计属于实现侧，手册原件在甲方处）。

**现状**：设计阶段，官网主页已放入口占位，未开功能。

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

```
backend/    # Spring Boot backend (battle state machine, adjudication, accounts and decks)
frontend/   # Vue 3 frontend (display and command forwarding only, no battle logic)
assets/     # client-supplied art: stage background, portraits, curtain/last-dash transitions
docs/       # module design docs (e.g. king-chess-design.md)
ship.py     # local one-click deploy script (build, upload, verify, restart)
```

## Site Structure (frontend)

- `/` — public **portal**（Reverse 企划官网主页，免登录；TEST / 国王棋模块入口）
- `/login`, `/register` — public guest-only
- `/test/*` — TEST 战斗系统（需登录；`/test` 作战室、`/test/battle/:id`、`/test/pvp`、`/test/builds`、`/test/records`、`/test/profile`、`/test/design`、`/test/admin/users`）
- 其余任何未知路径经 Vue Router catch-all 重定向到 `/`

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
