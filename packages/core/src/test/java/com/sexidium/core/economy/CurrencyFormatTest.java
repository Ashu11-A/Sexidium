package com.sexidium.core.economy;

import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrencyFormatTest {

  private static PropertiesConfigurationAdapter config() {
    return new PropertiesConfigurationAdapter();
  }

  @Test
  void enUs_putsTheSymbolFirstAndGroupsWithCommas() {
    CurrencyFormat format = new CurrencyFormat(config());
    assertEquals("$1,234.56", format.format(Money.ofMinor(123_456L)));
  }

  @Test
  void ptBr_withSymbolAfter() {
    PropertiesConfigurationAdapter configuration = config();
    configuration.set("economy.format.locale", "pt-BR");
    configuration.set("economy.currency.symbol-before", "false");
    CurrencyFormat format = new CurrencyFormat(configuration);
    assertEquals("1.234,56 $", format.format(Money.ofMinor(123_456L)));
  }

  @Test
  void groupingOff_dropsTheSeparators() {
    PropertiesConfigurationAdapter configuration = config();
    configuration.set("economy.format.grouping", "false");
    CurrencyFormat format = new CurrencyFormat(configuration);
    assertEquals("$1234.56", format.format(Money.ofMinor(123_456L)));
  }

  @Test
  void badLocale_fallsBackToUsRatherThanThrowing() {
    // Locale.forLanguageTag answers Locale.ROOT for nonsense, which formats without separators -- and
    // would look like a Sexidium bug rather than like the config typo it is.
    assertEquals(Locale.US, CurrencyFormat.resolveLocale("not a locale"));
    assertEquals(Locale.US, CurrencyFormat.resolveLocale(""));
    assertEquals(Locale.US, CurrencyFormat.resolveLocale(null));
    PropertiesConfigurationAdapter configuration = config();
    configuration.set("economy.format.locale", "not a locale");
    assertEquals("$1,234.56", new CurrencyFormat(configuration).format(Money.ofMinor(123_456L)));
  }

  @Test
  void reload_picksUpTheNewSpecOnEveryThread() {
    PropertiesConfigurationAdapter configuration = config();
    CurrencyFormat format = new CurrencyFormat(configuration);
    assertEquals("$1.00", format.format(Money.ofMinor(100L)));
    configuration.set("economy.currency.symbol", "R$");
    format.reload();
    assertEquals("R$1.00", format.format(Money.ofMinor(100L)));
  }

  @Test
  void eightThreadsFormattingConcurrentlyAllAgree() throws Exception {
    // DecimalFormat is NOT thread-safe: one shared instance corrupts its own buffer under concurrent
    // use and emits garbage digits, intermittently. The sidebar formats on the HUD render tick while
    // /pay formats on the main thread, so this is the real access pattern and not a synthetic one.
    CurrencyFormat format = new CurrencyFormat(config());
    int threads = 8;
    int iterations = 2_000;
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    CopyOnWriteArraySet<String> seen = new CopyOnWriteArraySet<>();
    for (int index = 0; index < threads; index++) {
      Thread worker = new Thread(() -> {
        try {
          start.await();
          for (int iteration = 0; iteration < iterations; iteration++) {
            seen.add(format.format(Money.ofMinor(123_456L)));
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        } finally {
          done.countDown();
        }
      });
      worker.setDaemon(true);
      worker.start();
    }
    start.countDown();
    assertTrue(done.await(30, TimeUnit.SECONDS), "the formatting threads did not finish");
    assertEquals(java.util.Set.of("$1,234.56"), new java.util.HashSet<>(seen),
        "concurrent formatting produced more than one answer for the same amount");
  }

  @Test
  void formatNamed_usesTheSingularAtExactlyOne() {
    CurrencyFormat format = new CurrencyFormat(config());
    assertEquals("1.00 dollar", format.formatNamed(Money.ofMinor(100L)));
    assertEquals("2.00 dollars", format.formatNamed(Money.ofMinor(200L)));
    assertEquals("0.00 dollars", format.formatNamed(Money.ZERO));
  }
}
