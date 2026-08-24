// The seven Sexidium rank classes, worst → best. This table mirrors the Java
// `com.sexidium.core.rank.RankClass` enum exactly so Discord and Minecraft agree on every tag.
// The HTTP bridge also returns the resolved class/colour, so prefer the server's value when present
// and fall back to deriving it from the level here.

export interface RankClassDef {
  name: string;
  color: string; // #RRGGBB
  minLevel: number;
  symbol: string; // Greek letter used as a compact tag glyph
}

export const RANK_CLASSES: RankClassDef[] = [
  { name: "Omega", color: "#9AA0A6", minLevel: 0, symbol: "Ω" },
  { name: "Epsilon", color: "#57F287", minLevel: 5, symbol: "Ε" },
  { name: "Delta", color: "#1ABC9C", minLevel: 10, symbol: "Δ" },
  { name: "Gamma", color: "#3498DB", minLevel: 20, symbol: "Γ" },
  { name: "Beta", color: "#9B59B6", minLevel: 35, symbol: "Β" },
  { name: "Alpha", color: "#F0B232", minLevel: 55, symbol: "Α" },
  { name: "Sigma", color: "#E74C3C", minLevel: 80, symbol: "Σ" },
];

export function classForLevel(level: number): RankClassDef {
  let resolved = RANK_CLASSES[0];
  for (const candidate of RANK_CLASSES) {
    if (level >= candidate.minLevel) resolved = candidate;
  }
  return resolved;
}

export function classByName(name?: string | null): RankClassDef | undefined {
  if (!name) return undefined;
  const lower = name.trim().toLowerCase();
  return RANK_CLASSES.find((rankClass) => rankClass.name.toLowerCase() === lower);
}

/** Prefer the server-resolved class; fall back to deriving it from the level. */
export function resolveClass(entry: { rankClass?: string | null; level: number }): RankClassDef {
  return classByName(entry.rankClass) ?? classForLevel(entry.level);
}
