package com.sexidium.core.lib.net;

import com.sexidium.core.lib.data.LeaderboardEntry;
import com.sexidium.core.lib.data.Profile;

import java.util.List;

/** Minimal hand-rolled JSON writer (avoids pulling in a dependency for a few endpoints). */
public final class Json {

    private Json() {}

    public static String of(Profile p) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        field(sb, "uuid", p.uuid()).append(',');
        field(sb, "name", p.name()).append(',');
        nullableField(sb, "discordUserId", p.discordUserId()).append(',');
        num(sb, "points", p.points()).append(',');
        num(sb, "level", p.level()).append(',');
        num(sb, "wins", p.wins()).append(',');
        num(sb, "kills", p.kills()).append(',');
        num(sb, "games", p.games());
        sb.append('}');
        return sb.toString();
    }

    public static String of(List<Profile> list) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(of(list.get(i)));
        }
        sb.append(']');
        return sb.toString();
    }

    public static String of(LeaderboardEntry e) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        nullableField(sb, "discordUserId", e.discordUserId()).append(',');
        field(sb, "name", e.name()).append(',');
        sb.append("\"names\":");
        stringArray(sb, e.names()).append(',');
        num(sb, "points", e.points()).append(',');
        num(sb, "level", e.level()).append(',');
        num(sb, "wins", e.wins()).append(',');
        num(sb, "kills", e.kills()).append(',');
        num(sb, "games", e.games()).append(',');
        field(sb, "rankClass", e.rankClass()).append(',');
        field(sb, "rankColor", e.rankColor());
        sb.append('}');
        return sb.toString();
    }

    public static String ofEntries(List<LeaderboardEntry> list) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(of(list.get(i)));
        }
        sb.append(']');
        return sb.toString();
    }

    private static StringBuilder stringArray(StringBuilder sb, List<String> values) {
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escape(values.get(i) == null ? "" : values.get(i))).append('"');
        }
        return sb.append(']');
    }

    private static StringBuilder field(StringBuilder sb, String key, String value) {
        return sb.append('"').append(escape(key)).append("\":\"").append(escape(value == null ? "" : value)).append('"');
    }

    private static StringBuilder nullableField(StringBuilder sb, String key, String value) {
        sb.append('"').append(escape(key)).append("\":");
        if (value == null || value.isBlank()) return sb.append("null");
        return sb.append('"').append(escape(value)).append('"');
    }

    private static StringBuilder num(StringBuilder sb, String key, long value) {
        return sb.append('"').append(escape(key)).append("\":").append(value);
    }

    public static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
