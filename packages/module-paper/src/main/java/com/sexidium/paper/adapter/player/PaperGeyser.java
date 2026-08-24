package com.sexidium.paper.adapter.player;

import com.sexidium.core.platform.BedrockPlayers;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Geyser/Bedrock gateway for the Paper platform — the single source of truth for "is this a Bedrock
 * player" and the channel for sending them native Cumulus forms. Bedrock players connect through Geyser
 * (optionally with Floodgate) from phones/consoles and need special treatment: every tap is a plain
 * left-click, and a touch-friendly form beats a chest GUI.
 *
 * <p>Everything is resolved reflectively, so the plugin needs no Geyser/Floodgate/Cumulus jar on the
 * compile classpath and degrades gracefully when they are absent. Two independent backends are
 * supported, because real servers run either:</p>
 * <ul>
 *   <li><b>Floodgate</b> — {@code FloodgateApi.isFloodgatePlayer(uuid)} / {@code sendForm(uuid, form)};
 *       Bedrock UUIDs also have all-zero high bits ({@link BedrockPlayers}).</li>
 *   <li><b>Geyser without Floodgate</b> (the common standalone / {@code auth-type: offline} case) —
 *       {@code GeyserApi.api().isBedrockPlayer(uuid)} and {@code connectionByUuid(uuid).sendForm(form)}.
 *       Here the player has an ordinary offline UUID, so the high-bits heuristic does <i>not</i> detect
 *       them; only the Geyser API does. This is why the Geyser path matters.</li>
 * </ul>
 */
public final class PaperGeyser {
  private static final String CUMULUS_FORM = "org.geysermc.cumulus.form.Form";

  // Floodgate API (resolved once): getInstance(), isFloodgatePlayer(UUID), sendForm(UUID, Form).
  private static volatile boolean floodgateResolved;
  private static volatile Object floodgateApi;
  private static volatile Method floodgateIsPlayer;
  private static volatile Method floodgateSendForm;

  // Geyser API (resolved once): api(), isBedrockPlayer(UUID), connectionByUuid(UUID).
  private static volatile boolean geyserResolved;
  private static volatile Object geyserApi;
  private static volatile Method geyserIsBedrockPlayer;
  private static volatile Method geyserConnectionByUuid;

  private static volatile boolean cumulusResolved;
  private static volatile Class<?> cumulusFormClass;

  private PaperGeyser() {
  }

  /**
   * Whether a Bedrock UI (Cumulus form) can be sent on this server at all — true when either Floodgate
   * or the Geyser API is present. When false, callers fall back to the chest GUI.
   */
  public static boolean bedrockUiAvailable() {
    if (!floodgateResolved) {
      resolveFloodgate();
    }
    if (floodgateApi != null) {
      return true;
    }
    if (!geyserResolved) {
      resolveGeyser();
    }
    return geyserApi != null;
  }

  /** The active Bedrock-form backend name for startup logging: {@code Floodgate}, {@code Geyser}, or {@code none}. */
  public static String bedrockBackend() {
    if (!floodgateResolved) {
      resolveFloodgate();
    }
    if (floodgateApi != null) {
      return "Floodgate";
    }
    if (!geyserResolved) {
      resolveGeyser();
    }
    return geyserApi != null ? "Geyser" : "none";
  }

  /** Whether the given player connected from Bedrock Edition (via Geyser, with or without Floodgate). */
  public static boolean isBedrock(UUID uuid) {
    if (uuid == null) {
      return false;
    }
    Boolean viaFloodgate = viaFloodgate(uuid);
    if (viaFloodgate != null) {
      return viaFloodgate;
    }
    Boolean viaGeyser = viaGeyser(uuid);
    if (viaGeyser != null) {
      return viaGeyser;
    }
    return BedrockPlayers.isBedrockUuid(uuid);
  }

  /**
   * Sends a built Cumulus form to a Bedrock player, preferring Floodgate then falling back to the Geyser
   * API. Returns true if a backend accepted the form. The {@code form} is passed as {@code Object} so
   * this class needs no Cumulus type on its signature.
   */
  public static boolean sendForm(UUID uuid, Object form) {
    if (uuid == null || form == null) {
      return false;
    }
    if (!floodgateResolved) {
      resolveFloodgate();
    }
    if (floodgateApi != null && floodgateSendForm != null) {
      try {
        if (Boolean.TRUE.equals(floodgateSendForm.invoke(floodgateApi, uuid, form))) {
          return true;
        }
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // fall through to the Geyser path
      }
    }
    Object connection = geyserConnection(uuid);
    if (connection != null) {
      Class<?> formClass = cumulusFormClass();
      if (formClass != null) {
        try {
          Object result = connection.getClass().getMethod("sendForm", formClass).invoke(connection, form);
          return !Boolean.FALSE.equals(result);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
          // no usable backend
        }
      }
    }
    return false;
  }

  private static Boolean viaFloodgate(UUID uuid) {
    if (!floodgateResolved) {
      resolveFloodgate();
    }
    if (floodgateApi == null || floodgateIsPlayer == null) {
      return null;
    }
    try {
      Object result = floodgateIsPlayer.invoke(floodgateApi, uuid);
      return result instanceof Boolean bool ? bool : null;
    } catch (ReflectiveOperationException | RuntimeException exception) {
      return null;
    }
  }

  private static Boolean viaGeyser(UUID uuid) {
    if (!geyserResolved) {
      resolveGeyser();
    }
    if (geyserApi == null) {
      return null;
    }
    if (geyserIsBedrockPlayer != null) {
      try {
        Object result = geyserIsBedrockPlayer.invoke(geyserApi, uuid);
        if (result instanceof Boolean bool) {
          return bool;
        }
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // fall through to a connection probe
      }
    }
    return geyserConnection(uuid) != null ? Boolean.TRUE : null;
  }

  private static Object geyserConnection(UUID uuid) {
    if (!geyserResolved) {
      resolveGeyser();
    }
    if (geyserApi == null || geyserConnectionByUuid == null) {
      return null;
    }
    try {
      return geyserConnectionByUuid.invoke(geyserApi, uuid);
    } catch (ReflectiveOperationException | RuntimeException exception) {
      return null;
    }
  }

  private static synchronized void resolveFloodgate() {
    if (floodgateResolved) {
      return;
    }
    floodgateResolved = true;
    try {
      Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
      Object instance = apiClass.getMethod("getInstance").invoke(null);
      if (instance != null) {
        floodgateApi = instance;
        floodgateIsPlayer = apiClass.getMethod("isFloodgatePlayer", UUID.class);
        Class<?> formClass = cumulusFormClass();
        if (formClass != null) {
          floodgateSendForm = apiClass.getMethod("sendForm", UUID.class, formClass);
        }
      }
    } catch (ReflectiveOperationException | RuntimeException exception) {
      floodgateApi = null;
      floodgateIsPlayer = null;
      floodgateSendForm = null;
    }
  }

  private static synchronized void resolveGeyser() {
    if (geyserResolved) {
      return;
    }
    geyserResolved = true;
    try {
      Class<?> apiClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
      Object instance = apiClass.getMethod("api").invoke(null);
      if (instance != null) {
        geyserApi = instance;
        geyserConnectionByUuid = apiClass.getMethod("connectionByUuid", UUID.class);
        try {
          geyserIsBedrockPlayer = apiClass.getMethod("isBedrockPlayer", UUID.class);
        } catch (NoSuchMethodException noMethod) {
          geyserIsBedrockPlayer = null; // older Geyser — connection probe is the fallback
        }
      }
    } catch (ReflectiveOperationException | RuntimeException exception) {
      geyserApi = null;
      geyserConnectionByUuid = null;
      geyserIsBedrockPlayer = null;
    }
  }

  private static synchronized Class<?> cumulusFormClass() {
    if (!cumulusResolved) {
      cumulusResolved = true;
      try {
        cumulusFormClass = Class.forName(CUMULUS_FORM);
      } catch (ClassNotFoundException notFound) {
        cumulusFormClass = null;
      }
    }
    return cumulusFormClass;
  }
}
