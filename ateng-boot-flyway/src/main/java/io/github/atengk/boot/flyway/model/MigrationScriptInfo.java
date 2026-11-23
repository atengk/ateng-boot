package io.github.atengk.boot.flyway.model;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * 单个 Flyway 迁移脚本明细视图对象
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Getter
@Builder
public class MigrationScriptInfo {

    /**
     * 版本号（可重复脚本为 null）
     */
    private final String version;

    /**
     * 迁移描述
     */
    private final String description;

    /**
     * 脚本类型（如 SQL / JDBC / BASELINE / DELETE）
     */
    private final String type;

    /**
     * 当前状态（如 SUCCESS / PENDING / FAILED / OUT_OF_ORDER）
     */
    private final String state;

    /**
     * 脚本文件名
     */
    private final String script;

    /**
     * 校验和 Checksum
     */
    private final Integer checksum;

    /**
     * 安装执行时间
     */
    private final Instant installedOn;

    /**
     * 执行耗时（毫秒）
     */
    private final Integer executionTimeMs;

    /**
     * 执行账号
     */
    private final String installedBy;
}
