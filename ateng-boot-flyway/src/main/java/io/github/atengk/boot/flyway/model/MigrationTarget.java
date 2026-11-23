package io.github.atengk.boot.flyway.model;

import io.github.atengk.boot.flyway.enums.BatchExecutionPolicy;
import io.github.atengk.boot.flyway.enums.MigrationTargetKind;
import io.github.atengk.boot.flyway.properties.AtengFlywayProperties;
import lombok.Builder;
import lombok.Getter;

import javax.sql.DataSource;

/**
 * 迁移目标数据源描述符
 * <p>
 * 封装物理直连 {@link DataSource}、目标标识、目标级别、可选隔离式 DDL 凭据、
 * 目标级参数覆写以及失败策略。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Getter
@Builder
public class MigrationTarget {

    /**
     * 目标数据源唯一标识（例如 default、master、log_db）
     */
    private final String targetKey;

    /**
     * 目标类别（主库 / 静态业务库 / 物理租户库 / 自定义）
     */
    @Builder.Default
    private final MigrationTargetKind kind = MigrationTargetKind.PRIMARY;

    /**
     * 关联租户标识（若为租户库或存在上下文绑定时填充）
     */
    private final String tenantId;

    /**
     * 解包后的直连物理数据源（仅使用独立 DDL URL 凭据时可为空）
     */
    private final DataSource dataSource;

    /**
     * 可选的隔离式高权限 DDL 迁移凭据配置
     */
    private final AtengFlywayProperties.CredentialProperties credential;

    /**
     * 可选的目标级 Flyway 配置覆写（为空时完全继承全局 spring.flyway.*）
     */
    private final AtengFlywayProperties.DataSourceMigrationProperties migrationConfig;

    /**
     * 本目标失败容错策略（主库与静态核心库缺省 FAIL_FAST）
     */
    @Builder.Default
    private final BatchExecutionPolicy failurePolicy = BatchExecutionPolicy.FAIL_FAST;
}
