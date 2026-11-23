package io.github.atengk.boot.sample.http.service;

import io.github.atengk.boot.sample.http.client.UserServiceClient;
import io.github.atengk.boot.sample.http.dto.UserRequest;
import io.github.atengk.boot.sample.http.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 声明式用户服务客户端业务场景服务
 * 封装 UserServiceClient 强类型契约 RPC 调用
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserClientScenarioService {

    private final UserServiceClient userServiceClient;

    /**
     * 1. 声明式 GET 查询用户详情
     */
    public UserResponse getUserById(Long id) {
        log.info("[UserClientService] Calling declarative getUserById for id: {}", id);
        return userServiceClient.getUserById(id);
    }

    /**
     * 2. 声明式 POST 创建用户
     */
    public UserResponse createUser(UserRequest request) {
        log.info("[UserClientService] Calling declarative createUser with: {}", request);
        return userServiceClient.createUser(request);
    }
}
