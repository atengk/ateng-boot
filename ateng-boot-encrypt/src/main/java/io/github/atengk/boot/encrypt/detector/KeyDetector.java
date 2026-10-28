package io.github.atengk.boot.encrypt.detector;

import io.github.atengk.boot.encrypt.exception.ConfigSecurityException;
import io.github.atengk.boot.encrypt.key.MultiKeyRegistry;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySources;

import java.util.Collections;
import java.util.Map;

/**
 * 解密主密钥分级探测与安全守卫
 * 优先级：启动命令行参数 > JVM 系统属性 > 操作系统环境变量 > 多密钥命名表
 * 防御铁律：严禁从正在解密的本地 YAML/Properties 配置文件本身读取主密钥
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class KeyDetector {

    public static final String PROPERTY_PASSWORD = "ateng.boot.encrypt.password";
    public static final String ENV_PASSWORD = "ATENG_BOOT_ENCRYPT_PASSWORD";
    public static final String KEYS_PREFIX = "ateng.boot.encrypt.keys.";

    private final MultiKeyRegistry registry;

    public KeyDetector(String defaultPassword, Map<String, String> keys) {
        this.registry = new MultiKeyRegistry(defaultPassword, keys);
    }

    public KeyDetector(MultiKeyRegistry registry) {
        this.registry = registry != null ? registry : new MultiKeyRegistry();
    }

    public KeyDetector() {
        this(null, Collections.emptyMap());
    }

    /**
     * 从当前环境执行级联探测并校验来源安全性
     *
     * @param environment Spring 运行环境
     * @return 探测初始化后的 KeyDetector 实例
     */
    public static KeyDetector detect(ConfigurableEnvironment environment) {
        KeyDetector detector = new KeyDetector();
        if (environment == null) {
            String defaultPassword = resolveDefaultPassword(null);
            detector.setDefaultPassword(defaultPassword);
            return detector;
        }

        // 1. 检查是否存在将密钥直接硬编码在本地配置文件的自欺欺人违规行为
        validateSelfEncryptionRisk(environment.getPropertySources());

        // 2. 级联获取默认主密钥
        String password = resolveDefaultPassword(environment);
        detector.setDefaultPassword(password);

        // 3. 收集命名多密钥 keys
        resolveNamedKeys(environment, detector);

        return detector;
    }

    /**
     * 按照优先级解析默认主密钥
     */
    public static String resolveDefaultPassword(ConfigurableEnvironment environment) {
        // 1. 优先尝试从系统属性 (-Dateng.boot.encrypt.password)
        String sysProp = System.getProperty(PROPERTY_PASSWORD);
        if (sysProp != null && !sysProp.trim().isEmpty()) {
            return sysProp.trim();
        }

        // 2. 尝试从操作系统环境变量 (ATENG_BOOT_ENCRYPT_PASSWORD)
        String envVar = System.getenv(ENV_PASSWORD);
        if (envVar != null && !envVar.trim().isEmpty()) {
            return envVar.trim();
        }

        // 3. 尝试从 Spring Environment 的系统属性与环境变量源获取
        if (environment != null) {
            String prop = environment.getProperty(PROPERTY_PASSWORD);
            if (prop != null && !prop.trim().isEmpty()) {
                return prop.trim();
            }
        }
        return null;
    }

    /**
     * 安全校验：防自欺欺人自加密陷阱
     */
    private static void validateSelfEncryptionRisk(PropertySources propertySources) {
        if (propertySources == null) {
            return;
        }

        // 检查非外部注入（如本地文件配置源）中是否直接定义了 password
        for (PropertySource<?> source : propertySources) {
            String name = source.getName();
            // 排除命令行、系统属性与系统环境变量
            if (isExternalTrustedSource(name)) {
                continue;
            }
            if (source.containsProperty(PROPERTY_PASSWORD)) {
                Object val = source.getProperty(PROPERTY_PASSWORD);
                if (val != null && !val.toString().trim().isEmpty()) {
                    throw new ConfigSecurityException(String.format(
                            "[ateng-boot-encrypt] 安全阻断：检测到在本地配置源 '%s' 中直接声明了主密钥 '%s'！" +
                                    "主密钥与密文共存于同一配置文件将导致加密机制形同虚设。" +
                                    "请立即从配置文件中移除该密钥，并统一改为通过环境变量 (%s)、JVM 启动参数 (-D%s) 或容器 Secret 注入！",
                            name, PROPERTY_PASSWORD, ENV_PASSWORD, PROPERTY_PASSWORD));
                }
            }
        }
    }

    private static boolean isExternalTrustedSource(String sourceName) {
        if (sourceName == null) {
            return false;
        }
        return sourceName.contains("commandLineArgs")
                || sourceName.contains("systemProperties")
                || sourceName.contains("systemEnvironment")
                || sourceName.contains("SimpleCommandLinePropertySource")
                || sourceName.contains("Inlined Test Properties")
                || sourceName.contains("test")
                || sourceName.equals("configurationProperties");
    }

    private static void resolveNamedKeys(ConfigurableEnvironment environment, KeyDetector detector) {
        if (environment == null) {
            return;
        }
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerableSource) {
                for (String propName : enumerableSource.getPropertyNames()) {
                    if (propName.startsWith(KEYS_PREFIX)) {
                        String keyId = propName.substring(KEYS_PREFIX.length()).trim();
                        Object val = source.getProperty(propName);
                        if (val != null && !keyId.isEmpty()) {
                            detector.registerKey(keyId, val.toString().trim());
                        }
                    }
                }
            }
        }
    }

    /**
     * 获取指定 keyId 对应的解密密钥
     *
     * @param keyId 密钥别名（null 或 "default" 对应默认全局密钥）
     * @return 密钥字符串；未找到时回退到默认全局密钥
     */
    public String getPassword(String keyId) {
        return registry.getKeyOrDefault(keyId);
    }

    public void registerKey(String keyId, String password) {
        registry.registerKey(keyId, password);
    }

    public void setDefaultPassword(String defaultPassword) {
        registry.setDefaultKey(defaultPassword);
    }

    /**
     * 获取底层的多密钥命名注册表
     */
    public MultiKeyRegistry getRegistry() {
        return registry;
    }
}
