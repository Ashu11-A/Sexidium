package com.sexidium.core.game.experience.challenges;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Consumer;

/**
 * The persistent roster of players ever roped into a Chained-Together experience, comma-joined in the
 * experience's shared state. Used to tell a brand-new late arrival (→ teleported to the host) from a
 * returning member (→ resumes at their saved spot). Bounded so a long-lived public experience can't
 * grow the persisted string without limit.
 *
 * <p>Decoupled from the {@code Challenge} state helpers via a read/write accessor pair the challenge
 * supplies, so the comma-string semantics live in one place.</p>
 */
final class ChainMemberRoster {
  // Bound on the persisted member roster so a long-lived public experience can't grow it without limit.
  private static final int MAX_REMEMBERED_MEMBERS = 256;

  private final Function<String, String> read;
  private final Consumer<String> write;

  ChainMemberRoster(Function<String, String> read, Consumer<String> write) {
    this.read = read;
    this.write = write;
  }

  private Set<String> memberSet() {
    Set<String> members = new LinkedHashSet<>();
    String raw = read.apply("");
    if (raw != null && !raw.isBlank()) {
      for (String part : raw.split(",")) {
        if (!part.isBlank()) {
          members.add(part.trim());
        }
      }
    }
    return members;
  }

  boolean knownMember(UUID id) {
    return memberSet().contains(id.toString());
  }

  void rememberMember(UUID id) {
    Set<String> members = memberSet();
    if (members.add(id.toString())) {
      // Bound the roster: evict the oldest entries (insertion order) so a busy public experience can't
      // grow the persisted string without limit.
      Iterator<String> it = members.iterator();
      while (members.size() > MAX_REMEMBERED_MEMBERS && it.hasNext()) {
        it.next();
        it.remove();
      }
      write.accept(String.join(",", members));
    }
  }

  void pruneMember(UUID id) {
    Set<String> members = memberSet();
    if (members.remove(id.toString())) {
      write.accept(String.join(",", members));
    }
  }
}
