package io.github.atengk.boot.flyway.guard;

import io.github.atengk.boot.flyway.enums.FlywayResultCode;
import io.github.atengk.boot.flyway.exception.FlywayException;
import io.github.atengk.boot.flyway.properties.AtengFlywayProperties;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.core.env.Environment;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 生产防误操作硬守卫
 * <p>
 * 在受保护的运行环境（如 {@code prod} / {@code production} / {@code prd}）下，
 * 物理拦截任何 {@code clean} 清理调用以及 {@code clean-on-validation-error=true} 等高危误配置，
 * 彻底消除因配置漂移或手工误调用引发的数据库清空事故。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class ProductionSafetyGuard {

    private final AtengFlywayProperties atengProperties;
    private final Environment environment;

    public ProductionSafetyGuard(AtengFlywayProperties atengProperties, Environment environment) {
        this.atengProperties = Objects.requireNonNull(atengProperties, "atengProperties 不能为空");
        this.environment = environment;
    }

    /**
     * 判断当前容器是否处于受保护的生产运行环境
     *
     * @return true 表示命中受保护 Profile
     */
    public boolean isProtectedEnvironment() {
        AtengFlywayProperties.GuardProperties guard = atengProperties.getGuard();
        if (guard == null || !guard.isEnabled()) {
            return false;
        }
        if (environment == null) {
            return false;
        }
        List<String> protectedProfiles = guard.getProtectedProfiles();
        if (CollectionUtils.isEmpty(protectedProfiles)) {
            return false;
        }
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles == null || activeProfiles.length == 0) {
            return false;
        }
        for (String active : activeProfiles) {
            if (!StringUtils.hasText(active)) {
                continue;
            }
            String normalizedActive = active.trim().toLowerCase(Locale.ROOT);
            for (String protectedProfile : protectedProfiles) {
                if (StringUtils.hasText(protectedProfile)
                        && normalizedActive.equals(protectedProfile.trim().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 启动及构建配置前校验是否存在高危配置（例如受保护环境下启用 clean-on-validation-error）
     *
     * @param targetKey        目标数据源标识
     * @param flywayProperties Flyway 原生配置属性
     */
    public void validateConfiguration(String targetKey, FlywayProperties flywayProperties) {
        AtengFlywayProperties.GuardProperties guard = atengProperties.getGuard();
        if (guard == null || !guard.isEnabled() || flywayProperties == null) {
            return;
        }
        if (isProtectedEnvironment()
                && guard.isForbidCleanOnValidationError()
                && flywayProperties.isCleanOnValidationError()) {
            throw new FlywayException(
                    FlywayResultCode.CLEAN_FORBIDDEN,
                    "生产安全守卫拦截 [target=" + targetKey + "]：受保护环境下严禁开启 spring.flyway.clean-on-validation-error=true"
            );
        }
    }

    /**
     * 判断构建 Flyway 配置时是否必须强制锁定 cleanDisabled=true
     *
     * @param configuredCleanDisabled 原生配置的 cleanDisabled 值
     * @return true 表示必须强制禁用 clean
     */
    public boolean resolveEffectiveCleanDisabled(boolean configuredCleanDisabled) {
        AtengFlywayProperties.GuardProperties guard = atengProperties.getGuard();
        if (guard == null || !guard.isEnabled()) {
            return configuredCleanDisabled;
        }
        if (guard.isForbidCleanAlways()) {
            return true;
        }
        if (guard.isForbidCleanInProtectedProfiles() && isProtectedEnvironment()) {
            return true;
        }
        return configuredCleanDisabled;
    }

    /**
     * 在执行编程式 clean 操作前进行 Fail-Fast 强校验
     *
     * @param targetKey 目标数据源标识
     * @param flyway    目标 Flyway 实例
     */
    public void assertCleanAllowed(String targetKey, Flyway flyway) {
        AtengFlywayProperties.GuardProperties guard = atengProperties.getGuard();
        if (guard != null && guard.isEnabled()) {
            if (guard.isForbidCleanAlways()) {
                throw new FlywayException(
                        FlywayResultCode.CLEAN_FORBIDDEN,
                        "生产安全守卫拦截 [target=" + targetKey + "]：当前全局策略禁止执行 clean 清理操作"
                );
            }
            if (guard.isForbidCleanInProtectedProfiles() && isProtectedEnvironment()) {
                throw new FlywayException(
                        FlywayResultCode.CLEAN_FORBIDDEN,
                        "生产安全守卫拦截 [target=" + targetKey + "]：受保护生产环境严禁执行破坏性 clean 操作"
                );
            }
        }
        if (flyway != null && flyway.getConfiguration() != null && flyway.getConfiguration().isCleanDisabled()) {
            throw new FlywayException(
                    FlywayResultCode.CLEAN_FORBIDDEN,
                    "清理操作已禁用 [target=" + targetKey + "]：cleanDisabled=true，拒绝执行 clean"
            );
        }
    }
}
