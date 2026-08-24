import { createCommand } from "#base";
import { MessageFlags } from "discord.js";

export default createCommand({
  name: "ping",
  description: "Check the bot is alive",
  async run(interaction) {
    await interaction.reply({ content: "Pong.", flags: MessageFlags.Ephemeral });
  },
});
