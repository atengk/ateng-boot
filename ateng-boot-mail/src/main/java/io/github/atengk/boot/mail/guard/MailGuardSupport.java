package io.github.atengk.boot.mail.guard;

import io.github.atengk.boot.mail.model.MailAccount;

import java.util.Locale;

/**
 * 邮件出站防护组件内部共享辅助工具
 *
 * @author Ateng
 * @since 2026-11-16
 */
final class MailGuardSupport {

    private static final String DEFAULT_ACCOUNT_KEY = "default";

    private MailGuardSupport() {
    }

    static String resolveAccountKey(MailAccount account) {
        if (account == null || account.getName() == null || account.getName().isBlank()) {
            return DEFAULT_ACCOUNT_KEY;
        }
        return account.getName().trim();
    }

    static String normalizeRecipient(String recipient) {
        if (recipient == null) {
            return "";
        }
        return recipient.trim().toLowerCase(Locale.ROOT);
    }
}
