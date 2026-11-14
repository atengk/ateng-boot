package io.github.atengk.boot.web.autoconfigure;

import io.github.atengk.boot.core.audit.OperateLogModel;
import io.github.atengk.boot.web.aspect.OperateLogAspect;
import io.github.atengk.boot.web.aspect.WebLogAspect;
import io.github.atengk.boot.web.audit.DefaultOperateLogStorageProvider;
import io.github.atengk.boot.web.audit.OperateLogStorageProvider;
import io.github.atengk.boot.web.properties.WebProperties;
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

    @Test
    @DisplayName("默认加载 11 个敏感排除词基线，且支持通过配置文件覆盖 operate-log/log default-exclude-params 并感知 save 别名")
    void shouldBindDefaultAndCustomExcludeParamsProperties() {
        contextRunner.withPropertyValues("ateng.boot.web.log.enabled=true")
                .run(context -> {
                    WebProperties properties = context.getBean(WebProperties.class);
                    assertThat(properties.getOperateLog().getDefaultExcludeParams())
                            .containsExactly("password", "passwd", "pwd", "oldPassword", "newPassword",
                                    "confirmPassword", "secret", "token", "accessToken", "refreshToken", "authorization");
                    assertThat(properties.getLog().getDefaultExcludeParams())
                            .containsExactlyElementsOf(properties.getOperateLog().getDefaultExcludeParams());
                    assertThat(context).hasSingleBean(WebLogAspect.class);

                    OperateLogStorageProvider provider = context.getBean(OperateLogStorageProvider.class);
                    OperateLogModel model = new OperateLogModel();
                    model.setModule("autowire-check");
                    provider.save(model);
                });

        contextRunner.withPropertyValues(
                        "ateng.boot.web.log.enabled=true",
                        "ateng.boot.web.operate-log.default-exclude-params[0]=customPin",
                        "ateng.boot.web.log.default-exclude-params[0]=customSecret"
                )
                .run(context -> {
                    WebProperties properties = context.getBean(WebProperties.class);
                    assertThat(properties.getOperateLog().getDefaultExcludeParams()).containsExactly("customPin");
                    assertThat(properties.getLog().getDefaultExcludeParams()).containsExactly("customSecret");
                });
    }
}
