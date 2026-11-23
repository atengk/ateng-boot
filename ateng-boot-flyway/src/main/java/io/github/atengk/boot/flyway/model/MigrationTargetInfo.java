package io.github.atengk.boot.flyway.model;

import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 目标数据源迁移版本全量视图对象
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Getter
@Builder
public class MigrationTargetInfo {

    /**
     * 目标数据源标识
     */
    private final String targetKey;

    /**
     * 当前数据库已应用最新版本（未初始化或无版本时为 null）
     */
    private final String currentVersion;

    /**
     * 已成功应用脚本数量
     */
    private final int appliedCount;

    /**
     * 待执行脚本数量
     */
    private final int pendingCount;

    /**
     * 失败脚本数量
     */
    private final int failedCount;

    /**
     * 全量脚本明细列表（按版本顺序）
     */
    private final List<MigrationScriptInfo> scripts;

    public List<MigrationScriptInfo> getScripts() {
        return scripts != null ? scripts : Collections.emptyList();
    }
}
