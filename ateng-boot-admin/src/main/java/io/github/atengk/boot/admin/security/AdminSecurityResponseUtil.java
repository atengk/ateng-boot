package io.github.atengk.boot.admin.security;

import io.github.atengk.boot.core.util.JsonUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.Map;

/**
 * Spring Boot Admin 安全过滤器通用 JSON 响应输出工具
 * 提供统一定义的状态码与安全转义的 JSON 响应体写出能力
 *
 * @author Ateng
 * @since 2026-11-02
 */
final class AdminSecurityResponseUtil {

    private AdminSecurityResponseUtil() {
    }

    /**
     * 向响应流写出标准 JSON 格式错误或阻断消息
     *
     * @param response HTTP 响应
     * @param status   目标 HTTP 状态码
     * @param msg      响应消息说明
     * @throws IOException IO 异常
     */
    static void writeJsonResponse(HttpServletResponse response, HttpStatus status, String msg) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = Map.of(
                "code", status.value(),
                "message", msg != null ? msg : ""
        );
        response.getWriter().write(JsonUtil.toJsonString(body));
    }
}
