package io.github.atengk.boot.flyway.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * 隔离式 DDL 凭据自动装配过滤器
 * <p>
 * 当 {@code ateng.boot.flyway.enabled=true} 且启用隔离式高权限 DDL 凭据时，
 * 抑制 Spring Boot 原生 {@code FlywayAutoConfiguration} 与 {@code FlywayEndpointAutoConfiguration}，
 * 防止容器暴露持有已关闭瞬态连接池的原生 {@code Flyway} Bean 或回退使用低权限 DML 数据源执行迁移。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class AtengFlywayAutoConfigurationImportFilter implements AutoConfigurationImportFilter, EnvironmentAware {

    private static final String BOOT_FLYWAY_AUTO_CONFIG =
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration";
    private static final String BOOT_FLYWAY_ENDPOINT_AUTO_CONFIG =
            "org.springframework.boot.actuate.autoconfigure.flyway.FlywayEndpointAutoConfiguration";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata autoConfigurationMetadata) {
        boolean[] matches = new boolean[autoConfigurationClasses.length];
        boolean suppressNativeFlyway = shouldSuppressNativeFlyway();
        for (int i = 0; i < autoConfigurationClasses.length; i++) {
            String candidate = autoConfigurationClasses[i];
            if (suppressNativeFlyway && (BOOT_FLYWAY_AUTO_CONFIG.equals(candidate)
                    || BOOT_FLYWAY_ENDPOINT_AUTO_CONFIG.equals(candidate))) {
                matches[i] = false;
            } else {
                matches[i] = true;
            }
        }
        return matches;
    }

    private boolean shouldSuppressNativeFlyway() {
        if (environment == null) {
            return false;
        }
        Boolean springEnabled = environment.getProperty("spring.flyway.enabled", Boolean.class);
        if (Boolean.FALSE.equals(springEnabled)) {
            return false;
        }
        return AtengFlywayAutoConfiguration.isIsolatedCredentialConfigured(environment);
    }
}
