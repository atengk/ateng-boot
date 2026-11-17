package io.github.atengk.boot.flyway.model;

import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 数据库基线初始化结果视图对象
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Getter
@Builder
public class MigrationBaselineResult {

    /**
     * 目标数据源标识
     */
    private final String targetKey;

    /**
     * 是否成功写入基线
     */
    private final boolean successfullyBaselined;

    /**
     * 基线版本号
     */
    private final String baselineVersion;

    /**
     * 警告消息列表
     */
    private final List<String> warnings;

    public List<String> getWarnings() {
        return warnings != null ? warnings : Collections.emptyList();
    }
}
