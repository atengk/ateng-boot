package io.github.atengk.boot.encrypt.env;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.detector.KeyDetector;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import io.github.atengk.boot.encrypt.wrapper.EncryptPropertySourceWrapper;
import org.springframework.boot.context.config.ConfigDataEnvironmentUpdateListener;
import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataResource;
import org.springframework.boot.context.config.Profiles;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ConfigData 极早环境更新监听器（第一阶段瞬态守卫）
 * 在本地 YAML/Properties 加载后、外部配置导入前即时拦截新加入的属性源并完成动态包装
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class EncryptConfigDataEnvironmentUpdateListener implements ConfigDataEnvironmentUpdateListener {

    private final ConfigurableEnvironment environment;
    private final KeyDetector keyDetector;
    private final EncryptAlgorithm defaultAlgorithm;
    private final Set<String> processedPropertySources = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public EncryptConfigDataEnvironmentUpdateListener(ConfigurableEnvironment environment,
                                                     KeyDetector keyDetector,
                                                     EncryptAlgorithm defaultAlgorithm) {
        this.environment = environment;
        if (keyDetector != null) {
            this.keyDetector = keyDetector;
        } else if (EncryptEnvironmentSupport.resolveBooleanProperty(
                environment, EncryptEnvironmentSupport.PROPERTY_ENABLED, EncryptEnvironmentSupport.ENV_ENABLED, true)) {
            this.keyDetector = KeyDetector.detect(environment);
        } else {
            this.keyDetector = new KeyDetector();
        }
        this.defaultAlgorithm = defaultAlgorithm != null ? defaultAlgorithm : EncryptAlgorithm.AES_GCM;
    }

    public EncryptConfigDataEnvironmentUpdateListener(ConfigurableEnvironment environment, KeyDetector keyDetector) {
        this(environment, keyDetector, EncryptAlgorithm.AES_GCM);
    }

    public EncryptConfigDataEnvironmentUpdateListener(ConfigurableEnvironment environment) {
        this(environment, null, EncryptAlgorithm.AES_GCM);
    }

    public EncryptConfigDataEnvironmentUpdateListener() {
        this(null, null, EncryptAlgorithm.AES_GCM);
    }

    @Override
    public void onPropertySourceAdded(PropertySource<?> propertySource, ConfigDataLocation location, ConfigDataResource resource) {
        if (propertySource == null || propertySource instanceof EncryptPropertySourceWrapper) {
            return;
        }
        if (!isEncryptEnabled(propertySource)) {
            return;
        }

        String sourceName = propertySource.getName();
        if (processedPropertySources.contains(sourceName)) {
            return;
        }

        // 1. 如果绑定了环境，从环境的 MutablePropertySources 中即时置换
        if (environment != null) {
            MutablePropertySources sources = environment.getPropertySources();
            if (sources.contains(sourceName)) {
                PropertySource<?> existing = sources.get(sourceName);
                if (existing != null && !(existing instanceof EncryptPropertySourceWrapper)) {
                    EncryptPropertySourceWrapper wrapper = createWrapper(existing);
                    sources.replace(sourceName, wrapper);
                    processedPropertySources.add(sourceName);
                    return;
                }
            }
        }

        // 2. 标记已处理
        processedPropertySources.add(sourceName);
    }

    @Override
    public void onSetProfiles(Profiles profiles) {
        // 当 profiles 确定时，无需特殊处理，保留扩展点
    }

    /**
     * 将给定的属性源装饰为解密包装器
     *
     * @param propertySource 原始属性源
     * @return 装饰后的属性源（禁用开关生效时原样返回）
     */
    public PropertySource<?> wrapPropertySource(PropertySource<?> propertySource) {
        if (propertySource == null || propertySource instanceof EncryptPropertySourceWrapper || !isEncryptEnabled(propertySource)) {
            return propertySource;
        }
        return createWrapper(propertySource);
    }

    private boolean isEncryptEnabled(PropertySource<?> propertySource) {
        return EncryptEnvironmentSupport.resolveBooleanProperty(
                environment, propertySource,
                EncryptEnvironmentSupport.PROPERTY_ENABLED, EncryptEnvironmentSupport.ENV_ENABLED, true);
    }

    private EncryptPropertySourceWrapper createWrapper(PropertySource<?> source) {
        EncryptAlgorithm effectiveAlgorithm = EncryptEnvironmentSupport.resolveDefaultAlgorithm(
                environment, source, this.defaultAlgorithm);
        boolean failOnError = EncryptEnvironmentSupport.resolveBooleanProperty(
                environment, source,
                EncryptEnvironmentSupport.PROPERTY_FAIL_ON_ERROR, EncryptEnvironmentSupport.ENV_FAIL_ON_ERROR, true);
        CiphertextParser parser = EncryptEnvironmentSupport.resolveCiphertextParser(environment, source);

        return new EncryptPropertySourceWrapper(source, parser, keyDetector, null, effectiveAlgorithm, failOnError);
    }

    public Set<String> getProcessedPropertySources() {
        return Collections.unmodifiableSet(processedPropertySources);
    }
}
