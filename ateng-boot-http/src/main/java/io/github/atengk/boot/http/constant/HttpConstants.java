package io.github.atengk.boot.http.constant;

/**
 * HTTP 模块内部系统常量与私有控制头契约
 *
 * @author Ateng
 * @since 2026-10-21
 */
public final class HttpConstants {

    private HttpConstants() {
    }

    /**
     * 单次请求上下文透传控制内部标记头（用于在拦截器链路中传递并在出站前严格剥离）
     */
    public static final String HEADER_PROPAGATE_CONTEXT_OVERRIDE = "X-Ateng-Internal-Propagate-Context";

    /**
     * 单次请求目标客户端名称内部标记头（用于在拦截器链路中传递并在出站前严格剥离）
     */
    public static final String HEADER_CLIENT_NAME_OVERRIDE = "X-Ateng-Internal-Client-Name";
}
