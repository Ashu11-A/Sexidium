import { createCommand } from "#base";
import { AttachmentBuilder } from "discord.js";
import { mc } from "../../minecraft/index.js";
import { generateRankImage } from "../../ui/images/rank.js";
import { skinHeadDataUri } from "../../lib/skin.js";
import type { RankEntry } from "../../types/rank.js";

const DEFAULT_AVATAR = "https://cdn.discordapp.com/embed/avatars/0.png";

export default createCommand({
  name: "leaderboard",
  description: "Show the Sexidium top players (summed across each player's linked accounts)",
  async run(interaction) {
    await interaction.deferReply();

    let top;
    try {
      top = await mc.rankTop(10);
    } catch {
      await interaction.editReply("The Minecraft server is offline right now. Try again once it's back up.");
      return;
    }

    if (top.length === 0) {
      await interaction.editReply(
        "No ranked players yet. Link in Minecraft with `/sx auth` then `/auth <code>` here, and play a game.",
      );
      return;
    }

    const entries: RankEntry[] = await Promise.all(
      top.map(async (entry): Promise<RankEntry> => {
        let username = entry.name;
        let discordAvatar: string | null = null;
        if (entry.discordUserId) {
          const user = await interaction.client.users.fetch(entry.discordUserId).catch(() => null);
          if (user) {
            username = user.globalName ?? user.username;
            discordAvatar = user.displayAvatarURL({ extension: "png", size: 128 });
          }
        }
        const avatarUrl = (await skinHeadDataUri(entry.name, 64)) ?? discordAvatar ?? DEFAULT_AVATAR;
        return {
          userId: entry.discordUserId ?? entry.name,
          username,
          score: entry.points,
          level: entry.level,
          rankClass: entry.rankClass,
          rankColor: entry.rankColor,
          names: entry.names,
          avatarUrl,
        };
      }),
    );

    try {
      const image = await generateRankImage(entries);
      await interaction.editReply({ files: [new AttachmentBuilder(image, { name: "leaderboard.png" })] });
    } catch (error) {
      console.error("Failed to render leaderboard image:", error);
      const lines = entries.map(
        (entry, index) =>
          `**${index + 1}.** ${entry.username} — **${entry.score}** pts · ${entry.rankClass} · lvl ${entry.level}`,
      );
      await interaction.editReply(lines.join("\n"));
    }
  },
});
