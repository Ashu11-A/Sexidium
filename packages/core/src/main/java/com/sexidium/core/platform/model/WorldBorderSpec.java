package com.sexidium.core.platform.model;

public record WorldBorderSpec(
    double centerX,
    double centerZ,
    double size,
    int warningDistance,
    double damagePerBlock
) {
}
