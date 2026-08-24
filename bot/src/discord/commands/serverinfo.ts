import { createCommand } from "#base";
import { AttachmentBuilder } from "discord.js";
import { mc } from "../../minecraft/index.js";
import { generateServerInfoImage } from "../../ui/images/serverinfo.js";
import type { ServerInfoData } from "../../types/serverinfo.js";

export default createCommand({
  name: "serverinfo",
  description: "Show live Sexidium server info (address, port, players online)",
  async run(interaction) {
    await interaction.deferReply();

    let info;
    try {
      info = await mc.serverInfo();
    } catch {
      await interaction.editReply("The Minecraft server is offline right now. Try again once it's back up.");
      return;
    }

    const data: ServerInfoData = {
      ...info,
      serverName: interaction.guild?.name ?? "Sexidium",
      address: info.ip && info.ip.length > 0 ? info.ip : "unknown",
    };

    try {
      const image = await generateServerInfoImage(data);
      await interaction.editReply({ files: [new AttachmentBuilder(image, { name: "serverinfo.png" })] });
    } catch (error) {
      console.error("Failed to render server-info image:", error);
      await interaction.editReply(
        `**${data.serverName}** — ${data.address}:${info.port} · **${info.online}/${info.max}** online · ${info.version}`,
      );
    }
  },
});
