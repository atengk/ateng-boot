package io.github.atengk.boot.flyway.model;

import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 数据库迁移历史修复结果视图对象
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Getter
@Builder
public class MigrationRepairResult {

    /**
     * 目标数据源标识
     */
    private final String targetKey;

    /**
     * 执行的修复动作列表
     */
    private final List<String> repairActions;

    /**
     * 警告消息列表
     */
    private final List<String> warnings;

    public List<String> getRepairActions() {
        return repairActions != null ? repairActions : Collections.emptyList();
    }

    public List<String> getWarnings() {
        return warnings != null ? warnings : Collections.emptyList();
    }
}
