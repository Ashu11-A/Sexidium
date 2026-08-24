import { createResponder } from "#base";
import { ResponderType } from "@constatic/base";
import { EmbedBuilder, MessageFlags } from "discord.js";
import { decideRequest, authDecisionMessage } from "../../minecraft/index.js";
import type { AuthDecision } from "../../types/dto.js";

/**
 * The Approve/Deny press.
 *
 * Every check that could go wrong is on the server: a second press hits a conditional UPDATE that
 * matches nothing (`already-decided`), a forwarded message or a hand-crafted customId fails the
 * ownership check against the request row (`forbidden`), and a stale button is `expired`. Stripping
 * the buttons below is cosmetic defence in depth, not the mechanism.
 */

function resolved(result: AuthDecision): EmbedBuilder {
  const good = result.status === "approved";
  const embed = new EmbedBuilder()
    .setColor(good ? 0x57f287 : 0xed4245)
    .setDescription(authDecisionMessage(result.status, result.minecraftName));
  if (good && result.sessionHours > 0) {
    embed.setFooter({
      text:
        `Valid for ${result.sessionHours}h on this network.` +
        ` / Válida por ${result.sessionHours}h nesta rede.`,
    });
  }
  return embed;
}

export default createResponder({
  customId: "auth/:requestId/:action",
  types: [ResponderType.Button],
  async run(interaction, { requestId, action }) {
    if (action !== "approve" && action !== "deny") return;
    await interaction.deferUpdate();
    try {
      const result = await decideRequest({
        requestId,
        action,
        // A CLAIM, not proof. Java compares it against the request row's own owner.
        discordUserId: interaction.user.id,
      });
      await interaction.editReply({ embeds: [resolved(result)], components: [] });
    } catch (error) {
      console.error("Auth decision failed:", error);
      await interaction.followUp({
        content: "O servidor não está acessível agora. / The server is not reachable right now.",
        flags: MessageFlags.Ephemeral,
      });
    }
  },
});
