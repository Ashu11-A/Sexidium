package com.sexidium.paper.adapter.server;

import com.sexidium.core.platform.version.ServerVersion;
import com.sexidium.core.platform.version.ServerVersionPort;
import com.sexidium.paper.adapter.ui.betterhud.PackFormats;
import io.papermc.paper.ServerBuildInfo;
import org.bukkit.Bukkit;

/**
 * The Paper half of {@link ServerVersionPort}: the cleanest version source first, two fallbacks behind
 * it, and every failure degrading to {@link ServerVersion#UNKNOWN} rather than to an exception.
 *
 * <ol>
 *   <li>{@code ServerBuildInfo.buildInfo().minecraftVersionId()} — the official answer, present on
 *       modern Paper; also the only source that distinguishes Paper from forks via
 *       {@code isBrandCompatible}.</li>
 *   <li>{@code Bukkit.getMinecraftVersion()} — exists on forks where ServerBuildInfo may not.</li>
 *   <li>{@code Bukkit.getBukkitVersion()}, cut at the first dash — coarsest, always there.</li>
 * </ol>
 *
 * <p>The pack-format mapping stays with {@code PackFormats}; this port carries its answer and keeps
 * its doctrine — an unplaceable version yields {@code -1}, "no opinion", never a refusal.
 */
public final class PaperServerVersionPort implements ServerVersionPort {

  private final ServerVersion version;

  private PaperServerVersionPort(ServerVersion version) {
    this.version = version;
  }

  /** Reads the running server's version through the three-step chain. Never throws. */
  public static PaperServerVersionPort probe() {
    return new PaperServerVersionPort(read());
  }

  /**
   * The chain, in order, with every step falling through to the next.
   *
   * <p>Falling through is the whole design and it is easy to break: a step that {@code return}s its own
   * unparseable answer, or that {@code return}s {@code UNKNOWN} from its own {@code catch}, silently
   * turns a three-step chain into a one-step one — on exactly the forks the later steps exist for. So
   * each step here returns only a version it actually {@link ServerVersion#known() knows}, and every
   * failure falls out of its {@code try} rather than out of the method.</p>
   */
  static ServerVersion read() {
    try {
      ServerVersion fromBuildInfo = ServerVersion.parse(ServerBuildInfo.buildInfo().minecraftVersionId());
      if (fromBuildInfo.known()) {
        return fromBuildInfo;
      }
    } catch (RuntimeException | LinkageError noBuildInfo) {
      // older Paper or an exotic fork without the API — next step
    }
    try {
      ServerVersion fromMinecraft = ServerVersion.parse(Bukkit.getMinecraftVersion());
      if (fromMinecraft.known()) {
        return fromMinecraft;
      }
    } catch (RuntimeException | LinkageError noMinecraftVersion) {
      // a fork that never got Bukkit.getMinecraftVersion(), or no server at all (unit tests) —
      // NOT a reason to skip the last resort below, which is the step written for this very case
    }
    try {
      String bukkitVersion = Bukkit.getBukkitVersion();
      return ServerVersion.parse(bukkitVersion == null ? "" : bukkitVersion.split("-")[0]);
    } catch (RuntimeException | LinkageError noServerAtAll) {
      return ServerVersion.UNKNOWN;
    }
  }

  @Override
  public ServerVersion version() {
    return version;
  }

  @Override
  public int packFormat() {
    try {
      return PackFormats.of(version.raw());
    } catch (RuntimeException ignored) {
      return -1;
    }
  }
}
