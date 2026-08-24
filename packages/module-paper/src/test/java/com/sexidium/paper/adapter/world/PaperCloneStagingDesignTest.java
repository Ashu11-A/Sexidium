package com.sexidium.paper.adapter.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural guards for the Paper half of A4 M9 (the map-template copy hoisted off the world thread).
 *
 * <p>The control layer copies a template straight into the folder this backend will create the clone
 * world from. That saves the server a multi-megabyte copy — and up to half a second of lock wait — on the
 * thread where every player is waiting, but it puts a trap in this file: the staged folder makes the
 * clone destination look like a world that was already on disk, and the two things the clone branch does
 * besides copying are exactly the two the "already on disk" branch skips. One of them (reading the
 * template's stored spawn) decides where the admin lands in the map editor; both of them decide the world
 * spawn, and the world border is CENTRED on the world spawn — so a map whose build sits far from the
 * origin (spawns at {@code -772,31,426} exist in the bundled maps) would end up outside its own 750-block
 * border, with no error anywhere. That is why staging had to be opt-in per backend, and why these guards
 * exist.</p>
 *
 * <p>Source-level, like {@code PaperLinkedDimensionDesignTest}: driving {@code PaperWorldControl} needs a
 * live Bukkit server. The behavioural proof that the copy really leaves the world thread lives in core's
 * {@code CloneStagingTest}, against a backend shaped like this one.</p>
 */
class PaperCloneStagingDesignTest {

  private static String source() {
    Path path = Path.of("src/main/java/com/sexidium/paper/adapter/world/PaperWorldControl.java");
    try {
      return Files.readString(path);
    } catch (IOException exception) {
      try {
        return Files.readString(Path.of("packages/module-paper").resolve(path));
      } catch (IOException nested) {
        throw new AssertionError("Could not read " + path, nested);
      }
    }
  }

  /** Opting in is what makes the hoist happen at all: the base class stages nothing by default. */
  @Test
  void theBackendTellsTheControlLayerWhereToStageAClone() {
    String source = source();
    assertTrue(source.contains("protected Path backendCloneStagingFolder(WorldRequest request)"),
        "PaperWorldControl must override backendCloneStagingFolder, or the copy stays on the world thread");
    int override = source.indexOf("protected Path backendCloneStagingFolder(WorldRequest request)");
    String body = source.substring(override, Math.min(source.length(), override + 400));
    assertTrue(body.contains("dimensionFolder(request)"),
        "the staging folder must be the very folder the world is created from, not a scratch directory");
    assertTrue(body.contains("WorldKind.CLONE"),
        "only a CLONE has a template to stage");
  }

  /**
   * The trap itself: a staged clone must keep taking the clone branch.
   *
   * <p>If {@code onDisk} ever swallows a staged folder, {@code cloneTemplate} stops running (no template
   * spawn) and {@code pinLandSpawn} stops running (no land spawn) — and the border follows the spawn.</p>
   */
  @Test
  void aStagedCloneIsNotMistakenForAWorldThatAlreadyExisted() {
    String source = source();
    int acquire = source.indexOf("protected Optional<WorldHandle> backendAcquire(WorldRequest request");
    assertTrue(acquire > 0, "backendAcquire must exist");
    String body = source.substring(acquire, source.indexOf("\n  }", acquire));

    assertTrue(body.contains("boolean staged = request.kind() == WorldKind.CLONE"),
        "backendAcquire must recognise a clone the control layer already staged");
    assertTrue(body.contains("boolean onDisk = !staged"),
        "a staged clone must NOT count as already-on-disk: that is what keeps the template spawn and"
            + " pinLandSpawn (and therefore the world border) exactly as they were");
    assertTrue(body.contains("cloneTemplate(request, folder, staged)"),
        "the clone branch must still run for a staged clone, and be told that it is staged");
    assertTrue(body.contains("if (!onDisk) {\n        pinLandSpawn(world, request);")
            || body.contains("if (!onDisk) {"),
        "the land-spawn pin must stay keyed on onDisk");
  }

  /** Skipping the copy is the point; skipping the spawn read with it is the regression. */
  @Test
  void theStagedShortCircuitStillReadsTheTemplateSpawn() {
    String source = source();
    int clone = source.indexOf("private boolean cloneTemplate(WorldRequest request, Path destination, boolean staged)");
    assertTrue(clone > 0, "cloneTemplate must take the staged flag");
    String body = source.substring(clone, source.indexOf("\n  }", clone));

    int shortCircuit = body.indexOf("if (staged)");
    assertTrue(shortCircuit > 0, "cloneTemplate must short-circuit its copy when the folder is already staged");
    String branch = body.substring(shortCircuit, body.indexOf("}", shortCircuit));
    assertTrue(branch.contains("pendingCloneSpawn = readLevelSpawn("),
        "the staged branch must still re-pin the template's spawn — the world border is centred on it");
    assertTrue(branch.contains("return true"), "a staged clone is a successful clone");
    // And the unstaged path must still be a VERIFIED copy: the staged one is verified by the control layer.
    assertTrue(body.contains("copyChunkDataChecked"),
        "the world-thread fallback must keep using the checked copy");
  }
}
