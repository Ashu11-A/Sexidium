package com.sexidium.core.auth;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * How a network is turned into something a session may be bound to.
 *
 * <p>Three separate jobs, deliberately pure and I/O-free so the whole of them is unit-testable:</p>
 *
 * <ul>
 *   <li>{@link #normalize} decides <em>what</em> is bound. IPv4 binds the full address. IPv6 binds a
 *       PREFIX (default /64): a residential IPv6 line rotates its interface identifier constantly,
 *       so binding the full address would demand a fresh Discord approval every few hours and buy
 *       nothing — the routed prefix is the part that identifies the line.</li>
 *   <li>{@link #hash} decides what is STORED. A raw IP is never written to the database; the column
 *       holds {@code SHA-256(pepper || normalizedIp)}. The pepper lives in config.yml, not in the
 *       database, so a database-only leak cannot brute-force the (small) IPv4 space back out.</li>
 *   <li>{@link #redact} decides what is SHOWN. The Discord message has to let a human recognise
 *       their own network without publishing it, so it keeps the routing half and drops the rest.</li>
 * </ul>
 */
public final class IpHasher {

  /** Bits of an IPv6 address a session binds to when the config gives no other answer. */
  public static final int DEFAULT_V6_PREFIX_BITS = 64;

  private IpHasher() {
  }

  /**
   * The address a session is actually keyed by.
   *
   * <p>An address that cannot be parsed is returned trimmed and lowercased rather than rejected: the
   * gate must still be able to key SOMETHING for it, and an unparseable address simply never matches
   * a real one. IPv4-mapped IPv6 ({@code ::ffff:1.2.3.4}) collapses to its IPv4 form so the same
   * client is one binding whichever way the platform reports it.</p>
   */
  public static String normalize(String ip, int v6PrefixBits) {
    String cleaned = ip == null ? "" : ip.trim().toLowerCase(Locale.ROOT);
    if (cleaned.isEmpty()) {
      return "";
    }
    // A "%eth0" zone id is a property of the local interface, not of the peer.
    int zone = cleaned.indexOf('%');
    if (zone >= 0) {
      cleaned = cleaned.substring(0, zone);
    }
    byte[] address;
    try {
      address = InetAddress.getByName(cleaned).getAddress();
    } catch (UnknownHostException | SecurityException unparseable) {
      return cleaned;
    }
    if (address.length == 4) {
      return format(address, 4);
    }
    int bits = v6PrefixBits <= 0 || v6PrefixBits > 128 ? DEFAULT_V6_PREFIX_BITS : v6PrefixBits;
    int keptBytes = Math.max(1, bits / 8);
    return format(address, keptBytes) + "/" + bits;
  }

  /** Hex of {@code SHA-256(pepper || normalizedIp)}. The pepper is never optional; see the callers. */
  public static String hash(String normalizedIp, String pepper) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update((pepper == null ? "" : pepper).getBytes(StandardCharsets.UTF_8));
      byte[] hashed = digest.digest((normalizedIp == null ? "" : normalizedIp).getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder(hashed.length * 2);
      for (byte b : hashed) {
        out.append(String.format("%02x", b));
      }
      return out.toString();
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is not available", unavailable);
    }
  }

  /**
   * A display form a player recognises as theirs and a bystander cannot dial: {@code 187.61.*.*}
   * for IPv4, {@code 2804:14d:1:2::/48} for IPv6. The ONLY plaintext network data this system stores.
   */
  public static String redact(String ip) {
    String cleaned = ip == null ? "" : ip.trim().toLowerCase(Locale.ROOT);
    if (cleaned.isEmpty()) {
      return "";
    }
    int zone = cleaned.indexOf('%');
    if (zone >= 0) {
      cleaned = cleaned.substring(0, zone);
    }
    byte[] address;
    try {
      address = InetAddress.getByName(cleaned).getAddress();
    } catch (UnknownHostException | SecurityException unparseable) {
      return "unknown";
    }
    if (address.length == 4) {
      return (address[0] & 0xFF) + "." + (address[1] & 0xFF) + ".*.*";
    }
    return format(address, 6) + "/48";
  }

  /** Textual form of the first {@code keptBytes} of an address, zeroing the rest. */
  private static String format(byte[] address, int keptBytes) {
    if (address.length == 4) {
      StringBuilder out = new StringBuilder(15);
      for (int i = 0; i < 4; i++) {
        out.append(i == 0 ? "" : ".").append(i < keptBytes ? (address[i] & 0xFF) : 0);
      }
      return out.toString();
    }
    StringBuilder out = new StringBuilder(39);
    for (int i = 0; i < 16; i += 2) {
      int high = i < keptBytes ? (address[i] & 0xFF) : 0;
      int low = (i + 1) < keptBytes ? (address[i + 1] & 0xFF) : 0;
      out.append(i == 0 ? "" : ":").append(Integer.toHexString((high << 8) | low));
    }
    return out.toString();
  }
}
