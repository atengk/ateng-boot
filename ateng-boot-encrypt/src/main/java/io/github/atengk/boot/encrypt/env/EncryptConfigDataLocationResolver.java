package io.github.atengk.boot.encrypt.env;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.detector.KeyDetector;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import io.github.atengk.boot.encrypt.wrapper.EncryptPropertySourceWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationResolver;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.boot.context.config.ConfigDataResource;
import org.springframework.boot.context.config.Profiles;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.Ordered;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ConfigData 瞬态位置解析穿透守卫（Pre-Resolver Hook）
 * 拥有最高优先级（Ordered.HIGHEST_PRECEDENCE），在所有配置中心解析器（如 Nacos、Apollo）解析外部 import 之前触发，
 * 动态穿透装饰当前已加载的本地配置源，使配置中心客户端在发起网络握手前获取解密后的真实凭据，彻底根除 403 认证死锁。
 * <p>
 * 契约说明：穿透探查机制针对 Spring Boot 3.x ConfigDataContributors 内部结构执行非破坏性轻量反射装饰；
 * 若因极端宿主安全策略限制导致反射未命中，本组件将自动防御性静默，由第二阶段 {@link EncryptEnvironmentPostProcessor} 执行全量保障。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class EncryptConfigDataLocationResolver implements ConfigDataLocationResolver<ConfigDataResource>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(EncryptConfigDataLocationResolver.class);
    private static final CiphertextParser PARSER = new CiphertextParser();

    private final Set<Integer> processedContextHashes = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public boolean isResolvable(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
        if (context != null) {
            penetrateAndDecorate(context);
        }
        // 作为穿透守卫，不承担具体的资源定位与加载，保底返回 false 以交由后续具体解析器处理
        return false;
    }

    @Override
    public List<ConfigDataResource> resolve(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
        return Collections.emptyList();
    }

    @Override
    public List<ConfigDataResource> resolveProfileSpecific(ConfigDataLocationResolverContext context,
                                                           ConfigDataLocation location,
                                                           Profiles profiles) {
        return Collections.emptyList();
    }

    /**
     * 穿透探查并动态装饰上下文中的属性源
     *
     * @param context 解析器上下文
     */
    public void penetrateAndDecorate(ConfigDataLocationResolverContext context) {
        int contextHash = System.identityHashCode(context);
        if (processedContextHashes.contains(contextHash)) {
            return;
        }

        try {
            // 1. 尝试从 context 反射获取 contributors (Spring Boot 3.3+ 真实字段为 "contributors"，Mock 为 "this$0")
            Class<?> ctxClass = context.getClass();
            Field outerField = findField(ctxClass, "contributors");
            if (outerField == null) {
                outerField = findField(ctxClass, "this$0");
            }
            if (outerField == null) {
                for (Field field : ctxClass.getDeclaredFields()) {
                    if (Iterable.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        outerField = field;
                        break;
                    }
                }
            }
            if (outerField == null) {
                return;
            }
            outerField.setAccessible(true);
            Object contributorsObj = outerField.get(context);
            if (!(contributorsObj instanceof Iterable<?> iterable)) {
                return;
            }

            KeyDetector keyDetector = KeyDetector.detect(null);

            // 动态读取可能由早阶已加载配置声明的密码以及自定义 prefix / suffix
            CiphertextParser parser = PARSER;
            if (context.getBinder() != null) {
                try {
                    String binderPass = context.getBinder().bind(KeyDetector.PROPERTY_PASSWORD, String.class).orElse(null);
                    if (binderPass != null && !binderPass.trim().isEmpty()) {
                        keyDetector.setDefaultPassword(binderPass.trim());
                    }
                    String prefix = context.getBinder().bind("ateng.boot.encrypt.prefix", String.class).orElse(null);
                    String suffix = context.getBinder().bind("ateng.boot.encrypt.suffix", String.class).orElse(null);
                    if (prefix != null || suffix != null) {
                        parser = new CiphertextParser(prefix, suffix);
                    }
                } catch (Exception ignored) {
                }
            }

            // 2. 遍历所有 contributor，寻找包含密文的未装饰 PropertySource
            for (Object item : iterable) {
                if (item == null) {
                    continue;
                }
                decorateContributorIfNecessary(item, keyDetector, parser);
            }

            processedContextHashes.add(contextHash);
        } catch (Exception e) {
            // 防御性静默，确保任何内部异常不阻断配置加载主流程
            log.trace("[ateng-boot-encrypt] 极早配置穿透探查完成或略过: {}", e.getMessage());
        }
    }

    private void decorateContributorIfNecessary(Object contributor, KeyDetector keyDetector, CiphertextParser parser) {
        try {
            Field propSourceField = findField(contributor.getClass(), "propertySource");
            if (propSourceField == null) {
                return;
            }
            propSourceField.setAccessible(true);
            Object propSourceObj = propSourceField.get(contributor);

            if (!(propSourceObj instanceof PropertySource<?> propertySource)) {
                return;
            }
            if (propertySource instanceof EncryptPropertySourceWrapper) {
                return;
            }

            // 检查该属性源是否含有密文
            if (!hasEncryptedValue(propertySource, parser)) {
                return;
            }

            // 执行装饰
            EncryptPropertySourceWrapper wrapper = new EncryptPropertySourceWrapper(
                    propertySource, parser, keyDetector, null, EncryptAlgorithm.AES_GCM, true);
            propSourceField.set(contributor, wrapper);

            // 同时刷新对应 contributor 内部绑定的 configurationPropertySource
            Field configPropSourceField = findField(contributor.getClass(), "configurationPropertySource");
            if (configPropSourceField != null) {
                configPropSourceField.setAccessible(true);
                Iterable<ConfigurationPropertySource> sources = ConfigurationPropertySources.from(wrapper);
                for (ConfigurationPropertySource cps : sources) {
                    configPropSourceField.set(contributor, cps);
                    break;
                }
            }
        } catch (Exception e) {
            log.trace("[ateng-boot-encrypt] 穿透装饰 contributor 异常: {}", e.getMessage());
        }
    }

    private boolean hasEncryptedValue(PropertySource<?> source, CiphertextParser parser) {
        if (source instanceof EnumerablePropertySource<?> enumerableSource) {
            CiphertextParser actualParser = parser != null ? parser : PARSER;
            for (String name : enumerableSource.getPropertyNames()) {
                Object val = enumerableSource.getProperty(name);
                if (val instanceof String strVal && actualParser.hasEncrypted(strVal)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();

    private Field findField(Class<?> clazz, String fieldName) {
        String cacheKey = clazz.getName() + "#" + fieldName;
        return FIELD_CACHE.computeIfAbsent(cacheKey, k -> {
            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                try {
                    Field f = current.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException e) {
                    current = current.getSuperclass();
                }
            }
            return null;
        });
    }
}
