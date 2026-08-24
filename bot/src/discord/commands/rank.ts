import { createCommand } from "#base";
import { ApplicationCommandOptionType, AttachmentBuilder, GuildMember } from "discord.js";
import { mc } from "../../minecraft/index.js";
import { generateProfileImage } from "../../ui/images/profile.js";
import { skinHeadDataUri } from "../../lib/skin.js";
import { syncMemberRank } from "../../lib/rank-sync.js";
import type { LeaderboardEntry } from "../../types/dto.js";
import type { ProfileData } from "../../types/profile.js";

const DEFAULT_AVATAR = "https://cdn.discordapp.com/embed/avatars/0.png";

export default createCommand({
  name: "rank",
  description: "Show a Sexidium rank card (yours, or a Minecraft player's)",
  options: [
    {
      name: "player",
      description: "Minecraft player name (defaults to your linked account)",
      type: ApplicationCommandOptionType.String,
      required: false,
    },
  ],
  async run(interaction) {
    await interaction.deferReply();
    const name = interaction.options.getString("player");

    let entry: LeaderboardEntry | null;
    try {
      entry = name ? await mc.rankByName(name) : await mc.rankByDiscord(interaction.user.id);
    } catch {
      await interaction.editReply("The Minecraft server is offline right now. Try again once it's back up.");
      return;
    }

    if (!entry) {
      await interaction.editReply(
        name
          ? `No rank data for \`${name}\` yet.`
          : "Your Discord account is not linked yet. In Minecraft run `/sx auth`, then `/auth <code>` here.",
      );
      return;
    }

    // Prefer the player's real Minecraft skin (set via SkinsRestorer); fall back to the linked
    // Discord avatar, then a generic avatar.
    let username = entry.name;
    let discordAvatar: string | null = null;
    if (entry.discordUserId) {
      const user = await interaction.client.users.fetch(entry.discordUserId).catch(() => null);
      if (user) {
        username = user.globalName ?? user.username;
        discordAvatar = user.displayAvatarURL({ extension: "png", size: 256 });
      }
    }
    const avatarUrl = (await skinHeadDataUri(entry.name, 96)) ?? discordAvatar ?? DEFAULT_AVATAR;

    const data: ProfileData = {
      username,
      avatarUrl,
      rankClass: entry.rankClass,
      rankColor: entry.rankColor,
      points: entry.points,
      level: entry.level,
      wins: entry.wins,
      kills: entry.kills,
      games: entry.games,
      names: entry.names.length > 0 ? entry.names : [entry.name],
    };

    try {
      const image = await generateProfileImage(data);
      await interaction.editReply({ files: [new AttachmentBuilder(image, { name: "rank.png" })] });
    } catch (error) {
      console.error("Failed to render rank image:", error);
      await interaction.editReply(
        `**${username}** — ${entry.rankClass} · **${entry.points}** pts · lvl ${entry.level} · ${entry.wins}W/${entry.kills}K`,
      );
    }

    // When you look up your own card, also refresh your Discord nickname tag + class role.
    if (!name && interaction.member instanceof GuildMember) {
      await syncMemberRank(interaction.member).catch(() => undefined);
    }
  },
});
