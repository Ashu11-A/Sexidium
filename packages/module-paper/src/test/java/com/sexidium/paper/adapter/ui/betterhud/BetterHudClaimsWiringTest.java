package com.sexidium.paper.adapter.ui.betterhud;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural guards on the exclusive sweep.
 *
 * <p>Source-level rather than behavioural because every branch here ends in a call into BetterHud, and
 * BetterHud is {@code compileOnly} — there is no server, and no vendor class, on the test runtime
 * classpath. What these pin is not the plumbing but the two decisions the plumbing exists to express,
 * both of which are invisible until an operator is looking at the wrong thing on screen.</p>
 */
class BetterHudClaimsWiringTest {

  /**
   * Exclusive mode has to strip when the claim set is EMPTY, not only when it has entries.
   *
   * <p>The empty case is the common one — every player in the lobby, and every player in a mode that
   * declares no surface. An earlier version returned early there after purging only our own
   * {@code sexidium_}-prefixed objects, which left BetterHud's demo hud and its {@code default: true}
   * compass drawing on every player forever, since neither is ours to purge.</p>
   */
  @Test
  void theExclusiveStripRunsEvenWhenNothingIsClaimed() {
    // Scoped to reconcile's own body: `owned.isEmpty()` is legitimate elsewhere (release() drops the
    // map entry when a player's last claim goes), and asserting over the whole file would fail on that.
    String reconcile = methodBody(read("BetterHudClaims.java"), "void reconcile(");

    int strip = reconcile.indexOf("api.retain(player, owned)");
    int loop = reconcile.indexOf("for (String id : claims.");
    assertTrue(strip > 0, "reconcile must strip in exclusive mode");
    assertTrue(loop > strip, "the strip must precede the re-add, or it removes what it just applied");
    assertFalse(reconcile.contains("owned.isEmpty()"),
        "an empty-claims early return is exactly the bug: the lobby IS the empty case, and it is the "
            + "one that has to strip");
  }

  /**
   * A popup on screen has to survive the exclusive strip for its own duration.
   *
   * <p>It is not a claim — it is fired and left to expire, and it must never be re-asserted — but the
   * strip removes everything outside the retain set, and it runs on the shared HUD cadence. So while a
   * popup was live but unrecorded, the sweep took it down within a second of it appearing: a
   * three-second announcement lasted one, and a countdown re-shown every second raced the sweep and
   * blinked. The two halves below have to hold together — defended from the strip, absent from the
   * re-add — or the fix trades a toast that dies early for one that can never be waited out.</p>
   */
  @Test
  void aLivePopupIsDefendedFromTheStripButNeverReAsserted() {
    String source = read("BetterHudClaims.java");
    String retained = methodBody(source, "Set<String> owned(");
    String reconcile = methodBody(source, "void reconcile(");

    assertTrue(retained.contains("firedPopups"),
        "the retain set must carry whatever popup is still on screen, or the sweep strips it");
    assertTrue(retained.contains("removeIf"),
        "a popup held past its expiry would defend an object the player is no longer wearing for ever");

    assertTrue(reconcile.contains("api.retain(player, owned)"),
        "the strip uses the wider set: claims plus live popups");
    assertFalse(reconcile.contains("for (String id : owned)"),
        "re-adding from the retain set would re-fire a live popup on every sweep, so a toast could"
            + " never expire; the re-add reads the CLAIMS");
  }

  /**
   * Re-showing a popup that is still on screen must not fire a second copy of it.
   *
   * <p>A popup is fired, not worn, and BetterHud draws a re-fire <em>beside</em> the live one rather
   * than in place of it. Callers legitimately re-show every second — the reset countdown does, because
   * the vanilla title it also feeds has no memory and has to be re-sent — so without this guard one
   * countdown stacks a fresh number on screen per second. The values are published either way, so the
   * live copy still counts down.</p>
   *
   * <p>The guard asks {@code showingPopup}, never {@code showing}: the two answer different questions
   * and only one of them works here. {@code showing} reads what BetterHud says the player is WEARING,
   * which is derived from {@code HudObject#add} — and firing a popup writes somewhere else entirely,
   * so it is false for every popup ever shown. A guard on it would never fire.</p>
   */
  @Test
  void reShowingALivePopupUpdatesItRatherThanStackingASecondOne() {
    String show = methodBody(read("BetterHudSurfaceHandle.java"), "public void show(");

    assertTrue(show.contains("publish(playerAdapter)"),
        "the new value must reach the player whether or not the popup is re-fired");
    int published = show.indexOf("publish(playerAdapter)");
    int guard = show.indexOf("claims.showingPopup(player, spec.id())");
    int claim = show.indexOf("claims.claim(");
    assertTrue(guard > published && guard > 0,
        "a popup already on screen needs the value, not a second instance of itself");
    assertTrue(claim > guard,
        "the guard must sit before the claim, or the re-fire happens anyway");
    assertTrue(show.contains("HudSurfaceKind.POPUP"),
        "the skip is about popups only: a persistent surface must keep being re-asserted");
  }

  /** The source of one method, from its signature to the first line that closes it at method indent. */
  private static String methodBody(String source, String signature) {
    int start = source.indexOf(signature);
    assertTrue(start > 0, "expected a method matching " + signature);
    int end = source.indexOf("\n  }", start);
    assertTrue(end > start, "could not find the end of " + signature);
    return source.substring(start, end);
  }

  /**
   * Teardown must stay bounded by our own namespace even though the exclusive sweep is not.
   *
   * <p>The two reaches are deliberately different and easy to conflate. A switched-off driver removing
   * another plugin's huds would be a plugin vandalising a server it was told to stay out of.</p>
   */
  @Test
  void teardownIsBoundedByOurNamespaceUnlikeTheExclusiveSweep() {
    String source = read("BetterHudClaims.java");
    int teardown = source.indexOf("void purgeOwned(");
    assertTrue(teardown > 0);

    String body = source.substring(teardown);
    assertTrue(body.contains("api.purge(player, HudSurfaceSpec.NAMESPACE)"),
        "the disabled path must only ever be able to undo our own work");
    assertFalse(body.contains("api.retain("),
        "the unbounded strip belongs to exclusive mode, never to teardown");
  }

  private static String read(String fileName) {
    Path relative = Path.of("src/main/java/com/sexidium/paper/adapter/ui/betterhud").resolve(fileName);
    try {
      return Files.readString(relative);
    } catch (IOException exception) {
      try {
        return Files.readString(Path.of("packages/module-paper").resolve(relative));
      } catch (IOException nested) {
        throw new AssertionError("Could not read " + relative, nested);
      }
    }
  }
}
