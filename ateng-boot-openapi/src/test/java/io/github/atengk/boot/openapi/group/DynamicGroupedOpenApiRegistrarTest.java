package io.github.atengk.boot.openapi.group;

import io.github.atengk.boot.openapi.autoconfigure.OpenApiAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动态接口分组注册器集成测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("动态接口分组注册器测试")
class DynamicGroupedOpenApiRegistrarTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpenApiAutoConfiguration.class));

    @Test
    @DisplayName("测试未显式配置 groups 时兜底注册默认 default 分组")
    void testDefaultGroupRegistration() {
        contextRunner.run(context -> {
            Map<String, GroupedOpenApi> groupBeans = context.getBeansOfType(GroupedOpenApi.class);
            assertThat(groupBeans).hasSize(1);
            assertThat(groupBeans).containsKey("defaultGroupedOpenApi");

            GroupedOpenApi defaultGroup = groupBeans.get("defaultGroupedOpenApi");
            assertThat(defaultGroup.getGroup()).isEqualTo("default");
            assertThat(defaultGroup.getDisplayName()).isEqualTo("全量接口文档");
            assertThat(defaultGroup.getPathsToMatch()).containsExactly("/**");
        });
    }

    @Test
    @DisplayName("测试配置驱动的多业务分组动态注册")
    void testMultipleGroupsRegistration() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.openapi.groups[0].group=admin",
                        "ateng.boot.openapi.groups[0].display-name=管理端接口",
                        "ateng.boot.openapi.groups[0].paths-to-match[0]=/admin/**",
                        "ateng.boot.openapi.groups[0].packages-to-scan[0]=io.github.atengk.sample.admin",
                        "ateng.boot.openapi.groups[1].group=app",
                        "ateng.boot.openapi.groups[1].display-name=移动端接口",
                        "ateng.boot.openapi.groups[1].paths-to-match[0]=/app/**",
                        "ateng.boot.openapi.groups[1].paths-to-match[1]=/user/**"
                )
                .run(context -> {
                    Map<String, GroupedOpenApi> groupBeans = context.getBeansOfType(GroupedOpenApi.class);
                    // 此时不应包含 default 分组，而是精准包含配置的 admin 与 app 两个分组
                    assertThat(groupBeans).hasSize(2);
                    assertThat(groupBeans).containsKeys("groupedOpenApi_admin", "groupedOpenApi_app");

                    GroupedOpenApi adminGroup = groupBeans.get("groupedOpenApi_admin");
                    assertThat(adminGroup.getGroup()).isEqualTo("admin");
                    assertThat(adminGroup.getDisplayName()).isEqualTo("管理端接口");
                    assertThat(adminGroup.getPathsToMatch()).containsExactly("/admin/**");
                    assertThat(adminGroup.getPackagesToScan()).containsExactly("io.github.atengk.sample.admin");

                    GroupedOpenApi appGroup = groupBeans.get("groupedOpenApi_app");
                    assertThat(appGroup.getGroup()).isEqualTo("app");
                    assertThat(appGroup.getDisplayName()).isEqualTo("移动端接口");
                    assertThat(appGroup.getPathsToMatch()).containsExactly("/app/**", "/user/**");
                });
    }

    @Test
    @DisplayName("测试当 enabled=false 时不注册任何 GroupedOpenApi 分组")
    void testDisabledGroupsRegistration() {
        contextRunner
                .withPropertyValues("ateng.boot.openapi.enabled=false")
                .run(context -> {
                    Map<String, GroupedOpenApi> groupBeans = context.getBeansOfType(GroupedOpenApi.class);
                    assertThat(groupBeans).isEmpty();
                });
    }
}
