import { mc } from "../client.js";
import type { AuthLinkResult, AuthLinkStatus } from "../../types/dto.js";
import type { ProcedureInput } from "../../types/contract.js";

export type { AuthLinkStatus, AuthLinkResult };

/**
 * Consume a `/sx auth` code for a Discord user. Linking is performed on the Java side (the single DB
 * writer) via the typed `auth.link` procedure — the bot never writes the account link itself.
 */
export function linkAccount(args: ProcedureInput<"auth.link">): Promise<AuthLinkResult> {
  return mc.authLink(args);
}

/** Human-readable, ephemeral reply text for each link status. */
export function authStatusMessage(status: AuthLinkStatus, minecraftName: string): string {
  switch (status) {
    case "linked":
      return `Linked your Discord account to Minecraft player \`${minecraftName}\`.`;
    case "invalid":
      return "That auth code is invalid. Run `/sx auth` in Minecraft for a new code.";
    case "expired":
      return `The code for \`${minecraftName}\` expired. Run \`/sx auth\` in Minecraft again.`;
    case "already-used":
      return `That code for \`${minecraftName}\` was already used. Run \`/sx auth\` in Minecraft again.`;
    case "minecraft-already-linked":
      return `Minecraft player \`${minecraftName}\` is already linked.`;
    case "discord-already-linked":
      return `Your Discord account is already linked to \`${minecraftName}\`.`;
    case "disabled":
      return "Account linking is disabled on the server.";
  }
}
