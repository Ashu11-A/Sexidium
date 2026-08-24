import { createCommand } from "#base";
import { MessageFlags } from "discord.js";
import { isStaff } from "../../lib/staff.js";
import { mc } from "../../minecraft/index.js";

export default createCommand({
  name: "restart",
  description: "Restart the Minecraft server (staff only)",
  async run(interaction) {
    if (!isStaff(interaction)) {
      await interaction.reply({ content: "You are not allowed to restart the server.", flags: MessageFlags.Ephemeral });
      return;
    }
    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    try {
      await mc.serverRestart();
      await interaction.editReply(
        "Restart requested. The server will come back shortly if it runs under a restart wrapper; otherwise it will just stop.",
      );
    } catch (error) {
      const message = error instanceof Error ? error.message : "error";
      if (message === "disabled") {
        await interaction.editReply("Server control is disabled: set a unique `api.token` in the server config.");
      } else {
        await interaction.editReply("Couldn't reach the server — it may already be offline.");
      }
    }
  },
});
