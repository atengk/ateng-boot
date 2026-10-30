package io.github.atengk.boot.core.env;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.ClassUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 零 XML 工业级日志引擎环境后处理器
 * <p>
 * 在 Spring Boot 容器启动早期接管并注入生产级日志基线：
 * 1. 默认向控制台注入带 MDC {@code traceId} 追踪标记与 ANSI 彩色高亮的日志格式；
 * 2. 具备 100% 平滑退让守卫：若检测到 Classpath 存在 {@code logback-spring.xml} / {@code logback.xml}
 *    或用户在配置中显式指定了 {@code logging.config} / {@code logging.pattern.console}，框架立即全量静默退出；
 * 3. 支持开箱即用的磁盘文件持久化落盘（{@code ateng.boot.core.logging.file.enabled: true}）：
 *    自动分流 {@code app-info.log} 与 {@code app-error.log}，支持按天切片、Gzip 压缩与历史自动清理；
 * 4. 提供 {@code ateng.boot.core.logging.enabled} 顶层大开关一键熔断退出。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
public class LoggingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /**
     * 日志默认属性源名称
     */
    public static final String PROPERTY_SOURCE_NAME = "atengLoggingDefaults";

    /**
     * 默认控制台彩色日志输出格式（包含 MDC traceId 标记与高亮）
     */
    public static final String DEFAULT_CONSOLE_PATTERN =
            "%d{yyyy-MM-dd HH:mm:ss.SSS} %highlight(%-5level) %magenta([%15.15thread]) %green([%replace(%X{traceId}){'^$', 'NO-TRACE'}]) %cyan(%-40.40logger{39}) : %msg%n";

    /**
     * 默认文件纯文本日志输出格式（包含 MDC traceId 标记）
     */
    public static final String DEFAULT_FILE_PATTERN =
            "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%15.15thread] [%replace(%X{traceId}){'^$', 'NO-TRACE'}] %-40.40logger{39} : %msg%n";

    /**
     * 默认日期时间格式
     */
    public static final String DEFAULT_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS";

    /**
     * 内置双文件持久化 Logback 配置文件类路径（支持 INFO 与 ERROR 独立分流）
     */
    public static final String CLASSPATH_LOGBACK_FILE = "classpath:io/github/atengk/boot/core/logging/ateng-logback-file.xml";

    /**
     * 内置单文件持久化 Logback 配置文件类路径
     */
    public static final String CLASSPATH_LOGBACK_FILE_SINGLE = "classpath:io/github/atengk/boot/core/logging/ateng-logback-file-single.xml";

    /**
     * 标准 Logback 自定义配置文件名称候选列表
     */
    private static final String[] STANDARD_LOGBACK_FILES = {
            "logback-test.xml",
            "logback.xml",
            "logback-spring.xml",
            "logback-test.groovy",
            "logback.groovy",
            "logback-spring.groovy"
    };

    /**
     * 执行优先级：低于大部分配置加载器，以便用户配置优先
     */
    public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 5;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment == null) {
            return;
        }

        // 1. 日志引擎总开关校验（默认 true，为 false 时完全保留原生 Spring 裸行为）
        Boolean loggingEnabled = environment.getProperty("ateng.boot.core.logging.enabled", Boolean.class, true);
        if (Boolean.FALSE.equals(loggingEnabled)) {
            return;
        }

        // 2. 检测是否存在自定义日志配置或用户显式覆写（若存在则 100% 静默退让）
        ClassLoader classLoader = application != null ? application.getClassLoader() : null;
        if (hasCustomLoggingConfiguration(environment, classLoader)) {
            return;
        }

        // 3. 构建默认日志属性源
        Map<String, Object> defaults = new LinkedHashMap<>();

        // 3.1 检查是否开启磁盘文件持久化落盘
        Boolean fileEnabled = environment.getProperty("ateng.boot.core.logging.file.enabled", Boolean.class, false);
        if (Boolean.TRUE.equals(fileEnabled)) {
            Boolean splitError = environment.getProperty("ateng.boot.core.logging.file.split-error", Boolean.class, true);
            String configLocation = Boolean.TRUE.equals(splitError) ? CLASSPATH_LOGBACK_FILE : CLASSPATH_LOGBACK_FILE_SINGLE;
            defaults.put("logging.config", configLocation);
        }

        // 3.2 注入控制台与文件格式默认基线
        Boolean consoleEnabled = environment.getProperty("ateng.boot.core.logging.console.enabled", Boolean.class, true);
        if (Boolean.TRUE.equals(consoleEnabled)) {
            defaults.put("logging.pattern.console", DEFAULT_CONSOLE_PATTERN);
            defaults.put("logging.pattern.dateformat", DEFAULT_DATE_FORMAT);
        }
        defaults.put("logging.pattern.file", DEFAULT_FILE_PATTERN);

        // 4. 以最低优先级注入属性源
        if (!environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
        }
    }

    /**
     * 探测是否存在用户自定义的日志配置文件或显式配置属性
     *
     * @param environment 当前运行时配置环境
     * @param classLoader 类加载器
     * @return true 表示检测到用户自定义日志配置，框架应静默退让
     */
    public static boolean hasCustomLoggingConfiguration(ConfigurableEnvironment environment, ClassLoader classLoader) {
        if (environment == null) {
            return false;
        }

        // 1. 用户显式指定了非内置的 logging.config
        if (environment.containsProperty("logging.config")) {
            String config = environment.getProperty("logging.config");
            if (config != null && !config.isBlank() && !config.startsWith("classpath:io/github/atengk/boot/core/logging/")) {
                return true;
            }
        }

        // 2. 用户在配置文件中显式配置了 logging.pattern.console
        if (environment.containsProperty("logging.pattern.console")) {
            return true;
        }

        // 3. Classpath 根目录下存在标准的自定义 Logback 文件
        ClassLoader cl = classLoader != null ? classLoader : ClassUtils.getDefaultClassLoader();
        if (cl != null) {
            for (String file : STANDARD_LOGBACK_FILES) {
                if (cl.getResource(file) != null) {
                    return true;
                }
            }
        }

        return false;
    }
}
