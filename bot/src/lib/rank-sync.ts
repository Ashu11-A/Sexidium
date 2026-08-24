import type { Guild, GuildMember, Role } from "discord.js";
import { RANK_CLASSES, resolveClass } from "./ranks.js";
import { mc } from "../minecraft/index.js";

// Discord nickname format requested by the design: "<real nickname> | <Class>".
const TAG_SEPARATOR = " | ";
const MAX_NICKNAME_LENGTH = 32;

/**
 * Ensures one Discord role per rank class exists (created with the class colour if missing) and
 * returns a name → Role map. The role is the visible "tag" alongside the "<nick> | <Class>" nickname.
 */
export async function ensureRankRoles(guild: Guild): Promise<Map<string, Role>> {
  const roles = new Map<string, Role>();
  for (const rankClass of RANK_CLASSES) {
    let role = guild.roles.cache.find((candidate) => candidate.name === rankClass.name);
    if (!role) {
      role = await guild.roles
        .create({
          name: rankClass.name,
          color: rankClass.color as `#${string}`,
          hoist: false,
          mentionable: false,
          reason: "Sexidium rank class tag",
        })
        .catch(() => undefined);
    }
    if (role) roles.set(rankClass.name, role);
  }
  return roles;
}

/**
 * Syncs a single member's rank tag: assigns the correct class role (removing the others) and renames
 * them to "<base nickname> | <Class>". Returns the applied class name, or null when the member has no
 * linked Sexidium account. All mutations are best-effort (role hierarchy / Manage Nicknames perms).
 */
export async function syncMemberRank(member: GuildMember, roleMap?: Map<string, Role>): Promise<string | null> {
  let entry;
  try {
    entry = await mc.rankByDiscord(member.id);
  } catch {
    return null; // bridge offline — leave the member's tag as-is
  }
  if (!entry) return null;

  const rankClass = resolveClass(entry);
  const roles = roleMap ?? (await ensureRankRoles(member.guild));
  const targetRole = roles.get(rankClass.name);

  const rankRoleIds = new Set(Array.from(roles.values(), (role) => role.id));
  const toRemove = member.roles.cache.filter((role) => rankRoleIds.has(role.id) && role.id !== targetRole?.id);
  if (toRemove.size > 0) await member.roles.remove(toRemove).catch(() => undefined);
  if (targetRole && !member.roles.cache.has(targetRole.id)) {
    await member.roles.add(targetRole).catch(() => undefined);
  }

  const base = stripTag(member.nickname ?? member.user.globalName ?? member.user.username);
  const nickname = `${base}${TAG_SEPARATOR}${rankClass.name}`.slice(0, MAX_NICKNAME_LENGTH);
  await member.setNickname(nickname, "Sexidium rank tag").catch(() => undefined);

  return rankClass.name;
}

function stripTag(name: string): string {
  const index = name.indexOf(TAG_SEPARATOR);
  return (index >= 0 ? name.slice(0, index) : name).trim();
}
