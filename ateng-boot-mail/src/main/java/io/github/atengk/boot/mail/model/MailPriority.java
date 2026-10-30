package io.github.atengk.boot.mail.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 邮件优先级与重要性强类型枚举
 * <p>
 * 遵循 RFC 标头规范标准，支持主流邮件客户端（Outlook、Foxmail、Apple Mail、Thunderbird 等）识别
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Getter
@RequiredArgsConstructor
public enum MailPriority {

    /**
     * 高优先级 / 重要邮件 (X-Priority: 1, Importance: High)
     */
    HIGH(1, "1", "High"),

    /**
     * 普通优先级 / 默认正常邮件 (X-Priority: 3, Importance: Normal)
     */
    NORMAL(3, "3", "Normal"),

    /**
     * 低优先级邮件 (X-Priority: 5, Importance: Low)
     */
    LOW(5, "5", "Low");

    /**
     * 数字优先级编号 (1, 3, 5)
     */
    private final int priority;

    /**
     * X-Priority 标头值 ("1", "3", "5")
     */
    private final String xPriority;

    /**
     * Importance / X-MSMail-Priority 标头值 ("High", "Normal", "Low")
     */
    private final String importance;
}
