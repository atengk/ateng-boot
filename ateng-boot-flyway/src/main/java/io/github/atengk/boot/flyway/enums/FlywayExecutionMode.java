package io.github.atengk.boot.flyway.enums;

import io.github.atengk.boot.core.enums.IBaseEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Flyway 启动及受管迁移执行模式
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Getter
@AllArgsConstructor
public enum FlywayExecutionMode implements IBaseEnum<String> {

    /**
     * 自动执行版本化 Schema 迁移（默认模式）
     */
    MIGRATE("MIGRATE", "自动执行迁移"),

    /**
     * 仅校验数据库当前版本与本地迁移脚本 Checksum 一致性，严禁变更任何表结构
     */
    VALIDATE_ONLY("VALIDATE_ONLY", "仅只读一致性校验"),

    /**
     * 跳过启动阶段自动迁移，仅保留编程式 {@code FlywayTemplate} 按需操作
     */
    DISABLED("DISABLED", "跳过启动自动迁移");

    private final String code;
    private final String label;
}
