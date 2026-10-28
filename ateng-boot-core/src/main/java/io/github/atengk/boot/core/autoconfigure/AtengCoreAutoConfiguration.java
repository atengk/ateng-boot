package io.github.atengk.boot.core.autoconfigure;

import io.github.atengk.boot.core.id.Snowflake;
import io.github.atengk.boot.core.properties.CoreProperties;
import io.github.atengk.boot.core.properties.SnowflakeProperties;
import io.github.atengk.boot.core.util.SpringUtil;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * Core 核心通用底座自动配置类
 * 负责在 Spring 容器就绪后自动装配 SpringUtil 全局静态容器门面与受管分布式 Snowflake 雪花发号器
 *
 * @author Ateng
 * @since 2026-10-17
 */
@AutoConfiguration
@ConditionalOnClass({SpringUtil.class, Snowflake.class})
@EnableConfigurationProperties(CoreProperties.class)
public class AtengCoreAutoConfiguration {

    /**
     * 自动装配 SpringUtil 静态门面 Bean
     *
     * @return SpringUtil 实例
     */
    @Bean
    @ConditionalOnMissingBean(SpringUtil.class)
    public SpringUtil springUtil() {
        return new SpringUtil();
    }

    /**
     * 自动装配分布式全局唯一 Snowflake 雪花发号器
     * 优先选用 CoreProperties 中显式配置的 workerId/datacenterId/epoch/tolerance，
     * 未显式配置时优雅平滑回退至自适应网卡 MAC、本机 IP 或安全随机数推导
     *
     * @param properties Core 核心配置属性
     * @return Snowflake 实例
     */
    @Bean
    @ConditionalOnMissingBean(Snowflake.class)
    @ConditionalOnProperty(prefix = "ateng.boot.core.snowflake", name = "enabled", havingValue = "true", matchIfMissing = true)
    public Snowflake snowflake(CoreProperties properties) {
        SnowflakeProperties snowflakeProps = properties.getSnowflake();
        Long workerId = snowflakeProps != null ? snowflakeProps.getWorkerId() : null;
        Long datacenterId = snowflakeProps != null ? snowflakeProps.getDatacenterId() : null;
        long epoch = snowflakeProps != null ? snowflakeProps.getEpoch() : Snowflake.DEFAULT_EPOCH;
        Duration tolerance = properties.getEffectiveClockBackoffTolerance();
        long maxBackwardMs = tolerance != null ? Math.max(0, tolerance.toMillis()) : Snowflake.DEFAULT_MAX_BACKWARD_MS;

        return new Snowflake(workerId, datacenterId, epoch, maxBackwardMs);
    }
}
