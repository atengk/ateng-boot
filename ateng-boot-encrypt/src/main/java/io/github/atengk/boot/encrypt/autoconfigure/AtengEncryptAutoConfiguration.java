package io.github.atengk.boot.encrypt.autoconfigure;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.config.EncryptProperties;
import io.github.atengk.boot.encrypt.decryptor.AesCbcConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.AesGcmConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.ConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.ConfigDecryptorFactory;
import io.github.atengk.boot.encrypt.decryptor.RsaConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.Sm2ConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.Sm4ConfigDecryptor;
import io.github.atengk.boot.encrypt.detector.KeyDetector;
import io.github.atengk.boot.encrypt.event.KeyRotatedEvent;
import io.github.atengk.boot.encrypt.guard.PlaintextIngressGuard;
import io.github.atengk.boot.encrypt.guard.SanitizingGuard;
import io.github.atengk.boot.encrypt.key.MultiKeyRegistry;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import io.github.atengk.boot.encrypt.template.EncryptTemplate;
import io.github.atengk.boot.encrypt.wrapper.EncryptPropertySourceWrapper;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.actuate.endpoint.SanitizingFunction;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * 配置加解密核心自动装配类
 *
 * @author Ateng
 * @since 2026-10-25
 */
@AutoConfiguration
@EnableConfigurationProperties(EncryptProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.encrypt", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengEncryptAutoConfiguration {

    private static final ReentrantLock CONTAINER_SYNC_LOCK = new ReentrantLock();
    private static final ThreadLocal<Boolean> CONTAINER_SYNCING = new ThreadLocal<>();

    private final ConfigurableEnvironment environment;

    public AtengEncryptAutoConfiguration(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    /**
     * 注册高优先级容器桥接后处理器，在普通单例 Bean 初始化前为已包装属性源挂载懒回填同步钩子
     *
     * @return 优先级最高的 BeanFactoryPostProcessor
     */
    @Bean
    @ConditionalOnMissingBean(name = "encryptContainerBridgePostProcessor")
    public static BeanFactoryPostProcessor encryptContainerBridgePostProcessor() {
        return new PriorityContainerBridgePostProcessor();
    }

    /**
     * 在容器所有非懒加载单例就绪后执行兜底同步，确保未触发早期 @Value 解析的属性源同样对齐容器期组件
     *
     * @param beanFactory 容器 Bean 工厂
     * @return SmartInitializingSingleton 回调
     */
    @Bean
    @ConditionalOnMissingBean(name = "encryptContainerBridgeInitializer")
    public SmartInitializingSingleton encryptContainerBridgeInitializer(ConfigurableListableBeanFactory beanFactory) {
        return () -> syncContainerRuntimeToEnvironment(environment, beanFactory);
    }

    private static final class PriorityContainerBridgePostProcessor implements BeanFactoryPostProcessor, PriorityOrdered {

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
            Environment env = beanFactory.getBeanProvider(Environment.class).getIfAvailable();
            if (!(env instanceof ConfigurableEnvironment configurableEnv)) {
                return;
            }
            BooleanSupplier syncHook = () -> syncContainerRuntimeToEnvironment(configurableEnv, beanFactory);
            for (PropertySource<?> source : configurableEnv.getPropertySources()) {
                if (source instanceof EncryptPropertySourceWrapper wrapper) {
                    wrapper.setContainerSyncHook(syncHook);
                }
            }
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }

    private static boolean syncContainerRuntimeToEnvironment(ConfigurableEnvironment env,
                                                             ConfigurableListableBeanFactory beanFactory) {
        if (env == null || beanFactory == null || Boolean.TRUE.equals(CONTAINER_SYNCING.get())) {
            return false;
        }
        CONTAINER_SYNCING.set(Boolean.TRUE);
        try {
            // 1. 锁外解析容器 Bean，杜绝持有全局锁期间递归访问 Environment 引发 AB-BA 死锁
            EncryptProperties properties = beanFactory.getBeanProvider(EncryptProperties.class).getIfAvailable();
            if (properties == null) {
                properties = new EncryptProperties();
            }
            if (!properties.isEnabled()) {
                markAllWrappersSynced(env);
                return true;
            }
            MultiKeyRegistry registry = beanFactory.getBeanProvider(MultiKeyRegistry.class).getIfAvailable();
            KeyDetector managedDetector = registry != null ? new KeyDetector(registry) : null;
            CiphertextParser parser = beanFactory.getBeanProvider(CiphertextParser.class).getIfAvailable();
            EncryptAlgorithm defaultAlgorithm = beanFactory.getBeanProvider(EncryptAlgorithm.class).getIfAvailable();
            if (defaultAlgorithm == null) {
                defaultAlgorithm = properties.getAlgorithm() != null ? properties.getAlgorithm() : EncryptAlgorithm.AES_GCM;
            }
            List<ConfigDecryptor> containerDecryptors = beanFactory.getBeanProvider(ConfigDecryptor.class)
                    .orderedStream()
                    .toList();
            List<ConfigDecryptor> resolvedDecryptors = ConfigDecryptorFactory.createDefaultDecryptors(
                    containerDecryptors, properties.getRetry());

            // 2. 仅在纯内存回填属性源引用阶段加锁串行更新并置位
            CONTAINER_SYNC_LOCK.lock();
            try {
                for (PropertySource<?> source : env.getPropertySources()) {
                    if (source instanceof EncryptPropertySourceWrapper wrapper) {
                        wrapper.updateRuntimeConfig(
                                parser, managedDetector, resolvedDecryptors, defaultAlgorithm, properties.isFailOnError());
                        wrapper.markContainerSynced();
                    }
                }
            } finally {
                if (CONTAINER_SYNC_LOCK.isHeldByCurrentThread()) {
                    CONTAINER_SYNC_LOCK.unlock();
                }
            }
            return true;
        } finally {
            CONTAINER_SYNCING.remove();
        }
    }

    private static void markAllWrappersSynced(ConfigurableEnvironment env) {
        CONTAINER_SYNC_LOCK.lock();
        try {
            for (PropertySource<?> source : env.getPropertySources()) {
                if (source instanceof EncryptPropertySourceWrapper wrapper) {
                    wrapper.markContainerSynced();
                }
            }
        } finally {
            if (CONTAINER_SYNC_LOCK.isHeldByCurrentThread()) {
                CONTAINER_SYNC_LOCK.unlock();
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public MultiKeyRegistry multiKeyRegistry(EncryptProperties properties) {
        if (environment != null) {
            for (PropertySource<?> source : environment.getPropertySources()) {
                if (source instanceof EncryptPropertySourceWrapper wrapper) {
                    MultiKeyRegistry registry = wrapper.getKeyRegistry();
                    if (registry != null) {
                        if (properties.getKeys() != null && !properties.getKeys().isEmpty()) {
                            properties.getKeys().forEach((keyId, secret) -> {
                                if (!registry.hasKey(keyId) || !Objects.equals(registry.getKey(keyId), secret)) {
                                    registry.registerKey(keyId, secret);
                                }
                            });
                        }
                        if (properties.getPassword() != null && !properties.getPassword().trim().isEmpty()) {
                            String trimmedDefault = properties.getPassword().trim();
                            if (!Objects.equals(registry.getDefaultKey(), trimmedDefault)) {
                                registry.setDefaultKey(trimmedDefault);
                            }
                        }
                        return registry;
                    }
                }
            }
        }
        return MultiKeyRegistry.fromProperties(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public EncryptAlgorithm defaultEncryptAlgorithm(EncryptProperties properties) {
        return properties.getAlgorithm() != null ? properties.getAlgorithm() : EncryptAlgorithm.AES_GCM;
    }

    @Bean
    @ConditionalOnMissingBean
    public CiphertextParser ciphertextParser(EncryptProperties properties) {
        return new CiphertextParser(properties.getPrefix(), properties.getSuffix(), properties.isCompatibleCipherPrefix());
    }

    @Bean
    @ConditionalOnMissingBean
    public AesGcmConfigDecryptor aesGcmConfigDecryptor() {
        return new AesGcmConfigDecryptor();
    }

    @Bean
    @ConditionalOnMissingBean
    public AesCbcConfigDecryptor aesCbcConfigDecryptor() {
        return new AesCbcConfigDecryptor();
    }

    @Bean
    @ConditionalOnMissingBean
    public Sm4ConfigDecryptor sm4ConfigDecryptor() {
        return new Sm4ConfigDecryptor();
    }

    @Bean
    @ConditionalOnMissingBean
    public RsaConfigDecryptor rsaConfigDecryptor() {
        return new RsaConfigDecryptor();
    }

    @Bean
    @ConditionalOnMissingBean
    public Sm2ConfigDecryptor sm2ConfigDecryptor() {
        return new Sm2ConfigDecryptor();
    }

    @Bean
    @ConditionalOnMissingBean
    public PlaintextIngressGuard plaintextIngressGuard(EncryptProperties properties,
                                                       CiphertextParser parser,
                                                       MultiKeyRegistry multiKeyRegistry,
                                                       ObjectProvider<ConfigDecryptor> decryptorsProvider) {
        return new PlaintextIngressGuard(properties, parser, multiKeyRegistry, resolveDecryptors(decryptorsProvider));
    }

    @Bean
    @ConditionalOnMissingBean
    public EncryptTemplate encryptTemplate(EncryptProperties properties,
                                           EncryptAlgorithm defaultEncryptAlgorithm,
                                           MultiKeyRegistry multiKeyRegistry,
                                           CiphertextParser ciphertextParser,
                                           ObjectProvider<ConfigDecryptor> decryptorsProvider) {
        return new EncryptTemplate(properties, defaultEncryptAlgorithm, multiKeyRegistry,
                ciphertextParser, resolveDecryptors(decryptorsProvider));
    }

    private List<ConfigDecryptor> resolveDecryptors(ObjectProvider<ConfigDecryptor> decryptorsProvider) {
        return decryptorsProvider != null
                ? decryptorsProvider.orderedStream().toList()
                : Collections.emptyList();
    }

    @EventListener
    public void onKeyRotated(KeyRotatedEvent event) {
        if (environment != null) {
            for (PropertySource<?> source : environment.getPropertySources()) {
                if (source instanceof EncryptPropertySourceWrapper wrapper) {
                    wrapper.clearCache();
                }
            }
        }
    }

    /**
     * Actuator 运行时端点脱敏守卫条件自动配置
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SanitizingFunction.class)
    public static class EncryptActuatorConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "encryptSanitizingGuard")
        public SanitizingGuard encryptSanitizingGuard(EncryptProperties properties, CiphertextParser parser) {
            return new SanitizingGuard(properties, parser);
        }
    }
}
