package io.github.atengk.boot.ai.registrar;

import io.github.atengk.boot.ai.annotation.AiAgent;
import io.github.atengk.boot.ai.annotation.AiAgentScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * 声明式大模型智能体（@AiAgent）接口动态注册器
 * 支持依据 AutoConfigurationPackages 自动探测主程序所在包路径，或基于 @AiAgentScan 显式指定扫描包。
 * 将扫描到的候选接口封装为 AiAgentFactoryBean 动态代理注入 Spring 容器。
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class AiAgentRegistrar implements ImportBeanDefinitionRegistrar, ResourceLoaderAware, EnvironmentAware, BeanFactoryAware {

    private static final Logger log = LoggerFactory.getLogger(AiAgentRegistrar.class);

    private ResourceLoader resourceLoader;
    private Environment environment;
    private BeanFactory beanFactory;

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        Set<String> basePackages = getBasePackages(importingClassMetadata, registry);
        if (basePackages.isEmpty()) {
            log.debug("未发现待扫描的智能体基础包，跳过 @AiAgent 接口注册");
            return;
        }

        // 构造专职接口扫描器
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false, environment) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                AnnotationMetadata metadata = beanDefinition.getMetadata();
                return metadata.isInterface() && !metadata.isAnnotation();
            }
        };

        if (resourceLoader != null) {
            scanner.setResourceLoader(resourceLoader);
        }
        scanner.addIncludeFilter(new AnnotationTypeFilter(AiAgent.class));

        for (String basePackage : basePackages) {
            if (!StringUtils.hasText(basePackage)) {
                continue;
            }
            Set<BeanDefinition> candidates = scanner.findCandidateComponents(basePackage.trim());
            for (BeanDefinition candidate : candidates) {
                if (candidate instanceof AnnotatedBeanDefinition annotatedBeanDefinition) {
                    registerAgentBeanDefinition(annotatedBeanDefinition, registry);
                }
            }
        }
    }

    private void registerAgentBeanDefinition(AnnotatedBeanDefinition beanDefinition, BeanDefinitionRegistry registry) {
        String interfaceClassName = beanDefinition.getBeanClassName();
        if (!StringUtils.hasText(interfaceClassName)) {
            return;
        }

        AnnotationMetadata metadata = beanDefinition.getMetadata();
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(
                metadata.getAnnotationAttributes(AiAgent.class.getName())
        );

        if (attributes == null) {
            return;
        }

        String model = attributes.getString("model");
        if (!StringUtils.hasText(model)) {
            model = attributes.getString("value");
        }
        Class<?>[] tools = attributes.getClassArray("tools");
        String contentRetriever = attributes.getString("contentRetriever");
        String chatMemory = attributes.getString("chatMemory");

        String beanName = generateBeanName(interfaceClassName);
        if (registry.containsBeanDefinition(beanName)) {
            log.debug("智能体 Bean [{}] 已存在，跳过重复注册", beanName);
            return;
        }

        BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(AiAgentFactoryBean.class);
        builder.addPropertyValue("agentInterface", interfaceClassName);
        builder.addPropertyValue("model", model);
        builder.addPropertyValue("tools", tools);
        builder.addPropertyValue("contentRetriever", contentRetriever);
        builder.addPropertyValue("chatMemory", chatMemory);
        builder.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE);

        registry.registerBeanDefinition(beanName, builder.getBeanDefinition());
        log.info("动态注册 @AiAgent 智能体 Bean: [{}] -> {}", beanName, interfaceClassName);
    }

    /**
     * 生成 Bean 名称：接口简单名称首字母小写（支持顶级接口及内部接口）
     */
    private String generateBeanName(String interfaceClassName) {
        String shortName = ClassUtils.getShortName(interfaceClassName);
        int lastDot = shortName.lastIndexOf('.');
        if (lastDot != -1) {
            shortName = shortName.substring(lastDot + 1);
        }
        int lastDollar = shortName.lastIndexOf('$');
        if (lastDollar != -1) {
            shortName = shortName.substring(lastDollar + 1);
        }
        return Character.toLowerCase(shortName.charAt(0)) + shortName.substring(1);
    }

    /**
     * 解析基础包名：优先读取 @AiAgentScan 配置，缺省回退至 AutoConfigurationPackages 自动探测
     */
    private Set<String> getBasePackages(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        Set<String> basePackages = new LinkedHashSet<>();

        // 1. 尝试从 @AiAgentScan 读取显式配置
        if (importingClassMetadata != null && importingClassMetadata.hasAnnotation(AiAgentScan.class.getName())) {
            AnnotationAttributes attributes = AnnotationAttributes.fromMap(
                    importingClassMetadata.getAnnotationAttributes(AiAgentScan.class.getName())
            );
            if (attributes != null) {
                String[] pkgs = attributes.getStringArray("basePackages");
                if (pkgs.length == 0) {
                    pkgs = attributes.getStringArray("value");
                }
                basePackages.addAll(Arrays.asList(pkgs));

                Class<?>[] classes = attributes.getClassArray("basePackageClasses");
                for (Class<?> clazz : classes) {
                    basePackages.add(ClassUtils.getPackageName(clazz));
                }
            }
        }

        // 2. 若未配置显式扫描包，自动探测主程序所在包
        if (basePackages.isEmpty()) {
            if (beanFactory != null && AutoConfigurationPackages.has(beanFactory)) {
                basePackages.addAll(AutoConfigurationPackages.get(beanFactory));
            } else if (registry instanceof BeanFactory bf && AutoConfigurationPackages.has(bf)) {
                basePackages.addAll(AutoConfigurationPackages.get(bf));
            } else if (importingClassMetadata != null) {
                basePackages.add(ClassUtils.getPackageName(importingClassMetadata.getClassName()));
            }
        }

        return basePackages;
    }
}
