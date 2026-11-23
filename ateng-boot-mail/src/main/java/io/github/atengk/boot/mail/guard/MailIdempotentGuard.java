package io.github.atengk.boot.mail.guard;

import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailMessage;

/**
 * 邮件出站幂等防重守卫 SPI
 * <p>
 * 防止网络抖动重推或业务上游重复触发导致向同一业务单据重复发送多封邮件。
 * 当物理发送失败时，调用方应通过 {@link #releaseLock} 释放占位锁以支持后续补偿重试。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-16
 */
public interface MailIdempotentGuard {

    /**
     * 尝试抢占指定幂等键的独占锁
     *
     * @param account       当前路由发信账号
     * @param message       待发送邮件领域消息
     * @param idempotentKey 标准化幂等键（通常为 bizId 或消息摘要指纹）
     * @return true 表示首次抢占成功允许发送；false 表示重复请求应被幂等拦截
     */
    boolean tryAcquire(MailAccount account, MailMessage message, String idempotentKey);

    /**
     * 在物理发信失败或被前置熔断时主动释放已抢占的幂等锁，允许后续补偿重试
     *
     * @param account       当前路由发信账号
     * @param message       待发送邮件领域消息
     * @param idempotentKey 标准化幂等键
     */
    void releaseLock(MailAccount account, MailMessage message, String idempotentKey);

    /**
     * 查询指定幂等键当前是否仍处于锁定有效期内
     *
     * @param idempotentKey 标准化幂等键
     * @return 若锁定中返回 true，否则返回 false
     */
    boolean isLocked(String idempotentKey);

    /**
     * 清空全部本地幂等锁状态（用于单元测试隔离或运维手工清理）
     */
    default void clear() {
    }
}
