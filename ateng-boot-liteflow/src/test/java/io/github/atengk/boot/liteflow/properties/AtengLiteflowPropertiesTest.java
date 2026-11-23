package io.github.atengk.boot.liteflow.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LiteFlow 配置属性单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
@DisplayName("LiteFlow 配置属性单元测试")
class AtengLiteflowPropertiesTest {

    @Test
    @DisplayName("验证默认配置属性与生产基线合理性")
    void testDefaultProperties() {
        AtengLiteflowProperties properties = new AtengLiteflowProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.isPrintExecutionLog()).isTrue();
        assertThat(properties.getRuleSource()).isEqualTo("classpath*:liteflow/*.el.xml,classpath*:liteflow/*.el.yml");

        AtengLiteflowProperties.ThreadPoolProperties pool = properties.getThreadPool();
        assertThat(pool.isPropagateContext()).isTrue();
        assertThat(pool.isUseManagedPool()).isTrue();
        assertThat(pool.getExecutorClass()).isNull();
        assertThat(pool.getCorePoolSize()).isEqualTo(16);
        assertThat(pool.getMaxPoolSize()).isEqualTo(64);
        assertThat(pool.getQueueCapacity()).isEqualTo(1000);
        assertThat(pool.getKeepAlive()).isEqualTo(Duration.ofSeconds(60));
        assertThat(pool.getThreadNamePrefix()).isEqualTo("ateng-liteflow-");
    }

    @Test
    @DisplayName("验证自定义配置属性修改生效")
    void testCustomProperties() {
        AtengLiteflowProperties properties = new AtengLiteflowProperties();
        properties.setEnabled(false);
        properties.setRuleSource("config/custom.el.json");
        properties.setPrintExecutionLog(false);

        AtengLiteflowProperties.ThreadPoolProperties pool = new AtengLiteflowProperties.ThreadPoolProperties();
        pool.setPropagateContext(false);
        pool.setCorePoolSize(8);
        pool.setMaxPoolSize(32);
        pool.setQueueCapacity(200);
        pool.setThreadNamePrefix("custom-liteflow-");
        properties.setThreadPool(pool);

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getRuleSource()).isEqualTo("config/custom.el.json");
        assertThat(properties.isPrintExecutionLog()).isFalse();
        assertThat(properties.getThreadPool().getCorePoolSize()).isEqualTo(8);
        assertThat(properties.getThreadPool().getMaxPoolSize()).isEqualTo(32);
        assertThat(properties.getThreadPool().getThreadNamePrefix()).isEqualTo("custom-liteflow-");
    }
}
