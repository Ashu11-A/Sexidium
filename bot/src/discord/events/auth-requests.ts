import { createEvent } from "#base";
import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
  EmbedBuilder,
  type Client,
} from "discord.js";
import { env } from "#env";
import { on } from "../../minecraft/index.js";
import type { AuthRequest } from "../../types/dto.js";

/**
 * Delivers the login-approval push.
 *
 * This is the whole friction budget of the design: a player who changes network gets one DM with two
 * buttons and taps one. No code to read, no code to type.
 *
 * Delivery is DM-first with a channel fallback, because closed DMs are very common — and if neither
 * works the player is not stuck, they still have the `/auth <code>` path.
 */

/** Relative timestamps render in each viewer's own locale and timezone, which is free localisation. */
function relative(millis: number): string {
  return `<t:${Math.floor(millis / 1000)}:R>`;
}

function render(request: AuthRequest): EmbedBuilder {
  const conflict = request.kind === "premium-conflict";
  const embed = new EmbedBuilder()
    .setTitle(`Confirm login — ${request.minecraftName}`)
    .setColor(conflict ? 0xff5f6d : 0x57f287)
    .setDescription(
      conflict
        ? "A **verified Mojang account** just tried to join with this name. Approve only if that is you.\n" +
          "Uma **conta Mojang verificada** tentou entrar com este nick. Aprove apenas se for você."
        : "Someone is trying to join as this account from a new network.\n" +
          "Alguém está tentando entrar com esta conta a partir de uma nova rede.",
    )
    .addFields(
      { name: "Account / Conta", value: `\`${request.minecraftName}\``, inline: true },
      {
        name: "Network / Rede",
        value: `\`${request.ipPrefix || "?"}\`${request.firstSeen ? " 🆕 new network / nova rede" : ""}`,
        inline: true,
      },
      { name: "When / Quando", value: relative(request.createdAt), inline: true },
      { name: "Expires / Expira", value: relative(request.expiresAt), inline: true },
    )
    .setFooter({ text: "If this was not you, press Deny. / Se não foi você, toque em Negar." });
  return embed;
}

/**
 * `auth/<32 hex>/approve` is 45 characters, comfortably inside Discord's 100-char customId limit.
 * The id is 128 bits of SecureRandom, and ownership is re-checked in Java — a customId is a
 * client-visible string, so it is a routing key and never a credential.
 */
function buttons(requestId: string): ActionRowBuilder<ButtonBuilder> {
  return new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder()
      .setCustomId(`auth/${requestId}/approve`)
      .setLabel("Approve / Aprovar")
      .setEmoji("✅")
      .setStyle(ButtonStyle.Success),
    new ButtonBuilder()
      .setCustomId(`auth/${requestId}/deny`)
      .setLabel("Deny / Negar")
      .setEmoji("⛔")
      .setStyle(ButtonStyle.Danger),
  );
}

async function deliver(client: Client, request: AuthRequest): Promise<void> {
  const payload = { embeds: [render(request)], components: [buttons(request.requestId)] };

  const user = await client.users.fetch(request.discordUserId).catch(() => null);
  if (user) {
    const sent = await user.send(payload).catch(() => null);
    if (sent) return;
  }

  if (env.AUTH_CHANNEL_ID) {
    const channel = await client.channels.fetch(env.AUTH_CHANNEL_ID).catch(() => null);
    if (channel?.isSendable()) {
      const sent = await channel
        .send({ content: `<@${request.discordUserId}>`, ...payload })
        .catch(() => null);
      if (sent) return;
    }
  }

  console.warn(
    `[auth] could not deliver approval ${request.requestId} for ${request.minecraftName};` +
      " the player's DMs are closed and no AUTH_CHANNEL_ID is reachable",
  );
}

let wired = false;

export default createEvent({
  name: "sexidium-auth-requests",
  event: "clientReady",
  once: true,
  async run(client) {
    if (wired) return;
    wired = true;

    on("auth.request", (request) => {
      void deliver(client, request);
    });

    console.log("[auth] approval pushes wired");
  },
});
