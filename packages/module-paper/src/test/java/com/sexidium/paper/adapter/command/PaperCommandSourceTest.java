package com.sexidium.paper.adapter.command;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperCommandSourceTest {

  @Test
  void name_returnsSenderName() {
    CommandSender sender = mock(CommandSender.class);
    when(sender.getName()).thenReturn("Steve");
    PaperCommandSource source = new PaperCommandSource(sender);
    assertEquals("Steve", source.name());
  }

  @Test
  void locale_isAlwaysEnglish() {
    CommandSender sender = mock(CommandSender.class);
    PaperCommandSource source = new PaperCommandSource(sender);
    assertEquals(java.util.Locale.ENGLISH, source.locale());
  }

  @Test
  void hasPermission_delegatesToSender() {
    CommandSender sender = mock(CommandSender.class);
    when(sender.hasPermission("sexidium.admin")).thenReturn(true);
    PaperCommandSource source = new PaperCommandSource(sender);
    assertTrue(source.hasPermission("sexidium.admin"));
    verify(sender).hasPermission("sexidium.admin");
  }

  @Test
  void hasPermission_withNull_returnsFalseWhenSenderDoesNotHave() {
    CommandSender sender = mock(CommandSender.class);
    when(sender.hasPermission((String) null)).thenReturn(false);
    PaperCommandSource source = new PaperCommandSource(sender);
    assertFalse(source.hasPermission(null));
  }

  @Test
  void sendMiniMessage_sendsAsAdventureComponent() {
    CommandSender sender = mock(CommandSender.class);
    PaperCommandSource source = new PaperCommandSource(sender);
    source.sendMiniMessage("<red>hello</red>");
    verify(sender).sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize("<red>hello</red>"));
  }

  @Test
  void sendMiniMessage_withNull_sendsEmptyComponent() {
    CommandSender sender = mock(CommandSender.class);
    PaperCommandSource source = new PaperCommandSource(sender);
    source.sendMiniMessage(null);
    verify(sender).sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(""));
  }

  @Test
  void sendPlainMessage_delegatesToSender() {
    CommandSender sender = mock(CommandSender.class);
    PaperCommandSource source = new PaperCommandSource(sender);
    source.sendPlainMessage("plain");
    verify(sender).sendPlainMessage("plain");
  }

  @Test
  void sendPlainMessage_withNull_sendsEmptyString() {
    CommandSender sender = mock(CommandSender.class);
    PaperCommandSource source = new PaperCommandSource(sender);
    source.sendPlainMessage(null);
    verify(sender).sendPlainMessage("");
  }
}
