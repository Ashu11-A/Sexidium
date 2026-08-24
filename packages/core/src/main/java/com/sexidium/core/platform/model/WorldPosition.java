package com.sexidium.core.platform.model;

public record WorldPosition(
    String worldName,
    double coordinateX,
    double coordinateY,
    double coordinateZ,
    float yaw,
    float pitch
) {
  public WorldPosition withWorldName(String targetWorldName) {
    return new WorldPosition(targetWorldName, coordinateX, coordinateY, coordinateZ, yaw, pitch);
  }

  public WorldPosition offset(double offsetX, double offsetY, double offsetZ) {
    return new WorldPosition(worldName, coordinateX + offsetX, coordinateY + offsetY, coordinateZ + offsetZ, yaw, pitch);
  }
}
