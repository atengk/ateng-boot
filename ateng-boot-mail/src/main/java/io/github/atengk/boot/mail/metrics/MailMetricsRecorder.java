package io.github.atengk.boot.mail.metrics;

import java.time.Duration;

/**
 * 邮件度量记录器 SPI（与 Micrometer 解耦，缺席观测依赖时平滑降级为 No-Op）
 *
 * @author Ateng
 * @since 2026-11-16
 */
public interface MailMetricsRecorder {

    /**
     * 记录单次发信请求的调用结果与执行耗时
     *
     * @param accountName 发信账号逻辑标识（可空，空值将收敛为 {@code unknown}）
     * @param outcome     发信终态结果
     * @param duration    执行耗时（非负数）
     */
    void recordSend(String accountName, MailSendOutcome outcome, Duration duration);

    /**
     * 记录一次物理投递重试事件
     *
     * @param accountName 触发重试的发信账号逻辑标识
     */
    void recordRetry(String accountName);

    /**
     * 记录一次主备账号顺延容灾切换事件
     *
     * @param primaryAccountName 主发信账号逻辑标识
     * @param success            本次备用账号切换投递是否成功
     */
    void recordFailover(String primaryAccountName, boolean success);

    /**
     * 返回零开销的空实现（用于未引入 Micrometer 时的平滑降级）
     *
     * @return 空操作记录器实例
     */
    static MailMetricsRecorder noop() {
        return NoopMailMetricsRecorder.INSTANCE;
    }

    /**
     * 内置空操作实现
     */
    final class NoopMailMetricsRecorder implements MailMetricsRecorder {

        private static final NoopMailMetricsRecorder INSTANCE = new NoopMailMetricsRecorder();

        private NoopMailMetricsRecorder() {
        }

        @Override
        public void recordSend(String accountName, MailSendOutcome outcome, Duration duration) {
        }

        @Override
        public void recordRetry(String accountName) {
        }

        @Override
        public void recordFailover(String primaryAccountName, boolean success) {
        }
    }
}
