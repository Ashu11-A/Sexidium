import { createCommand } from "#base";
import {
  ApplicationCommandOptionType,
  AttachmentBuilder,
  GuildScheduledEventEntityType,
  GuildScheduledEventPrivacyLevel,
  MessageFlags,
} from "discord.js";
import { isStaff } from "../../lib/staff.js";
import { generateEventImage } from "../../ui/images/event.js";
import type { EventData } from "../../types/event.js";

interface GameTheme {
  label: string;
  accent: string;
  tagline: string;
}

const GAMES: Record<string, GameTheme> = {
  race: { label: "Race for the Item", accent: "#3498DB", tagline: "First to collect every target item wins." },
  gather: { label: "Gather & Duel", accent: "#1ABC9C", tagline: "Gather resources, then duel to the last standing." },
  tntwar: { label: "TNT War", accent: "#E74C3C", tagline: "Blow your rivals off the platforms." },
  combat: { label: "Combat Arena", accent: "#F0B232", tagline: "Kit-based free-for-all PvP." },
  fugitive: { label: "The Fugitive", accent: "#9B59B6", tagline: "One player flees. Everyone else hunts." },
  experience: { label: "Custom Experience", accent: "#57F287", tagline: "A composable Experience challenge run." },
};

function parseDate(input: string): Date | null {
  const date = new Date(input.trim().replace(" ", "T"));
  return Number.isNaN(date.getTime()) ? null : date;
}

function formatDate(date: Date): string {
  return date.toLocaleString("en-GB", {
    day: "2-digit",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export default createCommand({
  name: "event",
  description: "Schedule a short-duration Sexidium minigame event with a generated banner",
  options: [
    { name: "name", description: "Event title", type: ApplicationCommandOptionType.String, required: true },
    {
      name: "game",
      description: "Minigame for this event",
      type: ApplicationCommandOptionType.String,
      required: true,
      choices: Object.entries(GAMES).map(([value, game]) => ({ name: game.label, value })),
    },
    {
      name: "date",
      description: "Start time, e.g. 2026-06-20 18:30 (server local time)",
      type: ApplicationCommandOptionType.String,
      required: true,
    },
    {
      name: "duration",
      description: "Duration in minutes (default 60)",
      type: ApplicationCommandOptionType.Integer,
      required: false,
      minValue: 5,
      maxValue: 1440,
    },
    {
      name: "description",
      description: "Optional extra details",
      type: ApplicationCommandOptionType.String,
      required: false,
    },
  ],
  async run(interaction) {
    if (!isStaff(interaction)) {
      await interaction.reply({ content: "You are not allowed to create events.", flags: MessageFlags.Ephemeral });
      return;
    }
    const guild = interaction.guild;
    if (!guild) {
      await interaction.reply({ content: "Run this in a server.", flags: MessageFlags.Ephemeral });
      return;
    }

    await interaction.deferReply();

    const title = interaction.options.getString("name", true);
    const gameId = interaction.options.getString("game", true);
    const dateInput = interaction.options.getString("date", true);
    const duration = interaction.options.getInteger("duration") ?? 60;
    const description = interaction.options.getString("description") ?? undefined;

    const start = parseDate(dateInput);
    if (!start) {
      await interaction.editReply("Couldn't parse that date. Use `YYYY-MM-DD HH:mm`, e.g. `2026-06-20 18:30`.");
      return;
    }
    if (start.getTime() <= Date.now()) {
      await interaction.editReply("The start time must be in the future.");
      return;
    }
    const end = new Date(start.getTime() + duration * 60_000);
    const game = GAMES[gameId] ?? GAMES.race;

    const data: EventData = {
      title,
      gameLabel: game.label,
      tagline: game.tagline,
      dateLabel: formatDate(start),
      durationLabel: `${duration} min`,
      accent: game.accent,
      serverName: guild.name,
    };

    let image: Buffer | null = null;
    try {
      image = await generateEventImage(data);
    } catch (error) {
      console.error("Failed to render event image:", error);
    }
    const files = image ? [new AttachmentBuilder(image, { name: "event.png" })] : [];

    try {
      const scheduled = await guild.scheduledEvents.create({
        name: title,
        scheduledStartTime: start,
        scheduledEndTime: end,
        privacyLevel: GuildScheduledEventPrivacyLevel.GuildOnly,
        entityType: GuildScheduledEventEntityType.External,
        entityMetadata: { location: `${game.label} — Minecraft` },
        description: description ?? `${game.label}: ${game.tagline}`,
        image: image ?? undefined,
      });
      await interaction.editReply({
        content: `Scheduled **${title}** (${game.label}) for **${formatDate(start)}** — ${scheduled.url}`,
        files,
      });
    } catch (error) {
      console.error("Failed to create scheduled event:", error);
      await interaction.editReply({
        content:
          "I rendered the banner but couldn't create the Discord scheduled event " +
          "(I need the **Manage Events** permission). Here's the banner:",
        files,
      });
    }
  },
});
