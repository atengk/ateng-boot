package io.github.atengk.boot.encrypt.env;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.detector.KeyDetector;
import io.github.atengk.boot.encrypt.guard.EnforceEncryptionPolicy;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import io.github.atengk.boot.encrypt.wrapper.EncryptPropertySourceWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

import java.util.ArrayList;
import java.util.List;

/**
 * 加密环境全量后置处理器（第二阶段保障 & 极早日志协同）
 * 排序介于 ConfigDataEnvironmentPostProcessor 与 LoggingApplicationListener 之间，
 * 负责在配置中心与本地配置加载就绪后，对所有属性源执行全量无差别动态解密装饰，
 * 同时协同早阶日志系统，确保 Logback <springProperty> 远程日志鉴权密码安全解密。
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class EncryptEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(EncryptEnvironmentPostProcessor.class);

    /**
     * 排序优先级：
     * 位于 ConfigDataEnvironmentPostProcessor (HIGHEST_PRECEDENCE + 10) 之后，
     * 位于 LoggingApplicationListener (HIGHEST_PRECEDENCE + 20) 之前。
     */
    public static final int ORDER = ConfigDataEnvironmentPostProcessor.ORDER + 5;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment == null) {
            return;
        }

        // 1. 级联探测主密钥与命名多密钥，并触发本地自加密风险刚性拦截
        KeyDetector keyDetector = KeyDetector.detect(environment);

        // 2. 动态读取配置的环境默认算法，未配置时保底为 AES_GCM
        String algoCode = environment.getProperty("ateng.boot.encrypt.algorithm");
        EncryptAlgorithm configuredAlgorithm = EncryptAlgorithm.fromCode(algoCode);
        EncryptAlgorithm defaultAlgorithm = configuredAlgorithm != null ? configuredAlgorithm : EncryptAlgorithm.AES_GCM;

        // 3. 扫描并无差别装饰所有尚未被包装的属性源
        MutablePropertySources propertySources = environment.getPropertySources();
        List<PropertySource<?>> toWrap = new ArrayList<>();

        for (PropertySource<?> source : propertySources) {
            if (shouldWrap(source)) {
                toWrap.add(source);
            }
        }

        for (PropertySource<?> source : toWrap) {
            EncryptPropertySourceWrapper wrapper = new EncryptPropertySourceWrapper(
                    source, keyDetector, defaultAlgorithm);
            propertySources.replace(source.getName(), wrapper);
            log.trace("[ateng-boot-encrypt] 属性源已装配解密代理: {}", source.getName());
        }

        // 4. 若开启生产明文强制门禁 (enforce-encrypted: true)，前置扫描并阻断未加密的裸明文敏感属性
        String enforceStr = environment.getProperty("ateng.boot.encrypt.enforce-encrypted");
        if (Boolean.parseBoolean(enforceStr)) {
            String pattern = environment.getProperty("ateng.boot.encrypt.sensitive-key-pattern",
                    EnforceEncryptionPolicy.DEFAULT_SENSITIVE_KEY_PATTERN);
            EnforceEncryptionPolicy.enforce(environment, pattern, new CiphertextParser());
        }
    }

    /**
     * Spring Boot 内部专用的 configurationProperties 虚拟适配源名称
     */
    public static final String CONFIGURATION_PROPERTIES_SOURCE_NAME = "configurationProperties";

    /**
     * 判断属性源是否应该被解密代理包装
     */
    private boolean shouldWrap(PropertySource<?> source) {
        if (source == null || source instanceof EncryptPropertySourceWrapper) {
            return false;
        }
        String name = source.getName();
        if (name == null) {
            return false;
        }
        // 排除 Spring Boot 内部专用的虚拟复合源与存根源
        if (CONFIGURATION_PROPERTIES_SOURCE_NAME.equals(name)) {
            return false;
        }
        return !name.startsWith("stub");
    }
}
