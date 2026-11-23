package io.github.atengk.boot.core.env;

import io.github.atengk.boot.core.properties.NetProperties;
import io.github.atengk.boot.core.util.NetUtil;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
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
 * 在 Spring Boot 容器启动极早阶段按正交职责执行全局系统防卫：
 * 1. 优先独立绑定 {@code ateng.boot.core.net.*} 网络通信与网卡探测配置至 {@link NetUtil}（不受 {@code system.enabled} 开关影响）；
 * 2. 若 {@code ateng.boot.core.system.enabled=true}（默认）：强制开启 {@code java.awt.headless=true} 并注入 10 秒 JVM 默认连接与读取超时保底；
 * 3. 若 {@code ateng.boot.core.system.timezone-guard-enabled=true}（默认）：校准全局时区（默认 {@code Asia/Shanghai}）与系统字符集 {@code UTF-8}。
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

        // 1. 优先独立绑定网络通信与网卡探测配置并同步至 NetUtil，不受 system.enabled 或 timezone-guard-enabled 开关制约
        applyNetworkConfiguration(environment);

        // 2. 系统增强总开关校验（默认 true，为 false 时跳过全部 JVM 系统属性防卫）
        Boolean systemEnabled = environment.getProperty("ateng.boot.core.system.enabled", Boolean.class, true);
        if (Boolean.FALSE.equals(systemEnabled)) {
            return;
        }

        // 3. 激活 Headless 模式与底层网络默认超时保底（10s）
        applyHeadlessAndNetworkTimeoutBaseline();

        // 4. 时区与编码校准防卫子开关校验（默认 true，为 false 时仅跳过默认时区与 file.encoding 校准）
        Boolean timezoneGuardEnabled = environment.getProperty("ateng.boot.core.system.timezone-guard-enabled", Boolean.class, true);
        if (Boolean.FALSE.equals(timezoneGuardEnabled)) {
            return;
        }

        // 5. 规范系统编码、校准目标时区并注入低优先级默认属性源
        System.setProperty("file.encoding", "UTF-8");
        String timezone = environment.getProperty("ateng.boot.core.system.timezone", String.class, DEFAULT_TIMEZONE);
        applyTimezoneBaseline(timezone);

        if (!environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            Map<String, Object> defaults = new LinkedHashMap<>();
            defaults.put("ateng.boot.core.system.timezone", timezone);
            defaults.put("user.timezone", timezone);
            environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
        }
    }

    /**
     * 绑定网络通信配置并同步至 NetUtil
     *
     * @param environment Spring 运行环境
     */
    private void applyNetworkConfiguration(ConfigurableEnvironment environment) {
        try {
            NetProperties netProperties = Binder.get(environment)
                    .bind("ateng.boot.core.net", NetProperties.class)
                    .orElseGet(NetProperties::new);
            NetUtil.configure(
                    netProperties.getPreferredIp(),
                    netProperties.getPreferredNetworks(),
                    netProperties.getIgnoredInterfaces()
            );
        } catch (Throwable ignored) {
            // 防御配置绑定异常
        }
    }

    /**
     * 应用 Headless 与底层网络超时保底基线
     */
    private void applyHeadlessAndNetworkTimeoutBaseline() {
        System.setProperty("java.awt.headless", "true");

        // 注入 JVM 底层网络连接与读取保底超时（防第三方无超时客户端无限挂起）
        if (System.getProperty("sun.net.client.defaultConnectTimeout") == null) {
            System.setProperty("sun.net.client.defaultConnectTimeout", DEFAULT_NETWORK_TIMEOUT_MS);
        }
        if (System.getProperty("sun.net.client.defaultReadTimeout") == null) {
            System.setProperty("sun.net.client.defaultReadTimeout", DEFAULT_NETWORK_TIMEOUT_MS);
        }
    }

    /**
     * 应用系统时区校准防卫
     *
     * @param timezone 目标时区标识
     */
    private void applyTimezoneBaseline(String timezone) {
        try {
            ZoneId zoneId = ZoneId.of(timezone);
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
            System.setProperty("user.timezone", zoneId.getId());
        } catch (Throwable ex) {
            System.err.println("[ATENG] Failed to configure system timezone [" + timezone + "]: " + ex.getMessage());
        }
    }
}
