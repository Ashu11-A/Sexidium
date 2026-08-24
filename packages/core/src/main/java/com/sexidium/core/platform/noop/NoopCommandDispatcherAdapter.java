package com.sexidium.core.platform.noop;

import com.sexidium.core.platform.CommandDispatcherAdapter;

public final class NoopCommandDispatcherAdapter implements CommandDispatcherAdapter {
  @Override
  public void dispatchFromConsole(String commandLine) {
  }
}
