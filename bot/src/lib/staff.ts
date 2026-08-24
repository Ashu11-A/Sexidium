import { PermissionFlagsBits, type ChatInputCommandInteraction } from "discord.js";
import { env } from "#env";

/** True when the caller is in the configured ADMIN_ROLE_ID, or is a server administrator. */
export function isStaff(interaction: ChatInputCommandInteraction): boolean {
  const member = interaction.member;
  if (env.ADMIN_ROLE_ID && member) {
    const roles = member.roles as unknown;
    if (Array.isArray(roles)) return roles.includes(env.ADMIN_ROLE_ID);
    const cache = (roles as { cache?: { has(id: string): boolean } }).cache;
    return cache ? cache.has(env.ADMIN_ROLE_ID) : false;
  }
  return interaction.memberPermissions?.has(PermissionFlagsBits.Administrator) ?? false;
}
