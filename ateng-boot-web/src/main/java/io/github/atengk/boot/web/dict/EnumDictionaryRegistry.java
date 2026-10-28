package io.github.atengk.boot.web.dict;

import io.github.atengk.boot.core.annotation.EnumAlias;
import io.github.atengk.boot.core.enums.IBaseEnum;
import io.github.atengk.boot.core.model.OptionItem;
import io.github.atengk.boot.core.util.EnumUtil;
import io.github.atengk.boot.core.util.StringUtil;
import io.github.atengk.boot.web.enums.EnumNamingStrategy;
import io.github.atengk.boot.web.properties.EnumDictionaryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.util.ClassUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 枚举字典启动期扫描注册中心
 * 负责扫描指定包路径下实现 IBaseEnum 的枚举，执行别名冲突校验并预编译构建只读不可变选项缓存
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class EnumDictionaryRegistry implements InitializingBean, BeanClassLoaderAware, ResourceLoaderAware {

    private static final Logger log = LoggerFactory.getLogger(EnumDictionaryRegistry.class);

    private final EnumDictionaryProperties properties;
    private ClassLoader classLoader;
    private ResourceLoader resourceLoader;

    private Map<String, List<OptionItem<Object>>> dictMap = Collections.emptyMap();
    private Map<String, Class<?>> aliasToClassMap = Collections.emptyMap();

    public EnumDictionaryRegistry(EnumDictionaryProperties properties) {
        this.properties = properties != null ? properties : new EnumDictionaryProperties();
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void afterPropertiesSet() {
        scanAndRegister();
    }

    /**
     * 执行包路径扫描与注册
     */
    public synchronized void scanAndRegister() {
        List<String> scanPackages = properties.getScanPackages();
        if (scanPackages == null || scanPackages.isEmpty()) {
            log.info("ateng-boot: 未配置枚举扫描包路径 [ateng.boot.web.enum.scan-packages]，枚举字典注册表保持为空");
            this.dictMap = Collections.emptyMap();
            this.aliasToClassMap = Collections.emptyMap();
            return;
        }

        ClassLoader targetClassLoader = this.classLoader != null ? this.classLoader : ClassUtils.getDefaultClassLoader();
        ClassPathScanningCandidateComponentProvider scanner = createScanner();

        Map<String, Class<?>> tempAliasMap = new LinkedHashMap<>();
        Map<String, List<OptionItem<Object>>> tempDictMap = new LinkedHashMap<>();
        EnumNamingStrategy strategy = properties.getNamingStrategy() != null
                ? properties.getNamingStrategy()
                : EnumNamingStrategy.LOWER_CAMEL_CASE;

        for (String scanPackage : scanPackages) {
            if (!StringUtil.hasText(scanPackage)) {
                continue;
            }
            String trimmedPackage = scanPackage.trim();
            for (var candidate : scanner.findCandidateComponents(trimmedPackage)) {
                if (candidate instanceof AnnotatedBeanDefinition annotatedBeanDefinition) {
                    AnnotationMetadata metadata = annotatedBeanDefinition.getMetadata();
                    try {
                        Class<?> clazz = ClassUtils.forName(metadata.getClassName(), targetClassLoader);
                        registerEnum(clazz, strategy, tempAliasMap, tempDictMap);
                    } catch (ClassNotFoundException | LinkageError e) {
                        log.error("ateng-boot: 加载待注册枚举类失败: {}", metadata.getClassName(), e);
                    }
                }
            }
        }

        this.aliasToClassMap = Collections.unmodifiableMap(tempAliasMap);
        this.dictMap = Collections.unmodifiableMap(tempDictMap);
        log.info("ateng-boot: 成功完成枚举字典扫描与预编译，共注册 {} 个枚举字典: {}", dictMap.size(), dictMap.keySet());
    }

    /**
     * 手动注册单个枚举类（支持编程动态扩展与单元测试）
     *
     * @param clazz 待注册的枚举类
     */
    public synchronized void register(Class<?> clazz) {
        EnumNamingStrategy strategy = properties.getNamingStrategy() != null
                ? properties.getNamingStrategy()
                : EnumNamingStrategy.LOWER_CAMEL_CASE;
        Map<String, Class<?>> tempAliasMap = new LinkedHashMap<>(this.aliasToClassMap);
        Map<String, List<OptionItem<Object>>> tempDictMap = new LinkedHashMap<>(this.dictMap);
        registerEnum(clazz, strategy, tempAliasMap, tempDictMap);
        this.aliasToClassMap = Collections.unmodifiableMap(tempAliasMap);
        this.dictMap = Collections.unmodifiableMap(tempDictMap);
    }

    private void registerEnum(Class<?> clazz, EnumNamingStrategy strategy,
                              Map<String, Class<?>> aliasMap, Map<String, List<OptionItem<Object>>> dict) {
        if (!clazz.isEnum() || !IBaseEnum.class.isAssignableFrom(clazz)) {
            return;
        }

        String alias = resolveAlias(clazz, strategy);

        // 冲突防卫：启动期 Fail-Fast
        Class<?> existing = aliasMap.get(alias);
        if (existing != null && !existing.equals(clazz)) {
            throw new IllegalStateException(String.format(
                    "ateng-boot: 检测到枚举字典别名碰撞冲突！别名 [%s] 同时被 [%s] 与 [%s] 使用，请通过 @EnumAlias 显式指定唯一别名以解决冲突！",
                    alias, existing.getName(), clazz.getName()
            ));
        }

        aliasMap.put(alias, clazz);
        List<OptionItem<Object>> options = EnumUtil.toOptionsUntyped(clazz);
        dict.put(alias, options);
    }

    private String resolveAlias(Class<?> clazz, EnumNamingStrategy strategy) {
        EnumAlias aliasAnn = clazz.getAnnotation(EnumAlias.class);
        if (aliasAnn != null && StringUtil.hasText(aliasAnn.value())) {
            return aliasAnn.value().trim();
        }
        return strategy.resolve(clazz);
    }

    private ClassPathScanningCandidateComponentProvider createScanner() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                AnnotationMetadata metadata = beanDefinition.getMetadata();
                return metadata.isIndependent();
            }
        };
        if (this.resourceLoader != null) {
            scanner.setResourceLoader(this.resourceLoader);
        }
        scanner.addIncludeFilter(new AssignableTypeFilter(IBaseEnum.class));
        return scanner;
    }

    /**
     * 根据别名获取单个枚举字典选项列表
     *
     * @param name 枚举别名
     * @return 选项列表，未找到返回 null
     */
    public List<OptionItem<Object>> getOptions(String name) {
        if (name == null) {
            return null;
        }
        return dictMap.get(name);
    }

    /**
     * 批量获取指定别名集合的枚举字典映射
     *
     * @param names 枚举别名列表
     * @return 匹配的字典映射（过滤未找到的名称）
     */
    public Map<String, List<OptionItem<Object>>> getBatchOptions(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<OptionItem<Object>>> result = new LinkedHashMap<>(names.size());
        for (String name : names) {
            if (name != null) {
                List<OptionItem<Object>> options = dictMap.get(name);
                if (options != null) {
                    result.put(name, options);
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 获取全量已开放的枚举字典映射
     *
     * @return 全量不可变字典映射
     */
    public Map<String, List<OptionItem<Object>>> getAllOptions() {
        return dictMap;
    }

    /**
     * 判断是否包含指定别名的枚举字典
     *
     * @param name 枚举别名
     * @return 是否包含
     */
    public boolean contains(String name) {
        return name != null && dictMap.containsKey(name);
    }

    /**
     * 根据别名获取对应枚举 Class
     *
     * @param name 枚举别名
     * @return 枚举 Class，未找到返回 null
     */
    public Class<?> getEnumClass(String name) {
        return name != null ? aliasToClassMap.get(name) : null;
    }

    /**
     * 获取所有已注册的字典别名集合
     *
     * @return 别名 Set
     */
    public Set<String> getAliases() {
        return dictMap.keySet();
    }
}
