import { createCommand } from "#base";
import { MessageFlags } from "discord.js";
import { isStaff } from "../../lib/staff.js";
import { ensureRankRoles, syncMemberRank } from "../../lib/rank-sync.js";

export default createCommand({
  name: "syncranks",
  description: "Sync every linked member's rank tag (nickname + class role) — staff only",
  async run(interaction) {
    if (!isStaff(interaction)) {
      await interaction.reply({ content: "You are not allowed to sync ranks.", flags: MessageFlags.Ephemeral });
      return;
    }
    const guild = interaction.guild;
    if (!guild) {
      await interaction.reply({ content: "Run this in a server.", flags: MessageFlags.Ephemeral });
      return;
    }

    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    const roleMap = await ensureRankRoles(guild);

    // Try a full fetch (needs the Guild Members intent); fall back to the cache when unavailable.
    const members = await guild.members.fetch().catch(() => guild.members.cache);

    let synced = 0;
    let checked = 0;
    for (const member of members.values()) {
      if (member.user.bot) continue;
      checked++;
      const rankClass = await syncMemberRank(member, roleMap);
      if (rankClass) synced++;
    }

    await interaction.editReply(
      `Ensured ${roleMap.size} rank role(s) and synced tags for **${synced}** linked member(s) out of ${checked} checked.`
    );
  },
});
