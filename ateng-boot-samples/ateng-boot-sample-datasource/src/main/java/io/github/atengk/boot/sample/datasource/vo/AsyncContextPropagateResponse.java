package io.github.atengk.boot.sample.datasource.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 五维异步上下文跨线程透传验证展示视图对象
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsyncContextPropagateResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 发起调用线程名称
     */
    private String callerThreadName;

    /**
     * 异步受管工作线程名称
     */
    private String workerThreadName;

    /**
     * 子线程观测到的 MDC traceId
     */
    private String traceId;

    /**
     * 子线程观测到的 UserContextHolder 用户标识
     */
    private String userId;

    /**
     * 子线程观测到的 TenantContextHolder 租户标识
     */
    private String tenantId;

    /**
     * 子线程观测到的 IgnoreTenant 忽略租户标志
     */
    private boolean ignoreTenant;

    /**
     * 子线程观测到的 DynamicDataSourceContextHolder 数据源标识
     */
    private String dsKey;

    /**
     * 是否确认为跨线程异步透传（调用线程与工作线程不同）
     */
    private boolean crossThreadVerified;
}
