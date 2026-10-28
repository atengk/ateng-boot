package io.github.atengk.boot.encrypt.guard;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.config.EncryptProperties;
import io.github.atengk.boot.encrypt.detector.KeyDetector;
import io.github.atengk.boot.encrypt.exception.ConfigSecurityException;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import io.github.atengk.boot.encrypt.wrapper.EncryptPropertySourceWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 配置中心动态热更新防明文倒灌守卫 (Plaintext Ingress Guard)
 * 监听配置中心热更新事件（如 Spring Cloud EnvironmentChangeEvent / RefreshEvent），
 * 原子置换清空所有解密缓存；并在 enforce-encrypted=true 时对新推入的敏感属性执行强制加密门禁，
 * 若运维在配置中心误提交裸明文，拦截器直接抛出 ConfigSecurityException 阻断生效，杜绝生产被动态穿透
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class PlaintextIngressGuard implements ApplicationListener<ApplicationEvent>, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(PlaintextIngressGuard.class);

    private final EncryptProperties properties;
    private final CiphertextParser parser;
    private volatile ConfigurableEnvironment environment;

    public PlaintextIngressGuard(EncryptProperties properties, CiphertextParser parser) {
        this.properties = properties != null ? properties : new EncryptProperties();
        this.parser = parser != null ? parser : new CiphertextParser();
    }

    public PlaintextIngressGuard() {
        this(new EncryptProperties(), new CiphertextParser());
    }

    @Override
    public void setEnvironment(Environment environment) {
        if (environment instanceof ConfigurableEnvironment configurableEnv) {
            this.environment = configurableEnv;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onApplicationEvent(ApplicationEvent event) {
        if (event == null || environment == null) {
            return;
        }
        String eventClassName = event.getClass().getName();

        // 识别 Spring Cloud EnvironmentChangeEvent
        if (eventClassName.endsWith("EnvironmentChangeEvent")) {
            try {
                Method getKeysMethod = event.getClass().getMethod("getKeys");
                Set<String> keys = (Set<String>) getKeysMethod.invoke(event);
                onEnvironmentChange(keys, environment);
            } catch (Exception e) {
                if (e instanceof ConfigSecurityException cse) {
                    throw cse;
                }
                log.warn("[ateng-boot-encrypt] 处理 EnvironmentChangeEvent 异常: {}", e.getMessage());
                onEnvironmentChange(null, environment);
            }
        } else if (eventClassName.endsWith("RefreshEvent")) {
            onEnvironmentChange(null, environment);
        }
    }

    /**
     * 执行环境变更响应：原子置换清空解密缓存，并执行防明文倒灌合规检测
     *
     * @param changedKeys 变更的属性键名集合（可为 null，为 null 时全量扫描）
     * @param env         Spring 可配置环境
     */
    public void onEnvironmentChange(Set<String> changedKeys, ConfigurableEnvironment env) {
        if (env == null) {
            return;
        }

        // 1. 重新包装外部配置中心新推入的未包装属性源，并原子置换清空存量解密缓存
        KeyDetector keyDetector = KeyDetector.detect(env);
        String algoCode = env.getProperty("ateng.boot.encrypt.algorithm");
        EncryptAlgorithm configuredAlgorithm = EncryptAlgorithm.fromCode(algoCode);
        EncryptAlgorithm defaultAlgorithm = configuredAlgorithm != null ? configuredAlgorithm : EncryptAlgorithm.AES_GCM;

        int wrapperCount = 0;
        List<PropertySource<?>> toWrap = new ArrayList<>();
        for (PropertySource<?> source : env.getPropertySources()) {
            if (source instanceof EncryptPropertySourceWrapper wrapper) {
                wrapper.clearCache();
                wrapperCount++;
            } else if (source != null && !EnforceEncryptionPolicy.shouldSkipPropertySource(source)) {
                toWrap.add(source);
            }
        }
        for (PropertySource<?> source : toWrap) {
            EncryptPropertySourceWrapper wrapper = new EncryptPropertySourceWrapper(
                    source, parser, keyDetector, null, defaultAlgorithm, true);
            env.getPropertySources().replace(source.getName(), wrapper);
            wrapperCount++;
        }
        if (!toWrap.isEmpty()) {
            ConfigurationPropertySources.attach(env);
        }
        log.info("[ateng-boot-encrypt] 已原子置换与更新 {} 个属性源解密缓存", wrapperCount);

        // 2. 若开启生产强制加密 (enforce-encrypted: true)，防范裸明文倒灌
        if (properties.isEnforceEncrypted()) {
            String patternStr = properties.getSensitiveKeyPattern();
            if (patternStr == null || patternStr.trim().isEmpty()) {
                patternStr = EnforceEncryptionPolicy.DEFAULT_SENSITIVE_KEY_PATTERN;
            }
            Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);

            if (changedKeys != null && !changedKeys.isEmpty()) {
                for (String key : changedKeys) {
                    if (pattern.matcher(key).matches()) {
                        validateKey(key, env);
                    }
                }
            } else {
                EnforceEncryptionPolicy.enforce(env, patternStr, parser);
            }
        }
    }

    private void validateKey(String key, ConfigurableEnvironment env) {
        if (EnforceEncryptionPolicy.isIgnoredKey(key)) {
            return;
        }
        for (PropertySource<?> source : env.getPropertySources()) {
            if (EnforceEncryptionPolicy.shouldSkipPropertySource(source)) {
                continue;
            }
            if (source.containsProperty(key)) {
                Object raw = (source instanceof EncryptPropertySourceWrapper wrapper)
                        ? wrapper.getDelegate().getProperty(key)
                        : source.getProperty(key);

                if (raw instanceof String strVal && !strVal.trim().isEmpty()) {
                    if (!parser.hasEncrypted(strVal)) {
                        throw new ConfigSecurityException(String.format(
                                "配置中心热更新防倒灌拦截 (enforce-encrypted=true)！检测到敏感配置字段被动态推送为裸明文: '%s'，已拒绝更新",
                                key
                        ));
                    }
                }
                break;
            }
        }
    }
}
