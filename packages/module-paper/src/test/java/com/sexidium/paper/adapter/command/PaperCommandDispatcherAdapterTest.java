package com.sexidium.paper.adapter.command;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperCommandDispatcherAdapterTest {

  @Test
  void dispatchFromConsole_dispatchesCleanedCommand() {
    Server server = mock(Server.class);
    org.bukkit.command.ConsoleCommandSender console = mock(org.bukkit.command.ConsoleCommandSender.class);
    when(server.getConsoleSender()).thenReturn(console);

    try (MockedStatic<Bukkit> staticBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
      staticBukkit.when(Bukkit::getServer).thenReturn(server);
      PaperCommandDispatcherAdapter adapter = new PaperCommandDispatcherAdapter();
      adapter.dispatchFromConsole("say hello world");
      verify(server).dispatchCommand(eq(console), eq("say hello world"));
    }
  }

  @Test
  void dispatchFromConsole_stripsLeadingSlash() {
    Server server = mock(Server.class);
    org.bukkit.command.ConsoleCommandSender console = mock(org.bukkit.command.ConsoleCommandSender.class);
    when(server.getConsoleSender()).thenReturn(console);

    try (MockedStatic<Bukkit> staticBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
      staticBukkit.when(Bukkit::getServer).thenReturn(server);
      PaperCommandDispatcherAdapter adapter = new PaperCommandDispatcherAdapter();
      adapter.dispatchFromConsole("/say hello");
      verify(server).dispatchCommand(eq(console), eq("say hello"));
    }
  }

  @Test
  void dispatchFromConsole_trimsWhitespace() {
    Server server = mock(Server.class);
    org.bukkit.command.ConsoleCommandSender console = mock(org.bukkit.command.ConsoleCommandSender.class);
    when(server.getConsoleSender()).thenReturn(console);

    try (MockedStatic<Bukkit> staticBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
      staticBukkit.when(Bukkit::getServer).thenReturn(server);
      PaperCommandDispatcherAdapter adapter = new PaperCommandDispatcherAdapter();
      adapter.dispatchFromConsole("   help   ");
      verify(server).dispatchCommand(eq(console), eq("help"));
    }
  }

  @Test
  void dispatchFromConsole_withNull_doesNothing() {
    Server server = mock(Server.class);

    try (MockedStatic<Bukkit> staticBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
      staticBukkit.when(Bukkit::getServer).thenReturn(server);
      PaperCommandDispatcherAdapter adapter = new PaperCommandDispatcherAdapter();
      adapter.dispatchFromConsole(null);
      verify(server, never()).dispatchCommand(any(), any());
    }
  }

  @Test
  void dispatchFromConsole_withBlank_doesNothing() {
    Server server = mock(Server.class);

    try (MockedStatic<Bukkit> staticBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
      staticBukkit.when(Bukkit::getServer).thenReturn(server);
      PaperCommandDispatcherAdapter adapter = new PaperCommandDispatcherAdapter();
      adapter.dispatchFromConsole("   ");
      verify(server, never()).dispatchCommand(any(), any());
    }
  }

  @Test
  void dispatchFromConsole_withJustSlash_doesNothing() {
    Server server = mock(Server.class);

    try (MockedStatic<Bukkit> staticBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
      staticBukkit.when(Bukkit::getServer).thenReturn(server);
      PaperCommandDispatcherAdapter adapter = new PaperCommandDispatcherAdapter();
      adapter.dispatchFromConsole("/");
      verify(server, never()).dispatchCommand(any(), any());
    }
  }
}
