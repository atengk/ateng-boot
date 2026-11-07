package io.github.atengk.boot.sample.social.controller;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.social.listener.SampleSocialEventListener;
import io.github.atengk.boot.sample.social.service.SampleSocialScenarioService;
import io.github.atengk.boot.social.config.SocialAppConfig;
import io.github.atengk.boot.social.model.SocialAuthResult;
import io.github.atengk.boot.social.template.SocialAuthTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.anduin.model.AuthCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 社交登录多租户切源与领域事件审计 Controller
 * <p>
 * 提供 SaaS 多租户动态切源 SPI (SocialAppConfigProvider) 配置验证、
 * OAuth2 回调中租户上下文 (TenantContextHolder) 自动恢复验证，
 * 以及领域事件四件套审计流水查询。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Slf4j
@RestController
@RequestMapping("/sample/social/tenant")
@RequiredArgsConstructor
public class SocialMultiTenantConfigController {

    private final SampleSocialScenarioService scenarioService;
    private final SocialAuthTemplate socialAuthTemplate;
    private final SampleSocialEventListener eventListener;

    /**
     * 演示 SaaS 多租户动态应用配置提取与切源验证
     *
     * @param appKey   社交应用唯一标识（如 tenant_saas_app 或 saas_github）
     * @param tenantId 可选租户标识（演示多租户动态上下文切源）
     * @return 标准化社交应用配置
     */
    @GetMapping("/app-config")
    public Result<SocialAppConfig> getAppConfig(
            @RequestParam("appKey") String appKey,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        SocialAppConfig config;
        if (tenantId != null && !tenantId.isBlank()) {
            config = TenantContextHolder.runWithTenant(tenantId, () -> scenarioService.getAppConfig(appKey));
        } else {
            config = scenarioService.getAppConfig(appKey);
        }
        return Result.success(config);
    }

    /**
     * 验证 OAuth2 回调中租户上下文 (TenantContextHolder) 自动恢复机制
     * <p>
     * 在生成授权跳转 URL 时若 StateContext 包含 tenantId，
     * 当第三方重定向回调进入系统换码时，框架底层将自动从防伪 State 中还原该 tenantId 并绑定至当前线程。
     * </p>
     *
     * @param appKey 社交应用唯一标识
     * @param code   第三方平台授权码 code
     * @param state  防伪状态码 state
     * @return 还原后的多租户隔离标识
     */
    @GetMapping("/verify-tenant-callback")
    public Result<String> verifyTenantCallback(
            @RequestParam("appKey") String appKey,
            @RequestParam("code") String code,
            @RequestParam("state") String state) {
        AuthCallback callback = AuthCallback.builder()
                .code(code)
                .state(state)
                .build();
        SocialAuthResult result = socialAuthTemplate.login(appKey, callback);
        String restoredTenantId = result.getStateContext() != null ? result.getStateContext().getTenantId() : null;
        return Result.success(restoredTenantId);
    }

    /**
     * 查询系统捕获的社交登录领域事件四件套审计流水记录
     *
     * @return 领域事件审计流水列表
     */
    @GetMapping("/audit-events")
    public Result<List<SampleSocialEventListener.AuditEventRecord>> getAuditEvents() {
        List<SampleSocialEventListener.AuditEventRecord> events = eventListener.getAuditEvents();
        return Result.success(events);
    }
}
