package io.github.atengk.boot.flyway.model;

import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 数据库清理结果视图对象
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Getter
@Builder
public class MigrationCleanResult {

    /**
     * 目标数据源标识
     */
    private final String targetKey;

    /**
     * 已清理的 Schema 列表
     */
    private final List<String> schemasCleaned;

    /**
     * 已删除的 Schema 列表
     */
    private final List<String> schemasDropped;

    /**
     * 警告消息列表
     */
    private final List<String> warnings;

    public List<String> getSchemasCleaned() {
        return schemasCleaned != null ? schemasCleaned : Collections.emptyList();
    }

    public List<String> getSchemasDropped() {
        return schemasDropped != null ? schemasDropped : Collections.emptyList();
    }

    public List<String> getWarnings() {
        return warnings != null ? warnings : Collections.emptyList();
    }
}
