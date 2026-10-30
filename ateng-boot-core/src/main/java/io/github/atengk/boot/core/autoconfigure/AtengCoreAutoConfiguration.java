package io.github.atengk.boot.core.autoconfigure;

import io.github.atengk.boot.core.aspect.SlowAlertAspect;
import io.github.atengk.boot.core.diagnostic.SlowBeanDiagnosticPostProcessor;
import io.github.atengk.boot.core.id.Snowflake;
import io.github.atengk.boot.core.lifecycle.AppShutdownListener;
import io.github.atengk.boot.core.lifecycle.AppTempDirCleanupListener;
import io.github.atengk.boot.core.properties.CoreProperties;
import io.github.atengk.boot.core.properties.SnowflakeProperties;
import io.github.atengk.boot.core.util.SpringUtil;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Core 核心通用底座自动配置类
 * 负责在 Spring 容器就绪后自动装配 SpringUtil 全局静态容器门面、分布式 Snowflake 雪花发号器、慢 Bean 诊断与 @SlowAlert 慢调用切面
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

    /**
     * 自动装配慢 Bean 初始化耗时诊断后处理器（声明为 static 且注入 Environment，避免引发配置属性类过早实例化）
     *
     * @param environment Spring 运行时环境
     * @return SlowBeanDiagnosticPostProcessor 实例
     */
    @Bean
    @ConditionalOnMissingBean(SlowBeanDiagnosticPostProcessor.class)
    @ConditionalOnProperty(prefix = "ateng.boot.core.system", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static SlowBeanDiagnosticPostProcessor slowBeanDiagnosticPostProcessor(org.springframework.core.env.Environment environment) {
        Duration threshold = environment.getProperty("ateng.boot.core.system.slow-bean-threshold", Duration.class, Duration.ofMillis(500));
        return new SlowBeanDiagnosticPostProcessor(threshold);
    }

    /**
     * 自动装配应用优雅停机与 Uptime 摘要监听器
     *
     * @param properties Core 核心配置属性
     * @return AppShutdownListener 实例
     */
    @Bean
    @ConditionalOnMissingBean(AppShutdownListener.class)
    @ConditionalOnProperty(prefix = "ateng.boot.core.system", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AppShutdownListener appShutdownListener(CoreProperties properties) {
        boolean enabled = properties.getSystem() != null && properties.getSystem().isShutdownUptimeEnabled();
        return new AppShutdownListener(enabled);
    }

    /**
     * 自动装配应用受管临时目录关机清理监听器
     *
     * @param environment Spring 运行时环境
     * @return AppTempDirCleanupListener 实例
     */
    @Bean
    @ConditionalOnMissingBean(AppTempDirCleanupListener.class)
    @ConditionalOnProperty(prefix = "ateng.boot.core.system", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AppTempDirCleanupListener appTempDirCleanupListener(org.springframework.core.env.Environment environment) {
        String appName = environment.getProperty("spring.application.name", "application");
        return new AppTempDirCleanupListener(appName);
    }

    /**
     * 方法级慢调用告警切面配置类（仅在引入 AspectJ 且开启配置时自动挂载）
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.aspectj.lang.annotation.Aspect")
    @ConditionalOnProperty(prefix = "ateng.boot.core.slow-alert", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class SlowAlertConfiguration {

        @Bean
        @ConditionalOnMissingBean(SlowAlertAspect.class)
        public SlowAlertAspect slowAlertAspect(CoreProperties properties) {
            return new SlowAlertAspect(properties.getSlowAlert());
        }
    }
}
