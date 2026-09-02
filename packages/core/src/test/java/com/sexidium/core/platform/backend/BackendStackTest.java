package com.sexidium.core.platform.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendStackTest {

  private static final class FakeBackend implements Backend<String> {
    private final Set<String> capabilities;
    private final java.util.List<String> closeOrder;
    private final String name;

    FakeBackend(String name, java.util.List<String> closeOrder, String... capabilities) {
      this.name = name;
      this.closeOrder = closeOrder;
      this.capabilities = Set.of(capabilities);
    }

    @Override
    public Set<String> capabilities() {
      return capabilities;
    }

    @Override
    public void close() {
      closeOrder.add(name);
    }
  }

  @Test
  @DisplayName("capabilities are the union across layers")
  void capabilitiesAreTheUnion() {
    BackendStack<String> stack = BackendStack.of(
        new FakeBackend("head", new java.util.ArrayList<>(), "overlay"),
        new FakeBackend("floor", new java.util.ArrayList<>(), "sidebar", "popup"));

    assertEquals(Set.of("overlay", "sidebar", "popup"), stack.capabilities());
    assertTrue(stack.supports("sidebar"));
    assertTrue(stack.supports("overlay"));
    assertFalse(stack.supports("nothing"));
    assertFalse(stack.supports(null));
  }

  @Test
  @DisplayName("select() walks the stack in preference order and stops at the first capable layer")
  void selectPrefersTheHead() {
    FakeBackend preferred = new FakeBackend("head", new java.util.ArrayList<>(), "shared");
    FakeBackend fallback = new FakeBackend("floor", new java.util.ArrayList<>(), "shared", "floor");
    BackendStack<String> stack = BackendStack.of(preferred, fallback);

    assertTrue(stack.select("shared").isPresent());
    assertEquals(preferred, stack.select("shared").get(), "the head must win when it can serve");
    assertTrue(stack.select("floor").isPresent());
    assertEquals(fallback, stack.select("floor").get(), "the floor serves what the head cannot");
    assertTrue(stack.select("absent").isEmpty());
  }

  @Test
  @DisplayName("an empty-capability layer is never selected — installed is not capable")
  void anIncapableLayerIsSkipped() {
    BackendStack<String> stack = BackendStack.of(
        new FakeBackend("head", new java.util.ArrayList<>(), "unused"),
        new FakeBackend("floor", new java.util.ArrayList<>(), "floor"));

    assertTrue(stack.select("floor").isPresent());
    assertEquals(Set.of("unused", "floor"), stack.capabilities());
  }

  @Test
  @DisplayName("close() runs least preferred first, so floors outlive the layers over them")
  void closesFromTheTail() {
    java.util.List<String> closeOrder = new java.util.ArrayList<>();
    BackendStack<String> stack = BackendStack.of(
        new FakeBackend("head", closeOrder, "a"),
        new FakeBackend("floor", closeOrder, "b"));

    stack.close();

    assertEquals(java.util.List.of("floor", "head"), closeOrder);
  }
}
