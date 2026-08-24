package com.sexidium.core.auth.premium;

import java.io.IOException;
import java.util.Optional;

/**
 * The one call this system makes to Mojang, behind an interface so no unit test touches the network.
 *
 * <p>An empty {@link Optional} means Mojang answered and the name is not registered. An
 * {@link IOException} means Mojang did not answer — a different fact with a different consequence,
 * which is why the two are not folded into one return value.</p>
 */
@FunctionalInterface
public interface MojangApiClient {

  /** The undashed uuid Mojang holds for a name, or empty when it holds none. */
  Optional<String> uuidForName(String name) throws IOException;
}
