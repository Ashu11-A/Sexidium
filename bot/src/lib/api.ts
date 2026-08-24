import { env } from "#env";

const API_URL = env.API_URL.replace(/\/$/, "");

/**
 * One leaderboard row as served by the Java HTTP bridge (`/rank`, `/player`, `/discord`). A single
 * Discord account may own several Minecraft names — points/wins/kills/games are summed across them and
 * `names` lists each one. `rankClass`/`rankColor` are resolved server-side from the summed level.
 */
export interface LeaderboardEntry {
  discordUserId: string | null;
  name: string;
  names: string[];
  points: number;
  level: number;
  wins: number;
  kills: number;
  games: number;
  rankClass: string;
  rankColor: string;
}

export type RunResult = "ok" | "forbidden" | "disabled" | "unauthorized" | "error";

async function getJson<T>(path: string): Promise<T | null> {
  try {
    const res = await fetch(`${API_URL}${path}`);
    if (!res.ok) return null;
    return (await res.json()) as T;
  } catch {
    return null;
  }
}

/** Top players, aggregated by Discord account, highest combined score first. */
export async function fetchTop(limit = 10): Promise<LeaderboardEntry[]> {
  return (await getJson<LeaderboardEntry[]>(`/rank?limit=${limit}`)) ?? [];
}

/** Aggregated totals for the Discord account that owns a Minecraft name. */
export async function fetchPlayer(name: string): Promise<LeaderboardEntry | null> {
  return getJson<LeaderboardEntry>(`/player?name=${encodeURIComponent(name)}`);
}

/** Aggregated totals for a Discord user id (used to render/sync a Discord member's rank). */
export async function fetchByDiscordId(discordUserId: string): Promise<LeaderboardEntry | null> {
  return getJson<LeaderboardEntry>(`/discord?id=${encodeURIComponent(discordUserId)}`);
}

/** Runs a console command on the Minecraft server via the token-gated bridge (staff actions/events). */
export async function runServerCommand(command: string): Promise<RunResult> {
  try {
    const res = await fetch(`${API_URL}/command`, {
      method: "POST",
      headers: { "X-Sexidium-Token": env.API_TOKEN ?? "", "Content-Type": "text/plain" },
      body: command,
    });
    if (res.ok) return "ok";
    if (res.status === 403) return "forbidden";
    if (res.status === 503) return "disabled";
    if (res.status === 401) return "unauthorized";
    return "error";
  } catch {
    return "error";
  }
}
