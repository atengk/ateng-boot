package io.github.atengk.boot.web.autoconfigure;

import io.github.atengk.boot.core.audit.OperateLogModel;
import io.github.atengk.boot.web.aspect.OperateLogAspect;
import io.github.atengk.boot.web.audit.DefaultOperateLogStorageProvider;
import io.github.atengk.boot.web.audit.OperateLogStorageProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构化操作审计自动装配测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("结构化操作审计自动装配测试")
class OperateLogAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengWebAutoConfiguration.class));

    @Test
    @DisplayName("默认情况下自动装配 OperateLogAspect 与 DefaultOperateLogStorageProvider")
    void shouldLoadOperateLogBeansByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(OperateLogAspect.class);
            assertThat(context).hasSingleBean(OperateLogStorageProvider.class);
            assertThat(context.getBean(OperateLogStorageProvider.class)).isInstanceOf(DefaultOperateLogStorageProvider.class);
        });
    }

    @Test
    @DisplayName("业务自定义 OperateLogStorageProvider 时默认实现平滑退让")
    void shouldBackOffWhenCustomStorageProviderProvided() {
        OperateLogStorageProvider customProvider = (OperateLogModel model) -> {
            // custom persistence
        };

        contextRunner.withBean(OperateLogStorageProvider.class, () -> customProvider)
                .run(context -> {
                    assertThat(context).hasSingleBean(OperateLogStorageProvider.class);
                    assertThat(context.getBean(OperateLogStorageProvider.class)).isSameAs(customProvider);
                    assertThat(context).hasSingleBean(OperateLogAspect.class);
                });
    }

    @Test
    @DisplayName("配置 ateng.boot.web.operate-log.enabled=false 时切面不装配")
    void shouldNotLoadAspectWhenDisabled() {
        contextRunner.withPropertyValues("ateng.boot.web.operate-log.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(OperateLogAspect.class);
                });
    }
}
