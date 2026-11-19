package io.github.atengk.boot.flyway.enums;

import io.github.atengk.boot.core.enums.IBaseEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 多目标批量迁移容错策略枚举（与动态数据源批处理容错语义保持严格对齐）
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Getter
@AllArgsConstructor
public enum BatchExecutionPolicy implements IBaseEnum<String> {

    /**
     * 快速失败：任一目标迁移或校验失败立即中断后续目标并抛出 {@code FlywayException}
     */
    FAIL_FAST("FAIL_FAST", "快速失败"),

    /**
     * 安全容错：隔离单目标失败异常并归集至批处理回执，继续完成其余目标
     */
    FAIL_SAFE("FAIL_SAFE", "安全容错");

    private final String code;
    private final String label;
}
