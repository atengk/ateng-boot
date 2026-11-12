package io.github.atengk.boot.sample.smoke.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.encrypt.template.EncryptTemplate;
import io.github.atengk.boot.http.template.HttpTemplate;
import io.github.atengk.boot.openapi.properties.OpenApiProperties;
import io.github.atengk.boot.realtime.template.RealtimeTemplate;
import io.github.atengk.boot.redis.util.RedisUtil;
import io.github.atengk.boot.sample.smoke.model.SmokeCoexistenceVO;
import io.github.atengk.boot.sample.smoke.model.SmokeLoginRequest;
import io.github.atengk.boot.sample.smoke.model.SmokeRedisProbeVO;
import io.github.atengk.boot.sample.smoke.model.SmokeSessionVO;
import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.util.LoginHelper;
import io.github.atengk.boot.statemachine.autoconfigure.StateMachineInitializer;
import io.github.atengk.boot.storage.template.StorageTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证鉴权与多模块协同探针领域控制器
 * 验证 Sa-Token 会话与 Core 四维上下文桥接、Redis 双模自适应及多 Starter 容器无冲突共存
 *
 * @author Ateng
 * @since 2026-11-12
 */
@Tag(name = "3. 认证鉴权与协同探针")
@RestController
@RequestMapping("/sample/smoke/security")
@RequiredArgsConstructor
public class SmokeSecurityCoordinationController {

    private static final String REDIS_PROBE_KEY = "smoke:coordination:probe";

    private final ObjectProvider<RedisUtil> redisUtilProvider;
    private final ObjectProvider<StorageTemplate> storageTemplateProvider;
    private final ObjectProvider<HttpTemplate> httpTemplateProvider;
    private final ObjectProvider<RealtimeTemplate> realtimeTemplateProvider;
    private final ObjectProvider<EncryptTemplate> encryptTemplateProvider;
    private final ObjectProvider<StateMachineInitializer> stateMachineInitializerProvider;
    private final ObjectProvider<OpenApiProperties> openApiPropertiesProvider;

    /**
     * 执行冒烟会话登录并验证 Sa-Token 到 Core 上下文自动桥接
     *
     * @param request 登录请求参数
     * @return 包含凭据与上下文解析结果的会话视图
     */
    @Operation(summary = "登录并验证上下文桥接")
    @PostMapping("/login")
    public Result<SmokeSessionVO> login(@RequestBody @Valid SmokeLoginRequest request) {
        LoginUser loginUser = LoginUser.builder()
                .userId(request.getUserId())
                .userAccount(request.getUserAccount())
                .userName(request.getUserName())
                .tenantId(request.getTenantId())
                .build();
        LoginHelper.login(loginUser);
        return Result.success(buildSessionSnapshot(loginUser));
    }

    /**
     * 获取当前受登录保护的会话快照
     *
     * @return 当前已登录会话详情
     */
    @Operation(summary = "获取当前已登录会话快照")
    @SaCheckLogin
    @GetMapping("/me")
    public Result<SmokeSessionVO> me() {
        LoginUser loginUser = LoginHelper.getLoginUser();
        return Result.success(buildSessionSnapshot(loginUser));
    }

    /**
     * 探测 Redis 双模状态（CI 离线自动降级，local Profile 下直连真实 Redis 读写往返）
     *
     * @return Redis 状态探测结果
     */
    @Operation(summary = "Redis 双模状态探测")
    @GetMapping("/redis-status")
    public Result<SmokeRedisProbeVO> redisStatus() {
        RedisUtil redisUtil = redisUtilProvider.getIfAvailable();
        if (redisUtil == null) {
            return Result.success(new SmokeRedisProbeVO(false, "OFFLINE_DEGRADED", REDIS_PROBE_KEY, "DISABLED"));
        }
        String probeValue = "pong-" + System.currentTimeMillis();
        RedisUtil.set(REDIS_PROBE_KEY, probeValue, Duration.ofSeconds(30));
        String echo = RedisUtil.get(REDIS_PROBE_KEY, String.class);
        return Result.success(new SmokeRedisProbeVO(true, "LOCAL_LIVE", REDIS_PROBE_KEY, echo));
    }

    /**
     * 查询全家桶与按需扩展 Starter 在同一容器中的装配共存状态
     *
     * @return 多 Starter 装配组件状态矩阵
     */
    @Operation(summary = "查询多 Starter 共存装配状态")
    @GetMapping("/coexistence")
    public Result<SmokeCoexistenceVO> coexistence() {
        Map<String, Boolean> map = new LinkedHashMap<>();
        map.put("ateng-boot-storage", storageTemplateProvider.getIfAvailable() != null);
        map.put("ateng-boot-http", httpTemplateProvider.getIfAvailable() != null);
        map.put("ateng-boot-realtime", realtimeTemplateProvider.getIfAvailable() != null);
        map.put("ateng-boot-encrypt", encryptTemplateProvider.getIfAvailable() != null);
        map.put("ateng-boot-statemachine", stateMachineInitializerProvider.getIfAvailable() != null);
        map.put("ateng-boot-openapi", openApiPropertiesProvider.getIfAvailable() != null);
        map.put("ateng-boot-redis", redisUtilProvider.getIfAvailable() != null);
        return Result.success(new SmokeCoexistenceVO(true, map));
    }

    private SmokeSessionVO buildSessionSnapshot(LoginUser loginUser) {
        return SmokeSessionVO.builder()
                .loggedIn(true)
                .userId(loginUser != null ? loginUser.getUserId() : null)
                .userAccount(loginUser != null ? loginUser.getUserAccount() : null)
                .userName(loginUser != null ? loginUser.getUserName() : null)
                .tenantId(loginUser != null ? loginUser.getTenantId() : null)
                .tokenName(StpUtil.getTokenName())
                .tokenValue(LoginHelper.getTokenValue())
                .resolvedContextUserId(UserContextHolder.getUserId())
                .resolvedContextTenantId(TenantContextHolder.getTenantId())
                .build();
    }
}
