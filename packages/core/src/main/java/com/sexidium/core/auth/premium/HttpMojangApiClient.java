package com.sexidium.core.auth.premium;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link MojangApiClient} over the JDK's own {@link HttpClient} — no networking dependency, matching
 * how {@code BridgeClient} talks WebSocket.
 *
 * <p>{@code 200} carries a profile, {@code 204}/{@code 404} means the name is free, and everything
 * else — including a timeout — is an {@link IOException}, because "Mojang is unwell" must not be
 * indistinguishable from "that name is available".</p>
 */
public final class HttpMojangApiClient implements MojangApiClient {

  private static final String ENDPOINT = "https://api.mojang.com/users/profiles/minecraft/";
  /** The response is one small object; a regex beats dragging a JSON parser onto the login path. */
  private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F]{32})\"");

  private final HttpClient httpClient;
  private final Duration timeout;

  public HttpMojangApiClient(long timeoutMillis) {
    this.timeout = Duration.ofMillis(Math.max(250L, timeoutMillis));
    this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
  }

  @Override
  public Optional<String> uuidForName(String name) throws IOException {
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT + name))
          .timeout(timeout)
          .header("Accept", "application/json")
          .GET()
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status == 204 || status == 404) {
        return Optional.empty();
      }
      if (status != 200) {
        // 429 in particular: being told to slow down is NOT evidence the name is free.
        throw new IOException("Mojang answered " + status + " for " + name);
      }
      Matcher matcher = ID.matcher(response.body() == null ? "" : response.body());
      if (!matcher.find()) {
        throw new IOException("Mojang answered 200 without a profile id for " + name);
      }
      return Optional.of(matcher.group(1).toLowerCase(java.util.Locale.ROOT));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while asking Mojang about " + name, interrupted);
    } catch (RuntimeException unexpected) {
      throw new IOException("Could not reach Mojang for " + name, unexpected);
    }
  }
}
