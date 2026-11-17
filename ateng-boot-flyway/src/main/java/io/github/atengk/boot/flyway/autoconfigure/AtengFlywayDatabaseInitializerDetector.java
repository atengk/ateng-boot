package io.github.atengk.boot.flyway.autoconfigure;

import org.springframework.boot.sql.init.dependency.AbstractBeansOfTypeDatabaseInitializerDetector;

import java.util.Set;

/**
 * 隔离式 Flyway 数据库初始化器 Bean 探测器
 * <p>
 * Spring Boot 原生 {@code FlywayMigrationInitializerDatabaseInitializerDetector} 已在 {@code order = 1}
 * 探测 {@code FlywayMigrationInitializer}（涵盖正常模式下的 {@link AtengFlywayMigrationInitializer}）；
 * 本探测器专职在同优先级 {@code order = 1} 下补充探测隔离式 DDL 凭据模式的 {@link AtengFlywayIsolatedInitializer}，
 * 避免与原生探测器重复分段导致初始化器自依赖环。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class AtengFlywayDatabaseInitializerDetector extends AbstractBeansOfTypeDatabaseInitializerDetector {

    @Override
    protected Set<Class<?>> getDatabaseInitializerBeanTypes() {
        return Set.of(AtengFlywayIsolatedInitializer.class);
    }

    @Override
    public int getOrder() {
        return 1;
    }
}
