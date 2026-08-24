package com.sexidium.core.lib.data;

/** A player's persisted rank row. */
public record Profile(
        String uuid,
        String name,
        String discordUserId,
        int points,
        int level,
        int wins,
        int kills,
        int games) {
}
