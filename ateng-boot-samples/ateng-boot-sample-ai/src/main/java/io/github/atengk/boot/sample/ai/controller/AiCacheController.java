package io.github.atengk.boot.sample.ai.controller;

import io.github.atengk.boot.ai.cache.AiSemanticCache;
import io.github.atengk.boot.ai.context.AiContextHolder;
import io.github.atengk.boot.ai.template.AiTemplate;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.dto.AiCacheChatRequest;
import io.github.atengk.boot.sample.ai.vo.AiCacheChatVo;
import io.github.atengk.boot.sample.ai.vo.AiCacheStatsVo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * AI 精确与向量语义双级响应缓存体验控制器
 * <p>
 * 提供缓存命中对话、强制绕过缓存（bypassCache）、按租户精准驱逐、全量清空与缓存命中统计接口。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-22
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/ai/cache")
@RequiredArgsConstructor
public class AiCacheController {

    private final AiTemplate aiTemplate;
    private final ObjectProvider<AiSemanticCache> semanticCacheProvider;

    /**
     * 发起带双级语义缓存治理的 AI 对话（支持 tenantId 隔离与 bypassCache 强制穿透）
     *
     * @param request 缓存对话请求 DTO
     * @return 缓存对话结果 VO
     */
    @PostMapping("/chat")
    public Result<AiCacheChatVo> chat(@Valid @RequestBody AiCacheChatRequest request) {
        AiSemanticCache semanticCache = semanticCacheProvider.getIfAvailable();

        String previousTenant = TenantContextHolder.getTenantId();
        boolean tenantSwitched = StringUtils.hasText(request.getTenantId());
        if (tenantSwitched) {
            TenantContextHolder.setTenantId(request.getTenantId().trim());
        }

        String reply;
        boolean cacheHit = false;
        try (AiSemanticCache.HitTracker hitTracker = semanticCache != null ? semanticCache.openHitTracker() : null) {
            if (request.isBypassCache()) {
                try (AiContextHolder.AiModelScope ignored = AiContextHolder.bypassCache()) {
                    reply = aiTemplate.chatWithModel(request.getModel(), request.getPrompt());
                }
            } else {
                reply = aiTemplate.chatWithModel(request.getModel(), request.getPrompt());
            }
            cacheHit = hitTracker != null && hitTracker.isHit();
        } finally {
            if (tenantSwitched) {
                if (StringUtils.hasText(previousTenant)) {
                    TenantContextHolder.setTenantId(previousTenant);
                } else {
                    TenantContextHolder.clear();
                }
            }
        }

        String resolvedModel = StringUtils.hasText(request.getModel()) ? request.getModel().trim() : "default";
        String resolvedTenant = StringUtils.hasText(request.getTenantId()) ? request.getTenantId().trim()
                : (StringUtils.hasText(previousTenant) ? previousTenant : "default");

        AiCacheChatVo vo = AiCacheChatVo.builder()
                .reply(reply)
                .model(resolvedModel)
                .tenantId(resolvedTenant)
                .cacheHit(cacheHit)
                .bypassed(request.isBypassCache())
                .cacheSize(semanticCache != null ? semanticCache.size() : 0L)
                .build();
        return Result.success(vo);
    }

    /**
     * 按租户标识精准驱逐全部响应缓存
     *
     * @param tenantId 租户标识
     * @return 驱逐后剩余缓存条目数
     */
    @DeleteMapping("/evict/{tenantId}")
    public Result<Long> evictByTenant(@PathVariable("tenantId") @NotBlank(message = "tenantId 不能为空") String tenantId) {
        AiSemanticCache semanticCache = semanticCacheProvider.getIfAvailable();
        if (semanticCache == null) {
            return Result.success(0L);
        }
        semanticCache.evictByTenant(tenantId.trim());
        log.info("[AI-CACHE-SAMPLE] 已驱逐租户 [{}] 全部语义缓存", tenantId);
        return Result.success(semanticCache.size());
    }

    /**
     * 清空全部 AI 语义缓存条目
     *
     * @return 清空后缓存条目数
     */
    @DeleteMapping("/clear")
    public Result<Long> clearAll() {
        AiSemanticCache semanticCache = semanticCacheProvider.getIfAvailable();
        if (semanticCache == null) {
            return Result.success(0L);
        }
        semanticCache.clearAll();
        return Result.success(semanticCache.size());
    }

    /**
     * 查询当前 AI 语义缓存状态与 Micrometer 命中率指标统计
     *
     * @return 缓存状态视图 VO
     */
    @GetMapping("/stats")
    public Result<AiCacheStatsVo> stats() {
        AiSemanticCache semanticCache = semanticCacheProvider.getIfAvailable();
        boolean enabled = semanticCache != null && semanticCache.getProperties().isEnabled();
        String storeType = semanticCache == null
                ? "DISABLED"
                : (semanticCache.getStore().isDistributed() ? "REDIS" : "IN_MEMORY");

        AiCacheStatsVo vo = AiCacheStatsVo.builder()
                .enabled(enabled)
                .semanticEnabled(semanticCache != null && semanticCache.getProperties().isSemanticEnabled())
                .storeType(storeType)
                .cacheSize(semanticCache != null ? semanticCache.size() : 0L)
                .hitCount(semanticCache != null ? semanticCache.getHitCount() : 0L)
                .missCount(semanticCache != null ? semanticCache.getMissCount() : 0L)
                .build();
        return Result.success(vo);
    }
}
