package io.github.atengk.boot.flyway.enums;

import io.github.atengk.boot.core.enums.IBaseEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Flyway 健康探针与待执行脚本度量指标的目标探测作用域枚举
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Getter
@AllArgsConstructor
public enum ObservabilityTargetScope implements IBaseEnum<String> {

    /**
     * 仅探测主目标数据源（单库 default 或主从物理主库 master）
     */
    PRIMARY("PRIMARY", "仅探测主目标数据源"),

    /**
     * 探测主目标数据源与显式纳管的静态业务库（排除海量物理租户库，兼顾精准度与探测性能）
     */
    PRIMARY_AND_STATIC("PRIMARY_AND_STATIC", "探测主目标与静态业务数据源"),

    /**
     * 探测全部受管目标数据源（包含主库、静态业务库与物理多租户库）
     */
    ALL("ALL", "探测全部受管数据源（含物理租户）");

    private final String code;
    private final String label;
}
