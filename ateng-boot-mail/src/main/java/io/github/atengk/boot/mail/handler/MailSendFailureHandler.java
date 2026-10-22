package io.github.atengk.boot.mail.handler;

import io.github.atengk.boot.mail.model.MailMessage;

/**
 * 邮件物理发信失败终极补偿处理器扩展点 (SPI)
 * <p>
 * 当事务提交后发信（{@code sendAfterCommit}）、受管异步发信（{@code sendAsync}）
 * 或常规发信重试耗尽最终失败时触发，业务系统可据此实现发信失败记录持久化入库、
 * 告警群机器人通知或外部补偿队列投递。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@FunctionalInterface
public interface MailSendFailureHandler {

    /**
     * 发信最终失败时被框架回调
     *
     * @param message 失败的邮件消息领域模型
     * @param cause   捕获的原始失败异常
     */
    void onFailure(MailMessage message, Throwable cause);
}
