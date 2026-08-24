import { createCommand } from "#base";
import { ApplicationCommandOptionType, MessageFlags } from "discord.js";
import { isStaff } from "../../lib/staff.js";
import { mc } from "../../minecraft/index.js";

export default createCommand({
  name: "console",
  description: "Show the latest Minecraft server console lines (staff only)",
  options: [
    {
      name: "lines",
      description: "How many lines to show (default 25)",
      type: ApplicationCommandOptionType.Integer,
      required: false,
      minValue: 1,
      maxValue: 100,
    },
  ],
  async run(interaction) {
    if (!isStaff(interaction)) {
      await interaction.reply({ content: "You are not allowed to view the console.", flags: MessageFlags.Ephemeral });
      return;
    }
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    const count = interaction.options.getInteger("lines") ?? 25;

    let lines;
    try {
      lines = await mc.consoleTail(count);
    } catch {
      await interaction.editReply("The Minecraft server is offline right now.");
      return;
    }

    if (lines.length === 0) {
      await interaction.editReply("No console output is buffered yet.");
      return;
    }

    // Discord message cap is 2000 chars; keep the tail and wrap in a code block.
    const body = lines.map((line) => line.message).join("\n").slice(-1900);
    await interaction.editReply("```\n" + body + "\n```");
  },
});
