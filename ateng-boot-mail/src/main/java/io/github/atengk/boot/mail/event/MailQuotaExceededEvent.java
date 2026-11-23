package io.github.atengk.boot.mail.event;

import io.github.atengk.boot.mail.model.MailMessage;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 邮件自然日总配额耗尽熔断解耦领域事件
 * <p>
 * 当单日累计去重收件人次达到 {@code dailyMaxCount} 上限触发硬熔断时同步发布，
 * 供业务侧通过 {@code @EventListener} 自愿订阅接入告警机器人或运维通知而不耦合通知模块。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Getter
public class MailQuotaExceededEvent extends MailSendEvent {

    /**
     * 触发配额熔断时路由的发信账号标识
     */
    private final String accountName;

    /**
     * 统计配额所属自然日
     */
    private final LocalDate quotaDate;

    /**
     * 配置的单日最大允许发送收件人次阈值
     */
    private final long dailyLimit;

    /**
     * 触顶时刻当天已累计消耗的发送人次
     */
    private final long currentCount;

    /**
     * 本次被熔断拦截的邮件去重收件人次
     */
    private final int requestedCount;

    /**
     * 熔断发生时间
     */
    private final Instant occurredAt;

    public MailQuotaExceededEvent(Object source,
                                  MailMessage message,
                                  String accountName,
                                  LocalDate quotaDate,
                                  long dailyLimit,
                                  long currentCount,
                                  int requestedCount) {
        super(source, message);
        this.accountName = accountName;
        this.quotaDate = quotaDate;
        this.dailyLimit = dailyLimit;
        this.currentCount = currentCount;
        this.requestedCount = requestedCount;
        this.occurredAt = Instant.now();
    }
}
