package com.sexidium.core.platform.model;

public record TitleSpec(
    String titleMiniMessage,
    String subtitleMiniMessage,
    long fadeInMillis,
    long stayMillis,
    long fadeOutMillis
) {
}
