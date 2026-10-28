package io.github.atengk.boot.core.properties;

import io.github.atengk.boot.core.enums.AsyncExecutionMode;
import io.github.atengk.boot.core.enums.AsyncRejectedPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异步执行器配置属性与策略枚举单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("异步执行器配置属性与策略枚举测试")
class AsyncPropertiesTest {

    @Test
    @DisplayName("验证 AsyncProperties 默认值与向后兼容适配方法")
    void testDefaultValuesAndBackwardCompatibility() {
        AsyncProperties properties = new AsyncProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getCorePoolSize()).isGreaterThanOrEqualTo(1);
        assertThat(properties.getMaxPoolSize()).isGreaterThanOrEqualTo(properties.getCorePoolSize());
        assertThat(properties.getQueueCapacity()).isEqualTo(1000);
        assertThat(properties.getKeepAlive()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.getKeepAliveSeconds()).isEqualTo(60);
        assertThat(properties.getThreadNamePrefix()).isEqualTo("ateng-async-");
        assertThat(properties.getVirtualThreadNamePrefix()).isEqualTo("ateng-vt-async-");
        assertThat(properties.getConcurrencyLimit()).isEqualTo(-1);
        assertThat(properties.isWaitForTasksToCompleteOnShutdown()).isTrue();
        assertThat(properties.getAwaitTermination()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.getAwaitTerminationSeconds()).isEqualTo(60);
        assertThat(properties.getMode()).isEqualTo(AsyncExecutionMode.AUTO);
        assertThat(properties.getRejectedPolicy()).isEqualTo(AsyncRejectedPolicy.CALLER_RUNS);
        assertThat(properties.getPools()).isNotNull().isEmpty();

        // 验证向后兼容 Setter
        properties.setKeepAliveSeconds(30);
        assertThat(properties.getKeepAlive()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getKeepAliveSeconds()).isEqualTo(30);

        properties.setAwaitTerminationSeconds(120);
        assertThat(properties.getAwaitTermination()).isEqualTo(Duration.ofSeconds(120));
        assertThat(properties.getAwaitTerminationSeconds()).isEqualTo(120);
    }

    @Test
    @DisplayName("验证 AsyncPoolProperties 线程名称前缀智能推导逻辑")
    void testResolveThreadNamePrefix() {
        AsyncPoolProperties pool = new AsyncPoolProperties();

        // 未配置前缀时根据池名自动推导
        assertThat(pool.resolveThreadNamePrefix("export")).isEqualTo("ateng-async-export-");
        assertThat(pool.resolveVirtualThreadNamePrefix("export")).isEqualTo("ateng-vt-async-export-");

        // 空池名时保底
        assertThat(pool.resolveThreadNamePrefix(null)).isEqualTo("ateng-async-");
        assertThat(pool.resolveVirtualThreadNamePrefix("")).isEqualTo("ateng-vt-async-");

        // 显式配置时以显式为准
        pool.setThreadNamePrefix("custom-pool-");
        pool.setVirtualThreadNamePrefix("custom-vt-");
        assertThat(pool.resolveThreadNamePrefix("export")).isEqualTo("custom-pool-");
        assertThat(pool.resolveVirtualThreadNamePrefix("export")).isEqualTo("custom-vt-");
    }

    @Test
    @DisplayName("验证 AsyncRejectedPolicy 枚举与 JDK 原生策略实例映射")
    void testRejectedPolicyMapping() {
        assertThat(AsyncRejectedPolicy.CALLER_RUNS.getHandler())
                .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        assertThat(AsyncRejectedPolicy.ABORT.getHandler())
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        assertThat(AsyncRejectedPolicy.DISCARD.getHandler())
                .isInstanceOf(ThreadPoolExecutor.DiscardPolicy.class);
        assertThat(AsyncRejectedPolicy.DISCARD_OLDEST.getHandler())
                .isInstanceOf(ThreadPoolExecutor.DiscardOldestPolicy.class);
    }

    @Test
    @DisplayName("通过 Spring Binder 绑定多线程池字典及枚举配置")
    void testBindingMultiPoolProperties() {
        Map<String, String> map = new HashMap<>();
        // 主池参数
        map.put("ateng.boot.async.core-pool-size", "4");
        map.put("ateng.boot.async.max-pool-size", "8");
        map.put("ateng.boot.async.rejected-policy", "abort");
        map.put("ateng.boot.async.mode", "platform");

        // 子池 export 参数
        map.put("ateng.boot.async.pools.export.enabled", "true");
        map.put("ateng.boot.async.pools.export.core-pool-size", "2");
        map.put("ateng.boot.async.pools.export.max-pool-size", "4");
        map.put("ateng.boot.async.pools.export.queue-capacity", "200");
        map.put("ateng.boot.async.pools.export.keep-alive", "30s");
        map.put("ateng.boot.async.pools.export.mode", "platform");
        map.put("ateng.boot.async.pools.export.rejected-policy", "caller_runs");

        // 子池 remoteNotify 参数（虚拟线程）
        map.put("ateng.boot.async.pools.remoteNotify.enabled", "true");
        map.put("ateng.boot.async.pools.remoteNotify.mode", "virtual");
        map.put("ateng.boot.async.pools.remoteNotify.concurrency-limit", "100");

        ConfigurationPropertySource source = new MapConfigurationPropertySource(map);
        Binder binder = new Binder(source);

        AsyncProperties properties = binder.bind("ateng.boot.async", AsyncProperties.class).get();

        // 验证主池
        assertThat(properties.getCorePoolSize()).isEqualTo(4);
        assertThat(properties.getMaxPoolSize()).isEqualTo(8);
        assertThat(properties.getRejectedPolicy()).isEqualTo(AsyncRejectedPolicy.ABORT);
        assertThat(properties.getMode()).isEqualTo(AsyncExecutionMode.PLATFORM);

        // 验证子池
        assertThat(properties.getPools()).hasSize(2);
        AsyncPoolProperties exportPool = properties.getPools().get("export");
        assertThat(exportPool).isNotNull();
        assertThat(exportPool.isEnabled()).isTrue();
        assertThat(exportPool.getCorePoolSize()).isEqualTo(2);
        assertThat(exportPool.getMaxPoolSize()).isEqualTo(4);
        assertThat(exportPool.getQueueCapacity()).isEqualTo(200);
        assertThat(exportPool.getKeepAlive()).isEqualTo(Duration.ofSeconds(30));
        assertThat(exportPool.getMode()).isEqualTo(AsyncExecutionMode.PLATFORM);
        assertThat(exportPool.getRejectedPolicy()).isEqualTo(AsyncRejectedPolicy.CALLER_RUNS);
        assertThat(exportPool.resolveThreadNamePrefix("export")).isEqualTo("ateng-async-export-");

        AsyncPoolProperties notifyPool = properties.getPools().get("remoteNotify");
        assertThat(notifyPool).isNotNull();
        assertThat(notifyPool.getMode()).isEqualTo(AsyncExecutionMode.VIRTUAL);
        assertThat(notifyPool.getConcurrencyLimit()).isEqualTo(100);
        assertThat(notifyPool.resolveVirtualThreadNamePrefix("remoteNotify")).isEqualTo("ateng-vt-async-remoteNotify-");
    }
}
