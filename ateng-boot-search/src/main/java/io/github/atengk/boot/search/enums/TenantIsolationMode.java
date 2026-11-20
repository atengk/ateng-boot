package io.github.atengk.boot.search.enums;

import io.github.atengk.boot.core.enums.IBaseEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 搜索引擎多租户行级隔离策略模式枚举
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Getter
@AllArgsConstructor
public enum TenantIsolationMode implements IBaseEnum<String> {

    /**
     * 严格全闭环隔离模式（默认）：
     * 写入注入与跨租户篡改阻断 + 查询/计数/条件清理隐式过滤 + 点查租户核验过滤 +
     * 单点删改前置归属校验防越权 + existsById 降级带租户条件 _count 探测。
     */
    STRICT("STRICT", "严格防越权闭环"),

    /**
     * 仅过滤与写注入模式：
     * 保留写入阶段租户注入/防篡改及查询/计数/条件清理隐式过滤，
     * 单点 get/exists/update/delete 保持轻量直通语义不做额外读取归属核验。
     */
    FILTER_ONLY("FILTER_ONLY", "仅过滤与写注入");

    private final String code;
    private final String label;
}
