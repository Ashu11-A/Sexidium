package com.sexidium.core.platform.capability;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What the running backend can actually do right now, probed rather than assumed — with a reason for
 * everything it cannot.
 *
 * <h2>Installed is not the same as capable</h2>
 * The failure mode that bites is the plugin that loads fine but cannot serve its surface (BetterHud
 * serving shaders for the wrong Minecraft version, SkinsRestorer with a changed storage API). A
 * registry that only answered "is it installed" would report those as usable and hand players white
 * boxes; this one carries a reason per missing capability instead, so the degradation path is chosen
 * deliberately and the operator can read why at boot or through {@code /sx admin capabilities}.
 *
 * <h2>Contract: unprobed is unsupported</h2>
 * A registry is built through {@link #probing()}, which requires an answer for every {@link Capability}
 * constant and treats a MISSING answer as unsupported, not as supported.
 *
 * <p>That direction is the whole point. An earlier shape took a map of misses and derived support by
 * subtraction from {@code EnumSet.allOf}, which meant a capability nobody probed — a constant added to
 * the enum with no matching probe — reported as available on every server, with no reason, silently, and
 * sent its callers down the capable path into a {@code NoSuchMethodError}. Fail-open is the wrong
 * default for a type whose entire job is choosing a degradation path, so the builder below fails closed
 * and says so in the reason.</p>
 */
public interface CapabilityRegistry {

  /**
   * The reason recorded for a capability the backend never answered. Its presence in a boot log is a
   * bug in the probe, not a fact about the server — and it is deliberately loud rather than absent.
   */
  String UNPROBED_REASON = "not probed by this backend (fail-closed: treated as unavailable)";

  /** Supports nothing, explains nothing — the headless/test default. */
  CapabilityRegistry EMPTY = new CapabilityRegistry() {
    @Override
    public Set<Capability> supported() {
      return Set.of();
    }

    @Override
    public Optional<String> reason(Capability capability) {
      return Optional.empty();
    }
  };

  /** Every capability answerable right now. Immutable. */
  Set<Capability> supported();

  /** Whether {@code capability} is available right now. Unknown/null answers false, never throws. */
  default boolean has(Capability capability) {
    return capability != null && supported().contains(capability);
  }

  /**
   * Why {@code capability} is NOT available — for the boot log and {@code /sx admin capabilities}.
   * Empty when it IS available, when nothing was recorded, or when the question is nonsense (null).
   */
  Optional<String> reason(Capability capability);

  /** Starts a probe. Every constant not answered before {@link Probe#build()} comes out unsupported. */
  static Probe probing() {
    return new Probe();
  }

  /**
   * Accumulates one probe result per {@link Capability} and builds the registry.
   *
   * <p>Not thread-safe, and does not need to be: a probe is filled in on one thread at enable and
   * published as an immutable {@link CapabilityRegistry}.</p>
   */
  final class Probe {

    private final Map<Capability, String> unsupported = new EnumMap<>(Capability.class);
    private final Set<Capability> answered = EnumSet.noneOf(Capability.class);

    private Probe() {
    }

    /** Records {@code capability} as available. */
    public Probe supports(Capability capability) {
      return answer(capability, Optional.empty());
    }

    /** Records {@code capability} as unavailable, with the reason an operator will read. */
    public Probe lacks(Capability capability, String reason) {
      return answer(capability, Optional.of(reason));
    }

    /**
     * Records one probe's verdict: an empty reason means available, a present one means not.
     *
     * <p>This is the shape a probe naturally has ("tell me why not, or nothing"), so a backend can hand
     * its answer straight over without branching — see {@code PaperCapabilityRegistry}.</p>
     *
     * @param unavailableReason why the capability cannot be served, or empty when it can
     */
    public Probe answer(Capability capability, Optional<String> unavailableReason) {
      if (capability == null) {
        return this;
      }
      answered.add(capability);
      unavailableReason
          .filter(reason -> !reason.isBlank())
          .ifPresentOrElse(reason -> unsupported.put(capability, reason),
              () -> unsupported.remove(capability));
      return this;
    }

    /**
     * The immutable registry. Anything never answered is unsupported, carrying {@link #UNPROBED_REASON}
     * — visible in the boot log rather than silently reported as working.
     */
    public CapabilityRegistry build() {
      Map<Capability, String> reasons = new EnumMap<>(unsupported);
      for (Capability capability : Capability.values()) {
        if (!answered.contains(capability)) {
          reasons.put(capability, UNPROBED_REASON);
        }
      }
      EnumSet<Capability> available = EnumSet.allOf(Capability.class);
      available.removeAll(reasons.keySet());
      Set<Capability> supported = Collections.unmodifiableSet(available);
      Map<Capability, String> unavailable = Collections.unmodifiableMap(reasons);
      return new CapabilityRegistry() {
        @Override
        public Set<Capability> supported() {
          return supported;
        }

        @Override
        public Optional<String> reason(Capability capability) {
          return capability == null ? Optional.empty() : Optional.ofNullable(unavailable.get(capability));
        }
      };
    }
  }
}
