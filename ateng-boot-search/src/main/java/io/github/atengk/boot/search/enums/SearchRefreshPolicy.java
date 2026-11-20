package io.github.atengk.boot.search.enums;

import io.github.atengk.boot.core.enums.IBaseEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 搜索引擎写入刷新策略枚举
 * <p>
 * 控制索引、更新或删除操作完成后使 Lucene 分段对后续检索即时可见的策略。
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Getter
@AllArgsConstructor
public enum SearchRefreshPolicy implements IBaseEnum<String> {

    /**
     * 不立即刷新（默认，依赖服务端周期性 refresh_interval，吞吐最高）
     */
    NONE("false", "不立即刷新"),

    /**
     * 等待下一次索引刷新周期完成后再返回响应（兼具较高吞吐与写后即读一致性，生产推荐）
     */
    WAIT_UNTIL("wait_for", "等待周期刷新完成"),

    /**
     * 立即强制刷新 Lucene 分段（毫秒级写后即读，高频写入场景慎用）
     */
    IMMEDIATE("true", "立即强制刷新");

    private final String code;
    private final String label;

    /**
     * 转换为布尔刷新标记（适用于 {@code _update_by_query}/{@code _delete_by_query} 等仅支持 {@code refresh=true/false} 的端点）
     *
     * @return {@code true} 表示等待/立即刷新，{@code false} 表示不刷新
     */
    public boolean toBooleanRefresh() {
        return this == IMMEDIATE || this == WAIT_UNTIL;
    }
}
