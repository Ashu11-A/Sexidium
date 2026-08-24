import { mc } from "../client.js";
import type { AuthDecision, AuthDecisionStatus, AuthSession } from "../../types/dto.js";
import type { ProcedureInput } from "../../types/contract.js";

export type { AuthDecision, AuthDecisionStatus, AuthSession };

/**
 * Apply one Approve/Deny press.
 *
 * Every check that matters — ownership, replay, expiry — happens on the Java side against the
 * request row. This function only carries the press across; it decides nothing.
 */
export function decideRequest(args: ProcedureInput<"auth.decide">): Promise<AuthDecision> {
  return mc.authDecide(args);
}

/** Every live IP+name session a Discord user owns, newest first. */
export function listSessions(discordUserId: string): Promise<AuthSession[]> {
  return mc.authSessions(discordUserId);
}

/** Revoke one session, or every session this user owns when `sessionId` is omitted. */
export function revokeSession(discordUserId: string, sessionId?: string): Promise<{ revoked: number }> {
  return mc.authRevoke(discordUserId, sessionId);
}

/**
 * Bilingual reply text for each decision status, matching the in-game convention (EN, then pt-BR).
 * Mirrors the shape of `authStatusMessage` for the `/auth` code flow.
 */
export function authDecisionMessage(status: AuthDecisionStatus, minecraftName: string): string {
  switch (status) {
    case "approved":
      return `✅ **${minecraftName}** is approved — reconnect and you're in.\n✅ **${minecraftName}** aprovado — reconecte e pronto.`;
    case "denied":
      return `⛔ Login for **${minecraftName}** was denied. Every session was revoked and this network is blocked for 24h.\n⛔ Login de **${minecraftName}** negado. Todas as sessões foram revogadas e esta rede fica bloqueada por 24h.`;
    case "expired":
      return `⌛ That request expired. Reconnect to the server for a new one.\n⌛ Este pedido expirou. Reconecte ao servidor para receber outro.`;
    case "already-decided":
      return `↩️ That request was already answered.\n↩️ Este pedido já foi respondido.`;
    case "not-found":
      return `❓ That request no longer exists.\n❓ Este pedido não existe mais.`;
    case "forbidden":
      return `🚫 That request is not yours to answer.\n🚫 Este pedido não é seu para responder.`;
    case "disabled":
      return `Login approvals are disabled on the server.\nAs aprovações de login estão desativadas no servidor.`;
  }
}
