package com.sexidium.core.auth;

import com.sexidium.core.i18n.Language;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.i18n.MessageService;

/**
 * Rendering for screens shown BEFORE a client locale exists.
 *
 * <p>Everything the gate says is said twice — English, a divider, Brazilian Portuguese — under the
 * brand header, because the handshake is not far enough along for the client to have told us
 * anything about itself. Shared by the login gate, the session gate and the in-world hold's first
 * title so the three never drift into three different-looking screens.</p>
 */
public final class AuthMessages {

  private AuthMessages() {
  }

  public static String bilingual(MessageService messageService, MessageKey key, MessageArg... args) {
    LocalizedText text = LocalizedText.of(key, args);
    String english = messageService.renderMini(Language.EN, text);
    String portuguese = messageService.renderMini(Language.PT, text);
    return "<gradient:#ff5f6d:#ffc371><bold>" + messageService.brandLabel() + "</bold></gradient>\n\n"
        + english
        + "\n\n<dark_gray>──────────────────</dark_gray>\n\n"
        + portuguese;
  }

  /** The same two languages without the brand header — for a title/actionbar that has no room. */
  public static String bilingualInline(MessageService messageService, MessageKey key, MessageArg... args) {
    LocalizedText text = LocalizedText.of(key, args);
    return messageService.renderMini(Language.EN, text)
        + " <dark_gray>|</dark_gray> "
        + messageService.renderMini(Language.PT, text);
  }
}
