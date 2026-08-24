package com.sexidium.core.game.experience.compose;

import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.experience.ExperienceHost;
import com.sexidium.core.game.experience.ExperienceState;
import com.sexidium.core.platform.BossBarHandle;
import com.sexidium.core.platform.HudPanelHandle;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the shared drop pipeline + block-break funnel — the headline composition mechanism. */
class DropPipelineTest {
  private static final ItemKey STONE = ItemKey.minecraft("stone");
  private static final ItemKey DIAMOND = ItemKey.minecraft("diamond");
  private static final WorldPosition POS = new WorldPosition("world", 0.5, 64.5, 0.5, 0.0F, 0.0F);

  // --- DropContext semantics ---

  @Test
  void multiply_scalesEveryStackAndSuppressesVanilla() {
    DropContext context = blockBreak(STONE);
    context.seedSourceItem();
    context.multiply(4, 1000);
    assertEquals(1, context.drops().size());
    assertEquals(4, context.drops().get(0).amount());
    assertEquals(STONE, context.drops().get(0).itemKey());
    assertTrue(context.vanillaSuppressed());
    assertTrue(context.dirty());
  }

  @Test
  void add_withoutSuppress_layersOnTopOfVanilla() {
    DropContext context = blockBreak(STONE);
    context.add(new ItemStackData(DIAMOND, 1, Map.of()));
    assertTrue(context.dirty());
    assertFalse(context.vanillaSuppressed());
  }

  @Test
  void multiply_clampsPerStackToMax() {
    DropContext context = blockBreak(STONE);
    context.drops().add(new ItemStackData(STONE, 100, Map.of()));
    context.multiply(1000, 256);
    assertEquals(256, context.drops().get(0).amount());
  }

  // --- pipeline ordering: GENERATE remap then TRANSFORM multiply ---

  @Test
  void generateRunsBeforeTransform_soMultiplierScalesRemappedItem() {
    FakeWorld world = new FakeWorld();
    DropPipeline pipeline = new DropPipeline(host(world));
    // Registered out of phase order on purpose; the pipeline must still run GENERATE first.
    pipeline.register(multiplyBy(3));
    pipeline.register(remapTo(DIAMOND));

    DropContext context = blockBreak(STONE);
    boolean suppress = pipeline.process(context);

    assertTrue(suppress, "remap+multiply should suppress vanilla");
    assertEquals(1, world.dropped.size());
    assertEquals(DIAMOND, world.dropped.get(0).itemKey(), "multiplier must apply to the remapped item");
    assertEquals(3, world.dropped.get(0).amount());
  }

  @Test
  void bareMultiply_seedsSourceBlockThenScales() {
    FakeWorld world = new FakeWorld();
    DropPipeline pipeline = new DropPipeline(host(world));
    pipeline.register(multiplyBy(8));

    pipeline.process(blockBreak(STONE));

    assertEquals(STONE, world.dropped.get(0).itemKey());
    assertEquals(8, world.dropped.get(0).amount());
  }

  @Test
  void emptyPipeline_leavesVanilla() {
    FakeWorld world = new FakeWorld();
    DropPipeline pipeline = new DropPipeline(host(world));
    assertFalse(pipeline.process(blockBreak(STONE)));
    assertTrue(world.dropped.isEmpty());
  }

  /**
   * Suppressing vanilla means "we dropped this instead". With nothing to drop it means "this block's loot
   * is destroyed", which is not something any contributor is trying to say.
   *
   * <p>This was a real, very visible bug and its trigger was a world reset: the reset empties every
   * inventory, so every player mines bare-handed for the next few minutes. A tool-gated block yields no
   * natural drops to a bare hand, so the pipeline's seed was empty — but a contributor had already
   * latched suppression while multiplying it. The block broke and nothing came out, for everyone, for as
   * long as it took to find a pickaxe.</p>
   */
  @Test
  void suppressionIsNeverClaimedForLootThatWasNotProduced() {
    FakeWorld world = new FakeWorld();
    DropPipeline pipeline = new DropPipeline(host(world));
    // A contributor that suppresses vanilla but yields nothing — a multiplier applied to an empty seed.
    pipeline.register(new DropContributor() {
      @Override
      public DropPhase phase() {
        return DropPhase.TRANSFORM;
      }

      @Override
      public void contribute(DropContext context) {
        context.suppressVanilla();
      }
    });

    assertFalse(pipeline.process(blockBreak(STONE)),
        "an empty result must leave vanilla alone, or the block's loot is destroyed outright");
    assertTrue(world.dropped.isEmpty(), "and nothing should have been emitted either");
  }

  /** The same contributor with something to show for itself still suppresses, as it must. */
  @Test
  void suppressionStillAppliesWhenLootWasActuallyProduced() {
    FakeWorld world = new FakeWorld();
    DropPipeline pipeline = new DropPipeline(host(world));
    pipeline.register(multiplyBy(3));

    DropContext context = blockBreak(STONE);
    context.seedSourceItem();
    assertTrue(pipeline.process(context));
    assertFalse(world.dropped.isEmpty());
  }

  // --- BlockBreakService: sweep loot routes through the same transforms ---

  @Test
  void sweepLoot_isMultiplied_soBreakAllFeedsTheMultiplier() {
    FakeWorld world = new FakeWorld();
    DropPipeline pipeline = new DropPipeline(host(world));
    pipeline.register(multiplyBy(2));
    BlockBreakService blocks = new BlockBreakService(pipeline);

    List<ItemStackData> resolved = blocks.transformRemovedLoot(DropSource.SWEEP, POS, STONE,
        List.of(new ItemStackData(STONE, 10, java.util.Map.of())), null);

    assertEquals(1, resolved.size());
    assertEquals(STONE, resolved.get(0).itemKey());
    assertEquals(20, resolved.get(0).amount(), "10 swept blocks * 2x multiplier");
  }

  @Test
  void manualBreak_emitsThroughWorld() {
    FakeWorld world = new FakeWorld();
    DropPipeline pipeline = new DropPipeline(host(world));
    pipeline.register(multiplyBy(5));
    BlockBreakService blocks = new BlockBreakService(pipeline);

    boolean suppress = blocks.onManualBreak(null,
        new com.sexidium.core.platform.model.BlockPosition("world", 0, 64, 0), STONE);

    assertTrue(suppress);
    assertEquals(5, world.dropped.get(0).amount());
  }

  // --- streamed emission: a huge payout is poured out over time, not spawned in one tick ---

  @Test
  void spreadOver_pouringIsSpreadAcrossTicks_andNothingIsLost() {
    FakeWorld world = new FakeWorld();
    FakeScheduler scheduler = new FakeScheduler();
    DropPipeline pipeline = new DropPipeline(host(world, scheduler));
    pipeline.maxStackSize(64);

    DropContext context = blockBreak(STONE);
    context.drops().add(new ItemStackData(STONE, 640, Map.of())); // 10 ground stacks of 64
    context.suppressVanilla();
    context.spreadOver(5);
    pipeline.emit(context);

    // Nothing lands in the breaking tick — the pour starts on the next one.
    assertTrue(world.dropped.isEmpty());
    scheduler.tick();
    assertEquals(2, world.dropped.size(), "10 stacks over 5 ticks = 2 per tick");
    scheduler.runUntilCancelled();
    assertEquals(10, world.dropped.size());
    assertEquals(640, totalAmount(world.dropped), "every item must still arrive");
    assertTrue(scheduler.cancelled, "the pour must cancel its own timer when done");
  }

  @Test
  void spreadOver_isIgnoredForASingleStack() {
    FakeWorld world = new FakeWorld();
    FakeScheduler scheduler = new FakeScheduler();
    DropPipeline pipeline = new DropPipeline(host(world, scheduler));

    DropContext context = blockBreak(STONE);
    context.drops().add(new ItemStackData(STONE, 1, Map.of()));
    context.suppressVanilla();
    context.spreadOver(200);
    pipeline.emit(context);

    assertEquals(1, world.dropped.size(), "one stack has nothing to spread — drop it now");
  }

  @Test
  void withoutASchedulerTheLootStillDrops() {
    FakeWorld world = new FakeWorld();
    DropPipeline pipeline = new DropPipeline(host(world)); // host whose runTimer returns null
    pipeline.maxStackSize(64);

    DropContext context = blockBreak(STONE);
    context.drops().add(new ItemStackData(STONE, 640, Map.of()));
    context.suppressVanilla();
    context.spreadOver(20);
    pipeline.emit(context);

    assertEquals(640, totalAmount(world.dropped), "a host with no scheduler must not swallow the loot");
  }

  private static int totalAmount(List<ItemStackData> stacks) {
    int total = 0;
    for (ItemStackData stack : stacks) {
      total += stack.amount();
    }
    return total;
  }

  /** Runs the single repeating task the pour registers, on demand. */
  private static final class FakeScheduler {
    private Runnable task;
    boolean cancelled;

    ScheduledTask register(Runnable runnable) {
      this.task = runnable;
      return () -> cancelled = true;
    }

    void tick() {
      if (task != null && !cancelled) {
        task.run();
      }
    }

    void runUntilCancelled() {
      for (int guard = 0; guard < 100 && !cancelled; guard++) {
        tick();
      }
    }
  }

  // --- block-change veto arbitration ---

  @Test
  void veto_blocksPlacementOfClaimedType() {
    BlockBreakService blocks = new BlockBreakService(new DropPipeline(host(new FakeWorld())));
    blocks.registerVeto(new BlockChangeVeto() {
      @Override
      public boolean allowsPlace(WorldPosition position, ItemKey type) {
        return type == null || !type.value().equals("stone");
      }
    });
    assertFalse(blocks.allowsPlace(POS, STONE));
    assertTrue(blocks.allowsPlace(POS, DIAMOND));
  }

  // --- helpers ---

  private static DropContext blockBreak(ItemKey key) {
    return new DropContext(DropSource.BLOCK_BREAK, key, POS, null, List.of());
  }

  private static DropContributor multiplyBy(int factor) {
    return new DropContributor() {
      @Override
      public DropPhase phase() {
        return DropPhase.TRANSFORM;
      }

      @Override
      public void contribute(DropContext context) {
        context.seedSourceItem();
        context.multiply(factor, 1_000_000);
      }
    };
  }

  private static DropContributor remapTo(ItemKey target) {
    return new DropContributor() {
      @Override
      public DropPhase phase() {
        return DropPhase.GENERATE;
      }

      @Override
      public void contribute(DropContext context) {
        int total = 0;
        for (ItemStackData stack : context.drops()) {
          total += stack.amount();
        }
        context.replaceAll(List.of(new ItemStackData(target, Math.max(1, total), Map.of())));
      }
    };
  }

  private static ExperienceHost host(WorldAdapter world) {
    return host(world, null);
  }

  private static ExperienceHost host(WorldAdapter world, FakeScheduler scheduler) {
    return new ExperienceHost() {
      @Override
      public GameContext gameContext() {
        return null;
      }

      @Override
      public List<PlayerAdapter> online() {
        return List.of();
      }

      @Override
      public boolean isParticipant(PlayerAdapter playerAdapter) {
        return true;
      }

      @Override
      public ScheduledTask runTimer(Runnable runnable, long delayTicks, long periodTicks) {
        return scheduler == null ? null : scheduler.register(runnable);
      }

      @Override
      public ScheduledTask runLater(Runnable runnable, long delayTicks) {
        return null;
      }

      @Override
      public BossBarHandle track(BossBarHandle bossBarHandle) {
        return bossBarHandle;
      }

      @Override
      public HudPanelHandle track(HudPanelHandle hudPanelHandle) {
        return hudPanelHandle;
      }

      @Override
      public WorldAdapter world() {
        return world;
      }

      @Override
      public ExperienceState sharedState() {
        return ExperienceState.empty();
      }

      @Override
      public void softRespawn(PlayerAdapter playerAdapter) {
      }

      @Override
      public void killParticipant(PlayerAdapter playerAdapter) {
      }
    };
  }

  /** Captures dropped item stacks. */
  private static final class FakeWorld implements WorldAdapter {
    final List<ItemStackData> dropped = new ArrayList<>();

    @Override
    public String name() {
      return "world";
    }

    @Override
    public WorldPosition spawnPosition() {
      return POS;
    }

    @Override
    public List<PlayerAdapter> players() {
      return List.of();
    }

    @Override
    public void dropItem(WorldPosition targetPosition, ItemStackData itemStackData) {
      dropped.add(itemStackData);
    }

    @Override
    public void playSound(WorldPosition targetPosition, SoundKey soundKey, float volume, float pitch) {
    }

    @Override
    public void setBorder(WorldBorderSpec worldBorderSpec) {
    }

    @Override
    public void resetBorder() {
    }

    @Override
    public void loadChunk(int chunkX, int chunkZ, boolean generate) {
    }
  }
}
