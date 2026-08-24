import { createCommand } from "#base";
import { ApplicationCommandOptionType, MessageFlags } from "discord.js";
import { isStaff } from "../../lib/staff.js";
import { runServerCommand, type RunResult } from "../../minecraft/index.js";

export default createCommand({
  name: "mc",
  description: "Run a Minecraft server command (staff only)",
  options: [
    {
      name: "command",
      description: "Command to run, without the leading slash",
      type: ApplicationCommandOptionType.String,
      required: true,
    },
  ],
  async run(interaction) {
    if (!isStaff(interaction)) {
      await interaction.reply({
        content: "You are not allowed to run server commands.",
        flags: MessageFlags.Ephemeral,
      });
      return;
    }

    const command = interaction.options.getString("command", true);
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    const result = await runServerCommand(command);
    const clean = command.replace(/^\//, "");
    const messages: Record<RunResult, string> = {
      ok: `Ran \`/${clean}\` on the server.`,
      forbidden: `\`/${clean}\` is not on the server's command allowlist (api.command-allowlist).`,
      disabled: "The command bridge is disabled: set a unique `api.token` in the server config.",
      unauthorized: "The bot's API_TOKEN does not match the server's api.token.",
      error: "Failed to run the command. Check that the Minecraft server is online.",
    };
    await interaction.editReply(messages[result]);
  },
});
