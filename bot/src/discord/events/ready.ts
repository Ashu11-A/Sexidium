import { createEvent } from "#base";
import type { Client } from "discord.js";
import { env } from "#env";
import { on } from "../../minecraft/index.js";
import { syncMemberRank } from "../../lib/rank-sync.js";
import { clearSkinCache } from "../../lib/skin.js";

async function sendToChannel(client: Client, channelId: string | undefined, content: string): Promise<void> {
  if (!channelId) return;
  const channel = await client.channels.fetch(channelId).catch(() => null);
  if (channel?.isSendable()) {
    await channel.send(content.slice(0, 1900)).catch(() => undefined);
  }
}

let wired = false;

/**
 * Subscribes the Discord client to the real-time Java event stream once it is ready:
 *  - `console.line`  -> a log channel (live console relay)
 *  - `player.join` / `player.leave` -> an events channel
 *  - `rank.changed`  -> refresh the member's Discord role/nick + drop the cached skin head
 * Channels are opt-in via LOG_CHANNEL_ID / EVENTS_CHANNEL_ID; unset = that relay is skipped.
 */
export default createEvent({
  name: "sexidium-rpc-relays",
  event: "clientReady",
  once: true,
  async run(client) {
    if (wired) return;
    wired = true;

    on("console.line", (line) => {
      void sendToChannel(client, env.LOG_CHANNEL_ID, `\`[${line.level}]\` ${line.message}`);
    });

    on("player.join", (player) => {
      void sendToChannel(client, env.EVENTS_CHANNEL_ID, `➕ **${player.name}** joined the server.`);
    });

    on("player.leave", (player) => {
      void sendToChannel(client, env.EVENTS_CHANNEL_ID, `➖ **${player.name}** left the server.`);
    });

    on("rank.changed", async (change) => {
      clearSkinCache(change.name);
      if (!change.discordUserId) return;
      for (const guild of client.guilds.cache.values()) {
        const member = await guild.members.fetch(change.discordUserId).catch(() => null);
        if (member) await syncMemberRank(member).catch(() => undefined);
      }
    });

    console.log("[rpc] event relays wired");
  },
});
