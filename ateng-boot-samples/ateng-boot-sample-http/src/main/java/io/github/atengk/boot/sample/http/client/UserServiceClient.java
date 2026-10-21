package io.github.atengk.boot.sample.http.client;

import io.github.atengk.boot.http.annotation.HttpClient;
import io.github.atengk.boot.sample.http.dto.UserRequest;
import io.github.atengk.boot.sample.http.dto.UserResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.Map;

/**
 * 内部微服务声明式 HTTP 客户端接口
 * 演示内部微服务强类型交互契约，透明贯通四维 TraceId/TenantId/UserId 上下文透传与统一异常转译
 *
 * @author Ateng
 * @since 2026-10-21
 */
@HttpClient(name = "user-service", url = "${sample.user-service.url:http://localhost:8080}")
@HttpExchange
public interface UserServiceClient {

    /**
     * 根据用户 ID 获取用户详细信息（强类型 VO 契约）
     *
     * @param id 用户主键
     * @return 用户详情响应对象
     */
    @GetExchange("/users/{id}")
    UserResponse getUserById(@PathVariable("id") Long id);

    /**
     * 创建新用户（强类型 DTO 契约）
     *
     * @param user 创建用户请求载荷
     * @return 创建结果映射
     */
    @PostExchange("/users")
    Map<String, Object> createUser(@RequestBody UserRequest user);
}
