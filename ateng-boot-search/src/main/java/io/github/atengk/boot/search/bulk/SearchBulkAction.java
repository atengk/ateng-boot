package io.github.atengk.boot.search.bulk;

import io.github.atengk.boot.core.context.AsyncContext;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 批量操作项载荷模型
 * <p>
 * 封装待提交文档的操作语义（索引/更新/Upsert/删除）、目标索引、文档 ID、分片路由键及估算容量。
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
@Builder(toBuilder = true)
@ToString(exclude = {"document", "contextSnapshot"})
public class SearchBulkAction implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 批量操作类型枚举
     */
    public enum ActionType {
        /**
         * 索引新增或全量覆盖
         */
        INDEX,
        /**
         * 局部更新（Partial Update）
         */
        UPDATE,
        /**
         * 未存在时 Upsert
         */
        UPSERT,
        /**
         * 物理删除
         */
        DELETE
    }

    /**
     * 操作类型
     */
    private final ActionType type;

    /**
     * 目标索引名
     */
    private final String index;

    /**
     * 文档唯一标识 ID
     */
    private final String id;

    /**
     * 分片路由键（可选）
     */
    private final String routing;

    /**
     * 文档实体或局部更新对象（删除操作时为 null）
     */
    private final Object document;

    /**
     * 估算序列化字节容量
     */
    private final long estimatedBytes;

    /**
     * 入队时捕获的实体类类型（用于异步刷盘阶段的多租户 @IgnoreTenant 判定，可能为 null）
     */
    private final Class<?> entityClass;

    /**
     * 入队时捕获的调用线程租户标识（可能为 null）
     */
    private final String tenantId;

    /**
     * 入队时是否触发忽略租户标志
     */
    private final boolean ignoreTenant;

    /**
     * 入队时刻捕获的四维异步上下文快照（供后台刷盘与死信回调精准恢复 MDC traceId/User/Tenant 现场）
     */
    private final transient AsyncContext.ContextSnapshot contextSnapshot;
}
