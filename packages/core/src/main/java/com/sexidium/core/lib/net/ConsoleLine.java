package com.sexidium.core.lib.net;

/** One captured server console line, buffered for {@code console.tail} and pushed as {@code console.line}. */
public record ConsoleLine(String level, String message, long timestamp) {
}
