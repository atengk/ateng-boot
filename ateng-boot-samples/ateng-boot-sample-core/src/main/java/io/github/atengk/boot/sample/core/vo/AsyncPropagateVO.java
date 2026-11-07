package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 多维上下文跨线程透传响应视图对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsyncPropagateVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主线程 TraceId
     */
    private String mainTraceId;

    /**
     * 异步工作线程捕获到的 TraceId
     */
    private String asyncTraceId;

    /**
     * 主线程用户 ID
     */
    private String mainUserId;

    /**
     * 异步工作线程捕获到的用户 ID
     */
    private String asyncUserId;

    /**
     * 主线程租户 ID
     */
    private String mainTenantId;

    /**
     * 异步工作线程捕获到的租户 ID
     */
    private String asyncTenantId;

    /**
     * 异步线程捕获的上下文是否与主线程完全匹配
     */
    private boolean contextMatched;

    /**
     * 实际执行任务的异步工作线程名称
     */
    private String asyncThreadName;

    /**
     * 扩展快照 Provider 透传的值
     */
    private String customSnapshotValue;
}
