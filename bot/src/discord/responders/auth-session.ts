import { createResponder } from "#base";
import { ResponderType } from "@constatic/base";
import { MessageFlags } from "discord.js";
import { revokeSession } from "../../minecraft/index.js";

/**
 * The per-row Revoke button on `/sessions`.
 *
 * Ownership is re-checked in Java against `discord_accounts`, so pressing somebody else's button
 * revokes nothing and simply reports zero.
 */
export default createResponder({
  customId: "auth/session/:sessionId/revoke",
  types: [ResponderType.Button],
  async run(interaction, { sessionId }) {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    try {
      const { revoked } = await revokeSession(interaction.user.id, sessionId);
      await interaction.editReply(
        revoked > 0
          ? "Session revoked. That network must approve again.\nSessão revogada. Aquela rede terá de aprovar de novo."
          : "That session is not yours, or it is already gone.\nEssa sessão não é sua, ou já não existe.",
      );
    } catch (error) {
      console.error("Session revoke failed:", error);
      await interaction.editReply(
        "O servidor não está acessível agora. / The server is not reachable right now.",
      );
    }
  },
});
