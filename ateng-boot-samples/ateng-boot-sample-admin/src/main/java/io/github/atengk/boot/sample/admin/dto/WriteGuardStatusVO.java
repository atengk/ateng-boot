package io.github.atengk.boot.sample.admin.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 控制台只读写守卫状态响应 VO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
public class WriteGuardStatusVO {

    /**
     * 当前是否允许写操作（false 代表处于只读模式）
     */
    private boolean enableWriteOperations;

    /**
     * SBA 控制台上下文前缀
     */
    private String contextPath;

    /**
     * 本次探测 HTTP 方法
     */
    private String probeMethod;

    /**
     * 本次探测目标请求路径
     */
    private String probeUri;

    /**
     * 目标路径是否命中微服务 Actuator 反向代理规则
     */
    private boolean actuatorProxyEndpoint;

    /**
     * 在当前写守卫策略下该请求是否会被拦截返回 403
     */
    private boolean wouldBlock;
}
