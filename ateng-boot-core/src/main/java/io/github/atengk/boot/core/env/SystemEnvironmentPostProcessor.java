package io.github.atengk.boot.core.env;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * 核心系统基线与运行环境后处理器
 * <p>
 * 在 Spring Boot 容器启动极早阶段执行全局系统防卫：
 * 1. 默认校准全局时区为 {@code Asia/Shanghai}（支持通过配置覆盖），消灭跨系统/容器时区漂移；
 * 2. 统一强制 JVM 文件编码为 {@code UTF-8}；
 * 3. 强制配置 {@code java.awt.headless=true}，确保无桌面 Linux/Docker 容器图形工具安全运行；
 * 4. 为 JVM 原生网络通信注入默认 10 秒连接与读取超时，防御老旧第三方客户端无限挂起；
 * 5. 支持 {@code ateng.boot.core.system.enabled} 顶层大开关一键熔断退出。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
public class SystemEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /**
     * 系统默认属性源名称
     */
    public static final String PROPERTY_SOURCE_NAME = "atengSystemDefaults";

    /**
     * 默认连接与读取超时时间（毫秒）：10 秒
     */
    public static final String DEFAULT_NETWORK_TIMEOUT_MS = "10000";

    /**
     * 默认时区标识
     */
    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

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

        // 1. 系统增强总开关校验（默认 true，为 false 时彻底熔断所有系统级干预）
        Boolean systemEnabled = environment.getProperty("ateng.boot.core.system.enabled", Boolean.class, true);
        if (Boolean.FALSE.equals(systemEnabled)) {
            return;
        }

        // 2. 时区与系统环境防卫子开关校验（默认 true）
        Boolean timezoneGuardEnabled = environment.getProperty("ateng.boot.core.system.timezone-guard-enabled", Boolean.class, true);
        if (Boolean.FALSE.equals(timezoneGuardEnabled)) {
            return;
        }

        // 3. 读取目标时区标识（默认 Asia/Shanghai）
        String timezone = environment.getProperty("ateng.boot.core.system.timezone", String.class, DEFAULT_TIMEZONE);
        applySystemBaseline(timezone);

        // 4. 以最低优先级注入属性源，确保业务配置中心/application.yml 无缝覆写
        if (!environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            Map<String, Object> defaults = new LinkedHashMap<>();
            defaults.put("ateng.boot.core.system.timezone", timezone);
            defaults.put("user.timezone", timezone);
            environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
        }
    }

    /**
     * 应用系统底层运行时基线防卫
     *
     * @param timezone 目标时区标识
     */
    private void applySystemBaseline(String timezone) {
        // 3.1 校准时区
        try {
            ZoneId zoneId = ZoneId.of(timezone);
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
            System.setProperty("user.timezone", zoneId.getId());
        } catch (Throwable ignored) {
            // 防御非法时区字符串异常
        }

        // 3.2 规范系统编码与 Headless 模式
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("java.awt.headless", "true");

        // 3.3 注入 JVM 底层网络连接与读取保底超时（防第三方无超时客户端无限挂起）
        if (System.getProperty("sun.net.client.defaultConnectTimeout") == null) {
            System.setProperty("sun.net.client.defaultConnectTimeout", DEFAULT_NETWORK_TIMEOUT_MS);
        }
        if (System.getProperty("sun.net.client.defaultReadTimeout") == null) {
            System.setProperty("sun.net.client.defaultReadTimeout", DEFAULT_NETWORK_TIMEOUT_MS);
        }
    }
}
