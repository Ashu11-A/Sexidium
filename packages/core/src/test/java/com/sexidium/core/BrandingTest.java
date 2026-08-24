package com.sexidium.core;

import com.sexidium.core.i18n.MessageService;
import com.sexidium.core.i18n.Language;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.noop.ClassLoaderResourceAdapter;
import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BrandingTest {
  @Test
  void configuredLabelIsUsed() {
    PropertiesConfigurationAdapter configuration = new PropertiesConfigurationAdapter();
    configuration.set(Branding.LABEL_PATH, "Ashu");

    assertEquals("Ashu", Branding.label(configuration));
  }

  @Test
  void blankLabelFallsBackToTheDefault() {
    assertEquals(Branding.DEFAULT_LABEL, Branding.label("  "));
  }

  @Test
  void messagePrefixUsesTheConfiguredLabel() {
    PropertiesConfigurationAdapter configuration = new PropertiesConfigurationAdapter();
    configuration.set(Branding.LABEL_PATH, "Sexidium Ashu");
    configuration.set("messages.prefix", "<brand> » ");

    MessageService messages = new MessageService(new ClassLoaderResourceAdapter(getClass().getClassLoader()),
        configuration, new StdoutLoggerAdapter("Test"));

    assertEquals("Sexidium Ashu » ", messages.prefixMiniMessage());
    assertEquals("<gradient:#ff5f6d:#ffc371><bold>Sexidium Ashu</bold></gradient>",
        messages.renderMini(Language.EN, LocalizedText.of(MessageKey.LOBBY_HUD_TITLE,
            MessageArg.text("brand", Branding.label(configuration)))));
  }
}
