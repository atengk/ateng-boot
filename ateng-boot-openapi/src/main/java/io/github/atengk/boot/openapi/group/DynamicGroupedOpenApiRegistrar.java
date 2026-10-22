package io.github.atengk.boot.openapi.group;

import io.github.atengk.boot.openapi.properties.OpenApiProperties;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 配置驱动的多业务分组动态注册器
 * <p>
 * 根据 {@code ateng.boot.openapi.groups} 配置列表动态向 Spring 容器注册 {@link GroupedOpenApi} Bean：
 * <ul>
 *     <li>若配置了多个业务分组，为每一个分组注册专职的 {@code GroupedOpenApi}；</li>
 *     <li>若未配置任何分组（且 openapi.enabled 为 true），兜底注册名为 {@code default} 的全量单分组。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class DynamicGroupedOpenApiRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        if (environment == null) {
            return;
        }

        // 绑定 OpenApiProperties 配置
        OpenApiProperties properties = Binder.get(environment)
                .bind("ateng.boot.openapi", OpenApiProperties.class)
                .orElseGet(OpenApiProperties::new);

        // 当 enabled=false 时物理切断，不注册任何 GroupedOpenApi
        if (!properties.isEnabled()) {
            return;
        }

        List<OpenApiProperties.Group> groups = properties.getGroups();
        if (groups != null && !groups.isEmpty()) {
            for (OpenApiProperties.Group group : groups) {
                if (StringUtils.hasText(group.getGroup())) {
                    String beanName = "groupedOpenApi_" + group.getGroup();
                    if (!registry.containsBeanDefinition(beanName)) {
                        BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(
                                GroupedOpenApi.class,
                                () -> buildGroupedOpenApi(group)
                        );
                        registry.registerBeanDefinition(beanName, builder.getBeanDefinition());
                    }
                }
            }
        } else {
            // 兜底注册默认全量分组
            String defaultBeanName = "defaultGroupedOpenApi";
            if (!registry.containsBeanDefinition(defaultBeanName)) {
                BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(
                        GroupedOpenApi.class,
                        () -> GroupedOpenApi.builder()
                                .group("default")
                                .displayName("全量接口文档")
                                .pathsToMatch("/**")
                                .build()
                );
                registry.registerBeanDefinition(defaultBeanName, builder.getBeanDefinition());
            }
        }
    }

    /**
     * 根据配置对象构造 GroupedOpenApi 实例
     *
     * @param group 分组配置
     * @return GroupedOpenApi 实例
     */
    public static GroupedOpenApi buildGroupedOpenApi(OpenApiProperties.Group group) {
        GroupedOpenApi.Builder builder = GroupedOpenApi.builder()
                .group(group.getGroup());

        if (StringUtils.hasText(group.getDisplayName())) {
            builder.displayName(group.getDisplayName());
        }

        List<String> paths = group.getPathsToMatch();
        if (paths != null && !paths.isEmpty()) {
            builder.pathsToMatch(paths.toArray(new String[0]));
        } else {
            builder.pathsToMatch("/**");
        }

        List<String> packages = group.getPackagesToScan();
        if (packages != null && !packages.isEmpty()) {
            builder.packagesToScan(packages.toArray(new String[0]));
        }

        return builder.build();
    }
}
