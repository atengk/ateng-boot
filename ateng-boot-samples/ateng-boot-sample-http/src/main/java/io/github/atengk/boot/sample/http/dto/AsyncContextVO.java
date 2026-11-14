package io.github.atengk.boot.sample.http.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * executeAsync 异步四维上下文透传验证视图对象
 *
 * @author Ateng
 * @since 2026-11-14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsyncContextVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主调用线程 MDC traceId
     */
    private String callerTraceId;

    /**
     * 异步执行线程接收到的 MDC traceId
     */
    private String asyncTraceId;

    /**
     * 异步执行线程接收到的 UserContextHolder 用户标识
     */
    private String asyncUserId;

    /**
     * 异步执行线程接收到的 TenantContextHolder 租户标识
     */
    private String asyncTenantId;

    /**
     * 异步执行线程接收到的忽略租户隔离标志
     */
    private boolean asyncIgnoreTenant;

    /**
     * 四维上下文是否全部完整保真透传
     */
    private boolean propagatedAllFour;

    /**
     * 异步线程内成功发起远端调用获取的用户响应
     */
    private UserResponse remoteUser;
}
