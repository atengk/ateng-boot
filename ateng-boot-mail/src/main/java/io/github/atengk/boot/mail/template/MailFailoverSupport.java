package io.github.atengk.boot.mail.template;

import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailMessage;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import java.util.Queue;

/**
 * 邮件主备顺延容灾地址对齐与队列辅助协作者
 *
 * @author Ateng
 * @since 2026-11-16
 */
final class MailFailoverSupport {

    private MailFailoverSupport() {
    }

    static void enqueueBackupAccounts(Queue<String> queue, MailAccount account) {
        if (queue == null || account == null || account.getBackupAccounts() == null) {
            return;
        }
        for (String backupName : account.getBackupAccounts()) {
            if (backupName != null && !backupName.isBlank()) {
                queue.offer(backupName.trim());
            }
        }
    }

    static String normalizeAccountName(String name) {
        return (name != null && !name.isBlank()) ? name.trim() : "default";
    }

    static String resolveAccountOwnFrom(MailAccount account) {
        if (account == null) {
            return null;
        }
        if (account.getDefaultFrom() != null && !account.getDefaultFrom().isBlank()) {
            return account.getDefaultFrom();
        }
        return (account.getUsername() != null && !account.getUsername().isBlank()) ? account.getUsername() : null;
    }

    static String resolveRawFromAddress(MailAccount account, MailMessage message) {
        if (message != null && message.getFrom() != null && !message.getFrom().isBlank()) {
            return message.getFrom();
        }
        return resolveAccountOwnFrom(account);
    }

    static String resolveValidEmailAddress(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            InternetAddress[] parsed = InternetAddress.parse(candidate.trim(), true);
            if (parsed == null || parsed.length != 1) {
                return null;
            }
            String addr = parsed[0].getAddress();
            if (addr == null || !addr.contains("@")) {
                return null;
            }
            return addr.trim();
        } catch (AddressException ignored) {
            return null;
        }
    }

    static FailoverAddressOverride resolveAddressOverride(boolean overrideFromOnSwitch,
                                                          MailAccount primaryAccount,
                                                          MailAccount backupAccount,
                                                          MailMessage message) {
        String rawInitialFrom = resolveRawFromAddress(primaryAccount, message);
        String validInitialEmail = resolveValidEmailAddress(rawInitialFrom);
        String initialReplyTo = message != null ? message.getReplyTo() : null;

        if (!overrideFromOnSwitch) {
            String preservedFromName = resolvePreservedFromName(primaryAccount, message);
            return new FailoverAddressOverride(rawInitialFrom, preservedFromName, initialReplyTo);
        }

        String backupFrom = resolveValidEmailAddress(resolveAccountOwnFrom(backupAccount));
        String targetFrom = backupFrom != null ? backupFrom : validInitialEmail;

        String targetReplyTo = initialReplyTo;
        if ((targetReplyTo == null || targetReplyTo.isBlank())
                && validInitialEmail != null
                && !validInitialEmail.isBlank()
                && targetFrom != null
                && !targetFrom.equalsIgnoreCase(validInitialEmail)) {
            targetReplyTo = validInitialEmail;
        }

        // overrideFromOnSwitch=true 且切换了发件地址时，若消息自身未指定 fromName，则使用备用账号 defaultFromName 对齐显示身份
        String targetFromName = (message != null && message.getFromName() != null && !message.getFromName().isBlank())
                ? message.getFromName()
                : (backupAccount != null ? backupAccount.getDefaultFromName() : null);

        return new FailoverAddressOverride(targetFrom, targetFromName, targetReplyTo);
    }

    private static String resolvePreservedFromName(MailAccount primaryAccount, MailMessage message) {
        if (message != null && message.getFromName() != null && !message.getFromName().isBlank()) {
            return message.getFromName();
        }
        String rawFrom = message != null ? message.getFrom() : null;
        if (rawFrom != null && !rawFrom.isBlank()) {
            try {
                InternetAddress[] parsed = InternetAddress.parse(rawFrom.trim(), true);
                if (parsed != null && parsed.length == 1
                        && parsed[0].getPersonal() != null && !parsed[0].getPersonal().isBlank()) {
                    return parsed[0].getPersonal();
                }
            } catch (AddressException ignored) {
            }
        }
        return primaryAccount != null ? primaryAccount.getDefaultFromName() : null;
    }

    /**
     * 主备顺延切换发信时的地址与昵称临时覆盖视图（避免污染原始 MailMessage 与多跳串扰）
     *
     * @param from     生效发件地址
     * @param fromName 生效发件人显示昵称
     * @param replyTo  生效回复地址
     */
    record FailoverAddressOverride(String from, String fromName, String replyTo) {
    }
}
