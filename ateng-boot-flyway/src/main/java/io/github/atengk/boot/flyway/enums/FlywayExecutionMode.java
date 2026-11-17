package io.github.atengk.boot.flyway.enums;

/**
 * Flyway 启动及受管迁移执行模式
 *
 * @author Ateng
 * @since 2026-11-17
 */
public enum FlywayExecutionMode {

    /**
     * 自动执行版本化 Schema 迁移（默认模式）
     */
    MIGRATE,

    /**
     * 仅校验数据库当前版本与本地迁移脚本 Checksum 一致性，严禁变更任何表结构
     */
    VALIDATE_ONLY,

    /**
     * 跳过启动阶段自动迁移，仅保留编程式 {@code FlywayTemplate} 按需操作
     */
    DISABLED
}
