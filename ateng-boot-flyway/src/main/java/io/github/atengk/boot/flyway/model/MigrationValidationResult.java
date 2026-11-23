package io.github.atengk.boot.flyway.model;

import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 数据库迁移一致性校验结果视图对象
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Getter
@Builder
public class MigrationValidationResult {

    /**
     * 目标数据源标识
     */
    private final String targetKey;

    /**
     * 校验是否全部通过
     */
    private final boolean valid;

    /**
     * 参与校验的迁移脚本数量
     */
    private final int validateCount;

    /**
     * 警告消息列表
     */
    private final List<String> warnings;

    public List<String> getWarnings() {
        return warnings != null ? warnings : Collections.emptyList();
    }
}
