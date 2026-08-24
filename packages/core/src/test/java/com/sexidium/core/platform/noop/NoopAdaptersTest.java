package com.sexidium.core.platform.noop;

import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.model.BossBarColor;
import com.sexidium.core.platform.model.BossBarOverlay;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NoopAdaptersTest {

  @Test
  void noopEventDispatcher_registerAndUnregister_doNotThrow() {
    NoopEventDispatcherAdapter adapter = new NoopEventDispatcherAdapter();
    assertDoesNotThrow(() -> adapter.registerGame(null));
    assertDoesNotThrow(() -> adapter.unregisterGame(null));
  }

  @Test
  void noopCommandDispatcher_dispatchFromConsole_doesNotThrow() {
    assertDoesNotThrow(() -> new NoopCommandDispatcherAdapter().dispatchFromConsole("any command"));
  }

  @Test
  void noopUiAdapter_createBossBar_returnsNoopBossBarHandle() {
    var handle = new NoopUiAdapter().createBossBar(
        LocalizedText.of(MessageKey.COMMAND_RELOAD), 1.0f, BossBarColor.RED, BossBarOverlay.PROGRESS);
    assertNotNull(handle);
    assertInstanceOf(NoopBossBarHandle.class, handle);
  }

  @Test
  void noopBossBarHandle_allMethods_doNotThrow() {
    NoopBossBarHandle handle = new NoopBossBarHandle();
    assertDoesNotThrow(() -> handle.title(LocalizedText.of(MessageKey.COMMAND_RELOAD)));
    assertDoesNotThrow(() -> handle.progress(0.5f));
    assertDoesNotThrow(() -> handle.show(null));
    assertDoesNotThrow(() -> handle.hide(null));
    assertDoesNotThrow(handle::close);
  }

  @Test
  void noopKitAdapter_returnsSensibleDefaults() {
    NoopKitAdapter kit = new NoopKitAdapter();
    assertFalse(kit.apply(null, "any"));
    assertFalse(kit.exists("any"));
    assertTrue(kit.names().isEmpty());
    assertDoesNotThrow(kit::reload);
  }

  @Test
  void noopWorldLeaseService_returnsDisabledState() {
    NoopWorldLeaseService svc = new NoopWorldLeaseService();
    assertFalse(svc.enabled());
    assertTrue(svc.acquireReady().isEmpty());
    assertTrue(svc.lobbySpawn().isEmpty());
    assertTrue(svc.reacquireByName("world").isEmpty());
    assertDoesNotThrow(() -> svc.preserve(java.util.List.of("w")));
    assertDoesNotThrow(() -> svc.discardByName("w"));
    assertDoesNotThrow(svc::start);
    assertDoesNotThrow(svc::shutdown);
  }

  @Test
  void noopWorldLeaseService_acquireOrCreate_callsOnFailure() {
    NoopWorldLeaseService svc = new NoopWorldLeaseService();
    boolean[] failureCalled = {false};
    svc.acquireOrCreate(java.util.List.of(), lease -> {}, () -> failureCalled[0] = true);
    assertTrue(failureCalled[0]);
  }

  @Test
  void classLoaderResourceAdapter_openResource_withMissing_returnsEmpty() {
    ClassLoaderResourceAdapter adapter = new ClassLoaderResourceAdapter(ClassLoader.getSystemClassLoader());
    assertTrue(adapter.openResource("nonexistent/path/resource.txt").isEmpty());
  }

  @Test
  void classLoaderResourceAdapter_nullClassLoader_usesSystem() {
    ClassLoaderResourceAdapter adapter = new ClassLoaderResourceAdapter(null);
    assertNotNull(adapter);
    assertTrue(adapter.openResource("no-such-resource").isEmpty());
  }
}
