package io.github.atengk.boot.sms.metrics;

import io.github.atengk.boot.sms.guard.SmsChannelCircuitBreaker;

import java.time.Duration;

/**
 * 短信可观测度量记录器 SPI（与 Micrometer 解耦，缺席观测依赖时平滑降级为 No-Op）
 *
 * @author Ateng
 * @since 2026-11-21
 */
public interface SmsMetricsRecorder {

    /**
     * 记录单次或批量出站发信的终态结果与耗时分布
     *
     * @param channelName  目标渠道逻辑名称（低基数收敛处理）
     * @param supplierCode 云厂商驱动编码（如 aliyun、tencent、mock）
     * @param outcome      执行终态枚举
     * @param duration     执行物理耗时（可为 null）
     */
    void recordSend(String channelName, String supplierCode, SmsSendOutcome outcome, Duration duration);

    /**
     * 记录一次单渠道物理 HTTP 重试事件
     *
     * @param channelName  发生重试的渠道逻辑名称
     * @param supplierCode 云厂商驱动编码
     */
    void recordRetry(String channelName, String supplierCode);

    /**
     * 记录一次主备渠道顺延容灾降级切换事件
     *
     * @param primaryChannelName 主发信渠道逻辑名称
     * @param supplierCode       主渠道驱动编码
     * @param success            最终降级投递是否成功
     */
    void recordFailover(String primaryChannelName, String supplierCode, boolean success);

    /**
     * 记录一次渠道健康度熔断器状态转换事件（如 CLOSED -> OPEN -> HALF_OPEN -> CLOSED）
     *
     * @param channelName 渠道名称
     * @param fromState   转换前状态
     * @param toState     转换后状态
     */
    void recordCircuitBreakerTransition(String channelName,
                                        SmsChannelCircuitBreaker.State fromState,
                                        SmsChannelCircuitBreaker.State toState);

    /**
     * 记录一次滑动窗口频控限流拦截事件
     *
     * @param namespace   限流命名空间（如 verify、notify）
     * @param channelName 渠道逻辑名称
     */
    void recordRateLimited(String namespace, String channelName);

    /**
     * 记录一次策略拦截事件（黑名单、免打扰或自定义拦截器阻断）
     *
     * @param channelName 渠道逻辑名称
     * @param reasonType  收敛后的低基数拦截原因分类（如 blacklist、quiet_hours、interceptor、sandbox）
     */
    void recordBlocked(String channelName, String reasonType);

    /**
     * 获取零开销空操作度量记录器（缺席 Micrometer 时使用）
     *
     * @return 空操作实例
     */
    static SmsMetricsRecorder noop() {
        return NoopSmsMetricsRecorder.INSTANCE;
    }

    /**
     * 内置空操作实现
     */
    final class NoopSmsMetricsRecorder implements SmsMetricsRecorder {

        private static final NoopSmsMetricsRecorder INSTANCE = new NoopSmsMetricsRecorder();

        private NoopSmsMetricsRecorder() {
        }

        @Override
        public void recordSend(String channelName, String supplierCode, SmsSendOutcome outcome, Duration duration) {
        }

        @Override
        public void recordRetry(String channelName, String supplierCode) {
        }

        @Override
        public void recordFailover(String primaryChannelName, String supplierCode, boolean success) {
        }

        @Override
        public void recordCircuitBreakerTransition(String channelName,
                                                   SmsChannelCircuitBreaker.State fromState,
                                                   SmsChannelCircuitBreaker.State toState) {
        }

        @Override
        public void recordRateLimited(String namespace, String channelName) {
        }

        @Override
        public void recordBlocked(String channelName, String reasonType) {
        }
    }
}
