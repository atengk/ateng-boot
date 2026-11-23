package io.github.atengk.boot.flyway.autoconfigure;

import io.github.atengk.boot.flyway.guard.ProductionSafetyGuard;
import io.github.atengk.boot.flyway.properties.AtengFlywayProperties;
import io.github.atengk.boot.flyway.template.FlywayTemplate;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;

import java.util.Objects;

/**
 * 受管 Flyway 启动迁移初始化器
 * <p>
 * 继承 Spring Boot {@link FlywayMigrationInitializer}，从而无缝复用 Spring Boot
 * {@code FlywayMigrationInitializerDatabaseInitializerDetector} 的初始化依赖探测机制，
 * 同时按 {@link io.github.atengk.boot.flyway.enums.FlywayExecutionMode}（MIGRATE / VALIDATE_ONLY / DISABLED）与 {@link ProductionSafetyGuard}
 * 统一调度启动迁移或只读校验。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class AtengFlywayMigrationInitializer extends FlywayMigrationInitializer {

    private final FlywayTemplate flywayTemplate;
    private final AtengFlywayProperties atengProperties;
    private final FlywayProperties flywayProperties;
    private final ProductionSafetyGuard safetyGuard;

    /**
     * 构建受管启动迁移初始化器
     *
     * @param flyway            受管 Flyway 实例
     * @param migrationStrategy 可选的自定义迁移策略
     * @param flywayTemplate    对称迁移门面
     * @param atengProperties   ateng-boot-flyway 配置属性
     * @param flywayProperties  Spring Boot 原生 Flyway 配置属性
     * @param safetyGuard       生产安全守卫
     */
    public AtengFlywayMigrationInitializer(Flyway flyway,
                                           FlywayMigrationStrategy migrationStrategy,
                                           FlywayTemplate flywayTemplate,
                                           AtengFlywayProperties atengProperties,
                                           FlywayProperties flywayProperties,
                                           ProductionSafetyGuard safetyGuard) {
        super(flyway, migrationStrategy);
        this.flywayTemplate = Objects.requireNonNull(flywayTemplate, "flywayTemplate 不能为空");
        this.atengProperties = Objects.requireNonNull(atengProperties, "atengProperties 不能为空");
        this.flywayProperties = Objects.requireNonNull(flywayProperties, "flywayProperties 不能为空");
        this.safetyGuard = Objects.requireNonNull(safetyGuard, "safetyGuard 不能为空");
    }

    @Override
    public void afterPropertiesSet() {
        AtengFlywayStartupSupport.runStartup(flywayTemplate, atengProperties, flywayProperties, safetyGuard);
    }
}
