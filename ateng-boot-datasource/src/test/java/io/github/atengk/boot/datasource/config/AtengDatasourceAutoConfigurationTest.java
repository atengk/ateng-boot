package io.github.atengk.boot.datasource.config;

import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceProperties;
import io.github.atengk.boot.datasource.enums.MultiTenancyMode;
import io.github.atengk.boot.datasource.enums.ReadAfterWriteScope;
import io.github.atengk.boot.datasource.guard.DynamicDatasourceGuard;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.readwrite.DefaultWriteOccurredResolver;
import io.github.atengk.boot.datasource.readwrite.ReadAfterWriteManager;
import io.github.atengk.boot.datasource.readwrite.WriteOccurredResolver;
import io.github.atengk.boot.datasource.routing.AtengDynamicRoutingDataSource;
import io.github.atengk.boot.datasource.strategy.HealthAwareDynamicDataSourceStrategy;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.github.atengk.boot.datasource.tenant.DefaultTenantDataSourceProvider;
import io.github.atengk.boot.datasource.tenant.TenantDataSourceProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动装配与配置绑定单元测试
 *
 * @author Ateng
 * @since 2026-10-24
 */
class AtengDatasourceAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengDatasourceAutoConfiguration.class));

    @Test
    @DisplayName("测试默认配置下自动装配与属性默认值")
    void testDefaultConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengDatasourceAutoConfiguration.class);
            assertThat(context).hasSingleBean(DatasourceProperties.class);
            assertThat(context).hasSingleBean(TenantDataSourceProvider.class);
            assertThat(context.getBean(TenantDataSourceProvider.class)).isInstanceOf(DefaultTenantDataSourceProvider.class);
            assertThat(context).hasSingleBean(DataSource.class);
            assertThat(context.getBean(DataSource.class)).isInstanceOf(AtengDynamicRoutingDataSource.class);
            assertThat(context).hasSingleBean(DynamicDatasourceGuard.class);
            assertThat(context).hasSingleBean(DynamicDatasourceTemplate.class);
            assertThat(context).hasSingleBean(WriteOccurredResolver.class);
            assertThat(context.getBean(WriteOccurredResolver.class)).isInstanceOf(DefaultWriteOccurredResolver.class);
            assertThat(context).hasSingleBean(ReadAfterWriteManager.class);
            assertThat(context).hasSingleBean(HealthAwareDynamicDataSourceStrategy.class);

            DatasourceProperties properties = context.getBean(DatasourceProperties.class);
            assertThat(properties.isEnabled()).isTrue();

            // 多租户默认配置
            assertThat(properties.getMultiTenancy().isEnabled()).isTrue();
            assertThat(properties.getMultiTenancy().getMode()).isEqualTo(MultiTenancyMode.FALLBACK_MASTER);
            assertThat(properties.getMultiTenancy().getDefaultDataSource()).isEqualTo("master");
            assertThat(properties.getMultiTenancy().getDataSourcePrefix()).isEqualTo("tenant_");

            // 读写分离默认配置
            assertThat(properties.getReadWriteSplit().isEnabled()).isTrue();
            assertThat(properties.getReadWriteSplit().getMasterDataSource()).isEqualTo("master");
            assertThat(properties.getReadWriteSplit().getSlaveDataSource()).isEqualTo("slave");
            assertThat(properties.getReadWriteSplit().getReadAfterWrite().isEnabled()).isTrue();
            assertThat(properties.getReadWriteSplit().getReadAfterWrite().getScope()).isEqualTo(ReadAfterWriteScope.REQUEST);
            assertThat(properties.getReadWriteSplit().getReadAfterWrite().getWindowTtl()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.getReadWriteSplit().getFailover().isEnabled()).isTrue();
            assertThat(properties.getReadWriteSplit().getFailover().getCooldownWindow()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.getReadWriteSplit().getFailover().isFallbackToMaster()).isTrue();

            // 守卫与动态加载默认配置
            assertThat(properties.getGuard().isEnabled()).isTrue();
            assertThat(properties.getGuard().isFailOnFalseSwitch()).isTrue();
            assertThat(properties.getDynamicLoad().isEnabled()).isTrue();
            assertThat(properties.getDynamicLoad().getProbeTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.getDynamicLoad().getDrainTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.getSecurity().isPasswordDecryptionEnabled()).isTrue();
            assertThat(properties.getSeata().isEnabled()).isFalse();
        });
    }

    @Test
    @DisplayName("测试显式关闭 enabled 时自动装配静默跳过")
    void testDisabledConfiguration() {
        contextRunner
                .withPropertyValues("ateng.boot.datasource.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengDatasourceAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(DatasourceProperties.class);
                    assertThat(context).doesNotHaveBean(TenantDataSourceProvider.class);
                    assertThat(context).doesNotHaveBean(DataSource.class);
                    assertThat(context).doesNotHaveBean(DynamicDatasourceGuard.class);
                    assertThat(context).doesNotHaveBean(DynamicDatasourceTemplate.class);
                    assertThat(context).doesNotHaveBean(WriteOccurredResolver.class);
                    assertThat(context).doesNotHaveBean(ReadAfterWriteManager.class);
                    assertThat(context).doesNotHaveBean(HealthAwareDynamicDataSourceStrategy.class);
                });
    }

    @Test
    @DisplayName("测试个性化配置属性绑定")
    void testCustomPropertiesBinding() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.datasource.multi-tenancy.mode=STRICT",
                        "ateng.boot.datasource.multi-tenancy.default-data-source=primary",
                        "ateng.boot.datasource.multi-tenancy.data-source-prefix=vip_",
                        "ateng.boot.datasource.read-write-split.read-after-write.scope=WINDOW",
                        "ateng.boot.datasource.read-write-split.read-after-write.window-ttl=8s",
                        "ateng.boot.datasource.read-write-split.failover.cooldown-window=1m",
                        "ateng.boot.datasource.dynamic-load.probe-timeout=4s",
                        "ateng.boot.datasource.dynamic-load.drain-timeout=10s",
                        "ateng.boot.datasource.seata.enabled=true"
                )
                .run(context -> {
                    DatasourceProperties properties = context.getBean(DatasourceProperties.class);
                    assertThat(properties.getMultiTenancy().getMode()).isEqualTo(MultiTenancyMode.STRICT);
                    assertThat(properties.getMultiTenancy().getDefaultDataSource()).isEqualTo("primary");
                    assertThat(properties.getMultiTenancy().getDataSourcePrefix()).isEqualTo("vip_");
                    assertThat(properties.getReadWriteSplit().getReadAfterWrite().getScope()).isEqualTo(ReadAfterWriteScope.WINDOW);
                    assertThat(properties.getReadWriteSplit().getReadAfterWrite().getWindowTtl()).isEqualTo(Duration.ofSeconds(8));
                    assertThat(properties.getReadWriteSplit().getFailover().getCooldownWindow()).isEqualTo(Duration.ofMinutes(1));
                    assertThat(properties.getDynamicLoad().getProbeTimeout()).isEqualTo(Duration.ofSeconds(4));
                    assertThat(properties.getDynamicLoad().getDrainTimeout()).isEqualTo(Duration.ofSeconds(10));
                    assertThat(properties.getSeata().isEnabled()).isTrue();
                });
    }
}
