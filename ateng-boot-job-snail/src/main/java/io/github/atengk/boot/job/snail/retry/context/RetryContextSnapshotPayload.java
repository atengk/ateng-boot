package io.github.atengk.boot.job.snail.retry.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 分布式重试四维上下文可序列化扁平传输快照
 *
 * <p>显式承载 MDC 映射、链路 {@code traceId}、操作人 {@code userId}、多租户 {@code tenantId}
 * 与忽略租户标志 {@code ignoreTenant}，规避直接序列化运行时不可变快照载体
 * （含私有构造器与 {@code IdentityHashMap} 扩展容器）导致反序列化失败的风险。</p>
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetryContextSnapshotPayload implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * MDC 键值映射快照
     */
    private Map<String, String> mdc;

    /**
     * 全链路追踪标识
     */
    private String traceId;

    /**
     * 显式绑定的操作人标识
     */
    private String userId;

    /**
     * 显式绑定的租户标识
     */
    private String tenantId;

    /**
     * 是否处于忽略多租户过滤状态
     */
    private boolean ignoreTenant;

    /**
     * 快照捕获时间戳（UTC 毫秒）
     */
    private long capturedAtEpochMillis;
}
