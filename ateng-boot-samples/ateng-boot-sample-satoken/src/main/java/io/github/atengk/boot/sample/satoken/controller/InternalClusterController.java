package io.github.atengk.boot.sample.satoken.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.satoken.annotation.Anonymous;
import io.github.atengk.boot.satoken.annotation.CheckSameToken;
import io.github.atengk.boot.satoken.cache.PermissionCacheService;
import io.github.atengk.boot.satoken.cluster.broadcaster.SaTokenClusterBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * 微服务内部零信任调用与集群同步示例控制器
 * 演示微服务内部 @CheckSameToken 零信任安全防护、RestClient 自动挂载 SameTokenClientHttpRequestInterceptor 凭据透传，
 * 以及 SaTokenClusterBroadcaster 实例广播与 PermissionCacheService 跨节点缓存失效
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class InternalClusterController {

    private final RestClient.Builder restClientBuilder;
    private final SaTokenClusterBroadcaster broadcaster;
    private final PermissionCacheService permissionCacheService;

    /**
     * 获取受管 RestClient.Builder 实例（便于集成测试与配置探查）
     *
     * @return RestClient.Builder 实例
     */
    public RestClient.Builder getRestClientBuilder() {
        return this.restClientBuilder;
    }

    /**
     * 微服务内网清算凭证受保护接口（仅限持有有效 SA-SAME-TOKEN 内网凭据的服务调用）
     *
     * @return 清算凭证数据
     */
    @GetMapping("/sample/satoken/internal/clearing/voucher")
    @Anonymous
    @CheckSameToken
    public Result<String> getClearingVoucher() {
        return Result.success("清算凭证数据 [CLR-20261108-9988]");
    }

    /**
     * 演示发起跨微服务内部清算调用
     * 通过自动注入 SameTokenClientHttpRequestInterceptor 的 RestClient 自动在请求头挂载 SA-SAME-TOKEN
     *
     * @param targetUrl 下游目标地址（默认为本地清算接口）
     * @return 调用结果与下游返回
     */
    @PostMapping("/sample/satoken/internal/call-clearing")
    @Anonymous
    public Result<String> callClearing(@RequestParam(value = "targetUrl", required = false) String targetUrl) {
        String url = (targetUrl != null && !targetUrl.isBlank())
                ? targetUrl
                : "http://localhost:8080/sample/satoken/internal/clearing/voucher";
        try {
            String response = restClientBuilder.build()
                    .get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);
            return Result.success("内部清算远程调用成功，下游返回: " + response);
        } catch (Exception e) {
            log.error("内部清算远程调用异常: ", e);
            return Result.fail("内部调用失败: " + e.getMessage());
        }
    }

    /**
     * 查询当前集群节点唯一实例 ID
     *
     * @return 节点实例 ID
     */
    @GetMapping("/sample/satoken/cluster/instance-id")
    @Anonymous
    public Result<String> getInstanceId() {
        return Result.success(broadcaster.getInstanceId());
    }

    /**
     * 触发指定用户权限缓存全网失效与本地清理广播
     *
     * @param userId 目标用户标识
     * @return 操作结果
     */
    @PostMapping("/sample/satoken/cluster/refresh-user")
    @Anonymous
    public Result<Void> refreshUserPermissions(@RequestParam("userId") String userId) {
        permissionCacheService.refreshUserPermissions(userId);
        return Result.success();
    }

    /**
     * 触发指定角色权限缓存全网失效与本地清理广播
     *
     * @param roleCode 目标角色编码
     * @return 操作结果
     */
    @PostMapping("/sample/satoken/cluster/refresh-role")
    @Anonymous
    public Result<Void> refreshRolePermissions(@RequestParam("roleCode") String roleCode) {
        permissionCacheService.refreshRolePermissions(roleCode);
        return Result.success();
    }

    /**
     * 触发全域两级权限缓存彻底清空广播
     *
     * @return 操作结果
     */
    @PostMapping("/sample/satoken/cluster/clear-all")
    @Anonymous
    public Result<Void> clearAllPermissions() {
        permissionCacheService.clearAll();
        return Result.success();
    }
}
