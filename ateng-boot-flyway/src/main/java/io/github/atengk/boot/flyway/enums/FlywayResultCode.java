package io.github.atengk.boot.flyway.enums;

import io.github.atengk.boot.core.code.IResultCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Flyway 数据库版本化迁移模块专属业务状态码枚举 (62001 ~ 62020)
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Getter
@AllArgsConstructor
public enum FlywayResultCode implements IResultCode {

    /**
     * 数据库版本化迁移执行失败
     */
    MIGRATION_FAILED(62001, "数据库版本化迁移执行失败"),

    /**
     * 数据库迁移一致性校验未通过
     */
    VALIDATION_FAILED(62002, "数据库迁移一致性校验未通过"),

    /**
     * 受保护环境或策略禁止执行破坏性清理操作
     */
    CLEAN_FORBIDDEN(62003, "受保护环境禁止执行破坏性清理操作"),

    /**
     * 未找到指定的迁移目标数据源
     */
    TARGET_NOT_FOUND(62004, "未找到指定的迁移目标数据源"),

    /**
     * 数据库基线初始化失败
     */
    BASELINE_FAILED(62005, "数据库基线初始化失败"),

    /**
     * 数据库迁移历史修复失败
     */
    REPAIR_FAILED(62006, "数据库迁移历史修复失败"),

    /**
     * 迁移数据源凭据解密失败
     */
    CREDENTIAL_DECRYPT_FAILED(62007, "迁移数据源凭据解密失败"),

    /**
     * 获取数据库迁移并发锁超时
     */
    LOCK_TIMEOUT(62008, "获取数据库迁移并发锁超时"),

    /**
     * Flyway 迁移配置非法
     */
    CONFIG_INVALID(62009, "Flyway 迁移配置非法");

    private final Integer code;
    private final String msg;
}
