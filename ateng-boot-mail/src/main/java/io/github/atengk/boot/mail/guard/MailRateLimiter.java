package io.github.atengk.boot.mail.guard;

import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailMessage;

import java.util.Collection;

/**
 * 邮件出站频率限制 SPI
 * <p>
 * 结合“发信账号令牌桶（平滑整体吞吐）”与“单收件人滑动窗口（防单点验证码轰炸）”，
 * 按去重后的实际物理收件人次计费拦截，提供单机内存与 Redis 分布式双模协同能力。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-16
 */
public interface MailRateLimiter {

    /**
     * 检查并申请本次邮件发送所需的出站频控许可（按去重收件人次扣减）
     *
     * @param account                当前路由发信账号
     * @param message                待发送邮件领域消息
     * @param deduplicatedRecipients 去重后的小写物理收件人邮箱集合（To + Cc + Bcc）
     * @return true 表示通过频控允许发信；false 表示触发限流拦截
     */
    boolean tryAcquire(MailAccount account, MailMessage message, Collection<String> deduplicatedRecipients);

    /**
     * 重置全部限流器状态（用于单元测试隔离或运维手动复位）
     */
    default void reset() {
    }
}
