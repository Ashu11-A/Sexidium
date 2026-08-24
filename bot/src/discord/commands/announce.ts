import { createCommand } from "#base";
import { ApplicationCommandOptionType, MessageFlags } from "discord.js";
import { isStaff } from "../../lib/staff.js";
import { runServerCommand, type RunResult } from "../../minecraft/index.js";

export default createCommand({
  name: "announce",
  description: "Broadcast a message to the Minecraft server (staff only)",
  options: [
    {
      name: "message",
      description: "The message to broadcast in-game",
      type: ApplicationCommandOptionType.String,
      required: true,
    },
  ],
  async run(interaction) {
    if (!isStaff(interaction)) {
      await interaction.reply({ content: "You are not allowed to broadcast.", flags: MessageFlags.Ephemeral });
      return;
    }
    const message = interaction.options.getString("message", true).replace(/\n/g, " ");
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });

    const result = await runServerCommand(`say [Discord] ${message}`);
    const messages: Record<RunResult, string> = {
      ok: `Broadcast to the server: \`${message}\``,
      forbidden: "`say` is not on the server's command allowlist (api.command-allowlist).",
      disabled: "The command bridge is disabled: set a unique `api.token` in the server config.",
      unauthorized: "The bot's API_TOKEN does not match the server's api.token.",
      error: "Failed to broadcast. Check that the Minecraft server is online.",
    };
    await interaction.editReply(messages[result]);
  },
});
