package io.github.atengk.boot.http.registrar;

import io.github.atengk.boot.http.annotation.EnableHttpInterfaces;
import io.github.atengk.boot.http.annotation.HttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 声明式 HTTP 客户端接口轻量包扫描注册器
 * 扫描指定包路径下标注 @HttpClient 的顶级接口并动态注入 HttpInterfaceFactoryBean
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public class HttpInterfaceRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware, ResourceLoaderAware, BeanClassLoaderAware {

    private Environment environment;
    private ResourceLoader resourceLoader;
    private ClassLoader classLoader;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(
                importingClassMetadata.getAnnotationAttributes(EnableHttpInterfaces.class.getName())
        );
        if (attributes == null) {
            return;
        }

        // 1. 收集待注册的候选接口集合
        Set<Class<?>> candidateInterfaces = new LinkedHashSet<>();

        // 1.1 处理显式声明的 clients
        Class<?>[] clientClasses = attributes.getClassArray("clients");
        for (Class<?> clientClass : clientClasses) {
            if (clientClass.isInterface() && !clientClass.isAnnotation()) {
                candidateInterfaces.add(clientClass);
            } else {
                log.warn("Class '{}' specified in @EnableHttpInterfaces(clients) is not an interface or is an annotation, skipping", clientClass.getName());
            }
        }

        // 1.2 收集待扫描的包路径并执行扫描
        Set<String> basePackages = getBasePackages(importingClassMetadata, attributes);
        if (!basePackages.isEmpty()) {
            candidateInterfaces.addAll(scanCandidateInterfaces(basePackages));
        }

        // 2. 依次解析候选接口并注册 HttpInterfaceFactoryBean
        for (Class<?> candidateInterface : candidateInterfaces) {
            registerHttpInterface(registry, candidateInterface);
        }
    }

    /**
     * 扫描基础包路径下的候选声明式接口
     *
     * @param basePackages 基础扫描包路径集合
     * @return 扫描到的候选接口类型集合
     */
    private Set<Class<?>> scanCandidateInterfaces(Set<String> basePackages) {
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        ClassPathScanningCandidateComponentProvider scanner = getScanner();

        for (String basePackage : basePackages) {
            if (!StringUtils.hasText(basePackage)) {
                continue;
            }
            Set<BeanDefinition> candidateComponents = scanner.findCandidateComponents(basePackage);
            for (BeanDefinition candidateComponent : candidateComponents) {
                if (candidateComponent instanceof AnnotatedBeanDefinition annotatedBeanDefinition) {
                    AnnotationMetadata metadata = annotatedBeanDefinition.getMetadata();
                    if (metadata.isInterface() && !metadata.isAnnotation()) {
                        try {
                            Class<?> targetClass = ClassUtils.forName(metadata.getClassName(), this.classLoader);
                            interfaces.add(targetClass);
                        } catch (ClassNotFoundException e) {
                            log.error("Failed to load scanned HTTP interface class: {}", metadata.getClassName(), e);
                        }
                    }
                }
            }
        }
        return interfaces;
    }

    /**
     * 构建配置专属于 HTTP 声明式接口的类路径扫描器
     *
     * @return ClassPathScanningCandidateComponentProvider 实例
     */
    private ClassPathScanningCandidateComponentProvider getScanner() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false, this.environment) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                AnnotationMetadata metadata = beanDefinition.getMetadata();
                return metadata.isInterface() && !metadata.isAnnotation() && metadata.isIndependent();
            }
        };
        if (this.resourceLoader != null) {
            scanner.setResourceLoader(this.resourceLoader);
        }
        // 支持 @HttpClient 注解及其元注解派生扫描
        scanner.addIncludeFilter(new AnnotationTypeFilter(HttpClient.class, true, true));
        return scanner;
    }

    /**
     * 注册单个声明式 HTTP 客户端接口的 BeanDefinition
     *
     * @param registry           Bean 定义注册表
     * @param candidateInterface 候选接口类型
     */
    private void registerHttpInterface(BeanDefinitionRegistry registry, Class<?> candidateInterface) {
        if (candidateInterface.isAnnotation()) {
            return;
        }

        MergedAnnotation<HttpClient> annotation = MergedAnnotations.from(
                candidateInterface,
                MergedAnnotations.SearchStrategy.TYPE_HIERARCHY
        ).get(HttpClient.class);

        if (!annotation.isPresent()) {
            log.warn("Interface '{}' is not annotated with @HttpClient, skipping registration", candidateInterface.getName());
            return;
        }

        String name = annotation.getString("name");
        if (!StringUtils.hasText(name)) {
            name = annotation.getString("value");
        }
        Assert.hasText(name, () -> "@HttpClient 'name' or 'value' must not be blank on interface " + candidateInterface.getName());
        String url = annotation.getString("url");

        String beanClassName = candidateInterface.getName();
        String beanName = generateBeanName(registry, candidateInterface);

        if (registry.containsBeanDefinition(beanName)) {
            log.debug("Bean definition for HTTP interface '{}' already exists with name '{}', skipping duplicate registration",
                    beanClassName, beanName);
            return;
        }

        BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(HttpInterfaceFactoryBean.class);
        builder.addPropertyValue("interfaceType", candidateInterface);
        builder.addPropertyValue("clientName", name);
        builder.addPropertyValue("url", url);
        builder.setLazyInit(false);

        AbstractBeanDefinition beanDefinition = builder.getBeanDefinition();
        beanDefinition.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, candidateInterface);
        beanDefinition.setPrimary(false);

        BeanDefinitionHolder holder = new BeanDefinitionHolder(beanDefinition, beanName);
        BeanDefinitionReaderUtils.registerBeanDefinition(holder, registry);
        log.info("Registered declarative HTTP interface client '{}' for [{}] with bean name '{}'",
                name, beanClassName, beanName);
    }

    /**
     * 生成 Spring 容器内唯一的 Bean 名称
     * 优先使用首字母小写的接口简短名称；若冲突则回退使用全限定类名
     *
     * @param registry           Bean 定义注册表
     * @param candidateInterface 候选接口类型
     * @return 唯一的 Bean 名称
     */
    private String generateBeanName(BeanDefinitionRegistry registry, Class<?> candidateInterface) {
        String shortName = ClassUtils.getShortNameAsProperty(candidateInterface);
        if (!registry.containsBeanDefinition(shortName)) {
            return shortName;
        }
        return candidateInterface.getName();
    }

    /**
     * 提取基础扫描包路径集合
     *
     * @param importingClassMetadata 导入配置类元数据
     * @param attributes             注解属性映射
     * @return 待扫描的基础包路径集合
     */
    private Set<String> getBasePackages(AnnotationMetadata importingClassMetadata, AnnotationAttributes attributes) {
        Set<String> basePackages = new LinkedHashSet<>();
        for (String pkg : attributes.getStringArray("value")) {
            if (StringUtils.hasText(pkg)) {
                basePackages.add(pkg);
            }
        }
        for (String pkg : attributes.getStringArray("basePackages")) {
            if (StringUtils.hasText(pkg)) {
                basePackages.add(pkg);
            }
        }
        for (Class<?> clazz : attributes.getClassArray("basePackageClasses")) {
            basePackages.add(ClassUtils.getPackageName(clazz));
        }
        // 当未显式指定扫描包且未显式指定 clients 时，默认使用导入类所在包路径
        if (basePackages.isEmpty() && attributes.getClassArray("clients").length == 0) {
            String defaultPackage = ClassUtils.getPackageName(importingClassMetadata.getClassName());
            basePackages.add(defaultPackage);
        }
        return basePackages;
    }

}
