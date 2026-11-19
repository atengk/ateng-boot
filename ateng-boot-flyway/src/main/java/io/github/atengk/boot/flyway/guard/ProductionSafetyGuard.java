package io.github.atengk.boot.flyway.guard;

import io.github.atengk.boot.flyway.enums.FlywayResultCode;
import io.github.atengk.boot.flyway.exception.FlywayException;
import io.github.atengk.boot.flyway.properties.AtengFlywayProperties;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 在受保护的运行环境（如 {@code prod} / {@code production} / {@code prd}）或全环境强守卫模式下，
 * 物理拦截任何 {@code clean} 清理调用、{@code clean-on-validation-error=true} 高危误配置
 * 以及待执行 SQL 脚本中未授权的 {@code DROP TABLE} / {@code DROP DATABASE} / {@code DROP SCHEMA} / {@code TRUNCATE}
 * 等破坏性 DDL 语句，彻底消除因配置漂移、手工误调用或脚本失误引发的删表事故。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class ProductionSafetyGuard {

    private static final Logger log = LoggerFactory.getLogger(ProductionSafetyGuard.class);

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
        if (flywayProperties == null) {
            return;
        }
        validateConfiguration(targetKey, flywayProperties.isCleanOnValidationError());
    }

    /**
     * 校验运行期或定制器修改后的 {@code cleanOnValidationError} 配置是否违反受保护环境安全规则
     *
     * @param targetKey              目标数据源标识
     * @param cleanOnValidationError 当前生效的 cleanOnValidationError 值
     */
    public void validateConfiguration(String targetKey, boolean cleanOnValidationError) {
        AtengFlywayProperties.GuardProperties guard = atengProperties.getGuard();
        if (guard == null || !guard.isEnabled()) {
            return;
        }
        if (isProtectedEnvironment()
                && guard.isForbidCleanOnValidationError()
                && cleanOnValidationError) {
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

    /**
     * 判断当前环境与策略配置是否启用了待执行 SQL 高危破坏性 DDL 拦截
     *
     * @return true 表示必须执行待执行 SQL 破坏性 DDL 静态预检
     */
    public boolean isDestructiveDdlBlocked() {
        AtengFlywayProperties.GuardProperties guard = atengProperties.getGuard();
        if (guard == null || !guard.isEnabled()) {
            return false;
        }
        if (guard.isForbidDestructiveDdlAlways()) {
            return true;
        }
        return guard.isForbidDestructiveDdlInProtectedProfiles() && isProtectedEnvironment();
    }

    /**
     * 对即将执行的待迁移（pending）SQL 脚本进行静态高危 DDL 预检
     * <p>
     * 仅扫描尚未执行的 SQL 类型迁移脚本，委托 {@link PendingSqlScanner} 剥离注释与字面量后拦截
     * {@code DROP TABLE}、{@code DROP DATABASE}、{@code DROP SCHEMA} 与 {@code TRUNCATE}，
     * 并支持经受审脚本在注释中标注 {@code -- @ateng-allow-destructive-ddl} 显式豁免放行。
     * </p>
     *
     * @param targetKey 目标数据源标识
     * @param flyway    受管 Flyway 实例
     */
    public void assertPendingSqlSafe(String targetKey, Flyway flyway) {
        if (flyway == null || !isDestructiveDdlBlocked()) {
            return;
        }
        MigrationInfo[] pendingInfos = flyway.info().pending();
        if (pendingInfos == null || pendingInfos.length == 0) {
            return;
        }
        Configuration configuration = flyway.getConfiguration();
        AtengFlywayProperties.GuardProperties guard = atengProperties.getGuard();
        boolean allowExemption = guard != null && guard.isAllowDestructiveDdlAnnotation();

        for (MigrationInfo info : pendingInfos) {
            if (!PendingSqlScanner.isSqlMigrationCandidate(info, configuration)) {
                continue;
            }
            String sqlContent = PendingSqlScanner.readPendingSqlContent(info, configuration);
            if (!StringUtils.hasText(sqlContent)) {
                continue;
            }
            PendingSqlScanner.DetectionResult detection = PendingSqlScanner.detectDestructiveDdl(sqlContent);
            if (detection != null) {
                String scriptName = StringUtils.hasText(info.getScript()) ? info.getScript() : info.getPhysicalLocation();
                if (allowExemption && detection.exempted()) {
                    log.warn("待执行 SQL 包含显式豁免标记 [target={}, script={}, statement={}]，已放行本次破坏性 DDL 执行",
                            targetKey, scriptName, detection.statement());
                    continue;
                }
                throw new FlywayException(
                        FlywayResultCode.DESTRUCTIVE_DDL_FORBIDDEN,
                        "生产安全守卫拦截 [target=" + targetKey + ", script=" + scriptName
                                + "]：检测到未授权的高危破坏性 DDL 语句 [" + detection.statement() + "]"
                );
            }
        }
    }
}
