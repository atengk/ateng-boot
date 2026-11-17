package io.github.atengk.boot.flyway.autoconfigure;

import io.github.atengk.boot.flyway.guard.ProductionSafetyGuard;
import io.github.atengk.boot.flyway.properties.AtengFlywayProperties;
import io.github.atengk.boot.flyway.template.FlywayTemplate;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.core.Ordered;

import java.util.Objects;

/**
 * 隔离式加密 DDL 凭据专属启动初始化器
 * <p>
 * 在隔离凭据模式下替代原生 {@code FlywayMigrationInitializer} 负责启动迁移调度，
 * 全程不向容器注册持久化 {@code DataSource} 或持有闭池连接的 {@code Flyway} Bean，
 * 同时通过 {@link AtengFlywayDatabaseInitializerDetector} 严格维持持久层初始化屏障。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class AtengFlywayIsolatedInitializer implements InitializingBean, Ordered {

    private final FlywayTemplate flywayTemplate;
    private final AtengFlywayProperties atengProperties;
    private final FlywayProperties flywayProperties;
    private final ProductionSafetyGuard safetyGuard;
    private int order = 0;

    /**
     * 构造隔离凭据启动迁移初始化器
     *
     * @param flywayTemplate   对称迁移门面
     * @param atengProperties  ateng-boot-flyway 配置属性
     * @param flywayProperties Spring Boot 原生 Flyway 配置属性
     * @param safetyGuard      生产安全守卫
     */
    public AtengFlywayIsolatedInitializer(FlywayTemplate flywayTemplate,
                                          AtengFlywayProperties atengProperties,
                                          FlywayProperties flywayProperties,
                                          ProductionSafetyGuard safetyGuard) {
        this.flywayTemplate = Objects.requireNonNull(flywayTemplate, "flywayTemplate 不能为空");
        this.atengProperties = Objects.requireNonNull(atengProperties, "atengProperties 不能为空");
        this.flywayProperties = Objects.requireNonNull(flywayProperties, "flywayProperties 不能为空");
        this.safetyGuard = Objects.requireNonNull(safetyGuard, "safetyGuard 不能为空");
    }

    @Override
    public void afterPropertiesSet() {
        AtengFlywayStartupSupport.runStartup(flywayTemplate, atengProperties, flywayProperties, safetyGuard);
    }

    @Override
    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }
}
