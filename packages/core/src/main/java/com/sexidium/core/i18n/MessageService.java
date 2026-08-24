package com.sexidium.core.i18n;

import com.sexidium.core.Branding;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.ResourceAdapter;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

public final class MessageService {
  private final ResourceAdapter resourceAdapter;
  private final ConfigurationAdapter configurationAdapter;
  private final LoggerAdapter loggerAdapter;
  private final Map<Language, Properties> catalogs = new EnumMap<>(Language.class);
  private Language defaultLanguage = Language.EN;
  private Language consoleLanguage = Language.EN;
  private String prefixMiniMessage;
  private String brandLabel;

  public MessageService(ResourceAdapter resourceAdapter, ConfigurationAdapter configurationAdapter, LoggerAdapter loggerAdapter) {
    this.resourceAdapter = resourceAdapter;
    this.configurationAdapter = configurationAdapter;
    this.loggerAdapter = loggerAdapter;
    reload();
  }

  public void reload() {
    brandLabel = Branding.label(configurationAdapter);
    String configuredPrefix = configurationAdapter.getString(
        "messages.prefix",
        "<gradient:#ff5f6d:#ffc371><bold><brand></bold></gradient> <dark_gray>»</dark_gray> "
    );
    prefixMiniMessage = configuredPrefix.contains("<brand>")
        ? configuredPrefix.replace("<brand>", brandLabel)
        : configuredPrefix.replace(Branding.DEFAULT_LABEL, brandLabel);
    defaultLanguage = Language.fromCode(configurationAdapter.getString("messages.default-language", "en"), Language.EN);
    consoleLanguage = Language.fromCode(configurationAdapter.getString("messages.console-language", "en"), defaultLanguage);
    catalogs.clear();
    for (Language language : Language.values()) {
      catalogs.put(language, load(language));
    }
  }

  public Language language(CommandSource commandSource) {
    if (commandSource != null && commandSource.playerSource()) {
      return Language.fromLocale(commandSource.locale(), defaultLanguage);
    }
    return consoleLanguage;
  }

  public String renderMini(CommandSource commandSource, LocalizedText localizedText) {
    return renderMini(language(commandSource), localizedText);
  }

  public String renderMini(Language language, LocalizedText localizedText) {
    String renderedTemplate = template(language, localizedText.messageKey());
    for (MessageArg messageArgument : localizedText.arguments()) {
      renderedTemplate = renderedTemplate.replace(
          "<" + messageArgument.name() + ">",
          messageArgument.render(this, language)
      );
    }
    return renderedTemplate;
  }

  public String renderPrefixedMini(CommandSource commandSource, LocalizedText localizedText) {
    return prefixMiniMessage + renderMini(commandSource, localizedText);
  }

  public String prefixMiniMessage() {
    return prefixMiniMessage;
  }

  public String brandLabel() {
    return brandLabel;
  }

  private Properties load(Language language) {
    Properties properties = new Properties();
    String resourcePath = "lang/" + language.code() + ".properties";
    try (InputStream resourceStream = resourceAdapter.openResource(resourcePath).orElse(null)) {
      if (resourceStream == null) {
        loggerAdapter.warning("Missing language catalog: " + resourcePath);
        return properties;
      }
      properties.load(new InputStreamReader(resourceStream, StandardCharsets.UTF_8));
    } catch (Exception exception) {
      loggerAdapter.warning("Failed to load language catalog " + resourcePath, exception);
    }
    return properties;
  }

  private String template(Language language, MessageKey messageKey) {
    Properties properties = catalogs.get(language);
    String value = properties == null ? null : properties.getProperty(messageKey.path());
    if (value != null) {
      return value;
    }
    Properties fallbackProperties = catalogs.get(Language.EN);
    value = fallbackProperties == null ? null : fallbackProperties.getProperty(messageKey.path());
    return value == null ? "<red>Missing translation: " + messageKey.path() + "</red>" : value;
  }
}
