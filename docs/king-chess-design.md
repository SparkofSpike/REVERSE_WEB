# King's Chess — Design Document

- **Status**: in design (development not started)
- **Nature**: the second playable module of the Reverse Project (the homepage
  entry is a stub, coming soon)
- **Rule source**: the client's design manual (kept locally; not tracked)
- **Parent doc**: the site is the Reverse Project official website — see the
  root `README.md`

> This file is the **implementation-side** design distilled from the client
> manual. Anything the manual leaves unspecified is flagged **TODO(client)**;
> develop against a sensible default and note it in code comments.

---

## 1. Overview

King's Chess is a **2–4 player, secret-action, d20 speed-check, later-drop-eats-earlier
drop** eat-and-score board game.

- The board is **rotational**: each of the four players occupies a cardinal
  side, surrounding a central public area.
- Three parts:
  - **The Court** (棋局, central): holds all public resources (public pieces,
    private pieces, props). A different Court = a different rule set/gameplay.
  - **The Field** (棋场): a 4-cell lane on each side; one piece per cell,
    arrows indicating direction.
  - **The Rack** (格栏): the player's personal zone; typically unused in
    classic play.
- Pieces are **private** (each player's own set) and **public** (shared pool).

### Ranzhong Dui (岚中对) — the first and most standard rule set

- Provides a **Royal Core** as the scoring goal: earn points via eats, holds,
  etc.; first to the target score wins.
- **2–4 players**. At game start, roll a **d20** to decide turn order (Fields
  are assigned clockwise); one round = every player takes one action.

**Round flow**

1. **Refresh & decide (secret)**: at each player's turn, the four Fields
   refresh public pieces; the player secretly decides how many pieces to drop
   and where (may use private pieces or previously eaten public pieces).
2. **Resolve (open)**: once all decisions are in, each player rolls a **d20**;
   higher rolls drop first.
3. **Same-cell conflict**: the **later drop eats the earlier drop**.
4. **Round end**: score, recycle public pieces, remove eaten private pieces
   (see below); advance to the next round.

**Win conditions**: reach **50 points**, or leave every other player with no
pieces on board.

**Recycle / remove**: eaten **public** pieces return to the Court (unless
marked otherwise); eaten **private** pieces leave the table (King excepted).

---

## 2. Piece Table (Ranzhong Dui)

### Public pieces (24 per game: 8 Provisions / 6 Soldiers / 4 Horses / 4 Chariots / 2 Knights)

| Piece | Spawn rarity | Effect | Score |
| --- | --- | --- | --- |
| Provisions (粮草) | most common | — | +4 on eat |
| Soldier (兵) | fairly common | can be placed directly | +2 on eat |
| Horse (马) | uncommon | after eating, may advance one cell along the arrow during your action | — |
| Chariot (车) | rare | after eating, may move any distance within its Field (not out of it) | — |
| Knight (骑士) | rare | after eating, may recall during your action (cannot recall if your speed is below the eater's) | — |

### Private pieces (eaten → leave table, King excepted)

| Piece | Effect |
| --- | --- |
| King (国王) | returns to hand when eaten; 5 lives; eating any piece scores +3 |
| Queen (王后) | +8 points when eaten (manual reads "partly rd12", likely a typo) |
| Martyr (死士) | while placed, your pieces are protected: any piece eaten is eaten by the Martyr instead, and its cell is taken by the later drop |
| Strategist (谋士) | while placed, you may recall one piece from the table |

---

## 3. Implementation Design

Follows the project's iron rule: **adjudication lives only in the backend**
(win checks, eats, scores, random dice are all backend-produced); the frontend
only displays and forwards.

### Reuse

- **Randomness**: go through `DiceRoller` exclusively (never scatter `Random`).
- **Adjudication**: a new `kingchess` domain package (mirrors the existing
  `combat/`), pure logic, unit-testable.
- **API**: REST + DTO contract; any DTO change must be synced on both sides.
- **Accounts**: reuse the existing JWT / login / Pinia auth store.

### Backend domain model (draft)

```text
KingGame            — one game's state machine (gameId, phase, players, board, score, turn)
  phase             — WAITING / PLACING(secret) / RESOLVING(open) / ROUND_END / FINISHED
  players[4]        — per-player private hand, acquired public pieces, score, living pieces
  board             — the four Fields (4 cells each) + the Court (public pool)
  pieces            — piece instances (kind, owner, position, state)
  pendingActions    — each player's secret drop decisions (hidden until resolve)
```

### Resolve algorithm order (critical)

1. Collect all `pendingActions` (not broadcast during the decision phase).
2. Each player rolls d20 → order the drops by value.
3. Drop in order; same-cell conflicts resolve as "later drop eats earlier drop".
4. Movement / special effects: **priority TBD** — the order between eating and
   Horse advance / Chariot move / Knight recall needs client confirmation.
5. Score: eats, King lives, recycle / removal.
6. Win check: >= 50 points OR opponents have no pieces → FINISHED.

---

## 4. TODO(client) — rule gaps

1. **Spawn probabilities** per public piece ("random" only in the manual).
2. **Resolve order** between eating and Horse/Chariot/Knight special effects.
3. **Arrow direction mapping** inside a 4-cell Field (per-cell? uniform?),
   which drives the Horse's advance.
4. **Martyr chain**: does a Martyr's substitute re-trigger a new cell conflict?
5. **Placement limits** for private pieces (only the King's 5 lives are given).
6. **Queen score**: confirm 8 vs "rd12"; and the exact victory target (50 is
   the working assumption).

---

## 5. Milestone suggestion

- **M1 — Ranzhong Dui, playable locally**: hot-seat (2–4 on one screen) or
  vs a simple AI; covers the full round loop, d20, eats, scoring and win/lose.
  Keeps scope small and establishes the core loop first.
- **M2 — online multiplayer**: real-time room sync (mirrors the existing PVP
  room + SSE push pattern).
- **M3 — expansion**: additional Courts, unofficial private/public pieces,
  Rack mechanics.
