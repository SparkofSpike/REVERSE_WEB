import { reactive } from 'vue'
import type { KingPieceKind } from '@/types/kingchess'

/**
 * Client art for King's Chess.
 *
 * The client's art pack ships two variants per piece kind and lives as a local
 * asset directory under `public/assets/kingchess/` — it is git-ignored, so a
 * fresh clone (and any build from a fresh clone) has none of it. Every lookup
 * here is therefore *optional*: when a file is missing the caller falls back to
 * the built-in glyph, which keeps the board readable with or without art.
 *
 *   icon/<kind>.png   456px framed plate (white card look) — public pieces in
 *                     the central court.
 *   piece/<kind>.png  2160px transparent sculpture — pieces standing on the
 *                     four fields, and the hand chips.
 *
 * Only eight of the nine kinds were delivered; CHARIOT (车) has no art.
 */

/** Piece kind -> file stem. Kinds missing here never resolve to a URL. */
const STEM: Partial<Record<KingPieceKind, string>> = {
  KING: 'king',
  QUEEN: 'queen',
  MARTYR: 'martyr',
  STRATEGIST: 'strategist',
  PROVISION: 'provision',
  SOLDIER: 'soldier',
  HORSE: 'horse',
  KNIGHT: 'knight'
}

export type KingArtVariant = 'icon' | 'piece'

const BASE = '/assets/kingchess'
const VARIANTS: KingArtVariant[] = ['icon', 'piece']

function urlOf(kind: KingPieceKind, variant: KingArtVariant): string | null {
  const stem = STEM[kind]
  return stem ? `${BASE}/${variant}/${stem}.png` : null
}

function keyOf(kind: KingPieceKind, variant: KingArtVariant): string {
  return `${variant}:${kind}`
}

/** key -> true once the bitmap decoded, false once loading failed. */
const decoded = reactive<Record<string, boolean>>({})
/** Keys already handed to an Image() probe, so repeated calls do not reload. */
const probing = new Set<string>()

function probe(kind: KingPieceKind, variant: KingArtVariant): void {
  const url = urlOf(kind, variant)
  if (url === null || typeof Image === 'undefined') return
  const key = keyOf(kind, variant)
  if (probing.has(key)) return
  probing.add(key)
  const image = new Image()
  image.onload = () => {
    decoded[key] = true
  }
  image.onerror = () => {
    decoded[key] = false
  }
  image.src = url
}

/**
 * Starts decoding every kind/variant pair. Idempotent: safe to call from a
 * component setup on each mount.
 */
export function preloadKingArt(): void {
  for (const kind of Object.keys(STEM) as KingPieceKind[]) {
    for (const variant of VARIANTS) probe(kind, variant)
  }
}

/**
 * The URL to render, or `null` while the bitmap is still loading / missing.
 *
 * Deliberately strict: the board shows the glyph until the image has actually
 * decoded, so a slow or absent asset pack never produces a broken-image flash
 * or a layout jump mid-round.
 */
export function kingArt(kind: KingPieceKind, variant: KingArtVariant): string | null {
  const url = urlOf(kind, variant)
  if (url === null) return null
  return decoded[keyOf(kind, variant)] === true ? url : null
}

/** True when this kind has art in the pack at all (used by tests/diagnostics). */
export function hasKingArt(kind: KingPieceKind): boolean {
  return kind in STEM
}
