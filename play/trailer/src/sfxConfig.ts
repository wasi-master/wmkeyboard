// Per-effect mix controls — the single place to tune or kill a sound.
// `vol` scales the volume each call site passes; `on: false` mutes the
// effect everywhere it is used.
export const SFX_MIX: Record<string, { vol: number; on: boolean }> = {
  whoosh: { vol: 1, on: true }, // scene transitions
  pop: { vol: 1, on: false }, // intro tagline, outro CTA
  swap: { vol: 1, on: false }, // Tools/Modes cut changes
  impact: { vol: 1, on: false }, // logo landings
  riser: { vol: 1, on: true }, // outro build-up
};

