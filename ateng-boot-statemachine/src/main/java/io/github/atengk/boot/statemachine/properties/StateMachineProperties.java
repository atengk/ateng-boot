package io.github.atengk.boot.statemachine.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

/**
 * 状态机组件配置属性
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ateng.boot.statemachine")
public class StateMachineProperties {

    /**
     * 是否启用状态机 Starter 自动装配，默认 true
     */
    private boolean enabled = true;

    /**
     * 状态机全链路日志审计配置
     */
    @NestedConfigurationProperty
    private Logging logging = new Logging();

    /**
     * 状态机 Micrometer 运维度量指标配置
     */
    @NestedConfigurationProperty
    private Metrics metrics = new Metrics();

    /**
     * 状态机日志审计属性
     */
    @Getter
    @Setter
    public static class Logging {

        /**
         * 是否启用状态机流转日志打印，默认 true
         */
        private boolean enabled = true;

        /**
         * 是否在日志中打印上下文载荷 (包含脱敏处理)，默认 false
         */
        private boolean logContext = false;

        /**
         * 单次状态跃迁耗时慢告警阈值（默认 500ms，设为 null 或非正数则关闭慢跃迁告警）
         */
        private Duration slowTransitionThreshold = Duration.ofMillis(500);
    }

    /**
     * 状态机运维度量指标属性
     */
    @Getter
    @Setter
    public static class Metrics {

        /**
         * 是否启用 Micrometer 性能度量监控（QPS 与耗时计时器），默认 true
         */
        private boolean enabled = true;
    }
}
