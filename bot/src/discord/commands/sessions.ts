import { createCommand } from "#base";
import {
  ActionRowBuilder,
  ApplicationCommandOptionType,
  ButtonBuilder,
  ButtonStyle,
  EmbedBuilder,
  MessageFlags,
} from "discord.js";
import { listSessions, revokeSession } from "../../minecraft/index.js";
import { isStaff } from "../../lib/staff.js";
import type { AuthSession } from "../../types/dto.js";

/**
 * Self-service visibility over the IP+name sessions the login gate hands out.
 *
 * This is the counterweight to sessions being server-side only: there is no token for a player to
 * check, so the list — and a one-tap Revoke on every row — is how they see what is remembered about
 * them and take it back.
 */

const DEVICE_LABEL: Record<AuthSession["device"], string> = {
  java: "Java",
  premium: "Mojang-verified",
  bedrock: "Bedrock",
};

function render(sessions: AuthSession[]): EmbedBuilder {
  const embed = new EmbedBuilder()
    .setTitle("Active login sessions / Sessões de login ativas")
    .setColor(0x5865f2);
  if (sessions.length === 0) {
    embed.setDescription(
      "No remembered networks. The next login will ask for a confirmation.\n" +
        "Nenhuma rede lembrada. O próximo login vai pedir uma confirmação.",
    );
    return embed;
  }
  for (const session of sessions) {
    embed.addFields({
      name: `${session.minecraftName} · ${DEVICE_LABEL[session.device]}`,
      value:
        `Network / Rede: \`${session.ipPrefix || "?"}\`\n` +
        `Last seen / Visto: <t:${Math.floor(session.lastSeenAt / 1000)}:R>\n` +
        `Expires / Expira: <t:${Math.floor(session.expiresAt / 1000)}:R>`,
    });
  }
  return embed;
}

/** Discord allows five buttons per row and five rows; the listing is capped well below either. */
function revokeButtons(sessions: AuthSession[]): ActionRowBuilder<ButtonBuilder>[] {
  const rows: ActionRowBuilder<ButtonBuilder>[] = [];
  for (let index = 0; index < sessions.length; index += 5) {
    rows.push(
      new ActionRowBuilder<ButtonBuilder>().addComponents(
        sessions.slice(index, index + 5).map((session) =>
          new ButtonBuilder()
            .setCustomId(`auth/session/${session.sessionId}/revoke`)
            .setLabel(`Revoke ${session.minecraftName}`.slice(0, 80))
            .setEmoji("⛔")
            .setStyle(ButtonStyle.Secondary),
        ),
      ),
    );
  }
  return rows.slice(0, 5);
}

export default createCommand({
  name: "sessions",
  description: "List (and revoke) the networks allowed to log in as your Minecraft accounts",
  options: [
    {
      name: "revoke-all",
      description: "Revoke every remembered network for your accounts",
      type: ApplicationCommandOptionType.Boolean,
      required: false,
    },
    {
      name: "user",
      description: "Staff only: inspect another member's sessions",
      type: ApplicationCommandOptionType.User,
      required: false,
    },
  ],
  async run(interaction) {
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });

    const requested = interaction.options.getUser("user");
    // Staff may look at anybody; everyone else is silently scoped back to themselves rather than
    // refused, because "you may not" and "here are yours" answer the same question.
    const target = requested && isStaff(interaction) ? requested : interaction.user;
    const own = target.id === interaction.user.id;

    try {
      if (interaction.options.getBoolean("revoke-all")) {
        const { revoked } = await revokeSession(target.id);
        await interaction.editReply(
          `Revoked ${revoked} session(s). Those networks must approve again.\n` +
            `${revoked} sessão(ões) revogada(s). Aquelas redes terão de aprovar de novo.`,
        );
        return;
      }

      const sessions = await listSessions(target.id);
      await interaction.editReply({
        embeds: [render(sessions)],
        // Revoke buttons only on your own list: the responder re-checks ownership anyway, so
        // offering staff a button that would refuse them is just a worse message.
        components: own ? revokeButtons(sessions) : [],
      });
    } catch (error) {
      console.error("Sessions command failed:", error);
      await interaction.editReply(
        "The Minecraft server is offline right now. Try again once it's back up.",
      );
    }
  },
});
